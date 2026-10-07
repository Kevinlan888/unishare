using UniShare.Application.Abstractions;

namespace UniShare.App.Services;

public interface INotificationPlatform : IDisposable
{
    void Register(Action<string> callback); void Show(string xml);
}
public sealed class AppNotificationService : IDisposable
{
    private readonly INotificationPlatform platform;
    private readonly Func<string, string> localize;
    private int disposed;
    public AppNotificationService(INotificationPlatform platform, Func<string, string> localize, Action<string> activate)
    {
        this.platform = platform;
        this.localize = localize;
        try
        {
            platform.Register(arguments => { if (Volatile.Read(ref disposed) == 0) activate(arguments); });
        }
        catch { Dispose(); }
    }
    public void Show(AppNotificationRequest request)
    {
        if (Volatile.Read(ref disposed) != 0)
            return;
        try
        {
            platform.Show(NotificationPayload.BuildXml(request, localize));
        }
        catch { /* Notification platform availability must not stop sharing. */ }
    }
    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) == 0)
            platform.Dispose();
    }
}
