# Android System Device Name Design

## Goal

Use the Android system device name as UniShare's initial display name without changing or overwriting a name that the user has saved in UniShare.

## Behavior

- Read `Settings.Global.DEVICE_NAME` without requesting Bluetooth permissions.
- Accept the system name only when it passes the existing UniShare device-name validation.
- If the system name is absent or invalid, use `Build.MODEL` when it is valid.
- If neither value is valid, use `Android`.
- A persisted `device_name` preference always wins over the computed default.
- The system default is resolved when the process graph is created; it is not persisted automatically.

## Verification

- Unit tests cover system-name selection and both fallback levels.
- Settings repository instrumentation tests cover the injected first-run default and persisted-name precedence.
- Run the complete Android unit-test suite, Android lint, and the relevant settings instrumentation tests.
