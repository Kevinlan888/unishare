using System.Runtime.ExceptionServices;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

// Own one instance at application scope; window activation only calls Initialize
// and Start. Cancellation applies to admission, not accepted SDK transitions.
public sealed class ShareSession : IAsyncDisposable
{
    private readonly IShareSdkClient sdk;
    private readonly ITransferDrain transfers;
    private readonly SemaphoreSlim lifecycle = new(1, 1);
    private readonly object admission = new();
    private readonly TaskCompletionSource exitAccepted = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private bool exitRequested;
    private bool pauseRequested;
    private int state = (int)SessionState.Paused;
    private bool initialized;
    private bool hasStarted;
    private bool needsStop;
    private long generation;
    private long? activeStartGeneration;
    private long? failedStartGeneration;
    private ExceptionDispatchInfo? initializationFailure;

    public ShareSession(IShareSdkClient sdk, ITransferDrain transfers)
    {
        this.sdk = sdk ?? throw new ArgumentNullException(nameof(sdk));
        this.transfers = transfers ?? throw new ArgumentNullException(nameof(transfers));
        transfers.BlockNewTransfers();
        Devices = new(sdk);
        sdk.EventReceived += OnSdkEvent;
    }

    public DeviceStore Devices { get; }
    public SessionState State => (SessionState)Volatile.Read(ref state);
    private void SetState(SessionState value) => Volatile.Write(ref state, (int)value);
    private void RequireAlive()
    {
        lock (admission)
            ObjectDisposedException.ThrowIf(exitRequested || State == SessionState.Exiting, this);
    }

    public async Task InitializeAsync(SdkOptions options, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        await lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            RequireAlive();
            if (initialized)
                return;
            initializationFailure?.Throw();
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                await sdk.InitializeAsync(options, CancellationToken.None).ConfigureAwait(false);
                initialized = true;
                Devices.Refresh();
            }
            catch (Exception error)
            {
                initializationFailure = ExceptionDispatchInfo.Capture(error);
                SetState(SessionState.Faulted);
                throw;
            }
        }
        finally { lifecycle.Release(); }
    }

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        await lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            RequireAlive();
            // Reopening a window must not undo an explicit tray pause.
            lock (admission)
                if (hasStarted || pauseRequested)
                    return;
            RequireInitialized();
            cancellationToken.ThrowIfCancellationRequested();
            await StartCoreAsync(SessionState.Faulted).ConfigureAwait(false);
        }
        finally { lifecycle.Release(); }
    }

    public async Task PauseAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (admission)
        {
            RequireAlive();
            pauseRequested = true;
            transfers.BlockNewTransfers();
        }
        await lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (admission)
                if (exitRequested)
                    return;
            if (State == SessionState.Paused)
                return;
            SetState(SessionState.Pausing);
            try
            {
                var drain = transfers.WaitForIdleAsync(CancellationToken.None);
                // Only explicit Exit may preempt drain. Accepted Pause ignores
                // caller cancellation, and never fabricates a fault on exit.
                if (await Task.WhenAny(drain, exitAccepted.Task).ConfigureAwait(false) == exitAccepted.Task)
                    return;
                await drain.ConfigureAwait(false);
                lock (admission)
                    if (exitRequested)
                        return;
                await StopCoreAsync().ConfigureAwait(false);
                lock (admission)
                    if (!exitRequested)
                        SetState(SessionState.Paused);
            }
            catch { SetState(SessionState.Faulted); throw; }
        }
        finally { lifecycle.Release(); }
    }

    public async Task ResumeAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (admission)
            if (!exitRequested)
                pauseRequested = false;
        await lifecycle.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            RequireAlive();
            if (State == SessionState.Running)
                return;
            RequireInitialized();
            cancellationToken.ThrowIfCancellationRequested();
            await StartCoreAsync(SessionState.Paused).ConfigureAwait(false);
        }
        finally { lifecycle.Release(); }
    }

    private void RequireInitialized()
    {
        if (!initialized)
            throw new InvalidOperationException("Session has not initialized.");
    }

    private async Task StartCoreAsync(SessionState failureState)
    {
        try
        {
            // Faulted or failed starts can leave native observation active.
            // Stop that generation before retrying, using the same SDK instance.
            await StopCoreAsync().ConfigureAwait(false);
            lock (admission)
            {
                activeStartGeneration = Interlocked.Increment(ref generation);
                failedStartGeneration = null;
                needsStop = true;
            }
            await sdk.StartAsync(CancellationToken.None).ConfigureAwait(false);
            Devices.Refresh();
            lock (admission)
            {
                // Start's successful task does not erase an SDK background
                // failure delivered synchronously or while this generation ran.
                if (failedStartGeneration == activeStartGeneration)
                    throw new InvalidOperationException("Unable to start device discovery.");
                activeStartGeneration = null;
                hasStarted = true;
                SetState(SessionState.Running);
                if (pauseRequested)
                    SetState(SessionState.Pausing);
                else if (!exitRequested)
                    transfers.AllowNewTransfers();
            }
        }
        catch
        {
            lock (admission)
            {
                activeStartGeneration = null;
                transfers.BlockNewTransfers();
                SetState(failureState);
            }
            throw;
        }
    }

    private async Task StopCoreAsync()
    {
        if (!needsStop)
            return;
        await sdk.StopAsync(CancellationToken.None).ConfigureAwait(false);
        needsStop = false;
        Interlocked.Increment(ref generation);
        // Explicit native Stop clears discovery and retires event delivery, so
        // no later offline/disconnected events can reconcile the old entries.
        Devices.Refresh(preserveConnections: false);
    }

    public async Task ExitAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        // Exit admission precedes queued native transitions. An accepted Start
        // may finish while exit waits, but can no longer reopen transfer work.
        lock (admission)
        {
            exitRequested = true;
            transfers.BlockNewTransfers();
            exitAccepted.TrySetResult();
        }
        await lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            if (State == SessionState.Exiting)
                return;
            SetState(SessionState.Exiting);
            transfers.BlockNewTransfers();
            sdk.EventReceived -= OnSdkEvent;
            try
            {
                await StopCoreAsync().ConfigureAwait(false);
            }
            finally
            {
                Devices.Dispose();
                await sdk.DisposeAsync().ConfigureAwait(false);
            }
        }
        finally { lifecycle.Release(); }
    }

    private void OnSdkEvent(object? sender, ShareSdkEvent value)
    {
        if (value is ShareSdkEvent.DiscoveryFailed)
        {
            lock (admission)
            {
                if (activeStartGeneration is { } start)
                    failedStartGeneration = start;
                if (!exitRequested && !pauseRequested && State == SessionState.Running)
                {
                    transfers.BlockNewTransfers();
                    SetState(SessionState.Faulted);
                }
            }
        }
        if (value is ShareSdkEvent.DiscoveryStateChanged { State: DiscoveryState.Stopped })
            _ = ObserveStoppedAsync(Volatile.Read(ref generation));
    }

    private async Task ObserveStoppedAsync(long observedGeneration)
    {
        await lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            if (observedGeneration != Volatile.Read(ref generation) || State != SessionState.Running)
                return;
            transfers.BlockNewTransfers();
            SetState(SessionState.Faulted);
        }
        finally { lifecycle.Release(); }
    }

    public ValueTask DisposeAsync() => new(ExitAsync());
}
