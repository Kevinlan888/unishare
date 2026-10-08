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

Task13 measurements on 2026-10-08, `emulator-5554`, `sdk_gphone64_x86_64`, API37.
Audit baseline `795ad53`; candidate changes are committed as
`fix(android): resolve release audit findings`. XML testcase/failure/error/skipped
nodes were counted independently. Full JVM evidence is preserved before focused
tests overwrite the default report directory.

| Check | Measured result |
| --- | --- |
| App JVM | 211 tests, 0 failures/errors/skipped; 25 XML suites, including the new ABI contract |
| App lint | 0 errors, 22 warnings |
| App full connected | Post-ABI-fix 91 tests, 0 failures/errors/skipped; API37 emulator, XML UTC 08:08:55 |
| SDK host unit | NO-SOURCE; no passed SDK JVM count |
| SDK PublicApiTests | Post-ABI-fix 10 tests, 0 failures/errors/skipped; XML UTC 08:22:29; all original eight cases plus clipboard-replacement/NSD-dispatch regressions |
| Focused Activity/Transfer/RuntimeController/ResourceParity JVM | 91 tests, 0 failures/errors/skipped, 6 suites; also included in the final full 211 |
| Verification-script fixtures | 27 cases, 0 failures; malformed/failed/skipped fixtures correctly exit1 |
| SDK lint | 0 errors, 15 warnings; seven NewApi errors resolved without suppression; ignored local SDK property normalized, never committed |
| Default and selected-device test-app wrappers | Both attempted, exit1 at inventory with sandbox network Permission denied; selected wrapper confirmed emulator-5554 online/API37 |
| Equivalent direct Gradle gates | Actual task inventory, complete host/lint/debug build, serial app then SDK connected, and both release assembles passed using the approved prefix |
| VerifyOnly / standalone build-app | Not rerun for this final candidate; no new passing wrapper claim |
| Release package inspection | API26/target36, non-exported providers/services, connectedDevice only, no boot/test provider; APK/AAR ABI set agrees; no packaged user/test content |

The original SDK image-roundtrip failure was a real external emulator overwrite:
platform `text/plain`, label `host clipboard`, no URI, Activity still focused.
The test now observes clipboard publication events and verifies MIME, URI bytes
and SDK reads under a strict deadline, rebuilding only after an observed host
overwrite. Production reads still return current clipboard content, never a cache.
SDK lint uses real runtime API guards and legacy NSD fallback on older Android.

An initial final-gate app run stalled at 41/91 and the emulator went offline; it
was terminated, not counted passed. Restarting the same AVD without wiping yielded
91/91. A later attempt failed before tests due to a stale UTP logcat file lock;
stopping residual daemons released the lock and the post-ABI 91/91 run passed.
The emulator interruption's underlying cause is not established.

Release inspection also found dependency-only x86 native libraries without an
x86 SDK library. A test-first fix filters all app dependency ABIs to the SDK's
debug two/release three supported ABIs. The corrected release APK has nine native
libraries across arm64-v8a, armeabi-v7a and x86_64; the AAR has the matching three
SDK libraries. Required JNI exports and system-only dynamic dependencies passed
independent ELF inspection. No database/log/identity/sample/test fixture is bundled.

| Artifact | Bytes | SHA-256 |
| --- | --- | --- |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | 37,521,020 | `377F197EFF7634538B204DB5D721ECF1D6986272A8E4479746568F4B910958FB` |
| `android/share-sdk/build/outputs/aar/share-sdk-release.aar` | 9,072,941 | `1BA5C5F35AEE755CC4AB13F34EE8C0EACD9C1FAA1A3AECD09D68D007AE460753` |
| `android/app/build/outputs/apk/debug/app-debug.apk` | 47,739,107 | `5AC7F167B969FFFB12294799B93713C476BC7A93A6DE398BD0C97E3079590B22` |

Release APK is unsigned; assembly and inspection are not signed-package/device
acceptance. These automated results close no blocked physical/API gate, and do
not resolve the API26–28 Downloads or generic SAF publication limitations.
