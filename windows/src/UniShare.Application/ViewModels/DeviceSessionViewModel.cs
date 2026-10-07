using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.Application.ViewModels;

public sealed class DeviceSessionViewModel : CommandViewModel, IDisposable
{
    private readonly DeviceStore devices;
    private readonly TransferCoordinator coordinator;
    private readonly ActivityTracker tracker;
    private readonly IShareSdkClient sdk;
    private readonly IViewModelFiles files;
    private readonly Dictionary<ActivityId, ActivityItemViewModel> items = [];
    private readonly HashSet<ActivityId> cancellationSubmitted = [];
    private readonly object eligibilityGate = new();
    private EligibilityGeneration sendEligibility = new();
    private volatile bool connectionAvailable;
    private long connectionGeneration;
    private IReadOnlyList<ActivityItemViewModel> activities = [];
    private string composerText = string.Empty;
    private volatile bool disposed;

    public DeviceSessionViewModel(DeviceEntry device, DeviceStore devices, TransferCoordinator coordinator,
        ActivityTracker tracker, IShareSdkClient sdk, IViewModelFiles files, IViewModelDispatcher dispatcher) : base(dispatcher)
    {
        Device = device;
        this.devices = devices;
        this.coordinator = coordinator;
        this.tracker = tracker;
        this.sdk = sdk;
        this.files = files;
        connectionAvailable = device.IsConnected;
        var cancellationLane = CreateCommandLane();
        SendTextCommand = Command(async () =>
        {
            var submitted = ComposerText;
            var eligibility = CaptureEligibilityToken();
            await EnsureConnectedAsync(eligibility).ConfigureAwait(false);
            await coordinator.SendTextAsync(Device.Id, submitted, ShareOrigin.Direct, eligibility).ConfigureAwait(false);
            await Dispatcher.InvokeAsync(() => { if (ComposerText == submitted) ComposerText = string.Empty; }).ConfigureAwait(false);
        }, () => CanSend && !string.IsNullOrWhiteSpace(ComposerText), "Transfer.SubmissionFailed");
        SendFileCommand = Command<string>(path => SendFileAsync(path!, ContentKind.File), path => CanSend && !string.IsNullOrWhiteSpace(path), "Transfer.SubmissionFailed");
        SendImageCommand = Command<string>(path => SendFileAsync(path!, ContentKind.Image), path => CanSend && !string.IsNullOrWhiteSpace(path), "Transfer.SubmissionFailed");
        SendClipboardCommand = Command(async () =>
        {
            var eligibility = CaptureEligibilityToken();
            await EnsureConnectedAsync(eligibility).ConfigureAwait(false);
            await coordinator.SendClipboardAsync(Device.Id, eligibility).ConfigureAwait(false);
        }, () => CanSend, "Transfer.SubmissionFailed");
        AcceptCommand = Command<ActivityId>(id => coordinator.AcceptAsync(id, CancellationToken.None), id => CanDecide(id), "Transfer.AcceptFailed");
        RejectCommand = Command<ActivityId>(id => coordinator.RejectAsync(id, CancellationToken.None), id => CanDecide(id), "Transfer.RejectFailed");
        CancelCommand = Command<ActivityId>(async id =>
        {
            cancellationSubmitted.Add(id);
            try
            {
                await coordinator.CancelAsync(id, CancellationToken.None).ConfigureAwait(false);
            }
            catch
            {
                await Dispatcher.InvokeAsync(() => cancellationSubmitted.Remove(id)).ConfigureAwait(false);
                throw;
            }
        }, id => !IdentityChanged && !cancellationSubmitted.Contains(id) && Find(id)?.Status is ActivityStatus.AwaitingPeer or ActivityStatus.AwaitingDecision or ActivityStatus.Transferring, "Transfer.CancelFailed", cancellationLane);
        RetryCommand = Command<ActivityId>(async id =>
        {
            var eligibility = CaptureEligibilityToken();
            await EnsureConnectedAsync(eligibility).ConfigureAwait(false);
            await coordinator.RetryAsync(id, eligibility).ConfigureAwait(false);
        }, id => CanSend && Find(id) is not null && tracker.CanRetry(id), "Transfer.RetryFailed");
        ForgetCommand = Command(async () =>
        {
            await devices.ForgetDeviceAsync(Device.Id).ConfigureAwait(false);
            await Dispatcher.InvokeAsync(() => { Refresh(); DeviceStateChanged?.Invoke(this, EventArgs.Empty); }).ConfigureAwait(false);
        }, () => Device.IsTrusted || IdentityChanged, "Device.ForgetFailed");
        PairCommand = Command(() => sdk.ConnectAsync(Device.Id, CancellationToken.None), () => Device.IsOnline && !Device.IsBlocked && !Device.IsTrusted, "Pairing.ConnectFailed");
        LoadHistoryCommand = Command(async () =>
        {
            await tracker.LoadDeviceAsync(Device.Id).ConfigureAwait(false);
            await Dispatcher.InvokeAsync(Refresh).ConfigureAwait(false);
        }, () => true, "History.LoadFailed");
        tracker.Changed += OnActivitiesChanged;
        sdk.EventReceived += OnSdkEvent;
        Refresh();
    }

