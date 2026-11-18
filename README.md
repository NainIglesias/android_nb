# Notebooks for Android

An Android app that opens Jupyter notebooks (`.ipynb`), runs them **on the phone**, and lets you edit and save them. No server or browser is needed: Python 3.10 is embedded in the app with [Chaquopy](https://chaquo.com/chaquopy/), and numpy, pandas, matplotlib, scipy and scikit-learn are bundled.

## Features

- **Projects.** On first launch the app asks you to create a project or bring one from GitHub. A project is a folder (in the app's private storage) with notebooks, Markdown notes, data files and subfolders. Notebooks in a project run inside their own folder, so `pd.read_csv("data/x.csv")` works with the repo's layout.
- **From GitHub, with Pull.** Paste `owner/repo` or a github.com link (`/tree/<branch>` picks a branch). The app downloads the files, and **Pull** later fetches only what changed. Your local edits are kept. If a file changed both on the phone and on GitHub, the phone's version stays and GitHub's is saved next to it as `name (GitHub).ext`. "Reset to GitHub" throws local changes away. Private repos need a token (a fine-grained token with read access to Contents). There is no push: it uses GitHub's REST API, not git.
- Markdown files (`.md`) are rendered, and can be edited and saved.
- Inside a project file, the ☰ button next to the back arrow shows the project's files in a side panel, with folders you can expand and collapse, so you can jump to another notebook or note.
- Open notebooks with the system file picker (local storage, Google Drive, …), with "Open with" or "Share" from other apps, or from the recent files list.
- Rendered Markdown cells, syntax-highlighted code, and Jupyter outputs: text, colored tracebacks, images, HTML tables (pandas), Markdown and JSON.
- Run a cell, run all cells, run from a given cell, interrupt, and restart. Cells run one at a time and share variables, like in Jupyter. "Run all" stops at the first error.
- `input()` opens a dialog. Matplotlib figures appear after `plt.show()` or at the end of a cell. `IPython.display` works (`display`, `HTML`, `Markdown`, `Image`, `clear_output`). `%%writefile` works, and other magics are skipped with a warning.
- Edit cells: insert, delete, move, and switch between code and text. The code editor has auto-indent and a symbol bar (Tab, brackets, quotes, …) above the keyboard.
- Closing asks to save only if you edited cells (code, text, adding, deleting or moving). Running doesn't: outputs made on the phone never match ones saved by Jupyter on a PC byte for byte (other library versions, other image rendering), and running again makes them again. If the outputs changed, the Save button stays available to keep them.
- Save back to the same file, or save a copy. Files are written in Jupyter's own format (1-space indent, sorted keys), so diffs stay clean.
- "Add data files" copies CSVs and other files into the folder the notebook runs in, so `pd.read_csv("data.csv")` works.

## Install on your phone

**From GitHub:** push a tag such as `v0.1.0`. The [build workflow](.github/workflows/build.yml) attaches `notebooks-v0.1.0.apk` to a GitHub release. Open the release page on the phone, download the APK, and allow installing from your browser when Android asks. Every build on `main` also uploads the APK as an Actions artifact.

**From this machine:** run `./gradlew assembleDebug`, then either:
- copy `app/build/outputs/apk/debug/app-debug.apk` to the phone, or
- install it over USB or Wi-Fi debugging with `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.

> **Signing:** without the signing secrets, CI signs each APK with a throwaway key. Android then refuses to install one CI build over another until you uninstall first. To get updates that install in place, create a keystore once (`keytool -genkeypair -v -keystore nb.jks -alias nb -keyalg RSA -keysize 4096 -validity 10000`) and add the repository secrets `NB_KEYSTORE_BASE64` (`base64 -w0 nb.jks`), `NB_KEYSTORE_PASSWORD`, `NB_KEY_ALIAS` and `NB_KEY_PASSWORD`. Keep `nb.jks` out of the repo.

## Build

Requirements: JDK 21 and Python 3.10 (both pinned in `mise.toml`, so run `mise install`), plus the Android SDK with `platforms;android-37.0`. Point to the SDK with `ANDROID_HOME` or `local.properties` (`sdk.dir=/home/you/Android/Sdk`).

```sh
./gradlew assembleDebug                       # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest                   # Kotlin unit tests (notebook format, lexer, ANSI, GitHub sync)
python -m unittest discover -s python-tests   # kernel tests on host Python (matplotlib tests need matplotlib installed)
```

## Python version and packages

The app uses **Python 3.10** because that is the newest version with Android builds of scikit-learn and scipy in [Chaquopy's repository](https://chaquo.com/pypi-13.1/). The bundled versions are numpy 1.23, pandas 2.1, matplotlib 3.6, scipy 1.8 and scikit-learn 1.3. That is why the APK is about 145 MB. Without scikit-learn, Python 3.13 would allow newer numpy and matplotlib and a ~77 MB APK.

### Adding packages

Packages are installed at build time, not at runtime: `!pip install` inside a notebook can't work. Add them to the `chaquopy { pip { … } }` block in `app/build.gradle.kts`. Only pure-Python packages, or packages that have Android wheels in [Chaquopy's repository](https://chaquo.com/pypi-13.1/), will install. Each one makes the APK bigger.

## How it works

| Part | Where |
|---|---|
| Kernel: runs cells, captures stdout/stderr, rich display, magics, `input()`, interrupts | `app/src/main/python/nbkernel.py` |
| matplotlib backend that sends figures as PNG | `app/src/main/python/nb_mpl_backend.py` |
| Kotlin ↔ Python bridge (one interpreter, one kernel thread) | `kernel/PythonKernel.kt` |
| nbformat v4 parsing and writing, output merging | `notebook/Notebook.kt` |
| State, file I/O, run queue | `ui/NotebookViewModel.kt` |
| Projects: list, folders, Markdown files | `data/Projects.kt`, `ui/ProjectsViewModel.kt` |
| GitHub pull: API client, three-way plan (GitHub / last pull / disk), applying it | `sync/GitHub.kt`, `sync/Sync.kt`, `sync/Puller.kt` |
| Compose UI | `ui/HomeScreen.kt`, `ui/ProjectScreen.kt`, `ui/MarkdownScreen.kt`, `ui/NotebookScreen.kt`, `ui/OutputViews.kt` |

## Limitations

- GitHub sync is pull-only, and needs a connection. Without a token GitHub allows 60 API requests an hour (a pull uses 2 or 3; file downloads don't count). Repos with more than ~100,000 files, and files over 50 MB, aren't downloaded. Git LFS files arrive as pointer files.
- Only Python, and only the bundled packages. No other kernels and no remote Jupyter server, yet.
- No ipywidgets, and HTML outputs run without JavaScript (so plotly and bokeh fall back to their text form). LaTeX in Markdown is shown as source.
- An interrupt takes effect at the next Python bytecode. A long call inside numpy, or a `time.sleep`, finishes first.
- "Restart" clears variables, but modules that were already imported stay loaded.
- nbformat 3 (pre-2015) notebooks aren't supported.
- The scipy and scikit-learn builds predate Android's 16 KB memory pages, so they may fail to load on phones that use 16 KB pages (some newer devices).
- Builds target ARM64 phones. Add `"x86_64"` to `abiFilters` to run the app on an emulator.
