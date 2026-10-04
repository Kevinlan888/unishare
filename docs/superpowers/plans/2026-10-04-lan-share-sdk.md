# Cross-Platform LAN Share SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a native Windows, Android, and iOS SDK for authenticated LAN discovery and user-approved text, image, file, and manual clipboard sharing.

**Architecture:** C++17 core owns identity, protocol, connection and transfer state. Platform adapters own DNS-SD, sockets, protected storage, files, clipboard and lifecycle. A stable C ABI supports Kotlin/JNI and Swift/Objective-C++ wrappers; Windows exposes a C++ wrapper.

**Tech Stack:** C++17, CMake/CTest, OpenSSL 3, Protocol Buffers, Win32 DNS-SD, Android NsdManager/JNI/Kotlin/Gradle, Apple Network framework/Swift/Xcode.

**Spec:** `docs/superpowers/specs/2026-10-04-lan-share-sdk-design.md` (approved, commit `e615c90`).

## Global Constraints

- Minimum OS: Windows 10, Android API 26, iOS 15.
- DNS-SD service: `_share._tcp.local.`; TXT keys: `id`, `name`, `platform`, `pv`; SRV/A/AAAA supply port and addresses.
- Identity: persistent random UUIDv4 plus persistent identity key; trust binds DeviceId to certificate public-key SHA-256 fingerprint.
- Transport: mutual-certificate TLS 1.3, no 0-RTT; six-digit SAS from `EXPORTER-unishare-pairing-v1`, both IDs/fingerprints/nonces; both users confirm first pairing.
- Wire version: `1.0`; 4-byte big-endian frame length includes the 1-byte type; control <=64 KiB, data payload <=256 KiB; one active transfer per connection.
- Defaults: text <=8 MiB, image <=100 MiB, file <=20 GiB; 8 connections, 4 pending receives, 4 MiB buffers; 60-second offer and pairing confirmation timeouts.
- Every transfer needs receiving user confirmation; clipboard is read only on explicit host action and is never watched.
- Mobile operation is guaranteed only while the host app is foreground and SDK started; no directory transfer, relay or resume.
- All callbacks run on the configured executor or the SDK serial callback queue; every operation has exactly one terminal result.

## File Map

- `CMakeLists.txt`, `CMakePresets.json`, `cmake/Dependencies.cmake`: core and Windows builds, tests, pinned dependencies.
- `include/unishare/{types,platform,share_sdk,discovery,connection,transfer,clipboard}.hpp`: public C++ interfaces and value types.
- `include/unishare/c_api.h`, `src/c_api.cpp`: versioned C ABI and ownership rules.
- `proto/share_v1.proto`, `src/protocol/{frame_codec,wire_codec}.{hpp,cpp}`: wire schema and bounded framing.
- `src/core/{device_catalog,identity,pairing,session,transfer_engine,share_sdk}.{hpp,cpp}`: narrow core units.
- `src/platform/windows/{dns_sd,storage,clipboard,files,adapter}.{hpp,cpp}`: Windows services.
- `android/`: Gradle AAR, Kotlin API, JNI and Android platform services.
- `ios/ShareSdk.xcodeproj`, `ios/`: Swift API, Objective-C++ bridge, Apple platform services, XCFramework script.
- `tests/core/`, `tests/windows/`, `tests/interop/`: unit, platform and paired-device checks.
- `docs/integration/{windows,android,ios}.md`, `docs/protocol-v1.md`: integration and wire contract.

## Review Focus

1. A malformed or oversized TXT record must not create a device or crash browsing; Task 2 tests it.
2. A familiar DeviceId advertised with a different key must fail connection without rewriting trust; Task 5 tests it.
3. Unsupported clipboard formats must return `InvalidInput` without reading or sending unrelated content; Tasks 9 and 10 test it.
4. Disk exhaustion after an accepted file offer must remove temporary output and report `StorageFailed`; Task 6 tests it.
5. A network interface switch during discovery must re-register, avoid duplicate devices, and emit one update; Tasks 2 and 8 test it.

---

### Task 1: Core contract and build baseline

**Files:** Create `CMakeLists.txt`, `CMakePresets.json`, `cmake/Dependencies.cmake`, `include/unishare/{types,platform,share_sdk,discovery,connection,transfer,clipboard}.hpp`, `tests/core/contracts_test.cpp`.

