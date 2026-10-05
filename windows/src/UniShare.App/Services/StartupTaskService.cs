namespace UniShare.App.Services;

public interface IStartupTaskPlatform
{
    bool HasPackageIdentity { get; }
    Task<bool> SetEnabledAsync(bool enabled);
}
public sealed class StartupTaskService
{
    private readonly IStartupTaskPlatform platform;
    private readonly SemaphoreSlim changes = new(1, 1);
    public StartupTaskService(IStartupTaskPlatform platform, bool demo)
    { this.platform = platform; Available = !demo && platform.HasPackageIdentity; }
    public bool Enabled { get; private set; }
    public bool Available { get; }
    public async Task<bool> SetEnabledAsync(bool enabled)
    {
        if (!Available) return false;
        await changes.WaitAsync();
        try
        {
            try { Enabled = await platform.SetEnabledAsync(enabled); }
            catch { Enabled = false; }
            return Enabled;
        }
        finally { changes.Release(); }
    }
}
