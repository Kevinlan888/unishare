using UniShare.Application.Abstractions;

namespace UniShare.App.Services;

public enum TrayCommand
{
    Open, TogglePause, Exit
}
public interface ITrayPlatform : IDisposable
{
    void Create(Action<TrayCommand> callback);
    void Update(TrayState state, string tooltip, string open, string pause, string exit);
}
public sealed class TrayIconService : IDisposable
{
    private readonly ITrayPlatform platform;
    private readonly Func<string, string> localize;
    private bool disposed;
    private TrayState state;
    public TrayIconService(ITrayPlatform platform, Func<string, string> localize, Action<TrayCommand> command)
    {
        this.platform = platform;
        this.localize = localize;
        try
        {
            platform.Create(value => { if (!disposed) command(value); });
            SetState(TrayState.Normal);
        }
        catch { Dispose(); throw; }
    }
    public void SetState(TrayState state)
    {
        if (disposed)
            return;
        this.state = state;
        platform.Update(state, localize("Tray." + state), localize("Tray.Open"),
            localize(state is TrayState.Paused or TrayState.Warning ? "Tray.Resume" : "Tray.Pause"), localize("Tray.Exit"));
    }
    public void RefreshText() => SetState(state);
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        platform.Dispose();
    }
}
