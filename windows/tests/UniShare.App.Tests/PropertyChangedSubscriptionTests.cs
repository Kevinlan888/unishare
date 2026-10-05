using System.ComponentModel;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;

namespace UniShare.App.Tests;

[TestClass]
public sealed class PropertyChangedSubscriptionTests
{
    [TestMethod]
    public void RepeatedReplacementOnlyNotifiesTheCurrentCard()
    {
        var activity = new ActivityChanges();
        var removedNotifications = 0;
        for (var replacement = 0; replacement < 200; replacement++)
        {
            var removed = new PropertyChangedSubscription(activity, () => removedNotifications++);
            removed.Dispose();
        }
        var currentNotifications = 0;
        using var current = new PropertyChangedSubscription(activity, () => currentNotifications++);
        activity.Change();
        Assert.AreEqual(0, removedNotifications, "Removed cards still receive live activity changes.");
        Assert.AreEqual(1, currentNotifications, "The current card must refresh exactly once.");
        Assert.AreEqual(1, activity.SubscriberCount, "Live activities must retain only the current card subscription.");
    }

    [TestMethod]
    public void DisposalIsIdempotentAndRemovesTheSubscription()
    {
        var activity = new ActivityChanges();
        var notifications = 0;
        var subscription = new PropertyChangedSubscription(activity, () => notifications++);
        activity.Change();
        subscription.Dispose();
        subscription.Dispose();
        activity.Change();
        Assert.AreEqual(1, notifications);
        Assert.AreEqual(0, activity.SubscriberCount);
    }

    private sealed class ActivityChanges : INotifyPropertyChanged
    {
        private PropertyChangedEventHandler? handler;
        public int SubscriberCount => handler?.GetInvocationList().Length ?? 0;
        public event PropertyChangedEventHandler? PropertyChanged { add => handler += value; remove => handler -= value; }
        public void Change() => handler?.Invoke(this, new PropertyChangedEventArgs("Status"));
    }
}
