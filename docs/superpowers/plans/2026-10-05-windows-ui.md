# UniShare Windows Native UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first packaged Windows client for UniShare as a native WinUI 3 device-centric sharing app with discovery, pairing, text/image/file transfer, clipboard-source sharing, activity summaries, tray operation, notifications, localization, and accessible light/dark UI.

**Architecture:** Add a Windows-only .NET solution under `windows/` with four production projects: WinUI presentation, application/domain logic, the frozen C ABI wrapper, and SQLite/filesystem infrastructure. A single `ShareSession` owns one SDK instance for the process; testable interfaces keep native callbacks, persistence, file staging, notifications, tray operations, and XAML concerns isolated.

**Tech Stack:** .NET SDK 10.0.400; C#; WinUI 3 / Windows App SDK 2.5.1; single-project MSIX; CommunityToolkit.Mvvm 8.4.2; Microsoft.Data.Sqlite 10.0.12; MSTest 4.4.1; x64 `unishare.dll` ABI v1.

**Spec:** `docs/superpowers/specs/2026-10-05-windows-ui-design.md`

**Version references:** [Windows App SDK 2.5.1](https://www.nuget.org/packages/Microsoft.WindowsAppSDK/2.5.1), [Windows SDK Build Tools 10.0.28000.2705](https://www.nuget.org/packages/Microsoft.Windows.SDK.BuildTools/10.0.28000.2705), [CommunityToolkit.Mvvm 8.4.2](https://www.nuget.org/packages/CommunityToolkit.Mvvm/8.4.2), [Microsoft.Data.Sqlite 10.0.12](https://www.nuget.org/packages/Microsoft.Data.Sqlite/10.0.12), and [single-project MSIX guidance](https://learn.microsoft.com/windows/apps/windows-app-sdk/single-project-msix), verified 2026-10-05.

## Global Constraints

- Target Windows 10 and Windows 11, x64 first; compile for `net10.0-windows10.0.19041.0` with `SupportedOSPlatformVersion` `10.0.17763.0`.
- Package the single executable as single-project MSIX; do not add a Windows service or a second background process.
- Keep exactly one application-scoped `ShareSession`; closing the window hides it, while tray “Exit” stops the SDK and terminates the process.
- Use only ABI v1 exports from `include/unishare/c_api.h` and `include/unishare/windows.h`; do not change the protocol or SDK for this feature.
- Support text, image, one file, and “send clipboard”; do not add folders, link previews, emoji, contacts, groups, or cloud features.
- “Send clipboard” reads the local clipboard and sends ordinary text or image. It never signals or performs remote clipboard writes.
- Trusted-device text is auto-accepted; trusted-device image/file offers require confirmation; every offer from an untrusted device requires confirmation.
- Persist activity metadata only: at most 500 records per device and no record older than 30 days. Never persist text bodies, clipboard bodies, image bytes, or history content copies.
- Diagnostics must not log text, clipboard content, or full file paths.
- Default the receive directory to the current user’s `Downloads\UniShare`; publish a received file only after terminal success and choose `name (n).ext` on collision.
- Provide `zh-CN` and `en-US`, follow the system light/dark theme, support high contrast, keyboard navigation, screen readers, and 100%–200% scaling.
- Keep the center device activity flow usable at the 960×640 minimum window size; collapse the right pane before compacting the left navigation.
- Use TDD for every behavior-bearing task and make one focused commit after its tests pass.

## Review Focus

- RFC UUID byte order must round-trip between `usdk_uuid` bytes and .NET `Guid`; Task 2 pins a known UUID vector in both directions.
- Concurrent outgoing sends interleaved with known incoming progress must bind the first unknown transfer ID to the correct pending operation; Task 3 covers ordering and cleanup.
- Borrowed callback spans, a late callback, or destroy-from-callback must never expose freed memory or complete an operation twice; Tasks 2 and 3 cover these lifetimes.
- Unsafe names, collision races, an unwritable receive directory, cancellation, and failed final publication must leave no visible partial file; Task 7 covers all five cases.
- Closing, notification activation, pause during active transfers, resume failure, and explicit exit must preserve one session and deterministic shutdown; Tasks 4 and 10 cover these transitions.

---

## File Structure

`windows/` is an independent .NET solution and does not alter the existing CMake ownership of the native SDK.

- `windows/src/UniShare.Application/`: domain records, policies, interfaces, session orchestration, transfer/activity services, and testable ViewModels. It has no WinUI, SQLite, or P/Invoke dependency.
- `windows/src/UniShare.Native/`: ABI structs, `LibraryImport` declarations, handle/callback ownership, native event mapping, and `IShareSdkClient` implementation.
- `windows/src/UniShare.Infrastructure/`: SQLite activity storage, JSON settings, privacy-safe diagnostics, receive-file publication, and transient clipboard/text staging.
- `windows/src/UniShare.App/`: WinUI application, three-pane shell, pages and controls, theme/localization resources, tray, notifications, activation, and MSIX manifest.
- `windows/tests/*`: one focused MSTest project per production boundary plus a real-DLL integration project.
- `windows/scripts/`: deterministic native staging, test, package, and acceptance entry points.

### Task 1: Solution Foundation and Application Contracts

**Files:**
- Create: `windows/global.json`
- Create: `windows/Directory.Build.props`
- Create: `windows/Directory.Packages.props`
- Create: `windows/UniShare.slnx`
- Create: `windows/src/UniShare.Application/UniShare.Application.csproj`
- Create: `windows/src/UniShare.Application/Models/DeviceModels.cs`
- Create: `windows/src/UniShare.Application/Models/ShareModels.cs`
- Create: `windows/src/UniShare.Application/Models/ActivityModels.cs`
- Create: `windows/src/UniShare.Application/Abstractions/IShareSdkClient.cs`
- Create: `windows/src/UniShare.Application/Abstractions/IActivityRepository.cs`
- Create: `windows/src/UniShare.Application/Abstractions/ISettingsStore.cs`
- Create: `windows/src/UniShare.Application/Abstractions/ITransferFiles.cs`
- Create: `windows/src/UniShare.Application/Abstractions/IClock.cs`
- Create: `windows/src/UniShare.Application/Policies/ReceivePolicy.cs`
- Create: `windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj`
- Create: `windows/tests/UniShare.Application.Tests/ReceivePolicyTests.cs`

**Interfaces:**
- Produces IDs `ActivityId(Guid Value)`, `DeviceId(Guid Value)`, `TransferId(Guid Value)`, `RequestId(ulong Value)`, and `OperationId(ulong Value)`.
- Produces enums `DevicePlatform`, `ContentKind`, `ShareDirection`, `ShareOrigin`, `ActivityStatus`, `ReceiveDecision`, and `ShareErrorKind`.
- Produces records `DeviceSnapshot`, `IncomingOffer`, `TransferProgress`, `TransferTerminal`, `ShareFailure`, `ShareContent`, `ShareSource`, `ActivityRecord`, `ActivityQuery(string? Text, DeviceId? Device, ActivityStatus? Status, ShareDirection? Direction)`, `AppSettings`, and `PendingSend(OperationId OperationId, Task<TransferTerminal> Completion)`.
- Produces the following exact cross-project contracts:

```csharp
public interface IShareSdkClient : IAsyncDisposable
{
    event EventHandler<ShareSdkEvent>? EventReceived;
    Task InitializeAsync(SdkOptions options, CancellationToken cancellationToken);
    Task StartAsync(CancellationToken cancellationToken);
    Task StopAsync(CancellationToken cancellationToken);
    IReadOnlyList<DeviceSnapshot> GetDevices();
    IReadOnlySet<DeviceId> GetTrustedDevices();
    Task ConnectAsync(DeviceId device, CancellationToken cancellationToken);
    Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken cancellationToken);
    Task RejectPairingAsync(RequestId request, CancellationToken cancellationToken);
    Task ForgetDeviceAsync(DeviceId device, CancellationToken cancellationToken);
    PendingSend BeginSendText(DeviceId device, string text);
    PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source);
    Task AcceptAsync(RequestId request, string stagingPath, CancellationToken cancellationToken);
    Task RejectAsync(RequestId request, CancellationToken cancellationToken);
    Task CancelAsync(TransferId transfer, CancellationToken cancellationToken);
    Task<ShareContent> ReadClipboardAsync(CancellationToken cancellationToken);
}

public interface IActivityRepository
{
    Task UpsertAsync(ActivityRecord record, CancellationToken cancellationToken);
    Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken);
    Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken cancellationToken);
    Task PruneAsync(DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, CancellationToken cancellationToken);
}

public interface ISettingsStore
{
    Task<AppSettings> LoadAsync(CancellationToken cancellationToken);
    Task SaveAsync(AppSettings settings, CancellationToken cancellationToken);
}

public interface ITransferDrain
{
    bool HasActiveTransfers { get; }
    void BlockNewTransfers();
    void AllowNewTransfers();
    Task WaitForIdleAsync(CancellationToken cancellationToken);
}

public interface ITransferFiles
{
    Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string receiveRoot, CancellationToken cancellationToken);
    Task<string> PublishAsync(PreparedReceive receive, CancellationToken cancellationToken);
    Task AbortAsync(PreparedReceive receive);
    Task<TransientDestination> CreateTextDestinationAsync(CancellationToken cancellationToken);
    Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mimeType, CancellationToken cancellationToken);
}

public interface IClock
{
    DateTimeOffset UtcNow { get; }
}
```

`ShareSdkEvent` is a closed record hierarchy for device/discovery/connection/pairing/identity/offer/registration/progress/result events. `PreparedReceive`, `TransientDestination`, and `TransientSource` expose paths plus async cleanup, never raw native handles.

- [ ] **Step 1: Create the pinned solution and project skeleton**

Pin SDK `10.0.400`; enable nullable, implicit usings, deterministic builds, warnings-as-errors, x64, and central package management. Pin `Microsoft.WindowsAppSDK` 2.5.1, `Microsoft.Windows.SDK.BuildTools` 10.0.28000.2705, `CommunityToolkit.Mvvm` 8.4.2, `Microsoft.Data.Sqlite` 10.0.12, `MSTest.TestFramework`/`MSTest.TestAdapter` 4.4.1, `Microsoft.NET.Test.Sdk` 18.10.1, and `coverlet.collector` 10.1.0.

Run: `dotnet sln windows/UniShare.slnx list`
Expected: the application and application-test projects are listed.

- [ ] **Step 2: Write the failing receive-policy tests**

Add tests named `TrustedTextIsAutoAccepted`, `TrustedImageAndFilePrompt`, and `UntrustedContentAlwaysPrompts`; assert all three `ContentKind` values and both trust states explicitly.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj --filter ReceivePolicyTests`
Expected: FAIL because `ReceivePolicy.Decide(bool, ContentKind)` is missing.

- [ ] **Step 4: Implement the domain records, interfaces, and `ReceivePolicy.Decide`**

Keep records immutable. `ActivityRecord` may contain a received-file path but must not contain text, clipboard bytes, or image bytes. Set `AppSettings` defaults to `DeviceName = Environment.MachineName`, `MaxActivitiesPerDevice = 500`, `RetentionDays = 30`, system theme/language, notifications enabled, and startup disabled.

- [ ] **Step 5: Run the foundation tests**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add windows/global.json windows/Directory.Build.props windows/Directory.Packages.props windows/UniShare.slnx windows/src/UniShare.Application windows/tests/UniShare.Application.Tests
git commit -m "feat(windows): define application contracts"
```

### Task 2: Frozen ABI Layout and Value Mapping

**Files:**
- Create: `windows/src/UniShare.Native/UniShare.Native.csproj`
- Create: `windows/src/UniShare.Native/Interop/NativeTypes.cs`
- Create: `windows/src/UniShare.Native/Interop/NativeMethods.cs`
- Create: `windows/src/UniShare.Native/Interop/NativeUtf8.cs`
- Create: `windows/src/UniShare.Native/Interop/NativeMapper.cs`
- Create: `windows/src/UniShare.Native/Interop/SafeNativeHandles.cs`
- Create: `windows/src/UniShare.Native/Interop/WindowsStreamFactory.cs`
- Create: `windows/tests/UniShare.Native.Tests/UniShare.Native.Tests.csproj`
- Create: `windows/tests/UniShare.Native.Tests/NativeLayoutTests.cs`
- Create: `windows/tests/UniShare.Native.Tests/NativeMapperTests.cs`
- Modify: `windows/UniShare.slnx`

**Interfaces:**
- Consumes: Task 1 IDs, content/device records, and `ShareFailure`.
- Produces: internal `[StructLayout(LayoutKind.Sequential)]` ABI types, `NativeMethods` ABI-v1 imports, `NativeMapper`, and safe SDK/platform/stream handle wrappers.

- [ ] **Step 1: Write failing x64 layout and UUID-vector tests**

Assert `NativeError=16`, `NativeSpan=16`, `NativeConfig=80`, `NativeDevice=72`, `NativeResult=96`, `NativeEvent=192`, and `NativeSource=48` bytes. Assert bytes `00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff` map to `00112233-4455-6677-8899-aabbccddeeff` and back.

- [ ] **Step 2: Write failing borrowed-span copy tests**

Allocate UTF-8 text and address spans, map them, overwrite/free the native buffers, and assert the managed strings and arrays remain unchanged. Add invalid UTF-8, excessive-count rejection, all 14 ABI error-code mappings, and unknown-error fallback cases.

- [ ] **Step 3: Run the native mapping tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Native.Tests/UniShare.Native.Tests.csproj --filter "NativeLayoutTests|NativeMapperTests"`
Expected: FAIL because ABI types and mapping helpers are missing.

- [ ] **Step 4: Implement ABI declarations and copy-before-return mapping**

Use `LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)` only for fixed signatures; manually marshal length-delimited spans. Reject ABI versions other than `1`. Use big-endian `Guid` span APIs for UUID conversion.

- [ ] **Step 5: Run the mapping tests**

Run: `dotnet test windows/tests/UniShare.Native.Tests/UniShare.Native.Tests.csproj --filter "NativeLayoutTests|NativeMapperTests"`
Expected: PASS on x64.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Native windows/tests/UniShare.Native.Tests windows/UniShare.slnx
git commit -m "feat(windows): map frozen native ABI"
```