    public DeviceEntry Device { get; }
    public event EventHandler? DeviceStateChanged;
    public string DeviceName => Device.Name;
    public bool CanSend => Device.CanTransfer;
    public bool IdentityChanged => Device.IsBlocked;
    public string? IdentityResourceKey => IdentityChanged ? "Device.IdentityChanged" : null;
    public IReadOnlyList<ActivityItemViewModel> Activities
    {
        get => activities; private set => SetProperty(ref activities, value);
    }
    public string ComposerText
    {
        get => composerText;
        set
        {
            if (SetProperty(ref composerText, value))
                SendTextCommand.NotifyCanExecuteChanged();
        }
    }
    public IAsyncRelayCommand SendTextCommand { get; }
    public IAsyncRelayCommand<string> SendFileCommand { get; }
    public IAsyncRelayCommand<string> SendImageCommand { get; }
    public IAsyncRelayCommand SendClipboardCommand { get; }
    public IAsyncRelayCommand<ActivityId> AcceptCommand { get; }
    public IAsyncRelayCommand<ActivityId> RejectCommand { get; }
    public IAsyncRelayCommand<ActivityId> CancelCommand { get; }
    public IAsyncRelayCommand<ActivityId> RetryCommand { get; }
    public IAsyncRelayCommand ForgetCommand { get; }
    public IAsyncRelayCommand PairCommand { get; }
    public IAsyncRelayCommand LoadHistoryCommand { get; }

    public Task DropFilesAsync(IReadOnlyList<string> paths) => paths.Count == 1 ? SendFileCommand.ExecuteAsync(paths[0]) : Task.CompletedTask;
    private async Task SendFileAsync(string path, ContentKind kind)
    {
        var eligibility = CaptureEligibilityToken();
        await EnsureConnectedAsync(eligibility).ConfigureAwait(false);
        _ = await coordinator.SendFileAsync(Device.Id, path, kind, ShareOrigin.Direct, eligibility).ConfigureAwait(false);
    }
    private ActivityRecord? Find(ActivityId id) => tracker.GetDeviceSnapshot(Device.Id).FirstOrDefault(value => value.Id == id);
    private bool CanDecide(ActivityId id) => !IdentityChanged && !cancellationSubmitted.Contains(id) && Find(id) is { Direction: ShareDirection.Incoming, Status: ActivityStatus.AwaitingDecision };

