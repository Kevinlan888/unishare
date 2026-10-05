using UniShare.Application.Abstractions;

namespace UniShare.Application.Services;

public sealed class TransferShutdown(TransferCoordinator transfers, ActivityTracker tracker) : IAppShutdown
{
    public void BlockNewWork() => transfers.BeginRetirement();
    public async Task RetireAsync()
    {
        await transfers.RetireAsync().ConfigureAwait(false);
        await tracker.SavePendingAsync().ConfigureAwait(false);
    }
}