### Task 3: Native SDK Client Lifecycle and Event Correlation

**Files:**
- Create: `windows/src/UniShare.Native/Interop/IUniShareNativeApi.cs`
- Create: `windows/src/UniShare.Native/Interop/UniShareNativeApi.cs`
- Create: `windows/src/UniShare.Native/NativeOperationRegistry.cs`
- Create: `windows/src/UniShare.Native/OutgoingTransferRegistry.cs`
- Create: `windows/src/UniShare.Native/NativeShareSdkClient.cs`
- Create: `windows/tests/UniShare.Native.Tests/FakeNativeApi.cs`
- Create: `windows/tests/UniShare.Native.Tests/NativeShareSdkClientTests.cs`
- Create: `windows/tests/UniShare.Native.Tests/OutgoingTransferRegistryTests.cs`

**Interfaces:**
- Consumes: Task 1 `IShareSdkClient` and Task 2 ABI mapping/handles.
- Produces: `NativeShareSdkClient : IShareSdkClient`; it synthesizes `OutgoingTransferRegistered(OperationId, TransferId)` when the first unknown progress event exposes an outgoing transfer ID.

- [ ] **Step 1: Write failing lifecycle and callback-ownership tests**

Cover initialize/start/stop/restart/destroy, synchronous native rejection with no callback, one-shot accepted completion, copied event data, late callback after stop, destroy invoked from a callback, source/destination handle registration, and stream-token release after accepted submission. Assert no task completes twice, borrowed file handles close at the documented point, and callback contexts remain alive only as long as allowed.

