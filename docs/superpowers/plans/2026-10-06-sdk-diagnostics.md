# SDK Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add privacy-safe stage diagnostics across native SDK startup, discovery, connection, pairing, sending and receiving, plus Windows host file boundaries, without changing transfer behavior.

**Architecture:** A per-SDK C++ diagnostic observer emits fixed numeric events through a separate, additive C ABI callback. The Windows .NET adapter copies them to a bounded queue and the existing NLog sink writes them; App-level boundary events use the same sink.

**Tech Stack:** C++17, C ABI v1 additive export, C#/.NET, NLog 6.2.1, CTest, MSTest.

**Spec:** `docs/superpowers/specs/2026-10-06-sdk-diagnostics-design.md`

## Global Constraints

- This work only adds diagnostics; it does not change sending, receiving, protocol or UI behavior.
- Never log paths, file or device names/IDs, addresses, pairing codes, payloads, keys, arbitrary messages or stacks.
- Keep native diagnostics optional, per SDK instance and best-effort; logging failure must not affect an operation.
- Do not change the layout of `usdk_config` or existing C ABI structs; initialization before callback registration remains covered by .NET logs.
- Stage transitions and terminal outcomes only; no per-chunk or per-device log traffic. Preserve existing NLog rotation.
- Do not touch the pre-existing untracked `windows/.vs/` directory.

## Review Focus

- A callback unregistered or destroyed while delivery is in flight must not run after return (Task 1 test).
- A full managed diagnostic queue must never block a native callback or change a transfer outcome (Task 4 test).
- Unknown native enum values must become `Unknown`, never unchecked text or out-of-range indexing (Task 4 test).
- A send without an authenticated connection must log a failed submission stage with `AuthenticationFailed` (Task 3 test).
- Source-open and receive-destination failures must log fixed stage/code fields without logging their paths (Task 5 test).

---

### Task 1: Native event contract and callback lifecycle

**Files:**
- Create: `include/unishare/diagnostics.hpp` (fixed enums and `DiagnosticEvent`)
- Modify: `include/unishare/share_sdk.hpp`, `src/core/share_sdk.hpp`, `src/core/share_sdk.cpp`, `src/platform/windows/adapter.cpp`
- Modify: `include/unishare/c_api.h`, `src/c_api_internal.hpp`, `src/c_api.cpp`, `src/c_api_events.cpp`
- Test: `tests/core/sdk_api_test.cpp`, `tests/core/c_api_header_test.c`, `windows/tests/UniShare.Integration.Tests/NativeAbiSmokeTests.cs`

**Interfaces:**
- Produces `enum class DiagnosticStage : uint32_t`, `DiagnosticOutcome`, `DiagnosticDirection`, `DiagnosticContentKind`, and `struct DiagnosticEvent { stage, outcome, direction, content_kind, sequence, ShareError optional; }` in `unishare/diagnostics.hpp`. Stable stages: `Permission=1`, `Listen=2`, `Publish=3`, `Browse=4`, `NetworkChange=5`, `Stop=6`, `Connect=7`, `SocketConnect=8`, `TlsHandshake=9`, `IdentityVerify=10`, `Pairing=11`, `Offer=12`, `Accept=13`, `TransferStream=14`, `IntegrityVerify=15`, `PeerComplete=16`, `Cancel=17`, `Transfer=18`; outcomes `Began=1`, `Succeeded=2`, `Failed=3`, `Cancelled=4`; directions `None=0`, `Send=1`, `Receive=2`; content types `None=0`, `Text=1`, `Image=2`, `File=3`.
- Produces `virtual void IShareSdk::SetDiagnosticHandler(std::function<void(DiagnosticEvent)>)` and `usdk_error usdk_set_diagnostic_handler(usdk_handle, usdk_diagnostic_handler, void*)`.
- C ABI `usdk_diagnostic_event` has only `uint32_t stage,outcome,direction,content_type`, `uint64_t sequence`, and `usdk_error error`; no pointers or free-form strings. Assert x64 size `40` and each field offset in C and C# tests.

