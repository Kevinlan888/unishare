using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.Application.ViewModels;

public enum ShellSection { Share, History, Settings }
public sealed record DiscoveryBannerState(string ResourceKey, ShareErrorKind ErrorKind, bool CanRetry);

public sealed class ShellViewModel : CommandViewModel, IDisposable
{
    private readonly DeviceStore store;
    private readonly IShareSdkClient sdk;
    private readonly Func<DeviceEntry, DeviceSessionViewModel> createSession;
    private readonly Dictionary<DeviceId, DeviceSessionViewModel> sessions = [];
    private ShellSection selectedSection = ShellSection.Share;
    private DeviceSessionViewModel? selectedSession;
    private DiscoveryBannerState? discoveryBanner;
    private bool disposed;

    public ShellViewModel(DeviceStore store, IShareSdkClient sdk, Func<Task> retryDiscovery,
        Func<DeviceEntry, DeviceSessionViewModel> createSession, IViewModelDispatcher dispatcher) : base(dispatcher)
    {
        this.store = store; this.sdk = sdk; this.createSession = createSession;
        Devices = new(store);
        Devices.Refresh();
        RetryDiscoveryCommand = Command(async () =>
        {
            await retryDiscovery().ConfigureAwait(false);
            await Dispatcher.InvokeAsync(() => DiscoveryBanner = null).ConfigureAwait(false);
        }, () => DiscoveryBanner?.CanRetry == true, "Discovery.RetryFailed");
        sdk.EventReceived += OnEvent;
    }

    public DeviceListViewModel Devices { get; }
    public ShellSection SelectedSection { get => selectedSection; set => SetProperty(ref selectedSection, value); }
    public DeviceEntry? SelectedDevice
    {
        get => store.SelectedDevice;
        set
        {
            if (ReferenceEquals(store.SelectedDevice, value)) return;
            store.SelectedDevice = value;
            if (value is null) SelectedSession = null;
            else
            {
                if (!sessions.TryGetValue(value.Id, out var session))
                {
                    sessions.Add(value.Id, session = createSession(value));
                    session.DeviceStateChanged += OnSessionDeviceStateChanged;
                }
                SelectedSession = session;
                SelectedSection = ShellSection.Share;
                _ = session.LoadHistoryCommand.ExecuteAsync(null);
            }
            OnPropertyChanged();
        }
    }
    public DeviceSessionViewModel? SelectedSession { get => selectedSession; private set => SetProperty(ref selectedSession, value); }
    public DiscoveryBannerState? DiscoveryBanner
    {
        get => discoveryBanner;
        private set { if (SetProperty(ref discoveryBanner, value)) RetryDiscoveryCommand.NotifyCanExecuteChanged(); }
    }
    public IAsyncRelayCommand RetryDiscoveryCommand { get; }
    private void OnSessionDeviceStateChanged(object? sender, EventArgs args) { if (!disposed) Devices.Refresh(); }

    private void OnEvent(object? sender, ShareSdkEvent value) => Dispatcher.Post(() =>
    {
        if (disposed) return;
        Devices.Refresh();
        if (value is ShareSdkEvent.DiscoveryFailed failure)
            DiscoveryBanner = new(failure.Failure.Kind == ShareErrorKind.PermissionDenied ? "Discovery.PermissionDenied" : "Discovery.Failed", failure.Failure.Kind, true);
        else if (value is ShareSdkEvent.DiscoveryStateChanged { State: DiscoveryState.Browsing }) DiscoveryBanner = null;
    });

    public void Dispose()
    {
        if (disposed) return; disposed = true; sdk.EventReceived -= OnEvent;
        foreach (var session in sessions.Values) { session.DeviceStateChanged -= OnSessionDeviceStateChanged; session.Dispose(); }
    }
}
