using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.App.Presentation;

public interface IRuntimeProjection
{
    DeviceId? SelectedDeviceId
    {
        get;
    }
    void RefreshDevices();
    void RenderActivities(DeviceId device);
}
public sealed class RuntimeProjectionAdapter : IDisposable
{
    private readonly ActivityTracker tracker;
    private readonly IRuntimeProjection projection;
    private readonly Action<Action> dispatch;
    private bool disposed;
    public RuntimeProjectionAdapter(ActivityTracker tracker, IRuntimeProjection projection, Action<Action> dispatch)
    {
        this.tracker = tracker;
        this.projection = projection;
        this.dispatch = dispatch;
        tracker.Changed += OnChanged;
    }
    public static bool RequiresSdkRefresh(ShareSdkEvent value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return value is not (ShareSdkEvent.OfferReceived or ShareSdkEvent.OutgoingTransferRegistered or
            ShareSdkEvent.ProgressReceived or ShareSdkEvent.TransferCompleted);
    }
    private void OnChanged(object? sender, EventArgs args) => dispatch(RefreshSelectedActivities);
    public void Refresh()
    {
        if (disposed)
            return;
        projection.RefreshDevices();
        RefreshSelectedActivities();
    }
    public void RefreshSelectedActivities()
    {
        if (disposed)
            return;
        if (projection.SelectedDeviceId is { } device)
            projection.RenderActivities(device);
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        tracker.Changed -= OnChanged;
    }
}
