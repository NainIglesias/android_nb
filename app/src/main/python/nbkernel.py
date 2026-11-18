"""A small Jupyter-like kernel that runs inside the app's embedded Python.

The Kotlin side creates one Session per open notebook and calls execute()
for each cell. Outputs are sent back as nbformat v4 output dicts, encoded as
JSON strings, through a sink object with these methods:

    onOutput(json: str) -> None
    requestInput(prompt: str, password: bool) -> str | None   (None = cancelled)

This module has no Android dependencies, so it is tested on the host with
plain CPython (see python-tests/).
"""

import ast
import base64
import builtins
import ctypes
import io
import json
import linecache
import os
import sys
import threading
import time
import traceback
import types

# Every figure is rendered by our own matplotlib backend (nb_mpl_backend).
os.environ.setdefault("MPLBACKEND", "module://nb_mpl_backend")

_current = None  # the Session that is executing right now, if any


def current_session():
    return _current


# --------------------------------------------------------------------------
# Rich display


_REPR_METHODS = [
    ("_repr_html_", "text/html"),
    ("_repr_markdown_", "text/markdown"),
    ("_repr_svg_", "image/svg+xml"),
    ("_repr_png_", "image/png"),
    ("_repr_jpeg_", "image/jpeg"),
    ("_repr_latex_", "text/latex"),
    ("_repr_json_", "application/json"),
]


def format_bundle(obj):
    """Return the nbformat mime bundle for obj, like IPython's formatter."""
    data = {}
    if _is_mpl_figure(obj):
        data["image/png"] = _figure_png(obj)
        data["text/plain"] = repr(obj)
        return data

    mimebundle = getattr(obj, "_repr_mimebundle_", None)
    if callable(mimebundle) and not isinstance(obj, type):
        try:
            result = mimebundle()
            if isinstance(result, tuple):
                result = result[0]
            if isinstance(result, dict):
                for mime, value in result.items():
                    data[mime] = _encode(mime, value)
        except Exception:
            pass

    for method, mime in _REPR_METHODS:
        if mime in data:
            continue
        fn = getattr(obj, method, None)
        if not callable(fn) or isinstance(obj, type):
            continue
        try:
            value = fn()
        except Exception:
            continue
        if isinstance(value, tuple):  # (data, metadata)
            value = value[0]
        if value is None:
            continue
        data[mime] = _encode(mime, value)

    if "text/plain" not in data:
        try:
            data["text/plain"] = repr(obj)
        except Exception as e:
            data["text/plain"] = f"<repr failed: {e!r}>"
    return data


def _encode(mime, value):
    if mime.startswith("image/") and mime != "image/svg+xml":
        if isinstance(value, (bytes, bytearray)):
            return base64.b64encode(value).decode("ascii")
        return str(value)
    if mime == "application/json":
        return value
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", "replace")
    return str(value)


def _is_mpl_figure(obj):
    mod = sys.modules.get("matplotlib.figure")
    return mod is not None and isinstance(obj, mod.Figure)


def _figure_png(fig):
    buf = io.BytesIO()
    fig.savefig(buf, format="png", bbox_inches="tight", dpi=110)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def display(*objs, raw=False, clear=False, **kwargs):
    """IPython-style display(): show each object as a display_data output."""
    session = _current
    for obj in objs:
        if session is None:
            print(repr(obj))
            continue
        if clear:
            session.clear_output(wait=True)
        bundle = obj if raw else format_bundle(obj)
        session.send({"output_type": "display_data", "data": bundle, "metadata": {}})


class _DisplayObject:
    mime = "text/plain"

    def __init__(self, data=None, url=None, filename=None, **kwargs):
        if filename is not None and data is None:
            mode = "rb" if self.mime.startswith("image/") and self.mime != "image/svg+xml" else "r"
            with open(filename, mode) as f:
                data = f.read()
        self.data = data
        self.url = url

    def _repr_mimebundle_(self, **kwargs):
        return {self.mime: self.data, "text/plain": f"<{type(self).__name__} object>"}


