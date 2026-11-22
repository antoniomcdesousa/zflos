# ZFLOS host (Android)

Opens .zfl packages (one HTML file each) and adds what a browser cannot do:
a real floating widget over other apps, a screenshot of another app, camera, share.

Version: 202611201809

## Build
Every push to `main` runs GitHub Actions and publishes `ZFLOS.apk` under **Releases**.
Open the newest release on your phone and download `ZFLOS.apk`.

## Notes
- The signing key `app/zflos.jks` is committed on purpose, so each new build installs over the old one and keeps your data. It is a personal-use key.
- Bundled packages (in `app/src/main/assets/bundled/`): Sudoku 1.1, Notepad.
- Package bridge: see `app/src/main/assets/bridge.js`.
