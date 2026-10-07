using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

public sealed class NotificationRouter : IDisposable
{
    private readonly IAppPresence presence;
    private readonly Func<DeviceId, bool> knownDevice;
    private readonly Func<RequestId, bool> pending;
    private readonly Func<RequestId, Task> reject;
    private readonly object gate = new();
    private readonly Dictionary<RequestId, IncomingOffer> decisions = [];
    private readonly Dictionary<TransferId, IncomingOffer> transfers = [];
    private readonly HashSet<DeviceId> completions = [];
    private bool disposed;
    public NotificationRouter(IAppPresence presence, Func<DeviceId, bool> knownDevice, Func<RequestId, bool> pending, Func<RequestId, Task> reject)
    {
        this.presence = presence;
        this.knownDevice = knownDevice;
        this.pending = pending;
        this.reject = reject;
    }

    public void OnOffer(IncomingOffer offer, bool trusted)
    {
        lock (gate)
        {
            if (disposed || !transfers.TryAdd(offer.TransferId, offer))
                return;
            if (trusted && offer.Kind == ContentKind.Text)
                return;
            decisions[offer.RequestId] = offer;
            if (!presence.IsWindowVisible)
                presence.ShowNotification(new("Notification.Offer", offer.DeviceId, offer.RequestId, [NotificationAction.Reject, NotificationAction.ViewAndAccept]));
        }
    }
    public async Task ActivateAsync(string arguments)
    {
        if (!NotificationArguments.TryParse(arguments, out var action, out var device, out var request))
            return;
        Task? rejection = null;
        lock (gate)
        {
            if (disposed || !knownDevice(device))
                return;
            if (action == NotificationAction.Open)
            {
                if (request is not null || !completions.Remove(device))
                    return;
                presence.ShowWindow(device);
                return;
            }
            if (request is not { } id || !decisions.TryGetValue(id, out var offer) || offer.DeviceId != device || !pending(id))
                return;
            decisions.Remove(id);
            if (action == NotificationAction.Reject)
                rejection = reject(id);
            else
                presence.ShowWindow(device); // The in-app offer owns acceptance and destination creation.
        }
        if (rejection is not null)
            await rejection.ConfigureAwait(false);
    }
    public void OnCompleted(TransferId transfer, bool success)
    {
        lock (gate)
        {
            if (disposed || !transfers.Remove(transfer, out var offer))
                return;
            decisions.Remove(offer.RequestId);
            completions.Add(offer.DeviceId);
            presence.ShowNotification(new(success ? "Notification.Completed" : "Notification.Failed", offer.DeviceId, null, []));
        }
    }
    public void OnSdkFailure()
    {
        lock (gate)
        {
            if (disposed)
                return;
            presence.SetTrayState(TrayState.Warning);
            presence.ShowNotification(new("Notification.SdkFailure", null, null, []));
        }
    }
    public void OnOutgoingCompleted(DeviceId device, bool success)
    {
        lock (gate)
        {
            if (disposed)
                return;
            completions.Add(device);
            presence.ShowNotification(new(success ? "Notification.Completed" : "Notification.Failed", device, null, []));
        }
    }
    public void Dispose()
    {
        lock (gate)
        {
            disposed = true;
            decisions.Clear();
            transfers.Clear();
            completions.Clear();
        }
    }
}

public static class NotificationArguments
{
    public static string Build(NotificationAction action, DeviceId device, RequestId? request = null) =>
        $"action={action}&device={device.Value:D}" + (request is { } id ? $"&request={id.Value.ToString(System.Globalization.CultureInfo.InvariantCulture)}" : "");

    public static bool TryParse(string? arguments, out NotificationAction action, out DeviceId device, out RequestId? request)
    {
        action = default;
        device = default;
        request = null;
        if (string.IsNullOrEmpty(arguments) || arguments.Length > 160)
            return false;
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var part in arguments.Split('&'))
        {
            var pair = part.Split('=');
            if (pair.Length != 2 || pair[0] is not ("action" or "device" or "request") || !values.TryAdd(pair[0], pair[1]))
                return false;
        }
        if (!values.TryGetValue("action", out var verb) || verb is not ("Open" or "Reject" or "ViewAndAccept") ||
            !Enum.TryParse(verb, out action) || !values.TryGetValue("device", out var uuid) ||
            !Guid.TryParseExact(uuid, "D", out var guid) || guid == Guid.Empty)
            return false;
        device = new(guid);
        if (values.TryGetValue("request", out var number))
        {
            if (!ulong.TryParse(number, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var id) || id == 0)
                return false;
            request = new(id);
        }
        return action == NotificationAction.Open ? request is null : request is not null;
    }
}
