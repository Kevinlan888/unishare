using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

public sealed class AppLifecycleCoordinator
{
    private readonly ShareSession session;
    private readonly IAppPresence presence;
    private readonly SemaphoreSlim transitions = new(1, 1);
    private readonly object gate = new();
    private Task? exit;
    private bool exiting;

    public AppLifecycleCoordinator(ShareSession session, IAppPresence presence)
    { this.session = session; this.presence = presence; }

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
        await transitions.WaitAsync().ConfigureAwait(false);
        try
        {
            lock (gate) { if (exiting) return; presence.SetTrayState(resume ? TrayState.Paused : TrayState.Pausing); }
            try
            {
                if (resume) await session.ResumeAsync().ConfigureAwait(false);
                else await session.PauseAsync().ConfigureAwait(false);
                lock (gate) if (!exiting) presence.SetTrayState(resume ? TrayState.Normal : TrayState.Paused);
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
        finally { transitions.Release(); }
    }
    public Task ExitAsync()
    {
        lock (gate)
        {
            exiting = true;
            return exit ??= ExitCoreAsync();
        }
    }
    private async Task ExitCoreAsync()
    {
        // ShareSession blocks admission synchronously, then owns native stop and
        // disposal. No UI resource is retired while those callbacks are alive.
        try { await session.ExitAsync().ConfigureAwait(false); }
        catch { /* Session's finally has already attempted SDK retirement. */ }
        finally
        {
            try { presence.DisposeResources(); }
            finally { presence.ExitApplication(); }
        }
    }
}
