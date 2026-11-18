# Notebooks for Android

An Android app that opens Jupyter notebooks (`.ipynb`), runs them **on the phone**, and lets you edit and save them. No server or browser is needed: Python 3.13 is embedded in the app with [Chaquopy](https://chaquo.com/chaquopy/), and numpy, pandas and matplotlib are bundled.

## Features

- Open notebooks with the system file picker (local storage, Google Drive, …), with "Open with" or "Share" from other apps, or from the recent files list.
- Rendered Markdown cells, syntax-highlighted code, and Jupyter outputs: text, colored tracebacks, images, HTML tables (pandas), Markdown and JSON.
- Run a cell, run all cells, run from a given cell, interrupt, and restart. Cells run one at a time and share variables, like in Jupyter. "Run all" stops at the first error.
- `input()` opens a dialog. Matplotlib figures appear after `plt.show()` or at the end of a cell. `IPython.display` works (`display`, `HTML`, `Markdown`, `Image`, `clear_output`). `%%writefile` works, and other magics are skipped with a warning.
- Edit cells: insert, delete, move, and switch between code and text. The code editor has auto-indent and a symbol bar (Tab, brackets, quotes, …) above the keyboard.
- Save back to the same file, or save a copy. Files are written in Jupyter's own format (1-space indent, sorted keys), so diffs stay clean.
- "Add data files" copies CSVs and other files into the folder the notebook runs in, so `pd.read_csv("data.csv")` works.

## Install on your phone

**From GitHub:** push a tag such as `v0.1.0`. The [build workflow](.github/workflows/build.yml) attaches `notebooks-v0.1.0.apk` to a GitHub release. Open the release page on the phone, download the APK, and allow installing from your browser when Android asks. Every build on `main` also uploads the APK as an Actions artifact.

**From this machine:** run `./gradlew assembleDebug`, then either:
- copy `app/build/outputs/apk/debug/app-debug.apk` to the phone, or
- install it over USB or Wi-Fi debugging with `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.

> **Signing:** without the signing secrets, CI signs each APK with a throwaway key. Android then refuses to install one CI build over another until you uninstall first. To get updates that install in place, create a keystore once (`keytool -genkeypair -v -keystore nb.jks -alias nb -keyalg RSA -keysize 4096 -validity 10000`) and add the repository secrets `NB_KEYSTORE_BASE64` (`base64 -w0 nb.jks`), `NB_KEYSTORE_PASSWORD`, `NB_KEY_ALIAS` and `NB_KEY_PASSWORD`. Keep `nb.jks` out of the repo.

## Build

Requirements: JDK 21 and Python 3.13 (both pinned in `mise.toml`, so run `mise install`), plus the Android SDK with `platforms;android-37.0`. Point to the SDK with `ANDROID_HOME` or `local.properties` (`sdk.dir=/home/you/Android/Sdk`).

```sh
./gradlew assembleDebug                       # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest                   # Kotlin unit tests (notebook format, lexer, ANSI)
python -m unittest discover -s python-tests   # kernel tests on host Python (matplotlib tests need matplotlib installed)
```

## Adding Python packages

Packages are installed at build time, not at runtime: `!pip install` inside a notebook can't work. Add them to the `chaquopy { pip { … } }` block in `app/build.gradle.kts`. Only pure-Python packages, or packages that have Android wheels in [Chaquopy's repository](https://chaquo.com/pypi-13.1/), will install. Each one makes the APK bigger.

## How it works

| Part | Where |
|---|---|
| Kernel: runs cells, captures stdout/stderr, rich display, magics, `input()`, interrupts | `app/src/main/python/nbkernel.py` |
| matplotlib backend that sends figures as PNG | `app/src/main/python/nb_mpl_backend.py` |
| Kotlin ↔ Python bridge (one interpreter, one kernel thread) | `kernel/PythonKernel.kt` |
| nbformat v4 parsing and writing, output merging | `notebook/Notebook.kt` |
| State, file I/O, run queue | `ui/NotebookViewModel.kt` |
| Compose UI | `ui/NotebookScreen.kt`, `ui/OutputViews.kt`, `ui/HomeScreen.kt` |

## Limitations

- Only Python, and only the bundled packages. No other kernels and no remote Jupyter server, yet.
- No ipywidgets, and HTML outputs run without JavaScript (so plotly and bokeh fall back to their text form). LaTeX in Markdown is shown as source.
- An interrupt takes effect at the next Python bytecode. A long call inside numpy, or a `time.sleep`, finishes first.
- "Restart" clears variables, but modules that were already imported stay loaded.
- nbformat 3 (pre-2015) notebooks aren't supported.
- Builds target ARM64 phones. Add `"x86_64"` to `abiFilters` to run the app on an emulator.
