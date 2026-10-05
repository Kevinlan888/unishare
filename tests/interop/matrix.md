# Interoperability evidence

Task11b review follow-up, 2026-10-05, SDK0.1.0, native source `b77f056` plus
review verifier/host metadata changes from `e8dedd2`. All six physical directions
passed on freshly rebuilt final Session-fix packages with Windows, a Pixel and an
iPhone. Four additional iOS Simulator directions passed on the same packages.
No production or host changes were made between these runs.

## Physical matrix

| Direction | Discovery / SAS | Nine contents, reject, cancel | Restart, isolation, Offline | Run / SAS |
| --- | --- | --- | --- | --- |
| Windows → physical Pixel | PASS | PASS | PASS | `wa30` / `351740` |
| Physical Pixel → Windows | PASS | PASS | PASS | `aw30` / `349277` |
| Windows → physical iPhone | PASS | PASS | PASS | `wi44` / `098481` |
| Physical iPhone → Windows | PASS | PASS | PASS | `iw43` / `620024` |
| Physical Pixel → physical iPhone | PASS | PASS | PASS | `ai43` / `443353` |
| Physical iPhone → physical Pixel | PASS | PASS | PASS | `ia43` / `374526` |

## Supplemental Simulator matrix

| Direction | Discovery / SAS | Nine contents, reject, cancel | Restart, isolation, Offline | Run / SAS |
| --- | --- | --- | --- | --- |
| Windows → iOS Simulator | PASS | PASS | PASS | `wi30` / `866975` |
| iOS Simulator → Windows | PASS | PASS | PASS | `iw31` / `986259` |
| Physical Pixel → iOS Simulator | PASS | PASS | PASS | `ai30` / `803145` |
| iOS Simulator → physical Pixel | PASS | PASS | PASS | `ia30` / `805518` |

[Exact measured records](results-2026-10-05.json) retain discovered DeviceIds,
platform/protocol/addresses/ports, matching SAS, eleven transfer IDs per run, sizes
and SHA-256, all recipient decisions/public completions, terminal outcomes, cancel
progress, staging deletion, Updated events, restart rediscovery and Offline.
All ten raw logs were rechecked with the final [fail-closed coordinator](evidence.py), whose eleven test groups reject
corrupted evidence. Raw logs and summaries remain ignored in `build-interop-RUN/`.
[Public package hosts and commands](README.md) use no endpoint injection or alternate
sharing transport.

## Devices and scenario

- Windows10.0.26200, WLAN192.168.2.160, MSVC14.51, public C++ DLL package.
- Physical Pixel10a, Android17/API37, host target36, 192.168.2.178, release Kotlin
  AAR with arm64-v8a/armeabi-v7a/x86_64 libraries.
- Physical iPhoneSE3, iOS17.6.1 (Build21G93), WLAN192.168.2.148, freshly signed
  public Swift host using the final XCFramework device slice. Each accepted run
  emitted begin, initialization and Start success on the actual phone.
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
Receiver observed the sender Offline after the final successful transfer. The gate
requires a genuine public Updated event for the paired remote on each host and a
fresh paired snapshot with a changed listener port after restart, backed by public
Online or Updated since Stop. `share_sdk.cpp` explicitly clears catalog and paired
metadata on Stop; `DeviceCatalog::Apply` emits Online for the first inserted record.
Therefore the first restarted sighting is normally Online. The independent Updated
requirement records the paired-state change without inventing an Updated restart event.

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
cancel, pin failure and no early delivery. All ten current rows use this final fix.
Earlier `wa13`/`aw12`/`iw10`/`ai14`/`wi20`/`ia20` logs remain historical;
none supplies a current final-package acceptance row.

## Release gates and remaining acceptance

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

Before the accepted physical runs, the actual `wi41` launch
at 17:49:38 local still returned CoreDeviceError10002 / FBSOpenApplication
RequestDenied, reason `Locked` (device was not, or could not be, unlocked).
The app never started; Windows sender was cancelled before discovery and this run
is excluded. Signing/install is not runtime acceptance.
Keeping the phone awake enabled actual subsequent launches. `wi43` then started
successfully: iPhone discovered and emitted Updated for Windows, but Windows
timed out without discovering iPhone; neither side reached pairing or transfer.
This failed attempt is retained in the measured results and ignored raw logs, and
does not contribute a passing matrix cell. Its underlying cause is unestablished.
The reverse `iw43` passed with Windows receiving first. `wi44` passed with Windows
browse started before the iPhone receiver, without a production change. Both
Pixel directions also passed. These outcomes establish the measured physical
interop scenarios; they do not establish discovery reliability for every launch
order or explain the earlier timeout.

Physical interop includes explicit same-process clipboard write/read and transport
of the actual content. The separate physical cross-process clipboard acceptance
follow-up has not been measured here. Earlier signed public network acceptance
has its own narrower scope. External document-provider grants, locked Keychain,
real OS permission denial and physical interface switching are not claimed by
these matrix runs.

Wrong digest, malicious lengths, changed identity, TLS authentication, timeouts and
budget failure use owning core/adapter regression evidence. Public APIs cannot
manufacture invalid encrypted frames or replace a pinned identity, so these are
not claimed as live cross-platform runs. The six-direction physical interop gate
is closed; broader unmeasured acceptance remains outside this evidence.