- [ ] **Step 2: Write failing outgoing-transfer correlation tests**

Queue two accepted outgoing operations, interleave progress for a known incoming transfer, then two unknown transfer IDs. Assert the known incoming ID consumes no queue entry, unknown IDs bind FIFO, terminal-without-progress removes its pending operation, and stop clears all registrations.

- [ ] **Step 3: Run the client tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Native.Tests/UniShare.Native.Tests.csproj --filter "NativeShareSdkClientTests|OutgoingTransferRegistryTests"`
Expected: FAIL because the client and registries are missing.

- [ ] **Step 4: Implement the fakeable API boundary, operation registry, and client**

Register an outgoing operation only after a native send returns success and a nonzero operation ID. Serialize UI-originated send submission, copy all callback/event data immediately, publish managed events off the native callback stack, and keep delegates/context roots alive through stop or destroy. Open source/destination paths with the sharing and delete semantics required by `docs/integration/windows.md`, register them with the Windows ABI, and release the returned stream token after accepted submission.

- [ ] **Step 5: Run all native tests**

Run: `dotnet test windows/tests/UniShare.Native.Tests/UniShare.Native.Tests.csproj`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Native windows/tests/UniShare.Native.Tests
git commit -m "feat(windows): wrap SDK lifecycle and events"
```

