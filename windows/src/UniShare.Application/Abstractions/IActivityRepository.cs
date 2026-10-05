using UniShare.Application.Models;

namespace UniShare.Application.Abstractions;

public interface IActivityRepository
{
    Task UpsertAsync(ActivityRecord record, CancellationToken cancellationToken);
    Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken);
    Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken cancellationToken);
    Task PruneAsync(DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, CancellationToken cancellationToken);
}
