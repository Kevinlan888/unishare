using UniShare.Application.Models;

namespace UniShare.Application.Services;

public static class ActivityTransition
{
    // Fast/empty transfers can complete without a progress callback; failures,
    // rejection and cancellation can also happen before registration or acceptance.
    private static readonly IReadOnlyDictionary<ActivityStatus, ActivityStatus[]> Legal =
        new Dictionary<ActivityStatus, ActivityStatus[]>
        {
            [ActivityStatus.AwaitingPeer] = [ActivityStatus.Transferring, ActivityStatus.Completed, ActivityStatus.Rejected, ActivityStatus.Cancelled, ActivityStatus.Failed],
            [ActivityStatus.AwaitingDecision] = [ActivityStatus.Transferring, ActivityStatus.Completed, ActivityStatus.Rejected, ActivityStatus.Cancelled, ActivityStatus.Failed],
            [ActivityStatus.Transferring] = [ActivityStatus.Completed, ActivityStatus.Rejected, ActivityStatus.Cancelled, ActivityStatus.Failed],
            [ActivityStatus.Completed] = [],
            [ActivityStatus.Rejected] = [],
            [ActivityStatus.Cancelled] = [],
            [ActivityStatus.Failed] = []
        };

    public static bool CanTransition(ActivityStatus from, ActivityStatus to) =>
        Legal.TryGetValue(from, out var targets) && targets.Contains(to);

    public static bool IsTerminal(ActivityStatus status) => status is
        ActivityStatus.Completed or ActivityStatus.Rejected or ActivityStatus.Cancelled or ActivityStatus.Failed;

    public static bool IsRetryable(ActivityRecord record) => record.Direction == ShareDirection.Outgoing &&
        record.Status == ActivityStatus.Failed && record.ErrorKind is ShareErrorKind.NetworkLost or ShareErrorKind.Timeout;

    internal static ActivityStatus TerminalStatus(ShareFailure? failure) => failure?.Kind switch
    {
        null => ActivityStatus.Completed,
        ShareErrorKind.TransferRejected => ActivityStatus.Rejected,
        ShareErrorKind.Cancelled => ActivityStatus.Cancelled,
        _ => ActivityStatus.Failed
    };
}