    // Public refresh is a binding-thread operation. Background service callbacks
    // always post to the injected dispatcher; no terminal event is applied here.
    public void Refresh()
    {
        if (disposed)
            return;
        if (!CanSend)
            RevokeSendEligibility();
        else
        {
            lock (eligibilityGate)
            {
                // Recheck the thread-safe device state inside generation
                // ownership so an event cannot race an unsafe reset.
                if (CanSend && sendEligibility.Source.IsCancellationRequested)
                    sendEligibility = new();
            }
        }
        var snapshot = tracker.GetDeviceSnapshot(Device.Id);
        var current = snapshot.Select(record =>
        {
            if (!items.TryGetValue(record.Id, out var item))
                items.Add(record.Id, item = new(record, files, Dispatcher));
            else
                item.Update(record);
            return item;
        }).ToArray();
        foreach (var id in items.Keys.Except(snapshot.Select(x => x.Id)).ToArray())
            items.Remove(id);
        Activities = current;
        OnPropertyChanged(nameof(DeviceName));
        OnPropertyChanged(nameof(CanSend));
        OnPropertyChanged(nameof(IdentityChanged));
        OnPropertyChanged(nameof(IdentityResourceKey));
        NotifyCommands();
    }

    private void OnActivitiesChanged(object? sender, EventArgs args) => Dispatcher.Post(Refresh);
    private void OnSdkEvent(object? sender, ShareSdkEvent value)
    {
        if (disposed)
            return;
        if (value is ShareSdkEvent.ConnectionChanged connectionState && connectionState.Device == Device.Id)
        {
            connectionAvailable = connectionState.State == ConnectionState.Connected;
            if (!connectionAvailable)
                Interlocked.Increment(ref connectionGeneration);
        }
        // This is non-binding state: revoke synchronously on the SDK thread.
        // Clipboard/staging continuations do not wait for UI notifications.
        if (value is ShareSdkEvent.DeviceOffline offline && offline.Device == Device.Id ||
            value is ShareSdkEvent.IdentityChanged changed && changed.Device == Device.Id ||
            value is ShareSdkEvent.ConnectionChanged { Failure.Kind: ShareErrorKind.IdentityChanged } connection && connection.Device == Device.Id ||
            value is ShareSdkEvent.PairingCompleted { Failure.Kind: ShareErrorKind.IdentityChanged } pairing && pairing.Device == Device.Id)
        {
            connectionAvailable = false;
            if (value is not ShareSdkEvent.ConnectionChanged)
                Interlocked.Increment(ref connectionGeneration);
            RevokeSendEligibility();
        }
        if (value is ShareSdkEvent.DeviceOnline or ShareSdkEvent.DeviceUpdated or ShareSdkEvent.DeviceOffline or
            ShareSdkEvent.ConnectionChanged or ShareSdkEvent.IdentityChanged or ShareSdkEvent.PairingCompleted)
            Dispatcher.Post(Refresh);
    }

    private CancellationToken CaptureEligibilityToken()
    {
        lock (eligibilityGate)
            return sendEligibility.Source.Token;
    }

    private async Task EnsureConnectedAsync(CancellationToken cancellationToken)
    {
        if (connectionAvailable || Device.IsConnected)
            return;
        var generation = Volatile.Read(ref connectionGeneration);
        await sdk.ConnectAsync(Device.Id, cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        if (Volatile.Read(ref connectionGeneration) != generation)
            throw new IOException("Connection was lost before sharing started.");
        connectionAvailable = true;
    }

    private void RevokeSendEligibility()
    {
        Task cancellation;
        lock (eligibilityGate)
        {
            if (sendEligibility.Source.IsCancellationRequested)
                return;
            // CancelAsync marks the token cancelled synchronously, but runs
            // callbacks asynchronously. No callback can invert the dispatcher/
            // generation lock order, and concurrent observers see revocation.
            cancellation = sendEligibility.Source.CancelAsync();
        }
        _ = ObserveCancellationAsync(cancellation);
    }

    private static async Task ObserveCancellationAsync(Task cancellation)
    {
        try
        {
            await cancellation.ConfigureAwait(false);
        }
        catch { /* Callback failures do not undo synchronous revocation. */ }
    }

    private sealed class EligibilityGeneration
    {
        public readonly CancellationTokenSource Source = new();
        // No timer or wait handle is allocated here. Tokens/registrations may
        // outlive the ViewModel or a reset, so do not prematurely Dispose their
        // source. Retired managed generations are collected after those owners
        // release them; no list of retired generations is retained by the VM.
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        tracker.Changed -= OnActivitiesChanged;
        sdk.EventReceived -= OnSdkEvent;
    }
}
