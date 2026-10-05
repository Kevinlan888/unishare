# Public SDK interoperability hosts

These test hosts consume the delivered Windows C++ package, Android Kotlin AAR,
and iOS Swift XCFramework. They discover the expected peer by its unique run name,
then connect only by the SDK's discovered DeviceId. They use production mDNS,
TCP/TLS, SAS, consent and streaming. ADB/simctl and files coordinate launches and
collect evidence; they never carry share content or inject a peer endpoint.

See [matrix.md](matrix.md) for measured results and physical release gates. A host `complete` record
alone is insufficient: **both logs must pass `evidence.py`**.

## Fixtures and scenario

Run `python tests/interop/fixtures.py tests/interop/build-fixtures`. It materializes
nine successful fixtures plus an 8 MiB cancellation source. It verifies the
successful fixture bytes against the committed
[SHA-256 manifest](fixtures/manifest.json): UTF-8 with an embedded NUL; valid 2×2
PNG and JPEG; an empty file; 700001 bytes (`byte[i] = i % 251`, crossing two 256 KiB
chunk boundaries); UTF-8 text for explicit clipboard read/send; 5242888-byte
UTF-8 text ending in `世界`, NUL and newline; clipboard PNG; and text sent after
failure to verify isolation. Ordinary PNG/JPEG fixture transfers retain exact
encoding, size and SHA-256 expectations.

Each sender sends the first eight items, then a text offer the recipient rejects,
then an 8 MiB file the recipient cancels after positive progress. Both SDKs
Stop/Start, verify the same trusted DeviceId and fingerprint, and rediscover the
new listener ports. The sender reconnects using that DeviceId and sends the ninth
fixture. The recipient observes final Offline before stopping. Each platform
checks 11 distinct terminals and operation completion exactly once.
The clipboard cases write known fixtures to
the foreground host's actual clipboard, reads it through the public SDK, checks
it, and explicitly sends it. **It replaces the clipboard contents.** It does not
claim cross-app paste permission. Windows clipboard
cannot preserve NUL; the wire text fixture and clipboard fixture are separate.

Both hosts receive an initial pairing request for the discovered expected peer.
Incoming pairing may precede discovery, so hosts defer confirmation until that
DeviceId has been discovered. The
explicit test-consent mode logs the SAS and calls public ConfirmPairing, and the
coordinator requires the two six-digit values to match. This is automated test
consent, not a production pairing UI; log comparison happens after the run. Each
incoming offer is independently accepted or rejected using the public API.
Successful receive bytes/hash are checked by the recipient, then compared with
the sender and committed manifest by the coordinator. Receiver staging paths are
locally generated, never based on remote display names. The rejected
offer creates no destination. Hosts check operation and terminal counts; the
coordinator also checks identical transfer-ID sets, one terminal per ID, kinds,
sizes, explicit decisions and no data/terminal record before the decision.
Each receiving decision must have exactly one successful public Accept/Reject
completion correlated by both request ID and transfer ID. A decision-intent log
alone cannot satisfy the gate.

Clipboard OS conversion may re-encode a PNG. Hosts decode the readback to 2×2
sRGB RGBA (red, green, blue, white), compare its pixel SHA-256 with the manifest,
and send the **actual public clipboard readback bytes**. The gate compares the
sender/recipient transport byte count and SHA-256 exactly, plus independent
decoded pixel hashes; it does not demand identical clipboard PNG encoding.
Cancellation evidence requires positive incomplete progress before public Cancel,
its successful completion, the failed transfer terminal, and deleted staging.
The known peer must reappear in discovery after restart before isolation consent;
otherwise its goodbye cannot produce Offline from an empty catalog. A trusted
reconnect requires a second explicit recipient connection decision.

Hosts record their configured local name, SDK platform and expected peer name.
The gate checks reciprocal names and discovered platforms against that metadata.
The public APIs do not expose the local DeviceId: the identity assertion is
limited to each host's remote pairing ID matching its own discovered remote ID.
It does **not** claim reciprocal binding to a separately observed local DeviceId.
Coherently altered logs are not authenticated evidence; preserve original logs.

Cancellation closes the active TLS connection and exercises interruption, cleanup
and reconnect isolation. Updated events from trust/address/port changes are logged
where observable. Host name/interface changes are not an API in this scenario.
Changed identity, malicious lengths and wrong digest cannot be generated through
these public APIs without tampering with the authenticated protocol. Existing core
regressions remain separate evidence, not new LAN matrix cases. Process recreation
and storage persistence are covered by owning platform acceptance; this matrix
verifies public Stop/Start and rediscovery identity/trust.

## Windows build and launch

Use an MSVC x64 developer shell with CMake on PATH. Supply the current OpenSSL
prefix with forward slashes. A stale build cache pointing to a removed dependency
directory must be reconfigured; it is not an installable package.

```powershell
cmake -S . -B build-interop-sdk -G 'NMake Makefiles' -DCMAKE_BUILD_TYPE=Debug -DOPENSSL_ROOT_DIR=D:/deps/openssl
cmake --build build-interop-sdk
cmake --install build-interop-sdk --prefix build-interop-package
cmake -S tests/interop/windows -B build-interop-windows -G 'NMake Makefiles' -Dunishare_DIR=D:/path/to/repo/build-interop-package/lib/cmake/unishare
cmake --build build-interop-windows
Copy-Item build-interop-package/bin/*.dll build-interop-windows
./tests/interop/run-windows.ps1 -Role sender -Run wa2 -Peer interop-wa2-receiver
```