class HTML(_DisplayObject):
    mime = "text/html"


class Markdown(_DisplayObject):
    mime = "text/markdown"


class Latex(_DisplayObject):
    mime = "text/latex"


class Math(_DisplayObject):
    mime = "text/latex"

    def _repr_mimebundle_(self, **kwargs):
        return {self.mime: f"$${self.data}$$", "text/plain": str(self.data)}


class SVG(_DisplayObject):
    mime = "image/svg+xml"


class JSON(_DisplayObject):
    mime = "application/json"


class Image(_DisplayObject):
    def __init__(self, data=None, url=None, filename=None, format=None, **kwargs):
        name = (filename or url or "").lower()
        fmt = (format or ("jpeg" if name.endswith((".jpg", ".jpeg")) else "png")).lower()
        self.mime = "image/jpeg" if fmt in ("jpg", "jpeg") else "image/png"
        if isinstance(data, str) and data and os.path.exists(data):
            filename, data = data, None
        super().__init__(data=data, url=url, filename=filename)


def clear_output(wait=False):
    if _current is not None:
        _current.clear_output(wait=wait)


def _install_ipython_shim():
    """Make `from IPython.display import display, HTML, ...` work."""
    try:
        import IPython  # noqa: F401  (a real IPython is installed: leave it)
        return
    except ImportError:
        pass
    ipython = types.ModuleType("IPython")
    disp = types.ModuleType("IPython.display")
    for name, value in {
        "display": display, "clear_output": clear_output, "HTML": HTML,
        "Markdown": Markdown, "Latex": Latex, "Math": Math, "SVG": SVG,
        "JSON": JSON, "Image": Image,
    }.items():
        setattr(disp, name, value)
    ipython.display = disp
    ipython.get_ipython = lambda: None  # libraries read this as "not inside IPython"
    ipython.version_info = (9, 0, 0, "")  # matplotlib checks this
    ipython.__version__ = "9.0.0"
    ipython.__path__ = []
    sys.modules["IPython"] = ipython
    sys.modules["IPython.display"] = disp


_install_ipython_shim()


# --------------------------------------------------------------------------
# Output streams


class _Stream(io.TextIOBase):
    """Line-buffered stdout/stderr that forwards text to the session."""

    FLUSH_BYTES = 4096
    FLUSH_SECONDS = 0.1

    def __init__(self, session, name):
        self._session = session
        self._name = name
        self._buf = []
        self._size = 0
        self._last = time.monotonic()

    @property
    def name(self):
        return f"<{self._name}>"

    def writable(self):
        return True

    def isatty(self):
        return False

    def write(self, s):
        if not isinstance(s, str):
            raise TypeError(f"write() argument must be str, not {type(s).__name__}")
        if not s:
            return 0
        self._buf.append(s)
        self._size += len(s)
        now = time.monotonic()
        if self._size >= self.FLUSH_BYTES or now - self._last >= self.FLUSH_SECONDS:
            self.flush()
        return len(s)

    def flush(self):
        self._last = time.monotonic()
        if not self._buf:
            return
        text = "".join(self._buf)
        self._buf.clear()
        self._size = 0
        self._session.send({"output_type": "stream", "name": self._name, "text": text})


# --------------------------------------------------------------------------
# Source transformations (IPython magics)