- [ ] Write failing C++ and C ABI tests: registration delivers a fixed `Stop/Began` event, replacement invalidates queued events, `NULL` unregisters, destroy waits for an in-flight callback, and existing ABI structures retain their size.
- [ ] Build `sdk_api_test` and `c_api_header_test` with `cmake --build windows/artifacts/native-build --config Release --target sdk_api_test c_api_header_test`; confirm new tests fail for missing interface/export.
- [ ] Implement the C++ observer and additive C ABI export. Emit `Stop/Began` as the first minimal native event so Task 1 is independently testable; Task 2 adds the remaining lifecycle phases. Reuse the current `State::gate` / `callbacks` generation pattern; copy event values, never call host code under `gate`, catch callback exceptions. Forward the observer through Windows `RetainedSdk`.
- [ ] Run `ctest --test-dir windows/artifacts/native-build -C Release -R 'sdk_api|sdk_api_c11' --output-on-failure`; expect both tests to pass.
- [ ] Commit only Task 1 files as `Add native SDK diagnostic callback contract`.

### Task 2: Startup and discovery stages

**Files:**
- Modify: `src/core/share_sdk.cpp`, `src/core/share_sdk.hpp`
- Test: `tests/core/sdk_lifecycle_test.cpp`, `tests/core/sdk_services_test.cpp`

**Interfaces:** Consumes Task 1 `DiagnosticEvent` and per-instance observer. Emits `Permission`, `Listen`, `Publish`, `Browse`, `NetworkChange`, `Stop` with `Began/Succeeded/Failed/Cancelled`, original `ShareError.code` and `platform_error`. Lifecycle events use sequence `0`.

- [ ] Add fake-platform tests for permission denial, listener failure, publish failure, browse error, network restart and normal stop; assert the last reached stage and exact error/category, plus no duplicate terminal event.
- [ ] Run `ctest --test-dir windows/artifacts/native-build -C Release -R 'sdk_lifecycle|sdk_services' --output-on-failure`; confirm the new assertions fail.
- [ ] Emit diagnostics at the phase boundaries in `share_sdk.cpp`, using fixed values only and keeping diagnostic delivery separate from business state/error events. Rate-limit repeated browse errors by stage/category here.
- [ ] Rebuild the two targets and rerun the same CTest filter; expect pass.
- [ ] Commit Task 2 as `Trace native startup and discovery stages`.

### Task 3: Connection, pairing and transfer stages

**Files:**
- Modify: `src/core/connection_service.cpp`, `src/core/session.cpp`, `src/core/transfer_service.cpp`, `src/core/transfer_engine.cpp`, `src/core/transfer_engine.hpp`, `src/core/share_sdk.hpp`
- Test: `tests/core/session_test.cpp`, `tests/core/transfer_engine_test.cpp`, `tests/core/sdk_services_test.cpp`

**Interfaces:** Consumes Task 1 event contract. Use process-local monotonic `uint64_t` attempt sequences; map internal transfer IDs to a sequence only in memory, never export those IDs. Emit `Connect`, `SocketConnect`, `TlsHandshake`, `IdentityVerify`, `Pairing`, `Offer`, `Accept`, `TransferStream`, `IntegrityVerify`, `PeerComplete`, `Cancel`, and terminal `Transfer`, with `Send/Receive` direction and fixed content type.

- [ ] Add failing tests for successful connect/pair, rejected pair, send without authenticated connection, offer accepted/rejected, read/write failure, integrity failure, cancellation, and successful text/file terminal. Assert phase order, failure code, exactly one terminal per attempt, and no event for each data chunk.
- [ ] Run `ctest --test-dir windows/artifacts/native-build -C Release -R 'session|transfer_engine|sdk_services' --output-on-failure`; confirm new assertions fail.
- [ ] Instrument session/connection and transfer state transitions without changing their existing callbacks or error propagation. Share one sequence per attempt across service and engine callbacks; suppress repeated transport errors by stage and category.
- [ ] Rebuild the affected native targets and rerun the CTest filter; expect pass.
- [ ] Commit Task 3 as `Trace native connection and transfer stages`.

### Task 4: Managed bridge and NLog output