### Task 4: Device Store and Application Session Lifecycle

**Files:**
- Create: `windows/src/UniShare.Application/Services/DeviceStore.cs`
- Create: `windows/src/UniShare.Application/Services/ShareSession.cs`
- Create: `windows/src/UniShare.Application/Services/SessionState.cs`
- Create: `windows/tests/UniShare.Application.Tests/DeviceStoreTests.cs`
- Create: `windows/tests/UniShare.Application.Tests/ShareSessionTests.cs`
- Create: `windows/tests/UniShare.Application.Tests/Fakes/FakeShareSdkClient.cs`
- Create: `windows/tests/UniShare.Application.Tests/Fakes/FakeTransferDrain.cs`

**Interfaces:**
- Consumes: `IShareSdkClient`, `ITransferDrain`, device/event records.
- Produces: `DeviceStore.Devices`, `TrustedDevices`, `NearbyDevices`, and `SelectedDevice`; `ShareSession.InitializeAsync`, `StartAsync`, `PauseAsync`, `ResumeAsync`, and `ExitAsync`.

- [ ] **Step 1: Write failing device aggregation tests**

Assert online/update/offline changes mutate one stable device entry, trusted devices sort before nearby devices, an offline trusted device remains visible, and identity change marks the device blocked until forgotten.

- [ ] **Step 2: Write failing lifecycle tests**

Assert repeated window activation does not create a second SDK, pause blocks new transfers then waits for `ITransferDrain.WaitForIdleAsync` before stop, resume calls start once, resume failure stays paused, and exit stops then disposes exactly once.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj --filter "DeviceStoreTests|ShareSessionTests"`
Expected: FAIL because the services are missing.

- [ ] **Step 4: Implement device aggregation and the serialized session state machine**

Use one async gate for lifecycle transitions. Treat `Running`, `Pausing`, `Paused`, `Faulted`, and `Exiting` as explicit states; never hold the gate while awaiting UI code.

- [ ] **Step 5: Run the application tests**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj --filter "DeviceStoreTests|ShareSessionTests"`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Application/Services windows/tests/UniShare.Application.Tests
git commit -m "feat(windows): manage devices and SDK session"
```

### Task 5: Activity State Machine

**Files:**
- Create: `windows/src/UniShare.Application/Services/ActivityTracker.cs`
- Create: `windows/src/UniShare.Application/Services/ActivityTransition.cs`
- Create: `windows/tests/UniShare.Application.Tests/ActivityTrackerTests.cs`

**Interfaces:**
- Consumes: Task 1 activity models/repository and Task 3 managed SDK events.
- Produces: `ActivityTracker : ITransferDrain`, `BeginOutgoing`, `AddIncomingOffer`, `BindTransfer`, `ApplyProgress`, `Complete`, and `RetryFrom`; exposes observable per-device activity snapshots.

- [ ] **Step 1: Write failing transition tests**

Assert `AwaitingPeer/AwaitingDecision -> Transferring -> terminal`, terminal states are immutable, progress never decreases, duplicate results do not duplicate records, network loss/timeout become retryable failures, rejection/cancellation remain distinct terminal states, and a retry creates a new record linked to the failed record.

- [ ] **Step 2: Write failing privacy and drain tests**

Assert persisted activity contains no text/image/clipboard payload, in-memory text disappears when records are reloaded, active count reaches zero only at terminal state, and `WaitForIdleAsync` completes once.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj --filter ActivityTrackerTests`
Expected: FAIL because `ActivityTracker` is missing.

- [ ] **Step 4: Implement the tracker and legal transition table**

Index records by local activity ID and transfer ID separately. Allow operation-only failure before a transfer ID is registered. Keep payload previews in a non-persisted memory side table.

