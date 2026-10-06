using UniShare.Application.Models;

namespace UniShare.Application.Services;

public static class RecentFileActivities
{
    public static IReadOnlyList<ActivityRecord> Select(IEnumerable<ActivityRecord> activities, int limit = 3) =>
        activities.Where(record => record.Kind is ContentKind.File or ContentKind.Image)
            .OrderByDescending(record => record.CreatedAt)
            .Take(limit)
            .ToArray();
}
