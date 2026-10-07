using UniShare.Application.Models;

namespace UniShare.App.Presentation;

internal enum ActivityCardKind
{
    Offer,
    Image,
    File,
    Text
}

internal sealed class ActivityCardReconciler<TCard> : IDisposable where TCard : class
{
    private readonly Func<ActivityProjection, ActivityCardKind, TCard> create;
    private readonly Action<TCard> retire;
    private readonly Dictionary<ActivityProjection, Entry> entries = new(ReferenceEqualityComparer.Instance);
    private bool disposed;

    public ActivityCardReconciler(Func<ActivityProjection, ActivityCardKind, TCard> create, Action<TCard> retire)
    {
        this.create = create;
        this.retire = retire;
    }

    public IReadOnlyList<TCard> Reconcile(IReadOnlyList<ActivityProjection> activities, Predicate<ActivityProjection> visible)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        ArgumentNullException.ThrowIfNull(activities);
        ArgumentNullException.ThrowIfNull(visible);
        var desired = activities.Where(activity => visible(activity)).ToArray();
        var desiredSet = new HashSet<ActivityProjection>(desired, ReferenceEqualityComparer.Instance);
        foreach (var obsolete in entries.Keys.Where(activity => !desiredSet.Contains(activity)).ToArray())
        {
            retire(entries[obsolete].Card);
            entries.Remove(obsolete);
        }
        var cards = new TCard[desired.Length];
        for (var index = 0; index < desired.Length; index++)
        {
            var activity = desired[index];
            var kind = CardKind(activity);
            if (!entries.TryGetValue(activity, out var entry) || entry.Kind != kind)
            {
                if (entry is not null)
                    retire(entry.Card);
                entry = new(kind, create(activity, kind));
                entries[activity] = entry;
            }
            cards[index] = entry.Card;
        }
        return cards;
    }

    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        foreach (var entry in entries.Values)
            retire(entry.Card);
        entries.Clear();
    }

    private static ActivityCardKind CardKind(ActivityProjection activity) => activity.Status == ActivityStatus.AwaitingDecision
        ? ActivityCardKind.Offer
        : activity.Kind switch
        {
            ContentKind.Image => ActivityCardKind.Image,
            ContentKind.File => ActivityCardKind.File,
            _ => ActivityCardKind.Text
        };

    private sealed record Entry(ActivityCardKind Kind, TCard Card);
}