- [ ] **Step 5: Run the activity tests**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj --filter ActivityTrackerTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Application/Services/ActivityTracker.cs windows/src/UniShare.Application/Services/ActivityTransition.cs windows/tests/UniShare.Application.Tests/ActivityTrackerTests.cs
git commit -m "feat(windows): track transfer activity states"
```

### Task 6: SQLite Activity, Settings, and Privacy-Safe Diagnostics

**Files:**
- Create: `windows/src/UniShare.Infrastructure/UniShare.Infrastructure.csproj`
- Create: `windows/src/UniShare.Infrastructure/Storage/ActivitySchema.cs`
- Create: `windows/src/UniShare.Infrastructure/Storage/SqliteActivityRepository.cs`
- Create: `windows/src/UniShare.Infrastructure/Storage/JsonSettingsStore.cs`
- Create: `windows/src/UniShare.Infrastructure/Logging/PrivacyDiagnosticSink.cs`
- Create: `windows/tests/UniShare.Infrastructure.Tests/UniShare.Infrastructure.Tests.csproj`
- Create: `windows/tests/UniShare.Infrastructure.Tests/SqliteActivityRepositoryTests.cs`
- Create: `windows/tests/UniShare.Infrastructure.Tests/JsonSettingsStoreTests.cs`
- Create: `windows/tests/UniShare.Infrastructure.Tests/PrivacyDiagnosticSinkTests.cs`
- Modify: `windows/UniShare.slnx`

**Interfaces:**
- Consumes: Task 1 repository/settings records.
- Produces: `SqliteActivityRepository`, `JsonSettingsStore`, and `PrivacyDiagnosticSink`.

- [ ] **Step 1: Write failing repository retention and recovery tests**

Use a unique temporary directory. Assert 501 records for one device retain 500, records older than 30 days are deleted, other devices are unaffected, name/status/direction history filters return the expected summaries, a partially written settings replacement leaves the prior JSON readable, first load returns the Task 1 defaults with receive path `Downloads\UniShare`, and schema creation is idempotent.

- [ ] **Step 2: Write failing privacy-log tests**

Pass a failure containing text and a full path; assert output contains error category/platform code but neither the body nor full path. Assert received-file paths remain available in the database only for successful received files.

- [ ] **Step 3: Run the infrastructure tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Infrastructure.Tests/UniShare.Infrastructure.Tests.csproj`
Expected: FAIL because the infrastructure implementations are missing.

- [ ] **Step 4: Implement schema v1, transactional pruning, atomic settings replacement, and redaction**

Store under `%LOCALAPPDATA%\UniShare\App`; enable SQLite foreign keys and WAL. Parameterize every SQL statement. Save settings through `settings.json.tmp` plus same-directory replace.

- [ ] **Step 5: Run the infrastructure tests**

Run: `dotnet test windows/tests/UniShare.Infrastructure.Tests/UniShare.Infrastructure.Tests.csproj`
Expected: PASS and temporary directories are removed.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Infrastructure windows/tests/UniShare.Infrastructure.Tests windows/UniShare.slnx
git commit -m "feat(windows): persist private activity summaries"
```

### Task 7: Transfer Coordination, Receive Publication, and Clipboard Source

**Files:**
- Create: `windows/src/UniShare.Application/Services/TransferCoordinator.cs`
- Create: `windows/src/UniShare.Infrastructure/Files/ReceiveFileStore.cs`
- Create: `windows/src/UniShare.Infrastructure/Files/TransientContentStore.cs`
- Create: `windows/tests/UniShare.Application.Tests/TransferCoordinatorTests.cs`
- Create: `windows/tests/UniShare.Infrastructure.Tests/ReceiveFileStoreTests.cs`
- Create: `windows/tests/UniShare.Infrastructure.Tests/TransientContentStoreTests.cs`

**Interfaces:**
- Consumes: `IShareSdkClient`, `ReceivePolicy`, `ActivityTracker`, `ITransferFiles`, and settings.
- Produces the exact application surface below; `ReceiveFileStore` and `TransientContentStore` implement Task 1 file interfaces.

```csharp
Task<ActivityId> SendTextAsync(DeviceId device, string text, ShareOrigin origin, CancellationToken cancellationToken);
Task<ActivityId> SendFileAsync(DeviceId device, string path, ContentKind kind, ShareOrigin origin, CancellationToken cancellationToken);
Task<ActivityId> SendClipboardAsync(DeviceId device, CancellationToken cancellationToken);
Task AcceptAsync(ActivityId activity, CancellationToken cancellationToken);
Task RejectAsync(ActivityId activity, CancellationToken cancellationToken);
Task CancelAsync(ActivityId activity, CancellationToken cancellationToken);
Task<ActivityId> RetryAsync(ActivityId activity, CancellationToken cancellationToken);
```

- [ ] **Step 1: Write failing send and clipboard tests**

Assert empty text is rejected locally; a selected file/image uses the matching SDK call; clipboard text calls ordinary text send; clipboard image is staged then calls ordinary image send; unsupported/empty clipboard creates no activity; and transient image files are deleted after every terminal outcome.

- [ ] **Step 2: Write failing incoming-policy and text-staging tests**

Assert trusted text auto-accepts to a transient UTF-8 destination, trusted image/file prompts, every untrusted offer prompts, received text is deleted after decoding, text acceptance failure leaves a failed summary, and no path or body is persisted.

- [ ] **Step 3: Write failing receive-file safety tests**

Cover names containing separators/control characters, `.`/`..`, simultaneous collision attempts, unwritable directories, cancellation, SDK failure, final rename failure, and success. Assert only success exposes a final file and no `.unishare-partial-*` remains otherwise.

- [ ] **Step 4: Run the transfer/file tests to verify they fail**

Run: `dotnet test windows/UniShare.slnx --filter "TransferCoordinatorTests|ReceiveFileStoreTests|TransientContentStoreTests"`
Expected: FAIL because coordinators and stores are missing.

- [ ] **Step 5: Implement transfer orchestration and same-directory staging**

Create hidden `.unishare-partial-{guid}` files with user-only access in the configured destination. Select the collision-free final name immediately before atomic publication. Never use the display name as a path without sanitizing it to one filename component.

- [ ] **Step 6: Run transfer and infrastructure tests**

Run: `dotnet test windows/UniShare.slnx --filter "TransferCoordinatorTests|ReceiveFileStoreTests|TransientContentStoreTests"`
Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add windows/src/UniShare.Application/Services/TransferCoordinator.cs windows/src/UniShare.Infrastructure/Files windows/tests/UniShare.Application.Tests/TransferCoordinatorTests.cs windows/tests/UniShare.Infrastructure.Tests
git commit -m "feat(windows): coordinate secure content transfers"
```

