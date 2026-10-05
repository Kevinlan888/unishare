# Interoperability evidence

Task11b, 2026-10-05, SDK0.1.0, changes based on `f5f0890`. Six directions passed
with Windows, a physical Pixel, and iOS Simulator. Physical iPhone interoperability
remains an open release gate.

| Direction | Discovery / SAS | Nine contents, reject, cancel | Restart, isolation, Offline | Run / SAS |
| --- | --- | --- | --- | --- |
| Windows → physical Pixel | PASS | PASS | PASS | `wa13` / `272066` |
| Physical Pixel → Windows | PASS | PASS | PASS | `aw12` / `858239` |
| Windows → iOS Simulator | PASS | PASS | PASS | `wi20` / `772115` |
| iOS Simulator → Windows | PASS | PASS | PASS | `iw10` / `752960` |
| Physical Pixel → iOS Simulator | PASS | PASS | PASS | `ai14` / `503098` |
| iOS Simulator → physical Pixel | PASS | PASS | PASS | `ia20` / `950604` |

[Exact measured records](results-2026-10-05.json) retain discovered DeviceIds,
platform/protocol/addresses/ports, matching SAS, eleven transfer IDs per run, sizes
and SHA-256, all recipient decisions/public completions, terminal outcomes, cancel
progress, staging deletion, restart and Offline. All six raw logs were rechecked
with the final [fail-closed coordinator](evidence.py), whose ten test groups reject
corrupted evidence. Raw logs and summaries remain ignored in `build-interop-RUN/`.
[Public package hosts and commands](README.md) use no endpoint injection or alternate
sharing transport.

## Devices and scenario

- Windows10.0.26200, WLAN192.168.2.160, MSVC14.51, public C++ DLL package.
- Physical Pixel10a, Android17/API37, host target36, 192.168.2.178, release Kotlin
  AAR with arm64-v8a/armeabi-v7a/x86_64 libraries.
- iOS Simulator26.3.1 (Build23D8133), iPhone17Pro runtime26.3,
  Xcode26.3/SDK26.2, Mac Wi-Fi en1 192.168.2.233, public Swift XCFramework.
  Simulator multicast/sockets use the Mac LAN; this does not establish physical
  iPhone behavior.

Each direction transferred UTF-8/NUL text, PNG, JPEG, a zero-byte file, a
700001-byte multichunk file, actual public clipboard text, 5242888-byte streamed
UTF-8 text, actual public clipboard image, and 14-byte isolation text after failure.
Both hosts explicitly confirmed matching SAS. Receivers made and completed eleven
offer decisions. An accepted 8 MiB file was cancelled after positive incomplete
progress; receiver terminal was Cancelled and partial staging was deleted. Sender
terminal was Cancelled or NetworkLost. Another offer was rejected. Each peer had
exactly eleven matching terminals: nine successes, one rejection, one cancellation.
Both peers retained trusted DeviceId/fingerprint through Stop/Start, rediscovered
the new listener, reconnected with recipient consent and completed isolation.
Receiver observed the sender Offline after the final successful transfer.

Ordinary files/images require exact fixture bytes/hashes. Clipboard image checks
require meaningful image/png readback, the expected normalized 2×2 RGBA pixel hash,
and matching hashes/lengths of the actual transported bytes. UIKit re-encoded the
130-byte fixture to 315 bytes; equality with the original encoded PNG is not a
clipboard contract. No replacement payload is substituted. Identity evidence binds
each host's pairing remote ID to its discovery remote ID. The public API does not
expose local DeviceId, so reciprocal local-ID binding is not claimed.

## Observed defects and fixes

Task11a `wa1` timed out in Windows browse despite valid Pixel mDNS records reaching
the WLAN. Native probes isolated the final dot in `DnsServiceBrowse.QueryName`:
`_share._tcp.local` returned status0 callbacks, while `_share._tcp.local.` suppressed
live results. The adapter removes the dot only at the native browse boundary.
The seam regression covers each interface; scoped IPv6, duplicate/removal and
callback lifetime retain their existing tests.

Public transfer events initially used a deterministic `75000000-...` counter.
The service now uses OpenSSL RAND_bytes128 and supplies the same ID to the wire
engine. Incoming public events retain the received ID. Public regression checks
independent instances, uniqueness and offer/progress/result/completion correlation.
Failed random generation or active-ID collision fails closed.

`ia14`, `ia15`, and diagnostic `ia18` failed after matching SAS/connection, before
any Pixel offer. Pixel recorded AuthenticationFailed. Session content arrived
before the queued Connected observer installed the public transfer handler.
The fix retains one budgeted frame after bilateral consent, waits for write/pin
success, and resolves the handler during deferred delivery after Connected.
Public SDK TDD reproduced the failure; Session tests also cover write failure,
cancel, pin failure and no early delivery. `ia20` and `wi20` include this final fix;
the other four passing runs precede it and are measured historical evidence.

## Release gates and physical limits

Final session-fixed Windows Debug/Release CTest passed14/14 each. Android internal
ports passed10/10 and public contracts7/7 on Pixel17 and Samsung Android15. Release
AAR architecture/dependency/JNI/no-probe inspection and minified target36 consumer
passed. The consumer initially surfaced PermissionDenied because its test-app UID
retained ACCESS_LOCAL_NETWORK ignore from an earlier denial test. Restoring only
that test app's allow mode made the exact consumer gate pass; this is separate
from product changes.

Mac core passed10/10 and native ports9/9; final XCFramework device/simulator build, Swift contracts
(five groups including8MiB), public consumer normal/permission, and Simulator
clipboard cross-process/network/trust/permission acceptance passed. Native ports
and final artifact details are retained in the Task11b report.

The physical iPhoneSE3 iOS17.6.1 has a signed installed public interop host, but
CoreDevice launch returned `Locked`. Signing/install is not runtime acceptance.
Earlier signed public network acceptance has its own narrower scope. Physical
clipboard and all four iPhone cross-platform directions need an unlocked device;
no physical iPhone matrix cell is marked passed here.

Wrong digest, malicious lengths, changed identity, TLS authentication, timeouts and
budget failure use owning core/adapter regression evidence. Public APIs cannot
manufacture invalid encrypted frames or replace a pinned identity, so these are
not claimed as live cross-platform runs. Physical release acceptance remains
incomplete until the iPhone gates run.
