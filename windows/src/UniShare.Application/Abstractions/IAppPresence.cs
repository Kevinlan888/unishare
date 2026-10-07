using UniShare.Application.Models;

namespace UniShare.Application.Abstractions;

public enum TrayState
{
    Normal, Pausing, Paused, Warning
}
public enum NotificationAction
{
    Open, Reject, ViewAndAccept
}
public sealed record AppNotificationRequest(string BodyResourceKey, DeviceId? Device, RequestId? Request, IReadOnlyList<NotificationAction> Actions);

public interface IAppPresence
{
    bool IsWindowVisible
    {
        get;
    }
    void ShowWindow(DeviceId? device);
    void HideWindow();
    void SetTrayState(TrayState state);
    void ShowNotification(AppNotificationRequest request);
    void DisposeResources();
    void ExitApplication();
}