**Interfaces:** Define `DeviceInfo`, `ShareError`, `Result<T>`, `OperationId`, `TransferId`, `ContentType`, `SdkConfig`; `IPlatform` exposes DNS-SD, socket, identity store, stream, clipboard and executor ports. Define the five public interfaces named in the spec with asynchronous result callbacks and `Start/Stop` lifecycle.

- [ ] Write `contracts_test.cpp`: assert the enum values, default limits, UUID parsing, and exactly-once completion of a cancelled operation using a fake executor.
- [ ] Run `cmake -S . -B build -DBUILD_TESTING=ON` and `ctest --test-dir build -R contracts --output-on-failure`; expect test target missing or failing.
- [ ] Implement the headers and smallest CMake test target; require OpenSSL 3 and Protobuf 3.21 or newer in `cmake/Dependencies.cmake`, and fail configuration clearly when dependencies are absent instead of downloading silently.
- [ ] Run the same commands; expect `contracts` PASS. Commit `feat: establish SDK core contracts`.

### Task 2: DNS-SD device catalog

**Files:** Create `src/core/device_catalog.{hpp,cpp}`, `tests/core/device_catalog_test.cpp`; extend `include/unishare/discovery.hpp`.

**Interfaces:** `DeviceCatalog::Apply(ServiceRecord) -> vector<DeviceEvent>`, `Remove(ServiceInstanceId, TimePoint)`, `Expire(TimePoint)`, `Snapshot() -> vector<DeviceInfo>`; platform records carry SRV target/port, A/AAAA addresses and TXT bytes.

- [ ] Write tests for TXT fields, invalid UTF-8/oversized TXT, same UUID on two interfaces, changed name/address, self UUID, incompatible major version, 5-second offline debounce and interface switch with one `Updated`.
- [ ] Run `ctest --test-dir build -R device_catalog --output-on-failure`; expect FAIL.
- [ ] Implement strict parsing and catalog keyed by DeviceId; use SRV/A/AAAA for endpoints and preserve per-instance references before emitting `Offline`.
- [ ] Run the test; expect PASS. Commit `feat: add deduplicated device catalog`.

### Task 3: Versioned and bounded wire codec

**Files:** Create `proto/share_v1.proto`, `src/protocol/{frame_codec,wire_codec}.{hpp,cpp}`, `tests/core/protocol_test.cpp`, `docs/protocol-v1.md`.

**Interfaces:** `FrameCodec::Encode(Message) -> Bytes`, `FrameCodec::Feed(ByteSpan) -> Result<vector<Message>>`; message IDs 1–9 and fields exactly match the spec. `Data` uses 16-byte transfer ID, 8-byte big-endian sequence and <=256 KiB payload.

- [ ] Write tests for a canonical Hello/Offer/Data vector, fragmented input, 64 KiB control limit, 256 KiB data limit, unknown type, invalid length and major version mismatch.
- [ ] Run `ctest --test-dir build -R protocol --output-on-failure`; expect FAIL.
- [ ] Define stable Protobuf field numbers and generated sources; implement incremental parser that rejects oversize lengths before allocating; publish the vectors in `docs/protocol-v1.md`.
- [ ] Run the test; expect PASS. Commit `feat: define sharing wire protocol v1`.

### Task 4: Persistent identity and trust storage

**Files:** Create `src/core/identity.{hpp,cpp}`, `tests/core/identity_test.cpp`; extend `include/unishare/platform.hpp`.

**Interfaces:** `IdentityManager::LoadOrCreate(IIdentityStore&) -> Result<LocalIdentity>`, `TrustStore::Pin(DeviceId, Fingerprint)`, `Match(DeviceId, Fingerprint)`, `Forget(DeviceId)`; store operations are atomic and errors propagate.

- [ ] Write tests for stable UUID/key across restart, fresh install identity, failed secure storage, trust pin/forget and changed fingerprint rejection.
- [ ] Run `ctest --test-dir build -R identity --output-on-failure`; expect FAIL.
- [ ] Implement UUIDv4 and identity-key creation using OpenSSL CSPRNG, atomic persistence through the platform port, and no silent replacement on read failure.
- [ ] Run the test; expect PASS. Commit `feat: persist identity and pinned peers`.

### Task 5: TLS session, pairing and connection states

**Files:** Create `src/core/{pairing,session}.{hpp,cpp}`, `tests/core/session_test.cpp`; extend `include/unishare/connection.hpp`.