### Task 8: Shell and Feature ViewModels

**Files:**
- Create: `windows/src/UniShare.Application/ViewModels/ShellViewModel.cs`
- Create: `windows/src/UniShare.Application/ViewModels/DeviceListViewModel.cs`
- Create: `windows/src/UniShare.Application/ViewModels/DeviceSessionViewModel.cs`
- Create: `windows/src/UniShare.Application/ViewModels/PairingViewModel.cs`
- Create: `windows/src/UniShare.Application/ViewModels/HistoryViewModel.cs`
- Create: `windows/src/UniShare.Application/ViewModels/SettingsViewModel.cs`
- Create: `windows/src/UniShare.Application/ViewModels/ActivityItemViewModel.cs`
- Create: `windows/tests/UniShare.Application.Tests/ViewModels/ShellViewModelTests.cs`
- Create: `windows/tests/UniShare.Application.Tests/ViewModels/DeviceSessionViewModelTests.cs`
- Create: `windows/tests/UniShare.Application.Tests/ViewModels/ActivityItemViewModelTests.cs`
- Create: `windows/tests/UniShare.Application.Tests/ViewModels/PairingAndSettingsViewModelTests.cs`

**Interfaces:**
- Consumes: Tasks 4–7 services and CommunityToolkit.Mvvm.
- Produces bindable members without WinUI types: `ShellViewModel.SelectedSection`, `SelectedDevice`, and `DiscoveryBanner`; `DeviceListViewModel.Trusted` and `Nearby`; `DeviceSessionViewModel.Activities`, `ComposerText`, `SendTextCommand`, `SendFileCommand`, `SendImageCommand`, `SendClipboardCommand`, `AcceptCommand`, `RejectCommand`, `CancelCommand`, and `RetryCommand`; `PairingViewModel.PendingRequest`, `ConfirmMatchesCommand`, and `RejectCommand`; `HistoryViewModel.Query`, `StatusFilter`, and `DirectionFilter`; and `SettingsViewModel.Settings`, `ValidateReceiveDirectoryAsync`, and `SaveCommand`.

- [ ] **Step 1: Write failing navigation and device-selection tests**

Assert Share is the default page, selection opens one device session, trusted/nearby groups update without losing selection, an offline selected device disables send commands, discovery failures expose a retryable banner, moved/deleted received files disable Open/OpenLocation, and identity-changed devices expose only forget/re-pair.

- [ ] **Step 2: Write failing interaction tests**

Assert send text clears the composer only after accepted submission, drag/drop routes one file, accept/reject/cancel/retry call the coordinator once, progress samples compute stable speed/ETA without division by zero, history filters combine query/status/direction, pairing code preserves spacing, and settings validation rejects an unwritable receive directory without saving.

- [ ] **Step 3: Run the ViewModel tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj --filter "ShellViewModelTests|DeviceSessionViewModelTests|ActivityItemViewModelTests|PairingAndSettingsViewModelTests"`
Expected: FAIL because the ViewModels are missing.

- [ ] **Step 4: Implement the ViewModels and async commands**

Keep UI strings out of ViewModels; expose resource keys or semantic states. Make every async command reject concurrent execution unless the operation is explicitly parallel-safe.

- [ ] **Step 5: Run all application tests**

Run: `dotnet test windows/tests/UniShare.Application.Tests/UniShare.Application.Tests.csproj`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Application/ViewModels windows/tests/UniShare.Application.Tests/ViewModels
git commit -m "feat(windows): add device sharing view models"
```

### Task 9: WinUI Three-Pane Shell, Themes, and Localization

**Files:**
- Create: `windows/src/UniShare.App/UniShare.App.csproj`
- Create: `windows/src/UniShare.App/App.xaml`
- Create: `windows/src/UniShare.App/App.xaml.cs`
- Create: `windows/src/UniShare.App/MainWindow.xaml`
- Create: `windows/src/UniShare.App/MainWindow.xaml.cs`
- Create: `windows/src/UniShare.App/Package.appxmanifest`
- Create: `windows/src/UniShare.App/CompositionRoot.cs`
- Create: `windows/src/UniShare.App/Assets/Square44x44Logo.png`
- Create: `windows/src/UniShare.App/Assets/Square150x150Logo.png`
- Create: `windows/src/UniShare.App/Assets/Wide310x150Logo.png`
- Create: `windows/src/UniShare.App/Assets/StoreLogo.png`
- Create: `windows/src/UniShare.App/Assets/SplashScreen.png`
- Create: `windows/src/UniShare.App/Features/Shell/ShellPage.xaml`
- Create: `windows/src/UniShare.App/Features/Share/SharePage.xaml`
- Create: `windows/src/UniShare.App/Features/Share/DeviceListPane.xaml`
- Create: `windows/src/UniShare.App/Features/Share/DeviceSessionView.xaml`
- Create: `windows/src/UniShare.App/Features/Share/DeviceDetailsPane.xaml`
- Create: `windows/src/UniShare.App/Features/Devices/DevicesPage.xaml`
- Create: `windows/src/UniShare.App/Features/History/HistoryPage.xaml`
- Create: `windows/src/UniShare.App/Features/Settings/SettingsPage.xaml`
- Create: `windows/src/UniShare.App/Controls/TextActivityCard.xaml`
- Create: `windows/src/UniShare.App/Controls/ImageActivityCard.xaml`
- Create: `windows/src/UniShare.App/Controls/FileActivityCard.xaml`
- Create: `windows/src/UniShare.App/Controls/IncomingOfferCard.xaml`
- Create: `windows/src/UniShare.App/Resources/Themes/Colors.xaml`
- Create: `windows/src/UniShare.App/Resources/Themes/Controls.xaml`
- Create: `windows/src/UniShare.App/Strings/en-US/Resources.resw`
- Create: `windows/src/UniShare.App/Strings/zh-CN/Resources.resw`
- Create: `windows/tests/UniShare.App.Tests/UniShare.App.Tests.csproj`
- Create: `windows/tests/UniShare.App.Tests/ResourceCoverageTests.cs`
- Create: `windows/tests/UniShare.App.Tests/XamlPolicyTests.cs`
- Modify: `windows/UniShare.slnx`

