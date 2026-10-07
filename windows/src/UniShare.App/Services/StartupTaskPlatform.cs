using Windows.ApplicationModel;

namespace UniShare.App.Services;

public sealed class StartupTaskPlatform : IStartupTaskPlatform
{
    public bool HasPackageIdentity
    {
        get
        {
            try
            {
                _ = Package.Current.Id;
                return true;
            }
            catch { return false; }
        }
    }
    public async Task<bool> SetEnabledAsync(bool enabled)
    {
        if (!HasPackageIdentity)
            return false;
        var task = await StartupTask.GetAsync("UniShareStartup");
        if (!enabled)
        {
            task.Disable();
            return false;
        }
        var state = task.State == StartupTaskState.Disabled ? await task.RequestEnableAsync() : task.State;
        return state is StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy;
    }
}
