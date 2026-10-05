using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

public sealed class AppLifecycleCoordinator
{
    private readonly ShareSession session;
    private readonly IAppPresence presence;
    private readonly IAppShutdown? shutdown;
    private readonly object gate = new();
    private Task? exit;
    private bool exiting;

    public AppLifecycleCoordinator(ShareSession session, IAppPresence presence, IAppShutdown? shutdown = null)
    { this.session = session; this.presence = presence; this.shutdown = shutdown; }

    public void OnWindowCloseRequested() { lock (gate) if (!exiting) presence.HideWindow(); }
    public Task ActivateAsync(DeviceId? device = null)
    {
        lock (gate) if (!exiting) presence.ShowWindow(device);
        return Task.CompletedTask;
    }
    public Task PauseAsync() => TransitionAsync(false);
    public Task ResumeAsync() => TransitionAsync(true);
    private async Task TransitionAsync(bool resume)
    {
        Task operation;
        lock (gate)
        {
            if (exiting) return;
            presence.SetTrayState(resume ? TrayState.Paused : TrayState.Pausing);
            // Session admission is synchronous even when its SDK transition
            // waits behind another transition. Do not queue user intent here.
            operation = resume ? session.ResumeAsync() : session.PauseAsync();
        }
        try
        {
            await operation.ConfigureAwait(false);
            lock (gate) if (!exiting) presence.SetTrayState(session.State switch
            { SessionState.Running => TrayState.Normal, SessionState.Pausing => TrayState.Pausing, SessionState.Faulted => TrayState.Warning, _ => TrayState.Paused });
        }
        catch
        {
            lock (gate)
            {
                if (exiting) return;
                presence.SetTrayState(TrayState.Warning);
                presence.ShowNotification(new("Notification.SdkFailure", null, null, []));
            }
        }
    }
    public Task ExitAsync()
    {
        lock (gate)
        {
            exiting = true;
            shutdown?.BlockNewWork();
            return exit ??= ExitCoreAsync();
        }
    }
    private async Task ExitCoreAsync()
    {
        // ShareSession owns native stop/disposal; accepted content continuations
        // can still be retiring afterward. Flush only beyond their owner boundary.
        try { await session.ExitAsync().ConfigureAwait(false); }
        catch { /* Session's finally has already attempted SDK retirement. */ }
        finally
        {
            if (shutdown is not null) await shutdown.RetireAsync().ConfigureAwait(false);
            try { presence.DisposeResources(); }
            finally { presence.ExitApplication(); }
        }
    }
}
