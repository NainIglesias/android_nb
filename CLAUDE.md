# android_nb

An Android app (Kotlin + Jetpack Compose) that opens, runs and edits `.ipynb` notebooks on the device. Python 3.13 is embedded with Chaquopy. See README.md for features and layout.

## Commands

- Build: `./gradlew assembleDebug`. JDK 21 and Python 3.13 come from `mise.toml`; the SDK is in `~/Android/Sdk` (set by `local.properties`, which is gitignored).
- Kotlin tests: `./gradlew testDebugUnitTest`
- Kernel tests: `python -m unittest discover -s python-tests`. The matplotlib tests are skipped unless matplotlib is installed.
- The SDK uses the new `android` CLI (`~/Android/Sdk/cmdline-tools/latest/bin/android sdk install <pkg>`); `sdkmanager` is deprecated.

## Notes

- The versions are coupled. Chaquopy 17.2 supports AGP up to 9.5. AGP 9 has built-in Kotlin (there is no `kotlin-android` plugin), and the Compose and serialization plugins must match the Kotlin version in `gradle/libs.versions.toml`. The markdown renderer needs compileSdk 37.
- Chaquopy's build Python must have the same minor version as the app's Python (`version = "3.13"`).
- `nbkernel.py` must stay free of Android imports, so it can be tested on host CPython.
- Outputs are stored as raw nbformat JSON objects (`JsonObject`) so saving loses nothing. Only `ui/OutputViews.kt` interprets them.
- The repo is public, so never commit keystores. CI signs releases from the `NB_KEYSTORE_*` secrets.
