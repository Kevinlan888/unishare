using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;
using UniShare.Application.Models;

namespace UniShare.App.Tests;

[TestClass]
public sealed class ActivityCardReconcilerTests
{
    [TestMethod]
    public void AddingAConcurrentTransferReusesTheActiveCard()
    {
        using var reconciler = Reconciler();
        var active = Projection(1, ActivityStatus.Transferring);
        var activeCard = reconciler.Reconcile([active], _ => true).Single();

        var cards = reconciler.Reconcile([active, Projection(2, ActivityStatus.AwaitingPeer)], _ => true);

        Assert.AreSame(activeCard, cards[0]);
        Assert.IsFalse(activeCard.Disposed);
        Assert.AreEqual(2, cards.Count);
    }

    [TestMethod]
    public void StatusTransitionReplacesOnlyTheCardWhoseVisualKindChanged()
    {
        using var reconciler = Reconciler();
        var incoming = Projection(1, ActivityStatus.AwaitingDecision);
        var active = Projection(2, ActivityStatus.Transferring);
        var before = reconciler.Reconcile([incoming, active], _ => true).ToArray();

        incoming.Status = ActivityStatus.Transferring;
        var after = reconciler.Reconcile([incoming, active], _ => true);

        Assert.AreNotSame(before[0], after[0]);
        Assert.IsTrue(before[0].Disposed);
        Assert.AreSame(before[1], after[1]);
        Assert.IsFalse(before[1].Disposed);
    }

    private static ActivityCardReconciler<Card> Reconciler() => new(
        (activity, kind) => new(activity, kind), card => card.Disposed = true);

    private static ActivityProjection Projection(int id, ActivityStatus status) => new(
        ContentKind.File, ShareDirection.Incoming, "large.bin", "", "100", "12:00", status)
    {
        Id = new(new Guid(id, 0, 0, new byte[8]))
    };

    private sealed class Card(ActivityProjection activity, ActivityCardKind kind)
    {
        public ActivityProjection Activity { get; } = activity;
        public ActivityCardKind Kind { get; } = kind;
        public bool Disposed { get; set; }
    }
}
