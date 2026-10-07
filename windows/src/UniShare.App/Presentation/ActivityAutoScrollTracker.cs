using System.Collections.Specialized;

namespace UniShare.App.Presentation;

internal sealed class ActivityAutoScrollTracker
{
    private bool pending;

    public void Observe(NotifyCollectionChangedEventArgs change)
    {
        ArgumentNullException.ThrowIfNull(change);
        if (change.Action == NotifyCollectionChangedAction.Add && change.NewItems?.Count > 0)
            pending = true;
    }

    public bool ConsumeAfterLayout()
    {
        if (!pending)
            return false;
        pending = false;
        return true;
    }
}
