using System.Collections.ObjectModel;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;

namespace UniShare.App.Tests;

[TestClass]
public sealed class ActivityProjectionTests
{
    private static readonly ActivityId Activity = new(Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
    private static readonly ActivityId SecondActivity = new(Guid.Parse("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"));

    [TestMethod]
    public void SynchronizingProgressKeepsTheExistingCardAndNeverMovesBackward()
    {
        var current = Projection(25);
        var activities = new ObservableCollection<ActivityProjection> { current };
        var collectionChanges = 0;
        activities.CollectionChanged += (_, _) => collectionChanges++;

        ActivityProjectionCollection.Synchronize(activities, [Projection(60)]);

        Assert.AreSame(current, activities.Single());
        Assert.AreEqual(60d, current.Progress);
        Assert.AreEqual(0, collectionChanges, "A progress update must not recreate the activity card.");

        ActivityProjectionCollection.Synchronize(activities, [Projection(40)]);

        Assert.AreSame(current, activities.Single());
        Assert.AreEqual(60d, current.Progress, "Delayed progress must not restart the progress bar.");
        Assert.AreEqual(0, collectionChanges);
    }

    [TestMethod]
    public void ProgressPercentageHandlesEmptyAndOutOfRangeTransfers()
    {
        Assert.AreEqual(0d, ActivityProjection.ProgressPercentage(0, 0));
        Assert.AreEqual(25d, ActivityProjection.ProgressPercentage(25, 100));
        Assert.AreEqual(100d, ActivityProjection.ProgressPercentage(200, 100));
    }

    [TestMethod]
    public void SynchronizingUpdatesLateContentWithoutReplacingTheProjection()
    {
        var current = Projection(0, text: "", status: ActivityStatus.AwaitingDecision);
        var activities = new ObservableCollection<ActivityProjection> { current };

        ActivityProjectionCollection.Synchronize(activities,
            [Projection(100, text: "received body", status: ActivityStatus.Completed)]);

        Assert.AreSame(current, activities.Single());
        Assert.AreEqual("received body", current.Text);
        Assert.AreEqual(ActivityStatus.Completed, current.Status);
    }

    [TestMethod]
    public void AddingANewTransferKeepsTheActiveProjectionAndStartsTheNewOneAtZero()
    {
        var active = Projection(60);
        var activities = new ObservableCollection<ActivityProjection> { active };

        ActivityProjectionCollection.Synchronize(activities,
            [Projection(70), Projection(0, id: SecondActivity)]);

        Assert.AreSame(active, activities[0]);
        Assert.AreEqual(70d, activities[0].Progress);
        Assert.AreEqual(SecondActivity, activities[1].Id);
        Assert.AreEqual(0d, activities[1].Progress);
    }

    [TestMethod]
    public void SynchronizingTheSameStatusRefreshesLocalizedFields()
    {
        var current = Projection(20, name: "Old text label");
        var activities = new ObservableCollection<ActivityProjection> { current };
        var statusTextChanges = 0;
        current.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(ActivityProjection.StatusText))
                statusTextChanges++;
        };

        ActivityProjectionCollection.Synchronize(activities, [Projection(30, name: "New text label")]);

        Assert.AreEqual("New text label", current.Name);
        Assert.AreEqual(1, statusTextChanges);
    }

    private static ActivityProjection Projection(double progress, ActivityId? id = null, string text = "",
        ActivityStatus status = ActivityStatus.Transferring, string name = "large.bin") => new(
        ContentKind.File, ShareDirection.Outgoing, name, text, "100", "12:00",
        status, ShareOrigin.Direct, progress)
    {
        Id = id ?? Activity
    };
}