**Interfaces:**
- Consumes: Task 8 ViewModels.
- Produces: the approved three-pane shell and feature pages; no page owns SDK, database, or file operations.

- [ ] **Step 1: Write failing resource and XAML policy tests**

Assert required keys exist in both languages, every feature page has an accessible title, actionable icon buttons have automation names, literal color values occur only in theme dictionaries, empty/no-selection/offline states have localized copy, and activity templates cover every `ContentKind`/terminal state.

- [ ] **Step 2: Run the UI policy tests to verify they fail**

Run: `dotnet test windows/tests/UniShare.App.Tests/UniShare.App.Tests.csproj`
Expected: FAIL because resources and XAML are missing.

- [ ] **Step 3: Implement the app project, three panes, activity cards, and resources**

Match the approved reference: trusted/nearby device list on the left, device activity flow and composer in the center, details/actions/recent files on the right. Include empty, no-selection, offline, loading, and error states. Use `VisualStateManager` to hide the right pane first and compact left navigation second at the 960×640 floor. Add a `--demo` composition mode that supplies deterministic fake devices/activities without loading the native DLL for visual QA; use the WinUI template package assets until a separately approved brand icon exists.

- [ ] **Step 4: Run UI policy tests and compile the app**

Run: `dotnet test windows/tests/UniShare.App.Tests/UniShare.App.Tests.csproj`
Expected: PASS.

Run: `dotnet build windows/src/UniShare.App/UniShare.App.csproj -p:Platform=x64`
Expected: build succeeds without requiring `unishare.dll` at compile time.

- [ ] **Step 5: Perform the visual/accessibility smoke**

Launch the app with fake composition data and inspect 960×640 and 1440×900 at 100%, 150%, and 200% in light, dark, and high-contrast modes. Verify keyboard traversal, visible focus, Chinese/English expansion, right-pane collapse, and no clipped send/accept/reject controls.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.App windows/tests/UniShare.App.Tests windows/UniShare.slnx
git commit -m "feat(windows): build native three-pane sharing UI"
```

### Task 10: Pairing, Tray, Notifications, and Window Lifecycle

**Files:**
- Create: `windows/src/UniShare.Application/Abstractions/IAppPresence.cs`
- Create: `windows/src/UniShare.Application/Services/AppLifecycleCoordinator.cs`
- Create: `windows/src/UniShare.App/Features/Pairing/PairingDialog.xaml`
- Create: `windows/src/UniShare.App/Services/TrayIconService.cs`
- Create: `windows/src/UniShare.App/Services/AppNotificationService.cs`
- Create: `windows/src/UniShare.App/Services/WindowController.cs`
- Create: `windows/src/UniShare.App/Services/StartupTaskService.cs`
- Modify: `windows/src/UniShare.App/App.xaml.cs`
- Modify: `windows/src/UniShare.App/MainWindow.xaml.cs`
- Modify: `windows/src/UniShare.App/CompositionRoot.cs`
- Modify: `windows/src/UniShare.App/Package.appxmanifest`
- Create: `windows/tests/UniShare.Application.Tests/AppLifecycleCoordinatorTests.cs`
- Create: `windows/tests/UniShare.App.Tests/NotificationPayloadTests.cs`

**Interfaces:**
- Consumes: `ShareSession`, pairing/transfer ViewModels, and `IAppPresence` ports.
- Produces `IAppPresence.ShowWindow(DeviceId? device)`, `HideWindow()`, `SetTrayState(TrayState state)`, and `ShowNotification(AppNotificationRequest request)`; `AppLifecycleCoordinator.OnWindowCloseRequested`, `ActivateAsync`, `PauseAsync`, `ResumeAsync`, and `ExitAsync`; a `Shell_NotifyIconW` tray implementation; App SDK notifications; activation routing; close-to-tray; and startup-task settings.

- [ ] **Step 1: Write failing lifecycle tests**

Assert close hides instead of exits, repeated activation reuses the same window/session, “Pause” drains active transfers before SDK stop, “Resume” failure displays warning state, and explicit exit stops the SDK before closing tray/window resources.

- [ ] **Step 2: Write failing notification payload tests**

Assert hidden-window image/file offers produce `Reject` and `ViewAndAccept` actions with request/device IDs, notification activation selects the correct device, reject does not open the window, trusted auto-accepted text produces a content-free completion notice, untrusted text requires a decision notification, and SDK failure changes the tray to warning state.

- [ ] **Step 3: Run lifecycle/notification tests to verify they fail**

Run: `dotnet test windows/UniShare.slnx --filter "AppLifecycleCoordinatorTests|NotificationPayloadTests"`
Expected: FAIL because lifecycle/presence services are missing.

- [ ] **Step 4: Implement pairing dialog, tray icon, notifications, activation, and startup task**

Use a private message-window/tray identifier owned by `TrayIconService`; unregister it during explicit exit. Notification actions carry opaque IDs only, never text bodies or file paths. Add notification and packaged startup-task declarations to the manifest, but keep startup disabled by default.

- [ ] **Step 5: Run lifecycle and UI tests**

Run: `dotnet test windows/UniShare.slnx --filter "AppLifecycleCoordinatorTests|NotificationPayloadTests|ResourceCoverageTests|XamlPolicyTests"`
Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add windows/src/UniShare.Application windows/src/UniShare.App windows/tests/UniShare.Application.Tests windows/tests/UniShare.App.Tests
git commit -m "feat(windows): add pairing tray and notifications"
```