**Files:**
- Modify: `windows/src/UniShare.Native/Interop/NativeTypes.cs`, `NativeMethods.cs`, `IUniShareNativeApi.cs`, `UniShareNativeApi.cs`, `NativeMapper.cs`
- Modify: `windows/src/UniShare.Native/NativeShareSdkClient.cs`, `windows/src/UniShare.Application/Models/DiagnosticModels.cs`, `windows/src/UniShare.Infrastructure/Logging/PrivacyDiagnosticSink.cs`, `windows/src/UniShare.App/Services/AppRuntime.cs`
- Test: `windows/tests/UniShare.Native.Tests/NativeLayoutTests.cs`, `NativeMapperTests.cs`, `NativeShareSdkClientTests.cs`, `FakeNativeApi.cs`; `windows/tests/UniShare.Infrastructure.Tests/PrivacyDiagnosticSinkTests.cs`

**Interfaces:** `IUniShareNativeApi.SetDiagnosticHandler(ulong sdk, nint callback, nint context)`; `NativeMapper.Diagnostic(NativeDiagnosticEvent) -> DiagnosticRecord` with `Unknown` fallback; `DiagnosticRecord` is an immutable record of stage, outcome, direction, content type, sequence, category and optional platform code in `DiagnosticModels.cs`; `PrivacyDiagnosticSink.WriteNativeEvent(DiagnosticRecord)`; `NativeShareSdkClient` registers immediately after create and unregisters before destroy. The unmanaged callback only copies the fixed struct into a bounded channel with `TryWrite`; one consumer writes NLog and reports dropped-event count as a fixed diagnostic record. An internal queue-capacity constructor parameter permits a deterministic full-queue test.

- [ ] Add failing layout/mapping tests, lifecycle tests for registration/stop/dispose and native callback exceptions, a full-queue test, and NLog tests for severity, rotation and deliberate sensitive sentinel strings not appearing.
- [ ] Run `dotnet test windows/tests/UniShare.Native.Tests/UniShare.Native.Tests.csproj -c Release -p:Platform=x64` and the Infrastructure test project; confirm the new tests fail.
- [ ] Implement interop, mapping, bounded delivery and NLog output. Keep the existing startup exception logging; if a new DLL export is missing, log `EntryPointNotFoundException` by type/code only and fail initialization clearly.
- [ ] Rerun both .NET projects and `UniShare.Integration.Tests` against the built DLL; expect pass.
- [ ] Commit Task 4 as `Route native diagnostics into NLog`.

### Task 5: Windows send and receive boundary diagnostics

**Files:**
- Modify: `windows/src/UniShare.Application/Services/TransferCoordinator.cs`, `windows/src/UniShare.Native/NativeShareSdkClient.cs`, `windows/src/UniShare.App/Services/AppRuntime.cs`, `windows/src/UniShare.Application/Models/DiagnosticModels.cs`
- Test: `windows/tests/UniShare.Application.Tests/TransferCoordinatorTests.cs`, `windows/tests/UniShare.Native.Tests/NativeShareSdkClientTests.cs`, `windows/tests/UniShare.Infrastructure.Tests/PrivacyDiagnosticSinkTests.cs`

**Interfaces:** Optional fixed-field diagnostic delegate injected into `TransferCoordinator`; phases `SourceOpen`, `CommandSubmit`, `DestinationPrepare`, `AcceptSubmit`, `Completion`. Correlation uses local operation sequence only, not path/device/transfer ID. Existing constructors remain compatible through an optional parameter.

- [ ] Add failing tests for source-open failure, successful send submission, failed native submission, receive-directory preparation failure, accept submission and terminal result; assert the diagnostic never contains the path or payload.
- [ ] Run Application and Native test projects; confirm new tests fail.
- [ ] Emit fixed stage events around existing boundaries without moving locks, awaits, ownership transfers, or exception handling; wire the delegate in `AppRuntime` to the existing sink.
- [ ] Run the full gate `windows/scripts/test.ps1 -OpenSslRoot 'D:/cs/unishare/.worktrees/windows-native-ui/windows/artifacts/deps/installed/x64-windows'`; expect all native CTests and .NET suites to pass.
- [ ] Commit Task 5 as `Trace Windows transfer boundaries`.

## Final verification

- [ ] Review the diff for sensitive field formatting, callback lifetime, new ABI layout and unrelated behavior changes.
- [ ] Run the full native/.NET gate again after review and record its pass counts. Check that the only remaining untracked path is the pre-existing `windows/.vs/`.
- [ ] On two working installs, send text and a file in both directions; inspect both `diagnostics.log` files for stage ordering and privacy. If two machines are unavailable, explicitly report this as unverified rather than claiming the send bug is fixed.
