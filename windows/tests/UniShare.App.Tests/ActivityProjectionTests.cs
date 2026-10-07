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
    public void FileSizesAutomaticallyUseByteKilobyteMegabyteAndGigabyteUnits()
    {
        (ulong Bytes, string Text)[] cases =
        [
            (0, "0 B"),
            (1023, "1023 B"),
            (1024, "1 KB"),
            (1536, "1.5 KB"),
            (1024 * 1024, "1 MB"),
            (1536 * 1024, "1.5 MB"),
            (1024UL * 1024 * 1024, "1 GB"),
            (1536UL * 1024 * 1024, "1.5 GB"),
            (1932735283, "1.8 GB")
        ];

        foreach (var (bytes, expected) in cases)
            Assert.AreEqual(expected, ActivityProjection.FormatSize(bytes), $"Unexpected size for {bytes} bytes.");
    }

    [TestMethod]
    public void ProgressTextUsesTheLivePercentageAndNotifiesWhenItChanges()
    {
        var property = typeof(ActivityProjection).GetProperty("ProgressText");
        Assert.IsNotNull(property, "The progress label has no live projection property.");
        var current = Projection(25);
        var activities = new ObservableCollection<ActivityProjection> { current };
        var textChanges = 0;
        current.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == "ProgressText")
                textChanges++;
        };

        Assert.AreEqual("25%", property.GetValue(current));
        ActivityProjectionCollection.Synchronize(activities, [Projection(60)]);

        Assert.AreEqual("60%", property.GetValue(current));
        Assert.AreEqual(1, textChanges);
    }

    [TestMethod]
    public void ProgressSamplesExposeTransferSpeedAndEstimatedRemainingTime()
    {
        var started = DateTimeOffset.Parse("2026-10-07T10:00:00Z");
        var current = Projection(25, transferredBytes: 256 * 1024, byteCount: 1024 * 1024, updatedAt: started);
        var activities = new ObservableCollection<ActivityProjection> { current };

        ActivityProjectionCollection.Synchronize(activities,
            [Projection(50, transferredBytes: 512 * 1024, byteCount: 1024 * 1024, updatedAt: started.AddSeconds(2))]);

        Assert.AreEqual(128 * 1024d, current.BytesPerSecond);
        Assert.AreEqual("128 KB/s", current.SpeedText);
        Assert.AreEqual(TimeSpan.FromSeconds(4), current.EstimatedRemaining);
        Assert.AreEqual("00:04", current.EstimatedRemainingText);
    }

    [TestMethod]
    public void DelayedProgressSamplesDoNotRegressTransferMetrics()
    {
        var started = DateTimeOffset.Parse("2026-10-07T10:00:00Z");
        var current = Projection(25, transferredBytes: 256 * 1024, byteCount: 1024 * 1024, updatedAt: started);
        var activities = new ObservableCollection<ActivityProjection> { current };
        ActivityProjectionCollection.Synchronize(activities,
            [Projection(50, transferredBytes: 512 * 1024, byteCount: 1024 * 1024, updatedAt: started.AddSeconds(2))]);

        ActivityProjectionCollection.Synchronize(activities,
            [Projection(40, transferredBytes: 400 * 1024, byteCount: 1024 * 1024, updatedAt: started.AddSeconds(1))]);

        Assert.AreEqual(128 * 1024d, current.BytesPerSecond);
        Assert.AreEqual(TimeSpan.FromSeconds(4), current.EstimatedRemaining);
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

    [TestMethod]
    public async Task CompletedIncomingFilesAndImagesUseTheSystemOpenBoundary()
    {
        foreach (var kind in new[] { ContentKind.File, ContentKind.Image })
        {
            var calls = new List<(string Path, bool Location)>();
            var item = new ActivityProjection(kind, ShareDirection.Incoming, "received", "", "1 MB", "12:00",
                ActivityStatus.Completed, receivedFilePath: "C:\\Downloads\\received.bin", fileExists: _ => true,
                open: (path, location) =>
                {
                    calls.Add((path, location));
                    return Task.CompletedTask;
                });

            Assert.IsTrue(item.FileAvailable);
            await item.OpenAsync(false);
            await item.OpenAsync(true);

            CollectionAssert.AreEqual(new[]
            {
                ("C:\\Downloads\\received.bin", false),
                ("C:\\Downloads\\received.bin", true)
            }, calls);
        }
    }

    [TestMethod]
    public void CompletionNotifiesFileAvailabilityAfterThePathAndStatusAreUpdated()
    {
        var current = new ActivityProjection(ContentKind.Image, ShareDirection.Incoming, "photo.png", "", "1 MB", "12:00",
            ActivityStatus.Transferring) { Id = Activity };
        var completed = new ActivityProjection(ContentKind.Image, ShareDirection.Incoming, "photo.png", "", "1 MB", "12:01",
            ActivityStatus.Completed, receivedFilePath: "C:\\Downloads\\photo.png", fileExists: _ => true) { Id = Activity };
        var observed = new List<bool>();
        current.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(ActivityProjection.FileAvailable))
                observed.Add(current.FileAvailable);
        };

        ActivityProjectionCollection.Synchronize(new ObservableCollection<ActivityProjection> { current }, [completed]);

        CollectionAssert.AreEqual(new[] { true }, observed);
    }

    [TestMethod]
    public async Task DeletedReceivedItemRefreshesAvailabilityAndDoesNotInvokeTheSystem()
    {
        var exists = true;
        var openCalls = 0;
        var item = new ActivityProjection(ContentKind.File, ShareDirection.Incoming, "received.bin", "", "1 MB", "12:00",
            ActivityStatus.Completed, receivedFilePath: "C:\\Downloads\\received.bin", fileExists: _ => exists,
            open: (_, _) => { openCalls++; return Task.CompletedTask; });
        var observed = new List<bool>();
        item.PropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(ActivityProjection.FileAvailable))
                observed.Add(item.FileAvailable);
        };

        exists = false;
        item.RefreshFileAvailability();
        await item.OpenAsync(false);

        Assert.IsTrue(observed.Count >= 1);
        Assert.IsTrue(observed.All(value => !value));
        Assert.AreEqual(0, openCalls);
    }

    private static ActivityProjection Projection(double progress, ActivityId? id = null, string text = "",
        ActivityStatus status = ActivityStatus.Transferring, string name = "large.bin", ulong transferredBytes = 0,
        ulong byteCount = 0, DateTimeOffset? updatedAt = null) => new(
        ContentKind.File, ShareDirection.Outgoing, name, text, "100", "12:00",
        status, ShareOrigin.Direct, progress, transferredBytes, byteCount, updatedAt)
    {
        Id = id ?? Activity
    };
}
