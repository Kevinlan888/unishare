# Android application build and acceptance

The application in `android/app` uses the repository `:share-sdk`, targets and
compiles API 36, and declares minimum API 26. The authoritative
[design](../superpowers/specs/2026-10-07-android-app-design.md) defines behavior.
[Application acceptance](../../android/tests/app-acceptance.md) keeps release gates
and measured evidence separate from historical SDK-host interoperability.

## Prerequisites and commands

Use Windows PowerShell 5.1+ or PowerShell 7, JDK 21, Gradle wrapper 9.4.1,
Android platform 36, Build Tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1.
Configure `JAVA_HOME`/PATH and the SDK through `android/local.properties` (`sdk.dir`)
or the SDK environment. In a Properties file escape a Windows drive colon, for
example `sdk.dir=C\:/path/to/Android/Sdk`; keep that machine-local file ignored.
Provision ABI-matched static OpenSSL in worktree-local
ignored `build-deps` as described in the [SDK guide](android.md). CMake downloads
no dependencies. App dependency ABI filters match the SDK: debug includes
arm64-v8a and x86_64; release also includes armeabi-v7a, not x86. A fresh machine may need
network access for Gradle's distribution/dependencies. Do not commit keys,
identities, reports, build output or received content.

From the repository root:

```powershell
# Host unit/lint and SDK debug checks; connected tests explicitly skipped.
powershell -NoProfile -ExecutionPolicy Bypass -File android/scripts/test-app.ps1

adb devices -l
# Exact online serial selected explicitly; never auto-run all connected devices.
powershell -NoProfile -ExecutionPolicy Bypass -File android/scripts/test-app.ps1 `
    -DeviceSerial emulator-5554

powershell -NoProfile -ExecutionPolicy Bypass -File android/scripts/build-app.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File android/scripts/test-app.ps1 -VerifyOnly
# Release-candidate assembly/inspection is a separate gate; APK is unsigned.
.\android\gradlew.bat -p android :app:assembleRelease :share-sdk:assembleRelease
git diff --check
```

Both scripts derive the Android project from `$PSScriptRoot`; absolute script
paths work from any current directory, including paths with spaces. Neither
calls `clean`. A real tool failure, missing output, malformed/empty test report,
failed/skipped test, or app lint Error/Fatal exits nonzero. Lint warnings are
counted. Build prints absolute APK path, SHA-256 and bytes without reading signing
secrets or invoking signingReport.

Normal testing checks actual Gradle task availability, then runs
`:app:testDebugUnitTest`, `:app:lintDebug`, `:share-sdk:testDebugUnitTest`,
`:share-sdk:assembleDebug` and `:app:assembleDebug`. The SDK has no host JUnit
sources; NO-SOURCE is reported honestly and supplies no passed SDK test count.
With `-DeviceSerial`, adb first verifies that exact serial is online/authorized.
The script sets/restores `ANDROID_SERIAL` for AGP and runs
`:app:connectedDebugAndroidTest`, then serially
`:share-sdk:connectedDebugAndroidTest -PpublicTests=ON`. The public SDK runner
exercises facade contracts, native lifecycle, URI ownership, identity, focused
clipboard and same-device acceptance; it does not establish peer delivery.
Supply `-AdbPath 'C:\path with spaces\adb.exe'` if necessary. An absent serial
prints connected SKIP even if adb has devices, leaving the connected/release gate
unverified. Missing/offline/unauthorized selected serials fail before Gradle starts.

`-VerifyOnly` runs neither Gradle nor adb. It verifies static AGP/module task
contracts, SDK values, source/SDK manifest permission union and connectedDevice
service, then existing APK/AAR, merged manifest application ID/SDK/permissions,
app unit XML/HTML and app lint XML/HTML. With
`-DeviceSerial`, it additionally requires both modules' connected XML/HTML. This
reads existing reports only; it cannot prove the named serial produced them or
that they are fresh. Operator provenance/freshness checks remain mandatory.
Static task inference is distinct from normal mode's actual task inventory.
`-OutputRoot <directory>` is only valid with VerifyOnly and expects `app/build`
and `share-sdk/build` below it. Point it at a new empty temporary directory to
verify missing-output failure without deleting real outputs.

## Artifacts and evidence

| Artifact | Path relative to repository root |
| --- | --- |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Debug SDK AAR | `android/share-sdk/build/outputs/aar/share-sdk-debug.aar` |
| Unsigned release APK | `android/app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release SDK AAR | `android/share-sdk/build/outputs/aar/share-sdk-release.aar` |
| App unit XML/HTML | `android/app/build/test-results/testDebugUnitTest/TEST-*.xml`, `android/app/build/reports/tests/testDebugUnitTest/index.html` |
| App lint XML/HTML | `android/app/build/reports/lint-results-debug.{xml,html}` |
| Connected XML/HTML | `android/{app,share-sdk}/build/outputs/androidTest-results/connected/debug/TEST-*.xml`, `android/{app,share-sdk}/build/reports/androidTests/connected/debug/index.html` |

