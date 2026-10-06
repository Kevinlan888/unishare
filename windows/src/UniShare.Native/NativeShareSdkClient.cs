using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading.Channels;
using Microsoft.Win32.SafeHandles;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Native.Interop;

namespace UniShare.Native;

public sealed class NativeShareSdkException(ShareFailure failure) : Exception(failure.Message)
{
    public ShareFailure Failure { get; } = failure;
}

public sealed class NativeShareSdkClient : IShareSdkClient
{
    private enum ClientState { New, Initialized, Running, Stopped, Disposed }
    private sealed record EventContext(NativeShareSdkClient Client, long Generation);
    private sealed class ApiHandle(ulong token, Func<ulong, NativeError> release) : SafeNativeHandle(token)
    {
        protected override bool ReleaseHandle() => release(Token).Code == 0;
    }

    private readonly IUniShareNativeApi api;
    private readonly string identityDirectory;
    private readonly Action<DiagnosticStage, DiagnosticOutcome, ShareFailure?, Exception?>? diagnostic;
    private readonly Action<DiagnosticRecord>? nativeDiagnostic;
    private readonly object submissionGate = new();
    private readonly SemaphoreSlim lifecycle = new(1, 1);
    private readonly NativeOperationRegistry operations = new();
    private readonly OutgoingTransferRegistry outgoing = new();
    private readonly Channel<Action> callbacks = Channel.CreateUnbounded<Action>(new() { SingleReader = true });
    private readonly Channel<(long Generation, ShareSdkEvent Event)> events = Channel.CreateUnbounded<(long, ShareSdkEvent)>(new() { SingleReader = true });
    private readonly Channel<DiagnosticRecord> diagnosticEvents;
    private readonly List<GCHandle> eventRoots = [];
    private GCHandle callbackRoot;
    private ApiHandle? sdk;
    private ClientState state;
    private bool diagnosticRegistered;
    private long droppedDiagnosticEvents;
    private long nextGeneration, activeGeneration, stoppedGeneration;

    public NativeShareSdkClient(string? identityDirectory = null,
        Action<DiagnosticStage, DiagnosticOutcome, ShareFailure?, Exception?>? diagnostic = null,
        Action<DiagnosticRecord>? nativeDiagnostic = null)
        : this(new UniShareNativeApi(), identityDirectory, diagnostic, nativeDiagnostic) { }

    internal NativeShareSdkClient(IUniShareNativeApi api, string? identityDirectory) : this(api, identityDirectory, null) { }

