# Public SDK interoperability hosts

These test hosts consume the delivered Windows C++ package, Android Kotlin AAR,
and iOS Swift XCFramework. They discover the expected peer by its unique run name,
then connect only by the SDK's discovered DeviceId. They use production mDNS,
TCP/TLS, SAS, consent and streaming. ADB/simctl and files coordinate launches and
collect evidence; they never carry share content or inject a peer endpoint.

Task 11a establishes the reusable hosts and one direction. See [matrix.md](matrix.md)
for measured results and the remaining Task 11b gates. A host `complete` record
alone is insufficient: **both logs must pass `evidence.py`**.

## Fixtures and scenario

Run `python tests/interop/fixtures.py tests/interop/build-fixtures`. It materializes
six fixtures and verifies their bytes against the committed
[SHA-256 manifest](fixtures/manifest.json): UTF-8 with an embedded NUL; valid 2×2
PNG and JPEG; an empty file; 700001 bytes (`byte[i] = i % 251`, crossing two 256 KiB
chunk boundaries); and UTF-8 text for explicit clipboard read/send. Images are
fixed binary fixtures, so image encoder versions cannot change expectations.

Each sender sends those six items in manifest order, then sends a text offer the
recipient explicitly rejects. The clipboard case writes the known fixture to
the foreground host's actual clipboard, reads it through the public SDK, checks
it, and explicitly sends it. **It replaces the clipboard contents.** It does not
claim cross-app paste permission or image clipboard coverage. Windows clipboard
cannot preserve NUL; the wire text fixture and clipboard fixture are separate.

Both hosts must receive one pairing request for the expected run name. The
explicit test-consent mode logs the SAS and calls public ConfirmPairing, and the
coordinator requires the two six-digit values to match. This is automated test
consent, not a production pairing UI; log comparison happens after the run. Each
incoming offer is independently accepted or rejected using the public API.
Successful receive bytes/hash are checked by the recipient, then compared with
the sender and committed manifest by the coordinator. Receiver staging paths are
locally generated, never based on remote display names. The seventh rejected
offer creates no destination. Hosts check operation and terminal counts; the
coordinator also checks identical transfer-ID sets, one terminal per ID, kinds,
sizes, explicit decisions and no data/terminal record before the decision.

Active cancellation, interruption cleanup, offline/update, changed identity,
malformed frame and wrong digest cases remain Task 11b extensions. This first
host scenario covers rejection only. Existing platform acceptance/core tests
cover additional failures but are not counted as cross-platform runs.

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

```powershell
./tests/interop/run-android.ps1 -Build -Serial 62041JEA316847 -Role receiver -Run wa2 -Peer interop-wa2-sender
```

The wrapper bounds instrumentation to 240 seconds, verifies its success marker
and instrumentation code, and pulls the app's JSONL to
`build-interop-wa2/android.jsonl` even on failure. It force-stops only this test app
on timeout. Use a new run name each time to avoid old trust/logs. Raw equivalent:

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
Keychain identity. Physical iPhone signing/runtime is not covered by this runner.

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
Keep that summary with the raw logs, SDK hashes, OS/tool versions and command
lines. Records contain `event`, `run`, `role`, discovered ID/platform/protocol/
addresses/port, SAS/decision, transfer ID, kind, byte count, SHA-256, progress,
operation completion and error category. Native error details are preserved in
discovery/error output when the SDK supplies them. Windows/iOS error codes use
the public numeric enum; Kotlin uses enum names. The gate normalizes the expected
TransferRejected outcome. Generated binaries, apps, fixture output and logs stay
under ignored build directories; credentials are never needed by these scripts.