Record XML test/failure/error/skipped counts, lint warnings, report paths,
timestamp, exact app commit, device/API and APK hash. BUILD SUCCESSFUL or an old
report alone is insufficient. The SDK release-AAR/consumer procedures remain in
the [SDK guide](android.md); this debug workflow does not claim to repeat release
package acceptance.

## Current release gates and concerns

API 26, API 33+ notification consent/revocation, each API 35/36 FGS path and
Android 17/API 37 target-36 compatibility require separate evidence. API 37
emulator instrumentation supplies partial automated evidence only. All physical
Android application ↔ Windows application rows remain BLOCKED / NOT RUN: a real
Android device, usable Windows application peer and operator are missing. Complete
the [matrix](../../android/tests/app-acceptance.md) against exact packages before
release; the [Windows gate](windows-app.md) stays independently intact.

Current publisher behavior rejects API 26–28 Downloads and generic SAF tree
publication because safe atomic publication is not established. These remain
functional concerns against the design's API 26+ and selectable-directory
requirements. The API guard prevents unsupported pending-Downloads recovery; it
does not implement the missing publication path. Keep storage acceptance blocked
until implementation and device/operator evidence satisfy the design.

Task13 resolved the SDK automated blocker. The image clipboard had actually been
overwritten by the emulator host as `text/plain` with label `host clipboard`,
despite Activity focus. The event-driven test verifies the SDK's publication
MIME/URI/bytes and current snapshot read, rebuilding only for an observed host
overwrite within a strict deadline. No production cache or fake read was added.
All original eight public cases now run, plus two current-content/NSD regressions:
10/10 passed. SDK lint moved from 8 errors/15 warnings to 0 errors/15 warnings:
seven NewApi errors were fixed with actual runtime guards and legacy fallback;
the sole remaining machine-local PropertyEscape was corrected only in the ignored
local file. No suppression or minSdk increase was used.

Final automated evidence: app JVM211/0, app connected91/0, SDK connected10/0,
focused JVM91/0, verifier fixtures27/0, app lint0 errors/22 warnings and SDK lint0
errors/15 warnings. Direct Gradle task inventory, debug builds and both release
assemblies passed. Default/selected test-app wrappers were attempted but exited1
at inventory with sandbox network Permission denied; the selected attempt did
confirm emulator-5554 online/API37. Equivalent approved direct Gradle commands
supplied the gate evidence, with app/SDK connected strictly serial. These are not
passing wrapper or standalone build-app claims. No final VerifyOnly rerun is claimed.

Release audit removed dependency-only x86 libraries unsupported by the SDK using
test-first app ABI filters. Actual package manifest/content/ELF inspections passed;
the unsigned release APK and AAR hashes/sizes are recorded in
[acceptance](../../android/tests/app-acceptance.md). One earlier app run stalled at
41/91 before the emulator went offline; it was not counted passed. A no-wipe AVD
restart and a later stale-daemon lock cleanup preceded successful full reruns.
Keep those environment failures visible; the emulator crash cause is unproven.