The runner creates `build-interop-wa2/windows/` with an isolated DPAPI identity and
staging. It refuses reuse and kills its own host after 240 seconds. It saves
`windows.jsonl` and `windows-error.log`. Firewall and multicast prerequisites are
in [Windows integration](../../docs/integration/windows.md); the runner does not
change firewall rules. Device reachability/ping does not prove mDNS browse.

## Android build and launch

Use an unlocked, awake device on the same LAN. The empty instrumentation activity
must gain focus for actual clipboard access. The host targets API 36 and consumes
the release AAR only; it includes its own private staging ContentProvider. This
does not test the target-37 local-network permission prompt.

The PowerShell runner uses the provisioned repository Gradle/SDK cache paths;
adapt those paths for another machine. `-Build` generates fixtures, compiles only
against the AAR, installs the test app, and launches. Omit it for subsequent runs.
The provisioned offline toolchain requires `android.aapt2FromMavenOverride`, which
AGP labels experimental. That isolated build-option warning is expected; the
host assets use the supported `directories` collection without a deprecation.

```powershell
./tests/interop/run-android.ps1 -Build -Serial 62041JEA316847 -Role receiver -Run wa2 -Peer interop-wa2-sender
```

The wrapper bounds instrumentation to 240 seconds, verifies its success marker
and instrumentation code, and pulls the app's JSONL to
`build-interop-wa2/android.jsonl` even on failure. It force-stops only this test app
on timeout. Evidence retrieval must also return adb exit0; a retrieval failure
fails a successful run and is reported alongside, without replacing, an earlier
host failure. Use a new run name each time to avoid old trust/logs. Raw equivalent:

```text
adb -s SERIAL shell am instrument -w -r -e role sender -e run ai2 -e peer interop-ai2-receiver -e testConsent true com.unishare.interop/.InteropHost
adb -s SERIAL shell run-as com.unishare.interop cat files/ai2-sender.jsonl
```

## iOS Simulator build and launch

On the controller's Mac, use the existing production XCFramework and a booted
arm64 Simulator. Start both hosts within the 60-second discovery window.

```sh
bash tests/interop/ios/run.sh build-ios-xcframework-final/UniShareSDK.xcframework \
  071982EE-2278-42D1-AEEF-B1DDE4E53FF4 receiver ai2 interop-ai2-sender
```

The script compiles only against the XCFramework, generates identical fixtures,
and installs a minimal foreground UIKit host. It reuses the existing
`com.unishare.ports` acceptance-test bundle (replacing that test app) and
Simulator-only synthetic Keychain entitlements. It saves the raw console under
`build-interop-ios-ai2/receiver.log`, fails on nonzero exit/missing completion,
and terminates its test app on a 240-second timeout. A distinct run isolates the
Keychain identity. Physical iPhone signing/runtime uses the runner below.

## Physical iPhone build and launch

Use an unlocked, awake iPhone on the same LAN. Configure the existing Personal
Team locally via `DEVELOPMENT_TEAM`; keep its value and signing material out of
source control. The generated Xcode project consumes only the XCFramework,
embeds fixtures, signs automatically and replaces the `com.unishare.acceptance`
test app. Xcode destination UDID and CoreDevice UUID are separate arguments.

```sh
MODE=build bash tests/interop/ios/device.sh ARCHIVE XCODE_UDID COREDEVICE_UUID receiver wi8 interop-wi8-sender
# After the opposite host starts, launch the installed app:
xcrun devicectl device process launch --device COREDEVICE_UUID --terminate-existing --console com.unishare.acceptance receiver wi8 interop-wi8-sender --test-consent
```

Omit `MODE=build` to build/install/launch in one bounded invocation. Output and
the generated project stay in ignored `build-interop-ios-device-RUN/`. A locked
device prevents launch; signing/install alone does not establish runtime acceptance.

Any platform can take either role by swapping `sender`/`receiver` and expected
name. Each process logs `interop-RUN-ROLE` as its own advertised name.

## Evidence gate

Copy the iOS log to the same collection machine without changing its JSON lines,
then run (sender first, receiver second):

```powershell
python tests/interop/evidence.py build-interop-ai2/android.jsonl build-interop-ai2/ios.log tests/interop/fixtures/manifest.json
python -m unittest discover -s tests/interop -p test_evidence.py
```

The verifier emits a JSON PASS summary only after both logs meet the scenario.
Extended evidence requires a public Updated event for the paired remote DeviceId
on each host, plus a fresh paired snapshot with a changed listener port after
Stop/Start, backed by a public Online or Updated event after Stop. Stop clears
the local catalog, so the first sighting on restart is normally Online; requiring
Updated for that first sighting would contradict the catalog's public semantics.
Keep that summary with the raw logs, SDK hashes, OS/tool versions and command
lines. Records contain `event`, `run`, `role`, discovered ID/platform/protocol/
addresses/port, SAS/decision, transfer ID, kind, byte count, SHA-256, progress,
operation completion and error category. Native error details are preserved in
discovery/error output when the SDK supplies them. Windows/iOS error codes use
the public numeric enum; Kotlin uses enum names. The gate normalizes the expected
TransferRejected outcome. Generated binaries, apps, fixture output and logs stay
under ignored build directories; credentials are never needed by these scripts.
