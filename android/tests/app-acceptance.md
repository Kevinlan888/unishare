# Android application acceptance

The release gate requires all automated checks, the version gates below, and the
physical Android application ↔ Windows application matrix. Existing SDK host
evidence is separate. An emulator never supplies physical peer evidence.

## Version gates

| Gate | Required evidence | Current status |
| --- | --- | --- |
| API 26 emulator | Install, native SDK initialization, foreground discovery, downloads/storage fallback and complete app instrumentation | BLOCKED / NOT RUN: no API 26 emulator available |
| API 33+ notification behavior | Operator grant/deny/revoke POST_NOTIFICATIONS, off-by-default background reception, denial retains off, settings recovery, notification navigation | BLOCKED / NOT RUN: no corresponding API 33 device/operator run |
| API 35 and 36 foreground service | Each API: connectedDevice permissions, foreground starts, background off/on, stop action, active send/accepted receive completion then service stop | BLOCKED / NOT RUN: no API 35/36 devices/operator run |
| Android 17 / API 37 compatibility | Target 36 native initialization and LAN access with implicit INTERNET grant; permission failure/recovery, lifecycle and real peer checks | BLOCKED as full gate: API 37 emulator instrumentation is only partial evidence; compatibility operator/real-peer run absent |

## Physical Android ↔ Windows matrix

Every row below is **BLOCKED / NOT RUN**. Missing environment for every row:
a real Android device plus a usable Windows application peer and an operator on
the same LAN. Only `emulator-5554` API 37 is currently available.

Record Android model/serial/API, app commit/APK SHA-256, Windows package
version/commit/MSIX SHA-256, network, operator, timestamp, sanitized logs or
screenshots and each result when the environment becomes available. Keep failures
and missing evidence visible; do not infer PASS from unit tests or historic SDK runs.

| Scenario | Required observation | Status |
| --- | --- | --- |
| Discovery / offline / rediscovery | Both clients see the actual peer; disconnect removes online state; reconnect restores it | BLOCKED / NOT RUN |
| First pairing | Compare all six digits on both devices; both approve before trust | BLOCKED / NOT RUN |
| Restart trust | Restart both applications; trusted reconnect succeeds without new SAS | BLOCKED / NOT RUN |
| Forget / re-pair | Forget peer, verify old trust unusable, explicitly re-pair | BLOCKED / NOT RUN |
| Text Android → Windows | Exact UTF-8 including Unicode; trustworthy text policy | BLOCKED / NOT RUN |
| Text Windows → Android | Exact UTF-8; trusted auto-receive, temporary peer consent | BLOCKED / NOT RUN |
| Image Android → Windows | Explicit consent and correct image/content | BLOCKED / NOT RUN |
| Image Windows → Android | Explicit consent, publish only after successful integrity | BLOCKED / NOT RUN |
| File Android → Windows | Exact size/content, empty and multichunk fixtures | BLOCKED / NOT RUN |
| File Windows → Android | Exact size/content, empty and multichunk fixtures | BLOCKED / NOT RUN |
| Explicit clipboard send | Foreground user click reads text/image; peer receives; no automatic/background read | BLOCKED / NOT RUN |
| Reject | Both directions terminate visibly; no published/staged content | BLOCKED / NOT RUN |
| Cancel | Sender and receiver cancellation; terminal state and staging cleanup, source preserved | BLOCKED / NOT RUN |
| Network loss / timeout | Both directions fail visibly, no partial publication; manual retry is new activity | BLOCKED / NOT RUN |
| Background reception off | No active transfer: leaving app stops discovery/reception | BLOCKED / NOT RUN |
| Background reception on | Notification consent then discovery/offer notification under lock/background; explicit accept/reject | BLOCKED / NOT RUN |
| Active transfer in background | Send and accepted receive finish with temporary FGS; service stops after terminal when setting off | BLOCKED / NOT RUN |
| Receive directory / revoked grant | Downloads and SAF directory work; revoked grant blocks publication and offers repair | BLOCKED / NOT RUN |
| Directory collision | Existing file untouched; new name uses increasing ` (1)` suffix | BLOCKED / NOT RUN |
| Byte integrity | SHA-256 and byte count match for files both directions; decoded pixels match clipboard image | BLOCKED / NOT RUN |
| UI / accessibility / privacy | Chinese/English, light/dark, large font, rotation, key actions reachable; history/diagnostics contain no payloads | BLOCKED / NOT RUN |

## Automated evidence

Measured 2026-10-08 on `emulator-5554`, `sdk_gphone64_x86_64`, API37. App source
commits `e466027` and `6818582`. XML counts parsed; HTML summaries inspected.

| Check | Measured result |
| --- | --- |
| App JVM | 210 tests, 0 failures/errors/skipped; 25 XML suites |
| App lint | 0 errors, 22 warnings |
| App full connected | 91 tests, 0 failures/errors/skipped; API37 emulator |
| SDK host unit | NO-SOURCE; no passed SDK JVM count |
| SDK PublicApiTests | **FAIL: 7 executed, 1 failure, 0 errors/skipped**; focusedClipboard Text-to-Image ClassCastException; eighth acceptance case unrun |
| Complete selected-device test-app | **exit1** at SDK gate; not all-green |
| VerifyOnly without device | exit0 for existing contracts/artifacts/unit/lint; connected SKIP |
| VerifyOnly with emulator selected | **exit1**, rejects SDK failed XML |
| Extra SDK lint diagnostic | 8 errors/15 warnings, not passed; debt outside Task12 app gate |
| Standalone build/default reruns | Sandbox exit1 at Gradle network Permission denied; retries interrupted pending approval, not passed |

Successfully assembled APK from the measured app workflow:
`D:\cs\unishare\.worktrees\android-client\android\app\build\outputs\apk\debug\app-debug.apk`.
**48,339,370 bytes**, SHA-256
`05A75984A2553056657E2631CB7B165E057F558AB455818E8C92DF4DFC5D2AFC`.
The standalone build script is not claimed passed. Task13 must fix and re-run the
SDK clipboard blocker. These automated results close no blocked physical/API gate.
