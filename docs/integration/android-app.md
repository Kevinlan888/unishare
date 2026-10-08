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
or the SDK environment. Provision ABI-matched static OpenSSL in worktree-local
ignored `build-deps` as described in the [SDK guide](android.md). CMake downloads
no dependencies. Debug includes arm64-v8a and x86_64. A fresh machine may need
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

An additional `:share-sdk:lintDebug` diagnostic run on 2026-10-08 failed with
8 errors and 15 warnings (7 NewApi findings in AndroidNsd and one local.properties
PropertyEscape). SDK lint is visible debt for Task 13 assessment; it is not a
required Task 12 app gate and has not passed. Task 12 requires app lint plus SDK
assembly/public contracts. No SDK lint finding was suppressed or fixed here.

The complete selected-emulator workflow currently exits1: app unit210/0, full
app connected91/0 and app lint0 errors/22 warnings passed. SDK PublicApiTests
ran7 cases with1 failure: focusedClipboard read Text after image write and threw
ClassCastException casting to Image; eighth acceptance case was not run. Emulator
was Awake, without lock screen, and its clipboard host had focus. This required
SDK gate remains unsuppressed; Task13 must fix/re-run it. Standalone build/default
reruns were blocked by sandbox network access and interrupted pending approval,
so they are not claimed passed. Package identity is in the measured acceptance.
