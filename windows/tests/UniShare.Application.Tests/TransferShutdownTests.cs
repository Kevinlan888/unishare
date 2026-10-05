using System.Collections.Immutable;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class TransferShutdownTests
{
    private static readonly DeviceId Device = new(Guid.NewGuid());
    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    [TestMethod]
    public async Task ExitWaitsForDelayedNativeCompletionThenStageCleanupThenMetadataFlush()
    {
        var x = await Create(); x.Files.CleanupRelease = Signal(); x.Repository.TerminalRelease = Signal();
        x.Sdk.Clipboard = new(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(1));
        await x.Transfers.SendClipboardAsync(Device, default);
        var path = x.Files.Source!.Path;
        var exit = x.Lifecycle.ExitAsync();
        Assert.IsFalse(exit.IsCompleted); Assert.IsTrue(File.Exists(path)); Assert.AreEqual(0, x.Presence.Calls.Count);
        x.Sdk.Finish(new(null, null));
        await x.Files.CleanupEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(exit.IsCompleted); Assert.IsTrue(File.Exists(path));
        x.Files.CleanupRelease.SetResult();
        await x.Repository.TerminalEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(exit.IsCompleted); Assert.IsFalse(File.Exists(path)); Assert.AreEqual(0, x.Presence.Calls.Count);
        x.Repository.TerminalRelease.SetResult(); await exit.WaitAsync(TimeSpan.FromSeconds(5));
        CollectionAssert.AreEqual(new[] { "resources", "exit" }, x.Presence.Calls);
        Assert.AreEqual(ActivityStatus.Completed, x.Repository.Saved.Last().Status);
    }

    [TestMethod]
    [DataRow(ContentKind.Text)]
    [DataRow(ContentKind.Image)]
    [DataRow(ContentKind.File)]
    public async Task ExitWaitsForAcceptedReceiveAndRetiresTransientOrPartial(ContentKind kind)
    {
        var x = await Create(); var entered = Signal(); var native = Signal();
        x.Sdk.Accept = _ => { entered.SetResult(); return native.Task; };
        x.Sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(kind)));
        await WaitUntil(() => x.Tracker.GetDeviceSnapshot(Device).Count == 1);
        var acceptance = x.Transfers.AcceptAsync(x.Tracker.GetDeviceSnapshot(Device).Single().Id, default);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var path = kind == ContentKind.Text ? x.Files.Destination!.Path : x.Files.Receive!.StagingPath;
        var exit = x.Lifecycle.ExitAsync();
        Assert.IsFalse(exit.IsCompleted); Assert.IsTrue(File.Exists(path)); Assert.AreEqual(0, x.Presence.Calls.Count);
        native.SetResult(); await acceptance; await exit.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(File.Exists(path));
        Assert.AreEqual(ActivityStatus.Cancelled, x.Tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.AreEqual(ActivityStatus.Cancelled, x.Repository.Saved.Last().Status);
    }

    [TestMethod]
    public async Task PublishedReceiveCleanupStillOwnsExitAfterTrackerIsIdle()
    {
        var x = await Create(); x.Files.CleanupRelease = Signal();
        x.Sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File)));
        await WaitUntil(() => x.Tracker.GetDeviceSnapshot(Device).Count == 1);
        var acceptance = x.Transfers.AcceptAsync(x.Tracker.GetDeviceSnapshot(Device).Single().Id, default);
        await x.Files.CleanupEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(0, x.Tracker.ActiveCount);
        var exit = x.Lifecycle.ExitAsync(); Assert.IsFalse(exit.IsCompleted); Assert.AreEqual(0, x.Presence.Calls.Count);
        x.Files.CleanupRelease.SetResult(); await acceptance; await exit.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(File.Exists(x.Files.Receive!.StagingPath));
        Assert.AreEqual(ActivityStatus.Completed, x.Repository.Saved.Last().Status);
    }

    [TestMethod]
    public async Task UndecidedOffersAreTerminalizedWithoutNativeDecisionAndLateEventsCannotWrite()
    {
        var x = await Create(); x.Sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File)));
        await WaitUntil(() => x.Tracker.GetDeviceSnapshot(Device).Count == 1);
        await x.Lifecycle.ExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(ActivityStatus.Cancelled, x.Repository.Saved.Last().Status);
        Assert.AreEqual(0, x.Sdk.AcceptCount); Assert.AreEqual(0, x.Sdk.Commands.Count);
        var count = x.Repository.Saved.Count;
        x.Sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text)));
        await x.Tracker.SavePendingAsync(); Assert.AreEqual(count, x.Repository.Saved.Count);
        await Assert.ThrowsAsync<ObjectDisposedException>(async () => await x.Transfers.SendTextAsync(Device, "new", ShareOrigin.Direct, default));
        await x.Lifecycle.ExitAsync(); Assert.AreEqual(2, x.Presence.Calls.Count);
    }

    private static IncomingOffer Offer(ContentKind kind) => new(new(7), new(Guid.NewGuid()), Device, kind, "content", kind == ContentKind.Image ? "image/png" : "text/plain", 1);
    private static async Task WaitUntil(Func<bool> condition)
    { using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5)); while (!condition()) await Task.Delay(10, timeout.Token); }
    private static async Task<Context> Create()
    {
        var sdk = new TransferCoordinatorTests.Sdk(); var repository = new Repository(); var files = new Files();
        var tracker = new ActivityTracker(repository, new Clock()); var session = new ShareSession(sdk, tracker);
        var transfers = new TransferCoordinator(sdk, tracker, files, () => new() { ReceiveDirectory = "receive" }); var presence = new Presence();
        await session.InitializeAsync(new()); await session.StartAsync();
        return new(sdk, repository, files, tracker, transfers, presence, new(session, presence, new TransferShutdown(transfers, tracker)));
    }
    private sealed record Context(TransferCoordinatorTests.Sdk Sdk, Repository Repository, Files Files, ActivityTracker Tracker, TransferCoordinator Transfers, Presence Presence, AppLifecycleCoordinator Lifecycle);
    private sealed class Clock : IClock { public DateTimeOffset UtcNow => DateTimeOffset.UtcNow; }
    private sealed class Repository : IActivityRepository
    {
        public List<ActivityRecord> Saved { get; } = [];
        public TaskCompletionSource TerminalEntered { get; } = Signal(); public TaskCompletionSource? TerminalRelease { get; set; }
        public async Task UpsertAsync(ActivityRecord record, CancellationToken cancellationToken)
        {
            if (record.Status is ActivityStatus.Completed or ActivityStatus.Cancelled) { TerminalEntered.TrySetResult(); if (TerminalRelease is not null) await TerminalRelease.Task; }
            Saved.Add(record);
        }
        public Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<ActivityRecord>>([]);
        public Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<ActivityRecord>>([]);
        public Task PruneAsync(DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, CancellationToken cancellationToken) => Task.CompletedTask;
    }
    private sealed class Presence : IAppPresence
    {
        public List<string> Calls { get; } = []; public bool IsWindowVisible => false;
        public void ShowWindow(DeviceId? device = null) { } public void HideWindow() { } public void SetTrayState(TrayState state) { }
        public void ShowNotification(AppNotificationRequest notification) { } public void DisposeResources() => Calls.Add("resources"); public void ExitApplication() => Calls.Add("exit");
    }
    private sealed class Files : ITransferFiles
    {
        public Source? Source { get; private set; } public TransientDestination? Destination { get; private set; } public Receive? Receive { get; private set; }
        public TaskCompletionSource CleanupEntered { get; } = Signal(); public TaskCompletionSource? CleanupRelease { get; set; }
        private async ValueTask Cleanup(string path) { CleanupEntered.TrySetResult(); if (CleanupRelease is not null) await CleanupRelease.Task; File.Delete(path); }
        public Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mimeType, CancellationToken cancellationToken)
        { Source = new(new(Path.GetTempFileName(), "Image", mimeType, (ulong)bytes.Length), Cleanup); return Task.FromResult<TransientSource>(Source); }
        public Task<TransientDestination> CreateTextDestinationAsync(CancellationToken cancellationToken)
        { Destination = new TransferCoordinatorTests.Destination(Path.GetTempFileName()); return Task.FromResult(Destination); }
        public Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string receiveRoot, CancellationToken cancellationToken)
        { Receive = new(Path.GetTempFileName(), Cleanup); return Task.FromResult<PreparedReceive>(Receive); }
        public Task<string> PublishAsync(PreparedReceive receive, CancellationToken cancellationToken) { cancellationToken.ThrowIfCancellationRequested(); return Task.FromResult("published"); }
        public Task AbortAsync(PreparedReceive receive) => receive.DisposeAsync().AsTask();
    }
    private sealed record Source(ShareSource Value, Func<string, ValueTask> Cleanup) : TransientSource(Value)
    { public override ValueTask DisposeAsync() => Cleanup(Path); }
    private sealed record Receive(string Value, Func<string, ValueTask> Cleanup) : PreparedReceive(Value, "published")
    { public override ValueTask DisposeAsync() => Cleanup(StagingPath); }
}
