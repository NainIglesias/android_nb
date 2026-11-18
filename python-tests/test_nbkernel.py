"""Host tests for the in-app kernel: python -m unittest discover python-tests"""

import json
import os
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))

import nbkernel  # noqa: E402


class Sink:
    def __init__(self, answers=()):
        self.outputs = []
        self.answers = list(answers)

    def onOutput(self, s):
        self.outputs.append(json.loads(s))

    def requestInput(self, prompt, password):
        return self.answers.pop(0) if self.answers else None

    def text(self, name="stdout"):
        return "".join(o["text"] for o in self.outputs
                       if o["output_type"] == "stream" and o["name"] == name)

    def of_type(self, t):
        return [o for o in self.outputs if o["output_type"] == t]


class KernelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.session = nbkernel.new_session(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def run_cell(self, code, sink=None):
        sink = sink or Sink()
        result = json.loads(self.session.execute(code, sink))
        return result, sink

    def test_print_and_result(self):
        result, sink = self.run_cell("print('hi')\n1 + 2")
        self.assertEqual(result, {"status": "ok", "execution_count": 1})
        self.assertEqual(sink.text(), "hi\n")
        [res] = sink.of_type("execute_result")
        self.assertEqual(res["data"]["text/plain"], "3")
        self.assertEqual(res["execution_count"], 1)

    def test_namespace_persists_and_semicolon_hides(self):
        self.run_cell("x = 41")
        _, sink = self.run_cell("x + 1;")
        self.assertEqual(sink.of_type("execute_result"), [])
        _, sink = self.run_cell("x + 1")
        self.assertEqual(sink.of_type("execute_result")[0]["data"]["text/plain"], "42")

    def test_error_traceback_hides_kernel_frames(self):
        result, sink = self.run_cell("def f():\n    return 1/0\nf()")
        self.assertEqual(result["status"], "error")
        [err] = sink.of_type("error")
        self.assertEqual(err["ename"], "ZeroDivisionError")
        tb = "\n".join(err["traceback"])
        self.assertIn("return 1/0", tb)
        self.assertNotIn("nbkernel.py", tb)

    def test_syntax_error(self):
        result, sink = self.run_cell("def (")
        self.assertEqual(result["status"], "error")
        self.assertEqual(sink.of_type("error")[0]["ename"], "SyntaxError")

    def test_stdout_restored(self):
        before = sys.stdout
        self.run_cell("print('x')")
        self.assertIs(sys.stdout, before)

    def test_rich_repr(self):
        code = ("class T:\n"
                "    def _repr_html_(self): return '<b>t</b>'\n"
                "T()")
        _, sink = self.run_cell(code)
        data = sink.of_type("execute_result")[0]["data"]
        self.assertEqual(data["text/html"], "<b>t</b>")
        self.assertIn("text/plain", data)

    def test_ipython_display_shim(self):
        _, sink = self.run_cell("from IPython.display import display, Markdown\n"
                                "display(Markdown('# hi'))")
        [d] = sink.of_type("display_data")
        self.assertEqual(d["data"]["text/markdown"], "# hi")

    def test_magics(self):
        _, sink = self.run_cell("%matplotlib inline\n!ls\nprint('ok')")
        self.assertEqual(sink.text(), "ok\n")
        self.assertIn("Not supported here: !ls", sink.text("stderr"))

    def test_writefile(self):
        _, sink = self.run_cell("%%writefile hello.py\nX = 5\n")
        self.assertIn("Writing hello.py", sink.text())
        self.assertTrue(os.path.exists(os.path.join(self.tmp.name, "hello.py")))
        _, sink = self.run_cell("import hello\nhello.X")
        self.assertEqual(sink.of_type("execute_result")[0]["data"]["text/plain"], "5")

    def test_input(self):
        _, sink = self.run_cell("name = input('who? ')\nprint('hi', name)", Sink(["Ana"]))
        self.assertEqual(sink.text(), "who? Ana\nhi Ana\n")
        result, sink = self.run_cell("input()", Sink())
        self.assertEqual(sink.of_type("error")[0]["ename"], "KeyboardInterrupt")

    def test_clear_output(self):
        _, sink = self.run_cell("print('a')\nclear_output()\nprint('b')")
        types = [o["output_type"] for o in sink.outputs]
        self.assertEqual(types, ["stream", "clear_output", "stream"])

    def test_interrupt(self):
        sink = Sink()
        box = {}

        def run():
            box["r"] = json.loads(self.session.execute("while True:\n    pass", sink))

        t = threading.Thread(target=run)
        t.start()
        deadline = time.time() + 5
        while not self.session.interrupt():
            self.assertLess(time.time(), deadline)
            time.sleep(0.01)
        t.join(5)
        self.assertFalse(t.is_alive())
        self.assertEqual(box["r"]["status"], "error")
        self.assertEqual(sink.of_type("error")[0]["ename"], "KeyboardInterrupt")
        # The session still works afterwards.
        result, _ = self.run_cell("1")
        self.assertEqual(result["status"], "ok")

    def test_reset(self):
        self.run_cell("y = 1")
        self.session.reset()
        result, sink = self.run_cell("y")
        self.assertEqual(sink.of_type("error")[0]["ename"], "NameError")
        self.assertEqual(result["execution_count"], 1)

    def test_stream_batches_many_prints(self):
        _, sink = self.run_cell("for i in range(2000):\n    print(i)")
        self.assertEqual(sink.text(), "".join(f"{i}\n" for i in range(2000)))
        self.assertLess(len(sink.outputs), 200)


try:
    import matplotlib  # noqa: F401
    HAVE_MPL = True
except ImportError:
    HAVE_MPL = False


@unittest.skipUnless(HAVE_MPL, "matplotlib not installed")
class MatplotlibTest(KernelTest.__base__):
    def setUp(self):
        self.session = nbkernel.new_session(None)

    def run_cell(self, code):
        sink = Sink()
        result = json.loads(self.session.execute(code, sink))
        return result, sink

    def test_figure_at_end_of_cell(self):
        result, sink = self.run_cell("import matplotlib.pyplot as plt\nplt.plot([1, 2, 3])")
        self.assertEqual(result["status"], "ok", sink.outputs)
        self.assertEqual(sink.of_type("execute_result"), [])
        [d] = sink.of_type("display_data")
        self.assertIn("image/png", d["data"])

    def test_show_mid_cell(self):
        _, sink = self.run_cell("import matplotlib.pyplot as plt\n"
                                "plt.plot([1]); plt.show()\nprint('after')\n"
                                "plt.plot([2]); plt.show()")
        types = [o["output_type"] for o in sink.outputs]
        self.assertEqual(types, ["display_data", "stream", "display_data"])


if __name__ == "__main__":
    unittest.main()