**Interfaces:** `Session::Connect(DeviceInfo)`, `Session::Accept(SocketHandle)`, `ConfirmPairing(OperationId,bool)`, `Close(ShareError)`; emits `PairingRequested`, `Connected`, `IdentityChanged`, `Disconnected`. `Pairing::ComputeSas(Exporter, InitiatorIdentity, ResponderIdentity) -> SixDigitCode`.

- [ ] Write loopback tests: both users see same SAS, an altered certificate/DeviceId/exporter changes SAS or fails trust, rejection/60-second timeout closes connection, changed pinned key never overwrites trust, repeated attempts hit rate limit.
- [ ] Run `ctest --test-dir build -R session --output-on-failure`; expect FAIL.
- [ ] Implement OpenSSL mutual-certificate TLS 1.3 (no 0-RTT), Hello negotiation, SAS, pairing confirmation and pinned reconnect; cap pending pairing at four and attempts per source at three per minute.
- [ ] Run the test; expect PASS. Commit `feat: add authenticated LAN sessions`.

### Task 6: Transfer engine and receiving confirmation

**Files:** Create `src/core/transfer_engine.{hpp,cpp}`, `tests/core/transfer_engine_test.cpp`; extend `include/unishare/transfer.hpp`.

**Interfaces:** `TransferEngine::Offer(ContentSource) -> TransferId`, `Accept(TransferId, ContentSink)`, `Reject(TransferId)`, `Cancel(TransferId)`; events include offer, progress and one terminal result.

- [ ] Write tests for all three content types, zero-byte file, 256 KiB chunk edge, rejected/expired offer, cancellation, SHA-256 mismatch, 20 GiB declared-size rejection, disk-full cleanup, filename traversal and disconnected peer.
- [ ] Run `ctest --test-dir build -R transfer_engine --output-on-failure`; expect FAIL.
- [ ] Implement streaming with one active transfer per session, backpressure and 4 MiB buffer cap; require accepted staging sink before Data; deliver only after checksum and byte count match.
- [ ] Run the test; expect PASS. Commit `feat: stream approved content safely`.

### Task 7: SDK orchestration and stable C ABI

**Files:** Create `src/core/share_sdk.{hpp,cpp}`, `include/unishare/c_api.h`, `src/c_api.cpp`, `tests/core/sdk_api_test.cpp`.

**Interfaces:** `ShareSdk::Initialize(SdkConfig,IPlatform&)`, `Start()`, `Stop()` and interface accessors; `usdk_create/usdk_start/usdk_stop/usdk_destroy` plus opaque handles, length-bearing strings/bytes, callback registration and `usdk_free` in ABI version 1.

- [ ] Write tests for Start/Stop idempotence, shutdown cancellation, serial callback ordering, C ABI ownership and exception-to-error conversion.
- [ ] Run `ctest --test-dir build -R sdk_api --output-on-failure`; expect FAIL.
- [ ] Wire catalog, session and transfer ports; implement C ABI with explicit ownership and version query, ensuring no C++ exception crosses ABI.
- [ ] Run the test; expect PASS. Commit `feat: expose SDK lifecycle and C ABI`.

### Task 8: Windows adapter and C++ package

**Files:** Create `src/platform/windows/{dns_sd,storage,clipboard,files,adapter}.{hpp,cpp}`, `tests/windows/windows_adapter_test.cpp`, `docs/integration/windows.md`; modify `CMakeLists.txt`.

**Interfaces:** `WindowsPlatform : IPlatform`; `CreateWindowsShareSdk(SdkConfig) -> unique_ptr<IShareSdk>`; package DLL/import library/headers.

- [ ] Write Windows tests for DNS-SD registration/browse/resolve, interface switch, DPAPI persistence, clipboard text/image, read/write file streams and permission/firewall failures.
- [ ] Run `ctest --test-dir build -R windows_adapter --output-on-failure`; expect FAIL on a configured Windows C++ toolchain.
- [ ] Implement Win32 `DnsServiceRegister/Browse/Resolve`, sockets, DPAPI, clipboard and file ports; package the C++ wrapper and write host setup instructions.
- [ ] Run the adapter test and a two-process Windows loopback text/file smoke test; expect PASS. Commit `feat: ship Windows LAN share adapter`.

### Task 9: Android adapter and Kotlin AAR