### Task 11: Native Runtime Staging, MSIX Packaging, and End-to-End Acceptance

**Files:**
- Modify: `windows/src/UniShare.App/Package.appxmanifest`
- Create: `windows/src/UniShare.App/Properties/PublishProfiles/win10-x64.pubxml`
- Create: `windows/scripts/build-native.ps1`
- Create: `windows/scripts/test.ps1`
- Create: `windows/scripts/package.ps1`
- Create: `windows/scripts/run-acceptance.ps1`
- Create: `windows/tests/UniShare.Integration.Tests/UniShare.Integration.Tests.csproj`
- Create: `windows/tests/UniShare.Integration.Tests/NativeAbiSmokeTests.cs`
- Create: `docs/integration/windows-app.md`
- Modify: `docs/integration/README.md`
- Modify: `windows/src/UniShare.App/UniShare.App.csproj`
- Modify: `windows/UniShare.slnx`

**Interfaces:**
- Consumes: all prior tasks and the CMake `unishare_windows` target.
- Produces: deterministic `windows/artifacts/native/bin` staging, full verification scripts, an unsigned CI MSIX, and documented signed-package/physical-device acceptance steps.

- [ ] **Step 1: Write the failing real-DLL smoke test**

Load staged `unishare.dll`, assert `usdk_abi_version() == 1`, create a Windows platform with an isolated absolute identity directory, initialize/stop/destroy once, and assert callback completion exactly once. Exercise `Start` only when `UNISHARE_LIVE_MDNS=1`; mark the physical peer matrix as an explicit opt-in category.

- [ ] **Step 2: Run the smoke test to verify staging is missing**

Run: `dotnet test windows/tests/UniShare.Integration.Tests/UniShare.Integration.Tests.csproj -p:Platform=x64`
Expected: FAIL with a clear missing `windows/artifacts/native/bin/unishare.dll` message.

- [ ] **Step 3: Implement native build/staging and the integration smoke**

`build-native.ps1` accepts `-OpenSslRoot` and `-Configuration`, builds the existing CMake DLL, installs into `windows/artifacts/native`, and verifies `unishare.dll` plus required OpenSSL runtime DLLs. App output and MSIX include those files unchanged.

- [ ] **Step 4: Implement test/package/acceptance scripts and documentation**

`test.ps1` runs existing CTest plus all .NET tests. `package.ps1` builds Release x64 MSIX with `GenerateAppxPackageOnBuild=true`; CI sets `AppxPackageSigningEnabled=false`, while documentation requires an external certificate for distribution. `run-acceptance.ps1` records Windows↔Windows/Android/iOS results for discovery, pairing, trust, text, image, file, clipboard-source transfer, rejection, cancellation, disconnect, restart, tray notification, and cleanup.

- [ ] **Step 5: Run the complete automated gate**

Run: `powershell -ExecutionPolicy Bypass -File windows/scripts/test.ps1 -Configuration Release -OpenSslRoot $env:OPENSSL_ROOT_DIR`
Expected: existing native CTest, .NET unit tests, and real-DLL smoke all PASS.

- [ ] **Step 6: Build and inspect the unsigned package**

Run: `powershell -ExecutionPolicy Bypass -File windows/scripts/package.ps1 -Configuration Release -Platform x64 -Unsigned`
Expected: one x64 `.msix` is produced; package contents include `UniShare.App.exe`, `unishare.dll`, matching OpenSSL runtime DLLs, `en-US`/`zh-CN` resources, and no test binaries or private source files.

- [ ] **Step 7: Run the physical acceptance matrix**

Run: `powershell -ExecutionPolicy Bypass -File windows/scripts/run-acceptance.ps1 -Output tests/interop/windows-ui-results-2026-10-05.json`
Expected: all spec Section 10.4 scenarios record PASS with device/platform provenance; clipboard-source text arrives as text, clipboard-source image arrives as an image offer, and neither modifies the receiver clipboard automatically.

- [ ] **Step 8: Commit**

```powershell
git add windows/src/UniShare.App windows/scripts windows/tests/UniShare.Integration.Tests docs/integration tests/interop/windows-ui-results-2026-10-05.json windows/UniShare.slnx
git commit -m "build(windows): package and verify native client"
```

## Final Verification

- [ ] Run `git status --short` and confirm only intentionally untracked local signing material or build output remains ignored.
- [ ] Run `powershell -ExecutionPolicy Bypass -File windows/scripts/test.ps1 -Configuration Release -OpenSslRoot $env:OPENSSL_ROOT_DIR` and retain the passing summary.
- [ ] Run the Release x64 MSIX build and inspect its file list.
- [ ] Launch the installed package, close to tray, resume from notification, pause/resume, and explicitly exit while watching for orphaned processes.
- [ ] Review diagnostics and SQLite rows to confirm no text body, clipboard body, image bytes, or full diagnostic path was persisted.
- [ ] Compare the shipped shell against the approved three-pane reference at 960×640 and 1440×900 in both languages and all required themes.
