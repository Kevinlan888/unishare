using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.App.Presentation;

namespace UniShare.App.Tests;

[TestClass]
public sealed class RuntimeProjectionAdapterTests
{
    private static readonly DeviceId Device = new(Guid.Parse("11111111-2222-3333-4444-555555555555"));
    private sealed class Repository : IActivityRepository
    {
        public IReadOnlyList<ActivityRecord> History = [];
        public Task UpsertAsync(ActivityRecord record, CancellationToken token) => Task.CompletedTask;
        public Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken token) => Task.FromResult(History);
        public Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken token) => Task.FromResult(History);
        public Task PruneAsync(DateTimeOffset now, int max, TimeSpan age, CancellationToken token) => Task.CompletedTask;
    }
    private sealed class Clock : IClock { public DateTimeOffset UtcNow => DateTimeOffset.Parse("2026-10-05T12:00:00Z"); }
    private sealed class Projection(ActivityTracker tracker) : IRuntimeProjection
    {
        public DeviceId? SelectedDeviceId { get; set; } = Device;
        public IReadOnlyList<ActivityRecord> Timeline = [];
        public int Selections;
        public int Renders;
        public void RefreshDevices() { /* Value-equal device replacement does not trigger selection. */ }
        public void RenderActivities(DeviceId device) { Timeline = tracker.GetDeviceSnapshot(device); Renders++; }
        public void Select(DeviceId device) { SelectedDeviceId = device; Selections++; }
    }
    [TestMethod]
    public async Task StableDeviceTrackerChangesRefreshOffersSendsHistoryAndTerminalsWithoutReselection()
    {
        var repository = new Repository(); var tracker = new ActivityTracker(repository, new Clock());
        var projection = new Projection(tracker); var queue = new Queue<Action>();
        using var adapter = new RuntimeProjectionAdapter(tracker, projection, queue.Enqueue);
        await tracker.LoadDeviceAsync(Device);
        var offer = tracker.AddIncomingOffer(new(new(42), new(Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")), Device, ContentKind.File, "file.txt", "text/plain", 3));
        while (queue.TryDequeue(out var action)) action();
        Assert.AreEqual(ActivityStatus.AwaitingDecision, projection.Timeline.Single().Status);
        var send = tracker.BeginOutgoing(Device, new(1), ContentKind.Text, "Text", "text/plain", 4, ShareOrigin.Direct);
        while (queue.TryDequeue(out var action)) action();
        Assert.AreEqual(2, projection.Timeline.Count);
        tracker.Complete(offer.Id, new(offer.TransferId, null)); tracker.Complete(send.Id, new(null, null));
        while (queue.TryDequeue(out var action)) action();
        Assert.IsTrue(projection.Timeline.All(record => record.Status == ActivityStatus.Completed));
        var history = send with { Id = new(Guid.Parse("ffffffff-1111-2222-3333-444444444444")), TransferId = null, OperationId = null };
        repository.History = [history]; await tracker.LoadDeviceAsync(Device);
        while (queue.TryDequeue(out var action)) action();
        Assert.AreEqual(3, projection.Timeline.Count); Assert.AreEqual(0, projection.Selections);
        Assert.AreEqual(Device, projection.SelectedDeviceId);
    }
    [TestMethod]
    public void DisposedAdapterIgnoresQueuedAndFutureTrackerChanges()
    {
        var tracker = new ActivityTracker(new Repository(), new Clock()); var projection = new Projection(tracker); var queue = new Queue<Action>();
        var adapter = new RuntimeProjectionAdapter(tracker, projection, queue.Enqueue);
        tracker.BeginOutgoing(Device, new(1), ContentKind.Text, "Text", "text/plain", 1, ShareOrigin.Direct);
        adapter.Dispose(); adapter.Dispose();
        while (queue.TryDequeue(out var action)) action();
        tracker.BeginOutgoing(Device, new(2), ContentKind.Text, "Text", "text/plain", 1, ShareOrigin.Direct);
        Assert.AreEqual(0, projection.Renders); Assert.AreEqual(0, queue.Count);
    }
}