def transform_source(code, write):
    """Handle the magics that make sense here and comment out the rest.

    `write(name, text)` is called with warnings for the user.
    Returns the Python source to execute.
    """
    lines = code.split("\n")
    first = lines[0].strip() if lines else ""

    if first.startswith("%%"):
        magic, _, arg = first[2:].partition(" ")
        body = "\n".join(lines[1:])
        if magic == "writefile":
            args = arg.split()
            append = "-a" in args or "--append" in args
            names = [a for a in args if not a.startswith("-")]
            if not names:
                return "raise UsageError('%%writefile needs a file name')"
            return (
                f"with open({names[0]!r}, {'a' if append else 'w'!r}) as __nb_f:\n"
                f"    __nb_f.write({body + chr(10)!r})\n"
                f"print({('Appending to ' if append else 'Writing ') + names[0]!r})"
            )
        if magic in ("time", "capture"):
            write("stderr", f"%%{magic} is not supported here; running the cell without it.\n")
        elif magic in ("bash", "sh", "script", "html", "javascript", "js", "latex"):
            if magic == "html":
                return f"display(HTML({body!r}))"
            write("stderr", f"%%{magic} cells can't run on Android.\n")
            return ""
        else:
            write("stderr", f"Cell magic %%{magic} is not supported; running the body as Python.\n")
        lines = lines[1:]

    out = []
    for line in lines:
        stripped = line.lstrip()
        indent = line[: len(line) - len(stripped)]
        if stripped.startswith(("%", "!")) and not stripped.startswith("%="):
            cmd = stripped[1:].split(" ", 1)[0]
            if stripped.startswith("%matplotlib") or cmd in ("load_ext", "reload_ext", "config"):
                pass  # harmless here
            elif cmd in ("pip", "conda") or stripped.startswith(("!pip", "!conda")):
                write("stderr", "Packages can't be installed at runtime. They are bundled with the app "
                                "(see chaquopy.pip in app/build.gradle.kts).\n")
            else:
                write("stderr", f"Not supported here: {stripped}\n")
            out.append(f"{indent}pass  # {stripped}")
        else:
            out.append(line)
    return "\n".join(out)


class UsageError(Exception):
    pass


# --------------------------------------------------------------------------
# Session


