using System.Reflection;
using System.Runtime.InteropServices;
using System.Threading.Channels;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Native.Interop;

namespace UniShare.Native.Tests;

[TestClass]
public sealed class NativeShareSdkClientTests
{
    [TestMethod]
    public async Task MissingDiagnosticExportFailsInitializationWithFixedExceptionType()
    {
        var api = new FakeNativeApi { DiagnosticRegistrationException = new EntryPointNotFoundException("secret path") };
        var events = new List<(DiagnosticStage Stage, DiagnosticOutcome Outcome, Exception? Error)>();
        await using var client = new NativeShareSdkClient(api, null,
            (stage, outcome, _, error) => events.Add((stage, outcome, error)));
        var initialize = client.InitializeAsync(new() { DeviceName = "device" }, default);
        await Assert.ThrowsExactlyAsync<EntryPointNotFoundException>(async () => await initialize.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.AreEqual(1, api.DestroyCount);
        Assert.IsTrue(events.Any(value => value is { Stage: DiagnosticStage.SdkInitialize, Outcome: DiagnosticOutcome.Failed,
            Error: EntryPointNotFoundException }));
    }
    [TestMethod]
    public async Task NativeDiagnosticsRemainRegisteredThroughStopAndUnregisterBeforeDestroy()
    {
        var api = new FakeNativeApi();
        var received = Channel.CreateUnbounded<DiagnosticRecord>();
        await using var client = new NativeShareSdkClient(api, null, null, value => received.Writer.TryWrite(value));
        var initialize = client.InitializeAsync(new() { DeviceName = "device" }, default);
        Assert.AreNotEqual((nint)0, api.DiagnosticCallback);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        var start = client.StartAsync(default);
        api.Complete(api.Latest.Operation);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
        var stop = client.StopAsync(default);
        api.Complete(api.Latest.Operation);
        await stop.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreNotEqual((nint)0, api.DiagnosticCallback);
        api.EmitDiagnostic(new() { Stage = 6, Outcome = 2 });
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        Assert.AreEqual(NativeDiagnosticStage.Stop, (await received.Reader.ReadAsync(timeout.Token)).Stage);
        await client.DisposeAsync();
        Assert.AreEqual((nint)0, api.DiagnosticCallback);
        Assert.AreEqual((nint)0, api.DiagnosticRegistrations[^1].Callback);
    }

    [TestMethod]
    public async Task FullDiagnosticQueueDropsWithoutBlockingNativeAndReportsCount()
    {
        var api = new FakeNativeApi();
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var received = Channel.CreateUnbounded<DiagnosticRecord>();
        await using var client = new NativeShareSdkClient(api, null, null, value =>
        {
            if (value.Sequence == 1) { entered.Set(); release.Wait(TimeSpan.FromSeconds(5)); }
            received.Writer.TryWrite(value);
        }, diagnosticQueueCapacity: 1);
        var initialize = client.InitializeAsync(new() { DeviceName = "device" }, default);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        api.EmitDiagnostic(new() { Stage = 18, Outcome = 1, Sequence = 1 });
        Assert.IsTrue(entered.Wait(TimeSpan.FromSeconds(5)));
        api.EmitDiagnostic(new() { Stage = 18, Outcome = 1, Sequence = 2 });
        var callback = Task.Run(() => api.EmitDiagnostic(new() { Stage = 18, Outcome = 1, Sequence = 3 }));
        await callback.WaitAsync(TimeSpan.FromSeconds(1));
        release.Set();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var values = new[] { await received.Reader.ReadAsync(timeout.Token), await received.Reader.ReadAsync(timeout.Token),
            await received.Reader.ReadAsync(timeout.Token) };
        Assert.IsTrue(values.Any(value => value.Stage == NativeDiagnosticStage.DiagnosticQueue && value.Sequence == 1));
        Assert.IsFalse(values.Any(value => value.Stage == NativeDiagnosticStage.Transfer && value.Sequence == 3));
    }

    [TestMethod]
    public async Task ThrowingDiagnosticSinkDoesNotBreakNativeCallback()
    {
        var api = new FakeNativeApi();
        await using var client = new NativeShareSdkClient(api, null, null, _ => throw new IOException("secret"));
        var initialize = client.InitializeAsync(new() { DeviceName = "device" }, default);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        api.EmitDiagnostic(new() { Stage = 18, Outcome = 3 });
        var start = client.StartAsync(default);
        api.Complete(api.Latest.Operation);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
    }
    private static readonly DeviceId Peer = new(Guid.Parse("00112233-4455-6677-8899-aabbccddeeff"));
    private static IShareSdkClient Create(FakeNativeApi api)
    {
        var type = Assembly.Load("UniShare.Native").GetType("UniShare.Native.NativeShareSdkClient");
        Assert.IsNotNull(type, "Missing native lifecycle client");
        return (IShareSdkClient)Activator.CreateInstance(type, BindingFlags.Instance | BindingFlags.NonPublic,
            null, new object?[] { api, null }, null)!;
    }
    private static async Task<IShareSdkClient> Running(FakeNativeApi api)
    {
        var client = Create(api);
        var initialize = client.InitializeAsync(new() { DeviceName = "设备", MaxConnections = 3 }, default);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        var start = client.StartAsync(default);
        api.Complete(api.Latest.Operation);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
        return client;
    }

    [TestMethod]
    public async Task FailedStartReportsSdkStageAndNativeFailure()
    {
        var api = new FakeNativeApi();
        var entries = new List<(DiagnosticStage Stage, DiagnosticOutcome Outcome, ShareFailure? Failure, Exception? Error)>();
        await using var client = new NativeShareSdkClient(api, null, (stage, outcome, failure, error) =>
            entries.Add((stage, outcome, failure, error)));
        var initialize = client.InitializeAsync(new() { DeviceName = "设备" }, default);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        var start = client.StartAsync(default);
        api.Complete(api.Latest.Operation, new() { Code = 2, HasPlatformError = 1, PlatformError = 10013 });
        await Assert.ThrowsExactlyAsync<NativeShareSdkException>(async () => await start.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.IsTrue(entries.Any(entry => entry.Stage == DiagnosticStage.SdkInitialize && entry.Outcome == DiagnosticOutcome.Succeeded));
        var failed = entries.Single(entry => entry.Stage == DiagnosticStage.SdkStart && entry.Outcome == DiagnosticOutcome.Failed);
        Assert.AreEqual(10013L, failed.Failure?.PlatformError);
        Assert.IsNull(failed.Error);
    }

    [TestMethod]
    public async Task FailedInitializeReportsSdkStageAndNativeFailure()
    {
        var api = new FakeNativeApi();
        var entries = new List<(DiagnosticStage Stage, DiagnosticOutcome Outcome, ShareFailure? Failure)>();
        await using var client = new NativeShareSdkClient(api, null, (stage, outcome, failure, _) =>
            entries.Add((stage, outcome, failure)));
        var initialize = client.InitializeAsync(new() { DeviceName = "设备" }, default);
        api.Complete(api.Latest.Operation, new() { Code = 11, HasPlatformError = 1, PlatformError = 5 });
        await Assert.ThrowsExactlyAsync<NativeShareSdkException>(async () => await initialize.WaitAsync(TimeSpan.FromSeconds(5)));
        var failed = entries.Single(entry => entry.Stage == DiagnosticStage.SdkInitialize && entry.Outcome == DiagnosticOutcome.Failed);
        Assert.AreEqual(5L, failed.Failure?.PlatformError);
    }
    private static NativeEvent Progress(Guid id) => new() { Kind = 11, Transfer = NativeMapper.ToNativeUuid(id), Transferred = 5, ByteCount = 20 };
    private static Channel<ShareSdkEvent> Events(IShareSdkClient client)
    {
        var channel = Channel.CreateUnbounded<ShareSdkEvent>();
        client.EventReceived += (_, value) => channel.Writer.TryWrite(value);
        return channel;
    }
    private static async Task<T> Next<T>(Channel<ShareSdkEvent> channel) where T : ShareSdkEvent
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        while (await channel.Reader.ReadAsync(timeout.Token) is { } value)
            if (value is T expected) return expected;
        throw new AssertFailedException("No matching event.");
    }

    [TestMethod]
    public async Task InitializeStartStopRestartDestroyOwnsSdkAndCopiesConfiguration()
    {
        var api = new FakeNativeApi();
        var client = await Running(api);
        Assert.AreEqual("设备", api.CopiedText);
        Assert.AreEqual(3u, api.Config.MaxConnections);
        CollectionAssert.Contains(api.Ownership, "platform released");
        var stop = client.StopAsync(default);
        Assert.AreEqual(NativeCommand.Stop, api.LastCommand);
        api.Complete(api.Latest.Operation);
        await stop.WaitAsync(TimeSpan.FromSeconds(5));
        var start = client.StartAsync(default);
        api.Complete(api.Latest.Operation);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
        await client.DisposeAsync(); await client.DisposeAsync();
        Assert.AreEqual(1, api.DestroyCount);
        Assert.ThrowsExactly<ObjectDisposedException>(() => client.BeginSendText(Peer, "hello"));
    }

    [TestMethod]
    public async Task SynchronousRejectionAndZeroOperationDoNotLeavePendingCompletion()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        api.NextError = new() { Code = 13 };
        var rejected = client.BeginSendText(Peer, "hello");
        Assert.AreEqual(0UL, rejected.OperationId.Value);
        Assert.AreEqual(ShareErrorKind.InvalidInput, (await rejected.Completion).Failure!.Kind);
        api.ZeroOperation = true;
        var malformed = client.BeginSendText(Peer, "hello");
        Assert.AreEqual(0UL, malformed.OperationId.Value);
        Assert.IsNotNull((await malformed.Completion).Failure);
        var events = Events(client);
        api.Emit(Progress(Peer.Value));
        Assert.IsInstanceOfType<ShareSdkEvent.ProgressReceived>(await events.Reader.ReadAsync());
    }

