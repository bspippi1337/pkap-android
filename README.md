# PKap // BLCKSWAN BT

Bluetooth/toolkit variant based on the uploaded `pkapbt.zip`, patched for Android 15 build reliability.

Changes in this branch include:
- BT permission flow waits for user approval before scan/connect actions.
- Android 12+ uses `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` without unnecessary location permission in the runtime request.
- Root capture tracks and terminates only its own tcpdump PID, including after rotation.
- `MANAGE_EXTERNAL_STORAGE` removed; app backup disabled; cleartext traffic disabled.
- Gradle wrapper quoting fixed.
- Version bumped to `1.1.0-blckswan-bt` (`versionCode 2`).
- UI branded `PKAP // BLCKSWAN BT`.

The complete patched source is stored in `variants/PKap-BLCKSWAN-BT-ci-source.zip`. GitHub Actions extracts that source into the runner and builds the debug APK from it.

Use Bluetooth controls only with devices you own or are authorized to manage.