    internal NativeShareSdkClient(IUniShareNativeApi api, string? identityDirectory,
        Action<DiagnosticStage, DiagnosticOutcome, ShareFailure?, Exception?>? diagnostic,
        Action<DiagnosticRecord>? nativeDiagnostic = null, int diagnosticQueueCapacity = 1024)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(diagnosticQueueCapacity);
        this.api = api;
        this.identityDirectory = identityDirectory ?? string.Empty;
        this.diagnostic = diagnostic;
        this.nativeDiagnostic = nativeDiagnostic;
        diagnosticEvents = Channel.CreateBounded<DiagnosticRecord>(new BoundedChannelOptions(diagnosticQueueCapacity)
        { SingleReader = true, SingleWriter = false, FullMode = BoundedChannelFullMode.Wait });
        _ = Task.Run(DrainCallbacksAsync);
        _ = Task.Run(DrainEventsAsync);
        _ = Task.Run(DrainDiagnosticsAsync);
    }

    public event EventHandler<ShareSdkEvent>? EventReceived;

    // Cancellation abandons the managed wait. Accepted native operations still
    // own their callback context and finish their lifecycle transition.
    public Task InitializeAsync(SdkOptions options, CancellationToken cancellationToken)
        => InitializeCoreAsync(options, cancellationToken).WaitAsync(cancellationToken);

    private async Task InitializeCoreAsync(SdkOptions options, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(options);
        await lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        Report(DiagnosticStage.SdkInitialize, DiagnosticOutcome.Began);
        var ownsInitialization = false;
        try
        {
            Task<NativeCompletion> completion;
            lock (submissionGate)
            {
                RequireState(ClientState.New);
                ownsInitialization = true;
                cancellationToken.ThrowIfCancellationRequested();
                NativeMapper.RequireAbiVersion(api.AbiVersion());
                Throw(api.ConfigInit(out var config));
                using var name = new NativeUtf8(options.DeviceName);
                using var directory = new NativeUtf8(identityDirectory);
                config.DeviceName = name.Span;
                config.MaxConnections = options.MaxConnections; config.MaxPendingRequests = options.MaxPendingRequests;
                config.MaxBufferBytes = options.MaxBufferBytes; config.MaxTextBytes = options.MaxTextBytes;
                config.MaxImageBytes = options.MaxImageBytes; config.MaxFileBytes = options.MaxFileBytes;
                config.ControlFrameBytes = options.ControlFrameBytes; config.DataChunkBytes = options.DataChunkBytes;
                config.RequestTimeoutSeconds = options.RequestTimeoutSeconds; config.OfflineDebounceSeconds = options.OfflineDebounceSeconds;
                Throw(api.CreatePlatform(directory.Span, out var platformToken));
                if (platformToken == 0) throw InvalidSubmission();
                using var platform = new ApiHandle(platformToken, api.ReleasePlatform);
                callbackRoot = GCHandle.Alloc(this);
                var error = api.Create(platform.Token, config, CompletionPointer, GCHandle.ToIntPtr(callbackRoot), out var sdkToken, out var operation);
                if (sdkToken != 0) sdk = new ApiHandle(sdkToken, api.Destroy);
                if (error.Code != 0 || operation == 0 || sdkToken == 0)
                {
                    DestroySdk();
                    Throw(error);
                    throw InvalidSubmission();
                }
                Throw(api.SetDiagnosticHandler(sdkToken, DiagnosticPointer, GCHandle.ToIntPtr(callbackRoot)));
                diagnosticRegistered = true;
                completion = operations.Register(new(operation));
            }
            var result = await completion.ConfigureAwait(false);
            Throw(result.Terminal.Failure);
            lock (submissionGate) { RequireState(ClientState.New); state = ClientState.Initialized; }
            Report(DiagnosticStage.SdkInitialize, DiagnosticOutcome.Succeeded);
        }
        catch (Exception error)
        {
            Report(DiagnosticStage.SdkInitialize, DiagnosticOutcome.Failed, error);
            lock (submissionGate) { if (ownsInitialization && state != ClientState.Disposed) DestroySdk(); }
            throw;
        }
        finally { lifecycle.Release(); }
    }

    public Task StartAsync(CancellationToken cancellationToken)
        => StartCoreAsync(cancellationToken).WaitAsync(cancellationToken);

    private async Task StartCoreAsync(CancellationToken cancellationToken)
    {
        await lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        Report(DiagnosticStage.SdkStart, DiagnosticOutcome.Began);
        try
        {
            Task<NativeCompletion> completion;
            long generation;
            lock (submissionGate)
            {
                RequireInitialized();
                if (state == ClientState.Running) return;
                cancellationToken.ThrowIfCancellationRequested();
                generation = ++nextGeneration;
                var root = GCHandle.Alloc(new EventContext(this, generation));
                eventRoots.Add(root);
                activeGeneration = generation;
                Throw(api.SetEventHandler(sdk!.Token, EventPointer, GCHandle.ToIntPtr(root)));
                completion = Submit(new(NativeCommand.Start)).Completion;
            }
            var result = await completion.ConfigureAwait(false);
            Throw(result.Terminal.Failure);
            lock (submissionGate)
            {
                RequireInitialized();
                // A native replacement can stop after Start succeeds but before
                // this continuation runs. Its newer state must win that race.
                if (stoppedGeneration != generation) state = ClientState.Running;
            }
            Report(DiagnosticStage.SdkStart, DiagnosticOutcome.Succeeded);
        }
        catch (Exception error)
        {
            Report(DiagnosticStage.SdkStart, DiagnosticOutcome.Failed, error);
            lock (submissionGate)
            {
                activeGeneration = 0;
                if (sdk is not null) Throw(api.SetEventHandler(sdk.Token, 0, 0));
            }
            throw;
        }
        finally { lifecycle.Release(); }
    }

    public Task StopAsync(CancellationToken cancellationToken)
        => StopCoreAsync(cancellationToken).WaitAsync(cancellationToken);

    private async Task StopCoreAsync(CancellationToken cancellationToken)
    {
        await lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        Report(DiagnosticStage.SdkStop, DiagnosticOutcome.Began);
        try
        {
            Task<NativeCompletion> completion;
            lock (submissionGate)
            {
                RequireInitialized();
                // Native failure leaves its observer/catalog active. Explicit
                // Stop must still retire that generation and pending operations.
                if (state != ClientState.Running && activeGeneration == 0)
                {
                    Report(DiagnosticStage.SdkStop, DiagnosticOutcome.Succeeded);
                    return;
                }
                cancellationToken.ThrowIfCancellationRequested();
                var submitted = Submit(new(NativeCommand.Stop));
                if (submitted.Operation.Value == 0) Throw(submitted.Completion.GetAwaiter().GetResult().Terminal.Failure);
                completion = submitted.Completion;
                activeGeneration = 0;
                state = ClientState.Stopped;
                outgoing.Clear();
                Throw(api.SetEventHandler(sdk!.Token, 0, 0));
            }
            var result = await completion.ConfigureAwait(false);
            lock (submissionGate) { operations.CancelAll(); outgoing.Clear(); }
            Throw(result.Terminal.Failure);
            Report(DiagnosticStage.SdkStop, DiagnosticOutcome.Succeeded);
        }
        catch (Exception error)
        {
            Report(DiagnosticStage.SdkStop, DiagnosticOutcome.Failed, error);
            throw;
        }
        finally { lifecycle.Release(); }
    }

    private void Report(DiagnosticStage stage, DiagnosticOutcome outcome, Exception? error = null)
    {
        try { diagnostic?.Invoke(stage, outcome, (error as NativeShareSdkException)?.Failure,
            error is NativeShareSdkException ? null : error); }
        catch { /* Logging must never change SDK lifecycle behavior. */ }
    }

    public IReadOnlyList<DeviceSnapshot> GetDevices()
    {
        lock (submissionGate)
        {
            RequireInitialized();
            Throw(api.GetDevices(sdk!.Token, out var root));
            try
            {
                if (root == 0) return [];
                var snapshot = Marshal.PtrToStructure<NativeDevices>(root);
                NativeMapper.ValidateArray(snapshot.Items, snapshot.Count);
                var result = new DeviceSnapshot[(int)snapshot.Count];
                for (var i = 0; i < result.Length; i++)
                    result[i] = NativeMapper.Device(Marshal.PtrToStructure<NativeDevice>(snapshot.Items + i * Marshal.SizeOf<NativeDevice>()));
                return result;
            }
            finally { if (root != 0) Throw(api.Free(root)); }
        }
    }

    public IReadOnlySet<DeviceId> GetTrustedDevices()
    {
        lock (submissionGate)
        {
            RequireInitialized();
            Throw(api.GetTrustedDevices(sdk!.Token, out var root));
            try
            {
                var result = new HashSet<DeviceId>();
                if (root == 0) return result;
                var snapshot = Marshal.PtrToStructure<NativeTrustedDevices>(root);
                NativeMapper.ValidateArray(snapshot.Items, snapshot.Count);
                for (var i = 0; i < (int)snapshot.Count; i++)
                    result.Add(new(NativeMapper.ToGuid(Marshal.PtrToStructure<NativeTrustedDevice>(snapshot.Items + i * Marshal.SizeOf<NativeTrustedDevice>()).Id)));
                return result;
            }
            finally { if (root != 0) Throw(api.Free(root)); }
        }
    }

    public Task ConnectAsync(DeviceId device, CancellationToken cancellationToken)
        => CommandAsync(new(NativeCommand.Connect, NativeMapper.ToNativeUuid(device.Value)), cancellationToken);
    public Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken cancellationToken)
        => CommandAsync(new(NativeCommand.ConfirmPairing, Request: request.Value, Matches: matches ? 1u : 0u), cancellationToken);
    public Task RejectPairingAsync(RequestId request, CancellationToken cancellationToken)
        => CommandAsync(new(NativeCommand.RejectPairing, Request: request.Value), cancellationToken);
    public Task ForgetDeviceAsync(DeviceId device, CancellationToken cancellationToken)
        => CommandAsync(new(NativeCommand.ForgetDevice, NativeMapper.ToNativeUuid(device.Value)), cancellationToken);
    public Task RejectAsync(RequestId request, CancellationToken cancellationToken)
        => CommandAsync(new(NativeCommand.Reject, Request: request.Value), cancellationToken);
    public Task CancelAsync(TransferId transfer, CancellationToken cancellationToken)
        => CommandAsync(new(NativeCommand.Cancel, NativeMapper.ToNativeUuid(transfer.Value)), cancellationToken);

    public PendingSend BeginSendText(DeviceId device, string text)
    {
        lock (submissionGate)
        {
            RequireState(ClientState.Running);
            using var storage = new NativeUtf8(text);
            var submitted = Submit(new(NativeCommand.SendText, NativeMapper.ToNativeUuid(device.Value), Text: storage.Span), outgoingSend: true);
            return new(submitted.Operation, TerminalAsync(submitted.Completion));
        }
    }

    public PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source)
    {
        lock (submissionGate)
        {
            RequireState(ClientState.Running);
            var command = kind switch
            {
                ContentKind.Text => NativeCommand.SendTextStream,
                ContentKind.Image => NativeCommand.SendImage,
                ContentKind.File => NativeCommand.SendFile,
                _ => throw new ArgumentOutOfRangeException(nameof(kind))
            };
            using var file = OpenSource(source.Path);
            using var stream = Register(file, destination: false);
            using var name = new NativeUtf8(source.DisplayName);
            using var mime = new NativeUtf8(source.MimeType);
            var value = new NativeSource { Stream = stream.Token, DisplayName = name.Span, MimeType = mime.Span, ByteCount = source.ByteCount };
            var submitted = Submit(new(command, NativeMapper.ToNativeUuid(device.Value), Source: value), outgoingSend: true);
            return new(submitted.Operation, TerminalAsync(submitted.Completion));
        }
    }

    public Task AcceptAsync(RequestId request, string stagingPath, CancellationToken cancellationToken)
    {
        Task<NativeCompletion> completion;
        lock (submissionGate)
        {
            RequireState(ClientState.Running);
            cancellationToken.ThrowIfCancellationRequested();
            using var file = WindowsNativeFile.OpenDestination(stagingPath);
            using var stream = Register(file, destination: true);
            completion = Submit(new(NativeCommand.Accept, Request: request.Value, Stream: stream.Token)).Completion;
        }
        return CheckAsync(completion, cancellationToken);
    }

    public async Task<ShareContent> ReadClipboardAsync(CancellationToken cancellationToken)
    {
        Task<NativeCompletion> completion;
        lock (submissionGate)
        {
            RequireState(ClientState.Running);
            cancellationToken.ThrowIfCancellationRequested();
            completion = Submit(new(NativeCommand.ClipboardRead)).Completion;
        }
        var result = await completion.WaitAsync(cancellationToken).ConfigureAwait(false);
        Throw(result.Terminal.Failure);
        return result.Content ?? throw new NativeShareSdkException(new(ShareErrorKind.InvalidInput, "Native clipboard result has no content."));
    }

    private ApiHandle Register(SafeFileHandle file, bool destination)
    {
        var retained = false;
        try
        {
            file.DangerousAddRef(ref retained);
            var error = destination ? api.RegisterDestination(file.DangerousGetHandle(), out var token)
                : api.RegisterSource(file.DangerousGetHandle(), out token);
            Throw(error);
            if (token == 0) throw InvalidSubmission();
            file.Dispose();
            return new ApiHandle(token, api.ReleaseStream);
        }
        finally { if (retained) file.DangerousRelease(); }
    }

    private static SafeFileHandle OpenSource(string path)
    {
        try { return File.OpenHandle(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
        {
            // File.OpenHandle's exception message includes the caller's full path.
            // Do not retain that exception as an inner exception or diagnostic.
            throw new NativeShareSdkException(new(ShareErrorKind.StorageFailed, "Unable to open source file.", error.HResult));
        }
    }

    private Task CommandAsync(NativeSubmission command, CancellationToken cancellationToken)
    {
        Task<NativeCompletion> completion;
        lock (submissionGate)
        {
            RequireState(ClientState.Running);
            cancellationToken.ThrowIfCancellationRequested();
            completion = Submit(command).Completion;
        }
        return CheckAsync(completion, cancellationToken);
    }

    private (OperationId Operation, Task<NativeCompletion> Completion) Submit(NativeSubmission command, bool outgoingSend = false)
    {
        var error = api.Submit(sdk!.Token, command, CompletionPointer, GCHandle.ToIntPtr(callbackRoot), out var operation);
        if (error.Code != 0 || operation == 0)
            return (default, Task.FromResult(new NativeCompletion(default, new(null,
                NativeMapper.Failure(error) ?? InvalidSubmission().Failure))));
        var id = new OperationId(operation);
        var completion = operations.Register(id);
        if (outgoingSend) outgoing.Register(id);
        return (id, completion);
    }

    private static async Task<TransferTerminal> TerminalAsync(Task<NativeCompletion> task) => (await task.ConfigureAwait(false)).Terminal;
    private static async Task CheckAsync(Task<NativeCompletion> task, CancellationToken cancellationToken)
        => Throw((await task.WaitAsync(cancellationToken).ConfigureAwait(false)).Terminal.Failure);
    private static void Throw(NativeError error) => Throw(NativeMapper.Failure(error));
    private static void Throw(ShareFailure? failure) { if (failure is not null) throw new NativeShareSdkException(failure); }
    private static NativeShareSdkException InvalidSubmission() => new(new(ShareErrorKind.InvalidInput, "Native submission returned an invalid token or operation."));
    private void RequireState(ClientState required)
    {
        ObjectDisposedException.ThrowIf(state == ClientState.Disposed, this);
        if (state != required) throw new InvalidOperationException("SDK lifecycle state does not allow this command.");
    }
    private void RequireInitialized()
    {
        ObjectDisposedException.ThrowIf(state == ClientState.Disposed, this);
        if (state == ClientState.New) throw new InvalidOperationException("SDK has not initialized.");
    }

    private static unsafe nint CompletionPointer => (nint)(delegate* unmanaged[Cdecl]<nint, nint, void>)&OnCompletion;
    private static unsafe nint EventPointer => (nint)(delegate* unmanaged[Cdecl]<nint, nint, void>)&OnEvent;
    private static unsafe nint DiagnosticPointer => (nint)(delegate* unmanaged[Cdecl]<nint, nint, void>)&OnDiagnostic;

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void OnDiagnostic(nint context, nint pointer)
    {
        try
        {
            var client = (NativeShareSdkClient)GCHandle.FromIntPtr(context).Target!;
            var record = NativeMapper.Diagnostic(Marshal.PtrToStructure<NativeDiagnosticEvent>(pointer));
            if (!client.diagnosticEvents.Writer.TryWrite(record))
                Interlocked.Increment(ref client.droppedDiagnosticEvents);
        }
        catch { /* Diagnostic delivery is best-effort and never unwinds into native code. */ }
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void OnCompletion(nint context, nint pointer)
    {
        // No exception may escape a reverse P/Invoke. Copy borrowed storage before
        // enqueuing; never take submissionGate here (Destroy drains this stack).
        try
        {
            var client = (NativeShareSdkClient)GCHandle.FromIntPtr(context).Target!;
            var value = Marshal.PtrToStructure<NativeResult>(pointer);
            NativeCompletion result;
            try
            {
                result = new(new(value.Operation), NativeMapper.Terminal(value),
                    value.Error.Code == 0 && value.Content.Type != 0 ? NativeMapper.Content(value.Content) : null);
            }
            catch { result = new(new(value.Operation), new(null, new(ShareErrorKind.InvalidInput, "Invalid native result data."))); }
            client.callbacks.Writer.TryWrite(() => client.ProcessCompletion(result));
        }
        catch { /* Native ABI guarantees live context and accessible pointers. */ }
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void OnEvent(nint context, nint pointer)
    {
        try
        {
            var registration = (EventContext)GCHandle.FromIntPtr(context).Target!;
            if (Volatile.Read(ref registration.Client.activeGeneration) != registration.Generation) return;
            var value = NativeMapper.Event(Marshal.PtrToStructure<NativeEvent>(pointer));
            registration.Client.callbacks.Writer.TryWrite(() => registration.Client.ProcessEvent(registration.Generation, value));
        }
        catch { /* Malformed events are dropped without leaking payloads into diagnostics. */ }
    }

    private void ProcessCompletion(NativeCompletion result)
    {
        lock (submissionGate)
        {
            if (!operations.Complete(result)) return;
            outgoing.Complete(result.Operation, result.Terminal.TransferId);
        }
    }

    private void ProcessEvent(long generation, ShareSdkEvent value)
    {
        lock (submissionGate)
        {
            if (generation != activeGeneration || state == ClientState.Disposed) return;
            if (value is ShareSdkEvent.DiscoveryStateChanged { State: DiscoveryState.Stopped })
            {
                stoppedGeneration = generation;
                state = ClientState.Stopped;
            }
            if (value is ShareSdkEvent.OfferReceived offer) outgoing.ObserveIncoming(offer.Offer.TransferId);
            if (value is ShareSdkEvent.ProgressReceived progress && outgoing.ObserveProgress(progress.Progress.TransferId) is { } registered)
                events.Writer.TryWrite((generation, registered));
            events.Writer.TryWrite((generation, value));
        }
    }

    private async Task DrainCallbacksAsync()
    {
        await foreach (var callback in callbacks.Reader.ReadAllAsync().ConfigureAwait(false)) callback();
    }

    private async Task DrainEventsAsync()
    {
        await foreach (var envelope in events.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            if (envelope.Generation != Volatile.Read(ref activeGeneration)) continue;
            if (EventReceived is not { } handlers) continue;
            foreach (EventHandler<ShareSdkEvent> handler in handlers.GetInvocationList())
            {
                if (envelope.Generation != Volatile.Read(ref activeGeneration)) break;
                try { handler(this, envelope.Event); }
                catch { /* A subscriber cannot unwind through native code or halt dispatch. */ }
            }
        }
    }

    private async Task DrainDiagnosticsAsync()
    {
        await foreach (var record in diagnosticEvents.Reader.ReadAllAsync().ConfigureAwait(false))
        {
            try { nativeDiagnostic?.Invoke(record); }
            catch { /* Logging cannot affect SDK behavior or halt diagnostics. */ }
            ReportDroppedDiagnostics();
        }
        ReportDroppedDiagnostics();
    }

    private void ReportDroppedDiagnostics()
    {
        var count = Interlocked.Exchange(ref droppedDiagnosticEvents, 0);
        if (count == 0) return;
        try { nativeDiagnostic?.Invoke(new(NativeDiagnosticStage.DiagnosticQueue, NativeDiagnosticOutcome.Failed,
            DiagnosticDirection.None, DiagnosticContentKind.None, (ulong)count, ShareErrorKind.ResourceLimit, null)); }
        catch { /* Best-effort diagnostic. */ }
    }

    private void DestroySdk()
    {
        if (sdk is not null)
        {
            if (diagnosticRegistered)
            {
                Throw(api.SetDiagnosticHandler(sdk.Token, 0, 0));
                diagnosticRegistered = false;
            }
            Throw(api.Destroy(sdk.Token));
            sdk.SetHandleAsInvalid(); sdk.Dispose(); sdk = null;
        }
        // ABI destroy drains all native callbacks before these roots are freed.
        if (callbackRoot.IsAllocated) callbackRoot.Free();
        foreach (var root in eventRoots) root.Free();
        eventRoots.Clear();
    }

    public ValueTask DisposeAsync()
    {
        lock (submissionGate)
        {
            state = ClientState.Disposed;
            activeGeneration = 0;
            outgoing.Clear();
            operations.CancelAll();
            DestroySdk();
            callbacks.Writer.TryComplete(); events.Writer.TryComplete(); diagnosticEvents.Writer.TryComplete();
        }
        // Do not await the event reader: disposal is allowed from its subscriber.
        return ValueTask.CompletedTask;
    }
}