    [TestMethod]
    public async Task AcceptedCompletionBeforeSubmissionReturnsSettlesExactlyOnce()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        api.DuringSubmit = call => Task.Run(() => api.Complete(call.Operation, new() { Code = 7 })).GetAwaiter().GetResult();
        var send = client.BeginSendText(Peer, "payload");
        api.DuringSubmit = null;
        var terminal = await send.Completion.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(ShareErrorKind.TransferRejected, terminal.Failure!.Kind);
        api.Complete(send.OperationId.Value, new() { Code = 8 });
        Assert.AreSame(terminal, await send.Completion);
    }

    [TestMethod]
    public async Task BorrowedEventStorageIsCopiedAndSubscriberRunsOffNativeStack()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var events = Events(client);
        var callbackStack = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        client.EventReceived += (_, _) => callbackStack.TrySetResult(FakeNativeApi.OnNativeStack);
        using (var name = new NativeUtf8("设备\0name"))
            api.Emit(new() { Kind = 1, Device = new() { Id = NativeMapper.ToNativeUuid(Peer.Value), Name = name.Span } });
        Assert.AreEqual("设备\0name", (await Next<ShareSdkEvent.DeviceOnline>(events)).Device.Name);
        Assert.IsFalse(await callbackStack.Task);
    }

    [TestMethod]
    public async Task StopCancelsSendsIgnoresLateEventsAndRestartUsesFreshCorrelation()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var events = Events(client);
        var send = client.BeginSendText(Peer, "hello");
        var old = api.LastRegistration;
        var stop = client.StopAsync(default);
        api.Complete(api.Latest.Operation);
        await stop.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(ShareErrorKind.Cancelled, (await send.Completion).Failure!.Kind);
        FakeNativeApi.Emit(old, Progress(Guid.NewGuid()));
        api.Complete(send.OperationId.Value, new() { Code = 8 });
        var start = client.StartAsync(default); api.Complete(api.Latest.Operation); await start;
        var fresh = client.BeginSendText(Peer, "fresh");
        FakeNativeApi.Emit(old, Progress(Guid.NewGuid()));
        api.Emit(Progress(Peer.Value));
        var registered = await Next<ShareSdkEvent.OutgoingTransferRegistered>(events);
        Assert.AreEqual(fresh.OperationId, registered.OperationId);
        Assert.AreEqual(new TransferId(Peer.Value), registered.TransferId);
        Assert.IsInstanceOfType<ShareSdkEvent.ProgressReceived>(await events.Reader.ReadAsync());
    }

    [TestMethod]
    public async Task DestroyFromManagedEventCallbackDoesNotDeadlockAndCancelsPendingTasks()
    {
        var api = new FakeNativeApi();
        var client = await Running(api);
        var send = client.BeginSendText(Peer, "hello");
        var disposed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        client.EventReceived += (_, _) =>
        {
            client.DisposeAsync().AsTask().GetAwaiter().GetResult();
            disposed.TrySetResult();
        };
        api.Emit(new() { Kind = 5, State = 1 });
        await disposed.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(1, api.DestroyCount);
        Assert.AreEqual(ShareErrorKind.Cancelled, (await send.Completion).Failure!.Kind);
    }

    [TestMethod]
    public async Task CallbackContextIsRootedAcrossStopAndReleasedAfterDestroy()
    {
        var api = new FakeNativeApi();
        var client = await Running(api);
        var context = api.Latest.Context;
        var root = new WeakReference(GCHandle.FromIntPtr(context).Target!);
        var stop = client.StopAsync(default); api.Complete(api.Latest.Operation); await stop;
        GC.Collect(); GC.WaitForPendingFinalizers(); GC.Collect();
        Assert.IsTrue(root.IsAlive);
        api.DuringDestroy = () => Assert.IsNotNull(GCHandle.FromIntPtr(context).Target);
        await client.DisposeAsync();
        // GCHandle is freed after destroy; the client may still be alive through this local.
        Assert.IsFalse(((GCHandle)client.GetType().GetField("callbackRoot", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(client)!).IsAllocated);
    }

    [TestMethod]
    public async Task ClipboardBytesAreCopiedBeforeCompletionReturns()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var read = client.ReadClipboardAsync(default);
        var memory = Marshal.AllocHGlobal(4);
        try
        {
            Marshal.Copy(new byte[] { 1, 2, 3, 4 }, 0, memory, 4);
            api.Complete(api.Latest.Operation, content: new() { Type = 2, Image = new() { Data = memory, Length = 4 } });
            Marshal.Copy(new byte[4], 0, memory, 4);
        }
        finally { Marshal.FreeHGlobal(memory); }
        CollectionAssert.AreEqual(new byte[] { 1, 2, 3, 4 }, (await read.WaitAsync(TimeSpan.FromSeconds(5))).ImageBytes.ToArray());
    }

    [TestMethod]
    public async Task IncomingProgressDoesNotBindOutgoingAndTerminalWithoutProgressRemovesPendingSend()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var events = Events(client);
        var first = client.BeginSendText(Peer, "first"); var second = client.BeginSendText(Peer, "second");
        var incoming = Guid.Parse("aaaaaaaa-0000-0000-0000-000000000001");
        api.Emit(new() { Kind = 10, ContentType = 1, Transfer = NativeMapper.ToNativeUuid(incoming) });
        api.Emit(Progress(incoming));
        await Next<ShareSdkEvent.ProgressReceived>(events);
        Assert.IsFalse(events.Reader.TryRead(out _));
        api.Complete(first.OperationId.Value, new() { Code = 7 }); await first.Completion;
        api.Emit(Progress(Peer.Value));
        Assert.AreEqual(second.OperationId, (await Next<ShareSdkEvent.OutgoingTransferRegistered>(events)).OperationId);
    }

    [TestMethod]
    public async Task FileAndDestinationRegistrationCloseOriginalHandlesAndReleaseStreamAfterSubmission()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var sourcePath = Path.GetTempFileName(); var stagingPath = Path.GetTempFileName();
        try
        {
            api.DuringSubmit = call =>
            {
                var handle = api.LastCommand == NativeCommand.Accept ? api.DestinationHandle : api.SourceHandle;
                Assert.IsFalse(GetHandleInformation(handle, out _), "Original must close after registration and before submission.");
            };
            var send = client.BeginSendFile(Peer, ContentKind.File, new(sourcePath, "sample", "application/octet-stream", 0));
            CollectionAssert.AreEqual(new[] { "platform released", "source registered", "submitted", "stream released" }, api.Ownership);
            api.Complete(send.OperationId.Value); await send.Completion;
            var accept = client.AcceptAsync(new(12), stagingPath, default);
            CollectionAssert.AreEqual(new[] { "platform released", "source registered", "submitted", "stream released", "destination registered", "submitted", "stream released" }, api.Ownership);
            api.Complete(api.Latest.Operation); await accept;
            Assert.IsTrue(File.Exists(sourcePath)); Assert.IsTrue(File.Exists(stagingPath));
        }
        finally { File.Delete(sourcePath); File.Delete(stagingPath); }
    }
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetHandleInformation(nint handle, out uint flags);

    [TestMethod]
    public async Task RejectedFileSubmissionStillReleasesRegisteredToken()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var path = Path.GetTempFileName();
        try
        {
            api.NextError = new() { Code = 14 };
            var send = client.BeginSendFile(Peer, ContentKind.Image, new(path, "image", "image/png", 0));
            Assert.AreEqual(ShareErrorKind.ResourceLimit, (await send.Completion).Failure!.Kind);
            Assert.AreEqual("stream released", api.Ownership[^1]);
            Assert.IsFalse(GetHandleInformation(api.SourceHandle, out _));
        }
        finally { File.Delete(path); }
    }

    [TestMethod]
    public async Task ConcurrentSendsAreSerializedBeforeFifoBinding()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var active = 0; var peak = 0;
        api.DuringSubmit = _ =>
        {
            var count = Interlocked.Increment(ref active);
            InterlockedExtensions.Max(ref peak, count);
            Thread.SpinWait(100000);
            Interlocked.Decrement(ref active);
        };
        var sends = await Task.WhenAll(Enumerable.Range(0, 12).Select(index => Task.Run(() => client.BeginSendText(Peer, index.ToString()))));
        Assert.AreEqual(1, peak);
        var events = Events(client);
        foreach (var send in sends.OrderBy(value => value.OperationId.Value))
        {
            var id = Guid.NewGuid(); api.Emit(Progress(id));
            Assert.AreEqual(send.OperationId, (await Next<ShareSdkEvent.OutgoingTransferRegistered>(events)).OperationId);
        }
    }

    [TestMethod]
    public async Task UnknownAbiIsRejectedBeforeNativeCreation()
    {
        var api = new FakeNativeApi { Version = 2 };
        await using var client = Create(api);
        await Assert.ThrowsExactlyAsync<NotSupportedException>(() => client.InitializeAsync(new(), default));
        Assert.AreEqual(0, api.Calls.Count);
    }

    [TestMethod]
    public async Task SourceOpenFailureDoesNotExposeFullPathInDiagnostics()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString(), "private-name.txt");
        var error = Assert.ThrowsExactly<UniShare.Native.NativeShareSdkException>(
            () => client.BeginSendFile(Peer, ContentKind.File, new(path, "private-name.txt", "text/plain", 0)));
        Assert.IsFalse(error.Message.Contains(path, StringComparison.Ordinal));
        Assert.IsFalse(error.Message.Contains("private-name", StringComparison.Ordinal));
    }

    [TestMethod]
    public async Task NativeFileOpenFailuresReportFixedBoundariesWithoutPaths()
    {
        var api = new FakeNativeApi();
        var channel = Channel.CreateUnbounded<TransferDiagnosticRecord>();
        await using var client = new NativeShareSdkClient(api, null, null, null, value => channel.Writer.TryWrite(value));
        var initialize = client.InitializeAsync(new() { DeviceName = "device" }, default);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        var start = client.StartAsync(default);
        api.Complete(api.Latest.Operation);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString(), "private-secret.txt");
        Assert.ThrowsExactly<NativeShareSdkException>(() => client.BeginSendFile(Peer, ContentKind.File,
            new(path, "private-secret.txt", "application/octet-stream", 0)));
        await Assert.ThrowsExactlyAsync<NativeShareSdkException>(async () => await client.AcceptAsync(new(1), path, default));
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var values = new[] { await channel.Reader.ReadAsync(timeout.Token), await channel.Reader.ReadAsync(timeout.Token),
            await channel.Reader.ReadAsync(timeout.Token), await channel.Reader.ReadAsync(timeout.Token) };
        Assert.IsTrue(values.Any(value => value is { Stage: TransferDiagnosticStage.SourceOpen,
            Outcome: NativeDiagnosticOutcome.Failed, Category: ShareErrorKind.StorageFailed }));
        Assert.IsTrue(values.Any(value => value is { Stage: TransferDiagnosticStage.DestinationPrepare,
            Outcome: NativeDiagnosticOutcome.Failed, Category: ShareErrorKind.StorageFailed }));
        Assert.IsFalse(string.Join(" ", values).Contains("private-secret", StringComparison.Ordinal));
    }

    [TestMethod]
    public async Task CancellingAcceptedInitializationReleasesCallerWhileSdkStillCompletesInitialization()
    {
        var api = new FakeNativeApi();
        await using var client = Create(api);
        using var cancellation = new CancellationTokenSource();
        var initialize = client.InitializeAsync(new(), cancellation.Token);
        var operation = api.Latest.Operation;
        cancellation.Cancel();
        await Assert.ThrowsExactlyAsync<TaskCanceledException>(() => initialize.WaitAsync(TimeSpan.FromSeconds(5)));
        api.Complete(operation);
        // Start is serialized behind the completing initialization.
        api.DuringSubmit = call => Task.Run(() => api.Complete(call.Operation)).GetAwaiter().GetResult();
        var start = client.StartAsync(default);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [TestMethod]
    public async Task RegistrationFailureClosesOwnedFileWithoutSubmittingOrReleasingInvalidToken()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var path = Path.GetTempFileName();
        try
        {
            api.RegistrationError = new() { Code = 11, HasPlatformError = 1, PlatformError = 5 };
            api.DuringRegistration = file => Assert.IsTrue(GetHandleInformation(file, out _));
            var failure = Assert.ThrowsExactly<UniShare.Native.NativeShareSdkException>(
                () => client.BeginSendFile(Peer, ContentKind.File, new(path, "file", "application/octet-stream", 0)));
            Assert.AreEqual(5L, failure.Failure.PlatformError);
            Assert.IsFalse(GetHandleInformation(api.SourceHandle, out _));
            CollectionAssert.AreEqual(new[] { "platform released", "source registered" }, api.Ownership);
        }
        finally { File.Delete(path); }
    }

    [TestMethod]
    public async Task NonemptyStagingIsRejectedWithoutTruncation()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var path = Path.GetTempFileName();
        try
        {
            File.WriteAllBytes(path, [1, 2, 3]);
            Assert.ThrowsExactly<IOException>(() => client.AcceptAsync(new(1), path, default));
            CollectionAssert.AreEqual(new byte[] { 1, 2, 3 }, File.ReadAllBytes(path));
            Assert.AreEqual(0, api.DestinationHandle);
        }
        finally { File.Delete(path); }
    }

    [TestMethod]
    public async Task SnapshotRootsAreFreedAfterNestedDataIsCopiedAndOnMappingFailure()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        using var text = new NativeUtf8("copied");
        var devices = Marshal.AllocHGlobal(Marshal.SizeOf<NativeDevice>());
        var root = Marshal.AllocHGlobal(Marshal.SizeOf<NativeDevices>());
        var freed = new List<nint>();
        try
        {
            Marshal.StructureToPtr(new NativeDevice { Id = NativeMapper.ToNativeUuid(Peer.Value), Name = text.Span }, devices, false);
            Marshal.StructureToPtr(new NativeDevices { Items = devices, Count = 1 }, root, false);
            api.DeviceRoot = root;
            api.FreeAllocation = allocation =>
            {
                freed.Add(allocation);
                Marshal.Copy(new byte[6], 0, text.Span.Data, 6);
            };
            var snapshot = client.GetDevices();
            Assert.AreEqual("copied", snapshot[0].Name);
            Assert.AreEqual(Peer, snapshot[0].Id);
            Marshal.StructureToPtr(new NativeDevices { Items = devices, Count = ulong.MaxValue }, root, false);
            Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => client.GetDevices());
            CollectionAssert.AreEqual(new[] { root, root }, freed);
        }
        finally { Marshal.FreeHGlobal(devices); Marshal.FreeHGlobal(root); }
    }

    [TestMethod]
    public async Task TrustedSnapshotIsCopiedAndItsRootIsFreed()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var items = Marshal.AllocHGlobal(Marshal.SizeOf<NativeTrustedDevice>());
        var root = Marshal.AllocHGlobal(Marshal.SizeOf<NativeTrustedDevices>());
        try
        {
            Marshal.StructureToPtr(new NativeTrustedDevice { Id = NativeMapper.ToNativeUuid(Peer.Value) }, items, false);
            Marshal.StructureToPtr(new NativeTrustedDevices { Items = items, Count = 1 }, root, false);
            api.TrustedRoot = root;
            var freed = false;
            api.FreeAllocation = allocation => { Assert.AreEqual(root, allocation); freed = true; Marshal.StructureToPtr(default(NativeTrustedDevice), items, false); };
            var trusted = client.GetTrustedDevices();
            Assert.IsTrue(freed);
            Assert.AreEqual(1, trusted.Count);
            Assert.IsTrue(trusted.Contains(Peer));
        }
        finally { Marshal.FreeHGlobal(items); Marshal.FreeHGlobal(root); }
    }

    [TestMethod]
    public async Task RepeatedInitializeIsRejectedWithoutDestroyingTheRunningSdk()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        await Assert.ThrowsExactlyAsync<InvalidOperationException>(() => client.InitializeAsync(new(), default));
        Assert.AreEqual(0, api.DestroyCount);
        var send = client.BeginSendText(Peer, "still running");
        api.Complete(send.OperationId.Value);
        Assert.IsNull((await send.Completion.WaitAsync(TimeSpan.FromSeconds(5))).Failure);
    }

    [TestMethod]
    public async Task NativeStoppedEventRequiresRealStartAndStaleStoppedEventCannotStopRestartedClient()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var events = Events(client);
        var old = api.LastRegistration;
        var initialStart = api.Latest.Operation;

        api.Emit(new() { Kind = 5, State = 3 });
        Assert.AreEqual(DiscoveryState.Stopped, (await Next<ShareSdkEvent.DiscoveryStateChanged>(events)).State);
        var restart = client.StartAsync(default);
        Assert.AreEqual(initialStart + 1, api.Latest.Operation, "Native Stopped must invalidate the cached Running shortcut.");
        Assert.AreEqual(NativeCommand.Start, api.Latest.Command);
        Assert.IsFalse(restart.IsCompleted, "Restart must wait for its native completion.");
        api.Complete(api.Latest.Operation);
        await restart.WaitAsync(TimeSpan.FromSeconds(5));

        // An obsolete registration must not reconcile the new generation to Stopped.
        FakeNativeApi.Emit(old, new() { Kind = 5, State = 3 });
        api.Emit(new() { Kind = 5, State = 1 });
        Assert.AreEqual(DiscoveryState.Browsing, (await Next<ShareSdkEvent.DiscoveryStateChanged>(events)).State);
        await client.StartAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(initialStart + 1, api.Latest.Operation, "The successfully restarted generation remains Running.");
        var send = client.BeginSendText(Peer, "after native restart");
        api.Complete(send.OperationId.Value);
        Assert.IsNull((await send.Completion.WaitAsync(TimeSpan.FromSeconds(5))).Failure);
    }

    [TestMethod]
    public async Task NativeStoppedEventDuringAcceptedStartCannotBeOverwrittenByStartContinuation()
    {
        var api = new FakeNativeApi();
        await using var client = Create(api);
        var initialize = client.InitializeAsync(new(), default);
        api.Complete(api.Latest.Operation);
        await initialize.WaitAsync(TimeSpan.FromSeconds(5));
        var events = Events(client);
        var start = client.StartAsync(default);
        var operation = api.Latest.Operation;

        // The lifecycle caller has not resumed from its accepted Start yet.
        api.Emit(new() { Kind = 5, State = 3 });
        Assert.AreEqual(DiscoveryState.Stopped, (await Next<ShareSdkEvent.DiscoveryStateChanged>(events)).State);
        api.Complete(operation);
        await start.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.ThrowsExactly<InvalidOperationException>(() => client.BeginSendText(Peer, "native services are stopped"));

        var retry = client.StartAsync(default);
        Assert.AreEqual(operation + 1, api.Latest.Operation);
        api.Complete(api.Latest.Operation);
        await retry.WaitAsync(TimeSpan.FromSeconds(5));
        var send = client.BeginSendText(Peer, "after retry");
        api.Complete(send.OperationId.Value);
        Assert.IsNull((await send.Completion.WaitAsync(TimeSpan.FromSeconds(5))).Failure);
    }

    [TestMethod]
    public async Task ExplicitStopAfterNativeStoppedStillRetiresTheActiveGeneration()
    {
        var api = new FakeNativeApi();
        await using var client = await Running(api);
        var events = Events(client);
        var send = client.BeginSendText(Peer, "pending native terminal");
        api.Emit(new() { Kind = 5, State = 3 });
        await Next<ShareSdkEvent.DiscoveryStateChanged>(events);

        var stop = client.StopAsync(default);
        Assert.AreEqual(send.OperationId.Value + 1, api.Latest.Operation,
            "A native failure retains its observer/catalog until explicit Stop retires them.");
        Assert.AreEqual(NativeCommand.Stop, api.Latest.Command);
        api.Complete(api.Latest.Operation);
        await stop.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(ShareErrorKind.Cancelled, (await send.Completion.WaitAsync(TimeSpan.FromSeconds(5))).Failure!.Kind);
        Assert.AreEqual(0, api.EventCallback);
    }
}

internal static class InterlockedExtensions
{
    internal static void Max(ref int target, int value)
    {
        int before;
        do { before = Volatile.Read(ref target); if (before >= value) return; }
        while (Interlocked.CompareExchange(ref target, value, before) != before);
    }
}
