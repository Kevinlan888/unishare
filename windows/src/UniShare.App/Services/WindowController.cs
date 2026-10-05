using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.App.Services;

public interface IWindowPlatform : IDisposable { void Show(DeviceId? device); void Hide(); }
public sealed class WindowController : IAppPresence
{
    private readonly IWindowPlatform window;
    private readonly TrayIconService tray;
    private readonly Action<AppNotificationRequest> notify;
    private readonly Action<Action> dispatch;
    private readonly Action exit;
    private readonly Action? retireActivation;
    private readonly object gate = new();
    private bool disposed, exitRequested, visible;
    public WindowController(IWindowPlatform window, TrayIconService tray, Action<AppNotificationRequest> notify, Action<Action> dispatch, Action exit, Action? retireActivation = null)
    { this.window = window; this.tray = tray; this.notify = notify; this.dispatch = dispatch; this.exit = exit; this.retireActivation = retireActivation; }
    public bool IsWindowVisible { get { lock (gate) return visible; } }
    public void ShowWindow(DeviceId? device) { lock (gate) { if (disposed) return; visible = true; dispatch(() => window.Show(device)); } }
    public void HideWindow() { lock (gate) { if (disposed) return; visible = false; dispatch(window.Hide); } }
    public void SetTrayState(TrayState state) { lock (gate) if (!disposed) dispatch(() => tray.SetState(state)); }
    public void ShowNotification(AppNotificationRequest request) { lock (gate) if (!disposed) dispatch(() => notify(request)); }
    public void DisposeResources()
    {
        lock (gate)
        {
            if (disposed) return; disposed = true; visible = false;
            dispatch(() => { retireActivation?.Invoke(); tray.Dispose(); window.Dispose(); });
        }
    }
    public void ExitApplication() { lock (gate) { if (exitRequested) return; exitRequested = true; dispatch(exit); } }
}
