using Microsoft.Windows.AppNotifications;

namespace UniShare.App.Services;

public sealed class AppNotificationPlatform : INotificationPlatform
{
    private Action<string>? callback;
    private bool registered, subscribed, disposed;
    public void Register(Action<string> callback)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        this.callback = callback;
        AppNotificationManager.Default.NotificationInvoked += OnInvoked;
        subscribed = true;
        AppNotificationManager.Default.Register();
        registered = true;
    }
    private void OnInvoked(AppNotificationManager sender, AppNotificationActivatedEventArgs args) => callback?.Invoke(args.Argument);
    public void Show(string xml)
    {
        if (!disposed && registered)
            AppNotificationManager.Default.Show(new AppNotification(xml));
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        callback = null;
        if (subscribed)
            AppNotificationManager.Default.NotificationInvoked -= OnInvoked;
        if (registered)
        {
            AppNotificationManager.Default.Unregister();
            registered = false;
        }
    }
}