**Files:** Create `android/gradlew.bat`, `android/gradle/wrapper/*`, `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/share-sdk/build.gradle.kts`, `android/share-sdk/src/main/{java/com/unishare/sdk/*.kt,cpp/jni_bridge.cpp,AndroidManifest.xml}`, `android/share-sdk/src/androidTest/java/com/unishare/sdk/InteropTest.kt`, `docs/integration/android.md`.

**Interfaces:** Kotlin `ShareSdk`, `DeviceDiscovery`, `ConnectionManager`, `TransferService`, `ClipboardService` mirror the C++ API; JNI maps opaque C ABI handles and posts callbacks to the configured executor.

- [ ] Write instrumentation tests for NsdManager publish/browse/resolve, identity restart, permissions, content URI streaming, text/image clipboard, unsupported clipboard format `InvalidInput`, lifecycle interruption and JNI callback ownership.
- [ ] Run `android/gradlew.bat :share-sdk:connectedDebugAndroidTest`; expect FAIL before implementation (requires Android SDK/device).
- [ ] Implement Android platform ports and Kotlin wrapper; build `arm64-v8a`, `armeabi-v7a`, `x86_64` native libraries into the AAR; document manifest/runtime permissions by API level.
- [ ] Run instrumentation and `android/gradlew.bat :share-sdk:assembleRelease`; expect PASS and AAR. Commit `feat: ship Android sharing AAR`.

### Task 10: iOS adapter and Swift XCFramework

**Files:** Create `ios/ShareSdk.xcodeproj/project.pbxproj`, `ios/Sources/ShareSdk/*.swift`, `ios/Sources/ShareSdkBridge/*.mm`, `ios/Sources/ShareSdkBridge/include/*.h`, `ios/Tests/ShareSdkTests.swift`, `ios/build-xcframework.sh`, `docs/integration/ios.md`.

**Interfaces:** Swift `ShareSdk`, `DeviceDiscovery`, `ConnectionManager`, `TransferService`, `ClipboardService` mirror Kotlin/C++; Objective-C++ bridges the C ABI and retains callbacks until cancellation/completion.

- [ ] Write iOS tests for Bonjour publish/browse, Keychain persistence, local-network denial, foreground/background interruption, file URL streaming, clipboard text/image and unsupported clipboard format `InvalidInput`.
- [ ] Run `xcodebuild test -project ios/ShareSdk.xcodeproj -scheme ShareSdk -destination 'platform=iOS Simulator,name=iPhone 16'`; expect FAIL before implementation (requires macOS/Xcode).
- [ ] Implement Network framework Bonjour/socket, Keychain, UIPasteboard and Swift wrapper; build device and simulator slices with `ios/build-xcframework.sh`; document Info.plist keys.
- [ ] Run Xcode tests and XCFramework build on macOS; expect PASS. Commit `feat: ship iOS sharing XCFramework`.

### Task 11: Cross-platform interoperability and release checks

**Files:** Create `tests/interop/README.md`, `tests/interop/matrix.md`, `tests/interop/fixtures/*`, `docs/integration/README.md`; modify platform docs as needed.

**Interfaces:** Use public native APIs only; no test-only protocol path. Record pairwise direction, OS version, SDK version, pass/fail and evidence in `tests/interop/matrix.md`.

- [ ] Define fixtures and scripts for zero-byte and large file, UTF-8 text, PNG/JPEG, manual clipboard, malicious length, wrong digest and changed identity.
- [ ] Run Windows↔Android, Windows↔iOS and Android↔iOS in both directions on one LAN; expect initial failures until all adapters and packages interoperate.
- [ ] Fix only observed protocol/platform defects, preserve golden wire vectors and add regression tests to owning task's test suite.
- [ ] Repeat all six directions; require discovery, pairing, all four content flows, offline/update, cancellation and failure isolation to PASS. Run full CTest, Android instrumentation and Xcode tests; commit `test: verify three-platform interoperability`.

## Execution environment

Visual Studio 18 Insiders is installed at `C:\Program Files\Microsoft Visual Studio\18\Insiders`; it contains MSVC 14.51.36231 and CMake 4.3.1 with the `Visual Studio 18 2026` generator, although they are not on the current PATH. Run `Common7\Tools\Launch-VsDevShell.ps1` from that installation and add `Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin` to PATH before Task 1. No Android SDK/NDK, Gradle wrapper, or macOS/Xcode runner has yet been confirmed; inspect/provision them before their platform gates. A release claim requires the physical cross-platform matrix; simulator-only or Windows-only results are partial evidence.
