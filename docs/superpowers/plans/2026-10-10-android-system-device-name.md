# Android System Device Name Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use the Android system device name as UniShare's initial display name while preserving every saved user choice.

**Architecture:** Add a small Android platform provider that selects a validated name from `Settings.Global.DEVICE_NAME`, `Build.MODEL`, then `Android`. Inject its result into `DataStoreSettingsRepository`, whose persisted preference remains the highest-priority value.

**Tech Stack:** Kotlin, Android Settings API, Preferences DataStore, JUnit 4, AndroidJUnit4

**Spec:** `docs/superpowers/specs/2026-10-10-android-system-device-name-design.md`

## Global Constraints

- Do not add Bluetooth permissions.
- Do not overwrite or migrate a persisted `device_name`.
- Reuse `isValidDeviceName` for every platform-derived candidate.
- Work directly on the current branch as explicitly authorized by the user; do not commit unless separately requested.

## Review Focus

- A blank global name falls back to a valid model name.
- An invalid global name does not enter discovery/session metadata.
- An invalid model name falls back to the literal `Android`.
- Whitespace around a valid platform name is normalized.
- A saved custom name remains unchanged after repository recreation.

---

### Task 1: Select a safe Android device name

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/platform/AndroidDeviceNameProvider.kt`
- Create: `android/app/src/test/java/com/unishare/app/platform/AndroidDeviceNameProviderTest.kt`

**Interfaces:**
- Consumes: `isValidDeviceName(raw: String): Boolean`
- Produces: `AndroidDeviceNameProvider.deviceName(): String` and testable `selectAndroidDeviceName(systemName: String?, modelName: String?): String`

- [ ] Write failing unit tests for valid system name, blank/invalid system fallback, invalid model fallback, and trimming.
- [ ] Run the focused unit test and confirm failure because the selector is absent.
- [ ] Implement the minimal provider and selector.
- [ ] Run the focused unit test and confirm it passes.

### Task 2: Wire the default into settings without overriding persistence

**Files:**
- Modify: `android/app/src/main/java/com/unishare/app/data/settings/DataStoreSettingsRepository.kt`
- Modify: `android/app/src/main/java/com/unishare/app/AppGraph.kt`
- Modify: `android/app/src/androidTest/java/com/unishare/app/data/settings/DataStoreSettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AndroidDeviceNameProvider.deviceName(): String`
- Produces: `DataStoreSettingsRepository(store, defaultDeviceName)` with persisted `device_name` precedence

- [ ] Write failing instrumentation tests for the injected first-run default and persisted-name precedence after recreation.
- [ ] Run the focused instrumentation test and confirm the new constructor behavior is absent.
- [ ] Add the injected default and wire the provider in `AppGraph`.
- [ ] Run the focused instrumentation test and confirm it passes.
- [ ] Run all Android unit tests and lint.

### Task 3: Completion review

**Files:**
- Review all files changed by Tasks 1-2.

**Interfaces:**
- Consumes: completed implementation and verification outputs
- Produces: a verified, uncommitted change ready for user review

- [ ] Inspect the final diff for permission changes, persistence regressions, and unnecessary scope.
- [ ] Run `git diff --check` and repeat the complete relevant verification.