class Session:
    def __init__(self, workdir=None):
        self.execution_count = 0
        self.namespace = {}
        self._sink = None
        self._tid = None
        self._lock = threading.Lock()
        self._pending_clear = False
        self.workdir = workdir
        if workdir:
            os.makedirs(workdir, exist_ok=True)
        self.reset()

    def reset(self):
        """Start from an empty namespace (modules stay imported)."""
        self.execution_count = 0
        ns = {"__name__": "__main__", "__builtins__": builtins}
        ns.update(display=display, HTML=HTML, Markdown=Markdown, Image=Image,
                  clear_output=clear_output, UsageError=UsageError)
        self.namespace = ns
        _close_figures()

    # ---- output plumbing

    def send(self, output):
        if output["output_type"] != "stream":
            self._flush_streams()  # keep printed text before this output
        if self._pending_clear:
            self._pending_clear = False
            self._raw_send({"output_type": "clear_output"})
        self._raw_send(output)

    def _raw_send(self, output):
        if self._sink is not None:
            self._sink.onOutput(json.dumps(output))

    @staticmethod
    def _flush_streams():
        for s in (sys.stdout, sys.stderr):
            if isinstance(s, _Stream):
                s.flush()

    def clear_output(self, wait=False):
        self._flush_streams()
        if wait:
            self._pending_clear = True
        else:
            self._pending_clear = False
            self._raw_send({"output_type": "clear_output"})

    def _input(self, prompt="", password=False):
        sys.stdout.flush()
        if self._sink is None:
            raise EOFError("no input available")
        answer = self._sink.requestInput(str(prompt), password)
        if answer is None:
            raise KeyboardInterrupt
        answer = str(answer)
        if not password:
            self.send({"output_type": "stream", "name": "stdout", "text": f"{prompt}{answer}\n"})
        return answer

    # ---- execution

    def execute(self, code, sink):
        """Run one cell. Returns a JSON string: {"status", "execution_count"}."""
        global _current
        self.execution_count += 1
        count = self.execution_count
        filename = f"<cell [{count}]>"
        self._sink = sink
        self._pending_clear = False
        old = sys.stdout, sys.stderr, builtins.input
        out, err = _Stream(self, "stdout"), _Stream(self, "stderr")
        status = "ok"
        old_cwd = None
        try:
            _current = self
            sys.stdout, sys.stderr = out, err
            builtins.input = self._input
            if self.workdir:
                old_cwd = os.getcwd()
                os.chdir(self.workdir)
                if self.workdir not in sys.path:
                    sys.path.insert(0, self.workdir)
            try:
                with self._lock:
                    self._tid = threading.get_ident()
                self._run(code, filename, err.write)
                self._flush_figures()
            except KeyboardInterrupt:
                status = "error"
                self._send_error("KeyboardInterrupt", "", ["KeyboardInterrupt: execution interrupted"])
            except SystemExit as e:
                if e.code not in (None, 0):
                    status = "error"
                    self._send_exception(e)
            except BaseException as e:
                status = "error"
                self._send_exception(e)
            finally:
                with self._lock:
                    self._tid = None
                _close_figures()
                out.flush()
                err.flush()
        except KeyboardInterrupt:
            # An interrupt that landed while we were tidying up.
            status = "error"
        finally:
            sys.stdout, sys.stderr, builtins.input = old
            if old_cwd is not None:
                try:
                    os.chdir(old_cwd)
                except OSError:
                    pass
            _current = None
            self._sink = None
        return json.dumps({"status": status, "execution_count": count})

    def _run(self, code, filename, warn):
        source = transform_source(code, lambda _name, text: warn(text))
        lines = source.splitlines(keepends=True)
        linecache.cache[filename] = (len(source), None, lines, filename)
        tree = ast.parse(source, filename=filename, mode="exec")
        last = None
        if tree.body and isinstance(tree.body[-1], ast.Expr):
            last = tree.body.pop()
        ns = self.namespace
        if tree.body:
            exec(compile(tree, filename, "exec"), ns)
        if last is not None:
            value = eval(compile(ast.Expression(last.value), filename, "eval"), ns)
            if value is not None and not source.rstrip().endswith(";"):
                ns["_"] = value
                ns[f"_{self.execution_count}"] = value
                if _is_mpl_figure(value):
                    # Shown by _flush_figures, so it isn't shown twice.
                    return
                if _is_mpl_artist(value):
                    return  # e.g. the list returned by plt.plot(): the figure is enough
                self.send({
                    "output_type": "execute_result",
                    "execution_count": self.execution_count,
                    "data": format_bundle(value),
                    "metadata": {},
                })

    def _flush_figures(self):
        plt = sys.modules.get("matplotlib.pyplot")
        if plt is None:
            return
        for num in plt.get_fignums():
            fig = plt.figure(num)
            self.send({
                "output_type": "display_data",
                "data": {"image/png": _figure_png(fig), "text/plain": repr(fig)},
                "metadata": {},
            })
        plt.close("all")

    def _send_exception(self, e):
        tb = e.__traceback__
        # Hide this module's frames: the user only cares about their code.
        while tb is not None and tb.tb_frame.f_code.co_filename == __file__:
            tb = tb.tb_next
        lines = traceback.format_exception(type(e), e, tb)
        self._send_error(type(e).__name__, str(e), ["".join(lines).rstrip("\n")])

    def _send_error(self, ename, evalue, tb):
        self.send({"output_type": "error", "ename": ename, "evalue": evalue, "traceback": tb})

    def interrupt(self):
        """Raise KeyboardInterrupt in the executing thread (called from another thread)."""
        with self._lock:
            tid = self._tid
            if tid is None:
                return False
            set_exc = ctypes.pythonapi.PyThreadState_SetAsyncExc
            set_exc.argtypes = [ctypes.c_ulong, ctypes.py_object]
            set_exc.restype = ctypes.c_int
            return set_exc(tid, KeyboardInterrupt) == 1


def _is_mpl_artist(value):
    artist = sys.modules.get("matplotlib.artist")
    if artist is None:
        return False
    if isinstance(value, artist.Artist):
        return True
    if isinstance(value, (list, tuple)) and value and all(isinstance(v, artist.Artist) for v in value):
        return True
    return False


def _close_figures():
    plt = sys.modules.get("matplotlib.pyplot")
    if plt is not None:
        plt.close("all")


def new_session(workdir=None):
    return Session(workdir)
