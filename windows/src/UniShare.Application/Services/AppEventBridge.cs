using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

public sealed class AppEventBridge : IDisposable
{
    private readonly IShareSdkClient sdk;
    private readonly ShareSession session;
    private readonly ActivityTracker tracker;
    private readonly TransferCoordinator transfers;
    private readonly NotificationRouter router;
    private readonly object gate = new();
    private readonly Dictionary<RequestId, IncomingOffer> offers = [];
    private readonly HashSet<ActivityId> outgoing = [];
    private bool disposed;
    public AppEventBridge(IShareSdkClient sdk, ShareSession session, ActivityTracker tracker, TransferCoordinator transfers, IAppPresence presence)
    {
        this.sdk = sdk; this.session = session; this.tracker = tracker; this.transfers = transfers;
        router = new(presence, id => session.Devices.Devices.Any(device => device.Id == id && !device.IsBlocked),
            id => Find(id)?.Status == ActivityStatus.AwaitingDecision, RejectAsync);
        sdk.EventReceived += OnEvent; tracker.Changed += OnChanged;
    }
    private ActivityRecord? Find(RequestId request)
    {
        lock (gate) return offers.TryGetValue(request, out var offer)
            ? tracker.GetDeviceSnapshot(offer.DeviceId).FirstOrDefault(record => record.TransferId == offer.TransferId) : null;
    }
    private async Task RejectAsync(RequestId request)
    {
        var record = Find(request);
        if (record?.Status == ActivityStatus.AwaitingDecision) await transfers.RejectAsync(record.Id, CancellationToken.None).ConfigureAwait(false);
    }
    private void OnEvent(object? sender, ShareSdkEvent value)
    {
        if (value is ShareSdkEvent.OfferReceived received && session.State == SessionState.Running)
        {
            lock (gate) { if (disposed) return; offers[received.Offer.RequestId] = received.Offer; }
            router.OnOffer(received.Offer, sdk.GetTrustedDevices().Contains(received.Offer.DeviceId));
            OnChanged(this, EventArgs.Empty);
        }
        else if (value is ShareSdkEvent.DiscoveryFailed || value is ShareSdkEvent.DiscoveryStateChanged { State: DiscoveryState.Stopped } && session.State is SessionState.Running or SessionState.Faulted)
            router.OnSdkFailure();
    }
    private void OnChanged(object? sender, EventArgs args)
    {
        IncomingOffer[] snapshot;
        lock (gate) { if (disposed) return; snapshot = offers.Values.ToArray(); }
        foreach (var offer in snapshot)
        {
            var record = tracker.GetDeviceSnapshot(offer.DeviceId).FirstOrDefault(value => value.TransferId == offer.TransferId);
            if (record?.CompletedAt is null) continue;
            router.OnCompleted(offer.TransferId, record.Status == ActivityStatus.Completed);
            lock (gate) offers.Remove(offer.RequestId);
        }
        var notices = new List<(DeviceId Device, bool Success)>();
        foreach (var device in session.Devices.Devices)
        {
            foreach (var record in tracker.GetDeviceSnapshot(device.Id).Where(record => record.Direction == ShareDirection.Outgoing))
            {
                lock (gate)
                {
                    if (disposed) return;
                    // Observe live work first; loading terminal history does not
                    // produce notifications for transfers from a previous run.
                    if (record.CompletedAt is null) outgoing.Add(record.Id);
                    else if (outgoing.Remove(record.Id)) notices.Add((record.DeviceId, record.Status == ActivityStatus.Completed));
                }
            }
        }
        foreach (var notice in notices) router.OnOutgoingCompleted(notice.Device, notice.Success);
    }
    public Task ActivateAsync(string arguments) => router.ActivateAsync(arguments);
    public void NotifySdkFailure() => router.OnSdkFailure();
    public void Dispose()
    {
        lock (gate) { if (disposed) return; disposed = true; offers.Clear(); outgoing.Clear(); }
        sdk.EventReceived -= OnEvent; tracker.Changed -= OnChanged; router.Dispose();
    }
}
