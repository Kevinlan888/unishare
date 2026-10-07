using System.Collections.Specialized;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;

namespace UniShare.App.Tests;

[TestClass]
public sealed class ActivityAutoScrollTrackerTests
{
    [TestMethod]
    public void AddedActivityRequestsExactlyOneScrollAfterLayout()
    {
        var tracker = new ActivityAutoScrollTracker();

        tracker.Observe(new NotifyCollectionChangedEventArgs(NotifyCollectionChangedAction.Add, 1));

        Assert.IsTrue(tracker.ConsumeAfterLayout());
        Assert.IsFalse(tracker.ConsumeAfterLayout(), "One activity addition must not keep forcing later layouts to the bottom.");
    }

    [TestMethod]
    [DataRow(NotifyCollectionChangedAction.Remove)]
    [DataRow(NotifyCollectionChangedAction.Reset)]
    [DataRow(NotifyCollectionChangedAction.Move)]
    public void NonAddChangesDoNotRequestScrolling(NotifyCollectionChangedAction action)
    {
        var tracker = new ActivityAutoScrollTracker();
        NotifyCollectionChangedEventArgs change = action switch
        {
            NotifyCollectionChangedAction.Remove => new(action, 1, 0),
            NotifyCollectionChangedAction.Move => new(action, 1, 1, 0),
            _ => new(action)
        };

        tracker.Observe(change);

        Assert.IsFalse(tracker.ConsumeAfterLayout());
    }
}
