using System.Collections.Immutable;
using System.Text.Json;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class ActivityTrackerTests
{
    private static readonly DeviceId Device = new(Guid.Parse("10000000-0000-0000-0000-000000000001"));
    private static readonly TransferId Transfer = new(Guid.Parse("20000000-0000-0000-0000-000000000001"));
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-05T00:00:00Z");
    private static ActivityTracker Create(Repository? repository = null) => new(repository ?? new(), new Clock());
    private static ActivityRecord Begin(ActivityTracker tracker, ulong operation = 1, ShareContent? preview = null) =>
        tracker.BeginOutgoing(Device, new OperationId(operation), preview?.Kind ?? ContentKind.File,
            "report.txt", preview?.MimeType ?? "text/plain", 100, preview is null ? ShareOrigin.Direct : ShareOrigin.Clipboard, preview);
    private static IncomingOffer Offer() => new(new RequestId(1), Transfer, Device, ContentKind.File, "report.txt", "text/plain", 100);
    private static TransferTerminal Failure(ShareErrorKind kind, TransferId? transfer = null) => new(transfer, new(kind, "private payload C:\\private\\secret.txt"));

    [TestMethod]
    public void OutgoingAndIncomingEnterTransferringBeforeCompletion()
    {
        var outgoing = Create();
        var started = Begin(outgoing);
        Assert.AreEqual(ActivityStatus.AwaitingPeer, started.Status);
        outgoing.BindTransfer(started.Id, Transfer);
        outgoing.ApplyProgress(new(Transfer, 40, 100));
        Assert.AreEqual(ActivityStatus.Transferring, outgoing.GetDeviceSnapshot(Device).Single().Status);
        outgoing.Complete(new(Transfer, null));
        Assert.AreEqual(ActivityStatus.Completed, outgoing.GetDeviceSnapshot(Device).Single().Status);

        var incoming = Create();
        Assert.AreEqual(ActivityStatus.AwaitingDecision, incoming.AddIncomingOffer(Offer()).Status);
        incoming.ApplyProgress(new(Transfer, 20, 100));
        Assert.AreEqual(ActivityStatus.Transferring, incoming.GetDeviceSnapshot(Device).Single().Status);
        incoming.Complete(new(Transfer, null), "C:\\Downloads\\report.txt");
        Assert.AreEqual("C:\\Downloads\\report.txt", incoming.GetDeviceSnapshot(Device).Single().ReceivedFilePath);
    }

    [TestMethod]
    public void LegalTableDisallowsResurrectionAndRegressingToWaiting()
    {
        Assert.IsTrue(ActivityTransition.CanTransition(ActivityStatus.AwaitingPeer, ActivityStatus.Transferring));
        Assert.IsTrue(ActivityTransition.CanTransition(ActivityStatus.AwaitingDecision, ActivityStatus.Rejected));
        Assert.IsFalse(ActivityTransition.CanTransition(ActivityStatus.AwaitingPeer, ActivityStatus.AwaitingDecision));
        Assert.IsFalse(ActivityTransition.CanTransition(ActivityStatus.Transferring, ActivityStatus.AwaitingPeer));
        foreach (var terminal in new[] { ActivityStatus.Completed, ActivityStatus.Rejected, ActivityStatus.Cancelled, ActivityStatus.Failed })
        {
            Assert.IsTrue(ActivityTransition.IsTerminal(terminal));
            foreach (var next in Enum.GetValues<ActivityStatus>())
                Assert.IsFalse(ActivityTransition.CanTransition(terminal, next));
        }
    }

    [TestMethod]
    public async Task TerminalRecordsIgnoreDuplicateResultsLateProgressAndLateBinding()
    {
        var repository = new Repository();
        var tracker = Create(repository);
        var started = Begin(tracker);
        tracker.BindTransfer(started.Id, Transfer);
        Assert.IsTrue(tracker.Complete(new(Transfer, null)));
        var terminal = tracker.GetDeviceSnapshot(Device).Single();
        Assert.IsFalse(tracker.Complete(Failure(ShareErrorKind.NetworkLost, Transfer)));
        Assert.IsFalse(tracker.ApplyProgress(new(Transfer, 99, 100)));
        Assert.IsFalse(tracker.BindTransfer(started.Id, new(Guid.NewGuid())));
        Assert.AreEqual(terminal, tracker.GetDeviceSnapshot(Device).Single());
        await tracker.SavePendingAsync();
        Assert.AreEqual(1, repository.Writes.Count(value => ActivityTransition.IsTerminal(value.Status)));
        Assert.AreEqual(0, tracker.ActiveCount);
    }

    [TestMethod]
    public void ProgressIsMonotonicBoundedAndCannotChangeOfferedSize()
    {
        var tracker = Create();
        var started = Begin(tracker);
        tracker.BindTransfer(started.Id, Transfer);
        tracker.ApplyProgress(new(Transfer, 75, 100));
        tracker.ApplyProgress(new(Transfer, 25, 200));
        Assert.AreEqual(75UL, tracker.GetDeviceSnapshot(Device).Single().TransferredBytes);
        Assert.AreEqual(100UL, tracker.GetDeviceSnapshot(Device).Single().ByteCount);
        tracker.ApplyProgress(new(Transfer, ulong.MaxValue, 100));
        Assert.AreEqual(100UL, tracker.GetDeviceSnapshot(Device).Single().TransferredBytes);
        Assert.AreEqual(1, tracker.ActiveCount);
    }

    [TestMethod]
    public void RejectionCancellationAndPermanentFailureStayDistinct()
    {
        var tracker = Create();
        foreach (var (kind, status) in new[] {
            (ShareErrorKind.TransferRejected, ActivityStatus.Rejected),
            (ShareErrorKind.Cancelled, ActivityStatus.Cancelled),
            (ShareErrorKind.StorageFailed, ActivityStatus.Failed) })
        {
            var record = Begin(tracker, (ulong)kind);
            tracker.Complete(record.Id, Failure(kind));
            var completed = tracker.GetDeviceSnapshot(Device).Single(value => value.Id == record.Id);
            Assert.AreEqual(status, completed.Status);
            Assert.AreEqual(kind, completed.ErrorKind);
            Assert.IsFalse(tracker.CanRetry(record.Id));
            Assert.Throws<InvalidOperationException>(() => tracker.RetryFrom(record.Id, new(99)));
        }
    }

    [TestMethod]
    public void NetworkAndTimeoutOperationFailuresCanRetryAsNewLinkedRecords()
    {
        foreach (var kind in new[] { ShareErrorKind.NetworkLost, ShareErrorKind.Timeout })
        {
            var tracker = Create();
            var first = Begin(tracker);
            Assert.IsTrue(tracker.Complete(first.Id, Failure(kind)));
            Assert.IsTrue(tracker.CanRetry(first.Id));
            var retry = tracker.RetryFrom(first.Id, new(2));
            Assert.AreNotEqual(first.Id, retry.Id);
            Assert.AreEqual(first.Id, retry.RetryOf);
            Assert.AreEqual(ActivityStatus.AwaitingPeer, retry.Status);
            Assert.IsNull(retry.TransferId);
            Assert.AreEqual(0UL, retry.TransferredBytes);
            Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == first.Id).Status);
            Assert.AreEqual(1, tracker.ActiveCount);
        }
    }

    [TestMethod]
    public void LocalOperationAndTransferIndexesCannotCrossOrStealRecords()
    {
        var tracker = Create();
        var first = Begin(tracker, 1);
        var second = Begin(tracker, 2);
        tracker.BindTransfer(new OperationId(2), Transfer);
        Assert.Throws<InvalidOperationException>(() => tracker.BindTransfer(first.Id, Transfer));
        Assert.Throws<InvalidOperationException>(() => Begin(tracker, 2));
        tracker.Complete(first.Id, Failure(ShareErrorKind.Timeout));
        tracker.Complete(new(Transfer, null));
        Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == first.Id).Status);
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == second.Id).Status);
        Assert.IsFalse(tracker.Complete(new(new TransferId(Guid.NewGuid()), null)));
        Assert.Throws<ArgumentException>(() => tracker.Complete(new(null, null)));
    }

    [TestMethod]
    public void CompletionBeforeRegistrationBindsTerminalIdAndDoesNotStealNextSend()
    {
        var tracker = Create();
        var first = Begin(tracker, 1);
        tracker.Complete(first.Id, new(Transfer, null));
        var next = Begin(tracker, 2);
        Assert.IsFalse(tracker.BindTransfer(new OperationId(1), Transfer));
        Assert.IsFalse(tracker.ApplyProgress(new(Transfer, 10, 100)));
        Assert.IsNull(tracker.GetDeviceSnapshot(Device).Single(value => value.Id == next.Id).TransferId);
        Assert.Throws<InvalidOperationException>(() => tracker.BindTransfer(next.Id, Transfer));
    }

    [TestMethod]
    public void DuplicateOffersAndSdkEventsResolveTheSameActivity()
    {
        var tracker = Create();
        var incoming = tracker.AddIncomingOffer(Offer());
        Assert.AreEqual(incoming.Id, tracker.AddIncomingOffer(Offer()).Id);
        tracker.Apply(new ShareSdkEvent.ProgressReceived(new(Transfer, 10, 100)));
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(Transfer, null)));
        Assert.AreEqual(1, tracker.GetDeviceSnapshot(Device).Count);
        Assert.AreEqual(ActivityStatus.Transferring, tracker.GetDeviceSnapshot(Device).Single().Status);
        tracker.Complete(incoming.Id, new(Transfer, null), "C:\\Downloads\\report.txt");
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
        var outgoing = Begin(tracker, 2);
        var another = new TransferId(Guid.NewGuid());
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(2), another));
        Assert.AreEqual(another, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == outgoing.Id).TransferId);
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(another, null)));
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == outgoing.Id).Status);
    }

    [TestMethod]
    public void RegistrationBeforeAdmissionIsRetainedAndReconciled()
    {
        var tracker = Create();
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(1), Transfer));
        tracker.Apply(new ShareSdkEvent.ProgressReceived(new(Transfer, 45, 100)));
        tracker.Apply(new ShareSdkEvent.ProgressReceived(new(Transfer, 15, 100)));
        var admitted = Begin(tracker);
        Assert.AreEqual(Transfer, admitted.TransferId);
        Assert.AreEqual(ActivityStatus.Transferring, admitted.Status);
        Assert.AreEqual(45UL, admitted.TransferredBytes);
        tracker.Apply(new ShareSdkEvent.ProgressReceived(new(Transfer, 65, 100)));
        Assert.AreEqual(65UL, tracker.GetDeviceSnapshot(Device).Single().TransferredBytes);
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(Transfer, null)));
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.AreEqual(0, tracker.ActiveCount);
    }

    [TestMethod]
    public void StopClearsEarlyRegistrationsAndPermitsFreshOperationReuse()
    {
        var tracker = Create();
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(2), Transfer));
        var old = Begin(tracker, 1);
        tracker.Complete(old.Id, Failure(ShareErrorKind.Timeout));
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(1), new(Guid.NewGuid())));
        tracker.Apply(new ShareSdkEvent.DiscoveryStateChanged(DiscoveryState.Stopped));
        var reused = Begin(tracker, 1);
        var withoutOldRegistration = Begin(tracker, 2);
        Assert.IsNull(reused.TransferId);
        Assert.IsNull(withoutOldRegistration.TransferId);
        var fresh = new TransferId(Guid.NewGuid());
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(1), fresh));
        Assert.AreEqual(fresh, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == reused.Id).TransferId);
        Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single(value => value.Id == old.Id).Status);
    }

    [TestMethod]
    public void TerminalBeforeAdmissionClearsEarlyRegistration()
    {
        var tracker = Create();
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(1), Transfer));
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(Transfer, null)));
        Assert.IsNull(Begin(tracker).TransferId);
        var fresh = new TransferId(Guid.NewGuid());
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(1), fresh));
        Assert.AreEqual(fresh, tracker.GetDeviceSnapshot(Device).Single().TransferId);
    }

    [TestMethod]
    public void DrainedAdmissionBlockClearsUnadmittedAndRetiredOperationBindings()
    {
        var tracker = Create();
        var old = Begin(tracker, 1);
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(2), Transfer));
        tracker.BlockNewTransfers();
        tracker.Complete(old.Id, Failure(ShareErrorKind.Cancelled));
        tracker.Apply(new ShareSdkEvent.OutgoingTransferRegistered(new(1), Transfer));
        tracker.AllowNewTransfers();
        Assert.IsNull(Begin(tracker, 1).TransferId);
        Assert.IsNull(Begin(tracker, 2).TransferId);
    }

    [TestMethod]
    public async Task IncomingNativeSuccessStaysActiveUntilOwnerPublishesFinalFile()
    {
        var repository = new Repository();
        var tracker = Create(repository);
        var record = tracker.AddIncomingOffer(Offer());
        tracker.ApplyProgress(new(Transfer, 100, 100));
        var idle = tracker.WaitForIdleAsync(default);
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(Transfer, null)));
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(Transfer, null)));
        Assert.IsFalse(idle.IsCompleted);
        Assert.AreEqual(1, tracker.ActiveCount);
        Assert.AreEqual(ActivityStatus.Transferring, tracker.GetDeviceSnapshot(Device).Single().Status);
        await tracker.SavePendingAsync();
        Assert.AreEqual(0, repository.Writes.Count(value => ActivityTransition.IsTerminal(value.Status)));
        Assert.IsTrue(tracker.Complete(record.Id, new(Transfer, null), "C:\\Downloads\\report.txt"));
        await idle.WaitAsync(TimeSpan.FromSeconds(5));
        await tracker.SavePendingAsync();
        Assert.AreEqual("C:\\Downloads\\report.txt", repository.Records[record.Id].ReceivedFilePath);
        Assert.AreEqual(1, repository.Writes.Count(value => ActivityTransition.IsTerminal(value.Status)));
    }

    [TestMethod]
    public async Task IncomingRenameFailureAfterNativeSuccessIsTerminalStorageFailure()
    {
        var repository = new Repository();
        var tracker = Create(repository);
        var record = tracker.AddIncomingOffer(Offer());
        tracker.Apply(new ShareSdkEvent.TransferCompleted(new(Transfer, null)));
        Assert.IsTrue(tracker.Complete(record.Id, Failure(ShareErrorKind.StorageFailed, Transfer)));
        await tracker.SavePendingAsync();
        Assert.AreEqual(ActivityStatus.Failed, repository.Records[record.Id].Status);
        Assert.AreEqual(ShareErrorKind.StorageFailed, repository.Records[record.Id].ErrorKind);
        Assert.IsNull(repository.Records[record.Id].ReceivedFilePath);
        Assert.IsFalse(tracker.HasActiveTransfers);
    }

    [TestMethod]
    public void IncomingNativeFailuresRejectionAndCancellationStillTerminate()
    {
        foreach (var (kind, status) in new[] {
            (ShareErrorKind.NetworkLost, ActivityStatus.Failed),
            (ShareErrorKind.TransferRejected, ActivityStatus.Rejected),
            (ShareErrorKind.Cancelled, ActivityStatus.Cancelled) })
        {
            var tracker = Create();
            tracker.AddIncomingOffer(Offer());
            tracker.Apply(new ShareSdkEvent.TransferCompleted(Failure(kind, Transfer)));
            Assert.AreEqual(status, tracker.GetDeviceSnapshot(Device).Single().Status);
            Assert.AreEqual(0, tracker.ActiveCount);
        }
    }

    [TestMethod]
    public async Task ReplayWhileHistoryLoadIsPendingUsesOneCanonicalTerminalActivity()
    {
        var repository = new Repository();
        var original = Create(repository);
        var canonical = original.AddIncomingOffer(Offer());
        original.Complete(canonical.Id, new(Transfer, null), "C:\\Downloads\\report.txt");
        await original.SavePendingAsync();
        repository.Loading = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var tracker = Create(repository);
        var loading = tracker.LoadDeviceAsync(Device);
        var replay = tracker.AddIncomingOffer(Offer());
        Assert.IsFalse(tracker.GetDeviceSnapshot(Device).Any(value => value.Id == replay.Id));
        var saving = tracker.SavePendingAsync();
        Assert.IsFalse(saving.IsCompleted);
        Assert.IsFalse(repository.Writes.Any(value => value.Id == replay.Id));
        var idle = tracker.WaitForIdleAsync(default);
        repository.Loading.SetResult(new[] { repository.Records[canonical.Id] });
        await loading;
        await saving.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(1, tracker.GetDeviceSnapshot(Device).Count);
        Assert.AreEqual(canonical.Id, tracker.GetDeviceSnapshot(Device).Single().Id);
        Assert.IsFalse(tracker.GetDeviceSnapshot(Device).Any(value => value.Id == replay.Id));
        Assert.AreEqual(0, tracker.ActiveCount);
        await idle.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(tracker.Complete(replay.Id, Failure(ShareErrorKind.StorageFailed, Transfer)));
        await tracker.SavePendingAsync();
        Assert.IsFalse(repository.Writes.Any(value => value.Id == replay.Id));
        Assert.AreEqual(1, repository.Records.Count);
        Assert.AreEqual(canonical.Id, tracker.AddIncomingOffer(Offer()).Id);
        Assert.AreEqual("C:\\Downloads\\report.txt", tracker.GetDeviceSnapshot(Device).Single().ReceivedFilePath);
    }

    [TestMethod]
    public async Task ReplayOwnerCanFinalizeCanonicalPendingActivityUsingProvisionalId()
    {
        var repository = new Repository();
        var original = Create(repository);
        var canonical = original.AddIncomingOffer(Offer());
        original.ApplyProgress(new(Transfer, 90, 100));
        await original.SavePendingAsync();
        repository.Loading = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var tracker = Create(repository);
        var loading = tracker.LoadDeviceAsync(Device);
        var replay = tracker.AddIncomingOffer(Offer());
        tracker.ApplyProgress(new(Transfer, 80, 100));
        repository.Loading.SetResult(new[] { repository.Records[canonical.Id] });
        await loading;
        Assert.AreEqual(canonical.Id, tracker.GetDeviceSnapshot(Device).Single().Id);
        Assert.AreEqual(90UL, tracker.GetDeviceSnapshot(Device).Single().TransferredBytes);
        Assert.AreEqual(1, tracker.ActiveCount);
        var idle = tracker.WaitForIdleAsync(default);
        Assert.IsTrue(tracker.Complete(replay.Id, new(Transfer, null), "C:\\Downloads\\report.txt"));
        await idle.WaitAsync(TimeSpan.FromSeconds(5));
        await tracker.SavePendingAsync();
        Assert.AreEqual(ActivityStatus.Completed, repository.Records[canonical.Id].Status);
        Assert.IsFalse(repository.Writes.Any(value => value.Id == replay.Id));
        Assert.AreEqual(1, repository.Records.Count);
        Assert.AreEqual(0, tracker.ActiveCount);
    }

    [TestMethod]
    public async Task OfferBeforeExplicitLoadCannotPersistASecondDurableIdentity()
    {
        var repository = new Repository();
        var original = Create(repository);
        var canonical = original.AddIncomingOffer(Offer());
        original.Complete(canonical.Id, new(Transfer, null), "C:\\Downloads\\report.txt");
        await original.SavePendingAsync();
        var tracker = Create(repository);
        var offered = tracker.AddIncomingOffer(Offer());
        await tracker.SavePendingAsync();
        Assert.AreEqual(1, repository.Records.Count);
        await tracker.LoadDeviceAsync(Device);
        var snapshot = tracker.GetDeviceSnapshot(Device);
        Assert.AreEqual(1, snapshot.Count);
        Assert.AreEqual(canonical.Id, snapshot.Single().Id);
        Assert.AreEqual(ActivityStatus.Completed, snapshot.Single().Status);
        Assert.AreEqual("C:\\Downloads\\report.txt", snapshot.Single().ReceivedFilePath);
        Assert.AreEqual(0, tracker.ActiveCount);
        Assert.IsFalse(tracker.HasActiveTransfers);
        await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(tracker.Complete(offered.Id, Failure(ShareErrorKind.StorageFailed, Transfer)));
        Assert.AreEqual(canonical.Id, tracker.AddIncomingOffer(Offer()).Id);
        Assert.AreEqual(1, repository.Records.Count);
    }

    [TestMethod]
    public async Task OfferStartsHistoryReadinessBeforePersistenceAndExplicitLoadJoinsIt()
    {
        var repository = new Repository();
        var original = Create(repository);
        var canonical = original.AddIncomingOffer(Offer());
        original.Complete(canonical.Id, new(Transfer, null), "C:\\Downloads\\report.txt");
        await original.SavePendingAsync();
        var before = repository.LoadCalls;
        repository.Loading = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var tracker = Create(repository);
        var provisional = tracker.AddIncomingOffer(Offer());
        Assert.AreEqual(before + 1, repository.LoadCalls);
        Assert.AreEqual(0, tracker.GetDeviceSnapshot(Device).Count);
        var idle = tracker.WaitForIdleAsync(default);
        var saving = tracker.SavePendingAsync();
        Assert.IsFalse(saving.IsCompleted);
        Assert.IsFalse(repository.Writes.Any(value => value.Id == provisional.Id));
        var loading = tracker.LoadDeviceAsync(Device);
        Assert.AreEqual(before + 1, repository.LoadCalls);
        repository.Loading.SetResult(new[] { repository.Records[canonical.Id] });
        await Task.WhenAll(saving, loading, idle).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(canonical.Id, tracker.GetDeviceSnapshot(Device).Single().Id);
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.AreEqual(0, tracker.ActiveCount);
        Assert.IsFalse(tracker.Complete(provisional.Id, new(Transfer, null), "C:\\Downloads\\duplicate.txt"));
        Assert.IsFalse(repository.Writes.Any(value => value.Id == provisional.Id));
        Assert.AreEqual(1, repository.Records.Count);
    }

    [TestMethod]
    public async Task FailedHistoryReadCannotPersistProvisionalIdentityAndCanRetrySafely()
    {
        var repository = new Repository();
        var original = Create(repository);
        var canonical = original.AddIncomingOffer(Offer());
        original.Complete(canonical.Id, new(Transfer, null));
        await original.SavePendingAsync();
        repository.Loading = new(TaskCreationOptions.RunContinuationsAsynchronously);
        repository.Loading.SetException(new IOException("history unavailable"));
        var tracker = Create(repository);
        var provisional = tracker.AddIncomingOffer(Offer());
        await Assert.ThrowsAsync<IOException>(() => tracker.SavePendingAsync());
        Assert.AreEqual(0, tracker.GetDeviceSnapshot(Device).Count);
        Assert.IsFalse(repository.Writes.Any(value => value.Id == provisional.Id));
        Assert.AreEqual(1, repository.Records.Count);
        repository.Loading = null;
        await tracker.LoadDeviceAsync(Device);
        await tracker.SavePendingAsync();
        Assert.AreEqual(canonical.Id, tracker.GetDeviceSnapshot(Device).Single().Id);
        Assert.IsFalse(tracker.Complete(provisional.Id, Failure(ShareErrorKind.StorageFailed, Transfer)));
        Assert.AreEqual(0, tracker.ActiveCount);
        Assert.AreEqual(1, repository.Records.Count);
    }

    [TestMethod]
    public async Task PayloadAndFailureMessageNeverReachHistoryAndReloadCannotReconstructThem()
    {
        var repository = new Repository();
        var tracker = Create(repository);
        var text = new ShareContent(ContentKind.Text, "SECRET clipboard text", "text/plain", []);
        var first = tracker.BeginOutgoing(Device, new(1), ContentKind.Text, text.Text!, "text/plain", 22, ShareOrigin.Clipboard, text);
        var image = new ShareContent(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(11, 22, 33, 44));
        var second = Begin(tracker, 2, image);
        Assert.AreSame(text, tracker.GetPreview(first.Id));
        Assert.AreSame(image, tracker.GetPreview(second.Id));
        tracker.Complete(first.Id, Failure(ShareErrorKind.Timeout), "C:\\private\\text-payload.txt");
        tracker.Complete(second.Id, new(null, null), "C:\\private\\image-payload.png");
        await tracker.SavePendingAsync();
        var persisted = JsonSerializer.Serialize(repository.Writes);
        Assert.IsFalse(persisted.Contains("SECRET", StringComparison.Ordinal));
        Assert.IsFalse(persisted.Contains("private", StringComparison.Ordinal));
        Assert.IsFalse(persisted.Contains("ImageBytes", StringComparison.Ordinal));
        var reload = Create(repository);
        await reload.LoadDeviceAsync(Device);
        Assert.AreEqual(2, reload.GetDeviceSnapshot(Device).Count);
        Assert.IsNull(reload.GetPreview(first.Id));
        Assert.IsNull(reload.GetPreview(second.Id));
        Assert.AreEqual(0, reload.ActiveCount);
    }

    [TestMethod]
    public void FileMetadataStoresLeafNameRatherThanSourcePath()
    {
        var tracker = Create();
        var record = tracker.BeginOutgoing(Device, new(1), ContentKind.File, "C:\\private\\report.txt", "text/plain", 100);
        Assert.AreEqual("report.txt", record.DisplayName);
    }

    [TestMethod]
    public async Task DrainWaitersCompleteOnlyOnLastTerminalAndOnlyOncePerGeneration()
    {
        var tracker = Create();
        var first = Begin(tracker, 1);
        var second = tracker.AddIncomingOffer(Offer());
        var idle = tracker.WaitForIdleAsync(default);
        var alsoIdle = tracker.WaitForIdleAsync(default);
        tracker.ApplyProgress(new(Transfer, 100, 100));
        tracker.Complete(first.Id, Failure(ShareErrorKind.Cancelled));
        Assert.IsFalse(idle.IsCompleted);
        Assert.IsTrue(tracker.HasActiveTransfers);
        tracker.Complete(second.Id, new(Transfer, null));
        await Task.WhenAll(idle, alsoIdle).WaitAsync(TimeSpan.FromSeconds(5));
        var third = Begin(tracker, 3);
        var nextIdle = tracker.WaitForIdleAsync(default);
        Assert.IsFalse(nextIdle.IsCompleted);
        tracker.Complete(second.Id, Failure(ShareErrorKind.Timeout, Transfer));
        Assert.IsFalse(nextIdle.IsCompleted);
        tracker.Complete(third.Id, Failure(ShareErrorKind.Timeout));
        await nextIdle.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(TaskStatus.RanToCompletion, idle.Status);
        Assert.AreEqual(0, tracker.ActiveCount);
    }

    [TestMethod]
    public async Task CancellingOneWaiterDoesNotCancelTheSharedDrain()
    {
        var tracker = Create();
        var record = Begin(tracker);
        using var cancellation = new CancellationTokenSource();
        var cancelled = tracker.WaitForIdleAsync(cancellation.Token);
        var remaining = tracker.WaitForIdleAsync(default);
        cancellation.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => cancelled);
        Assert.IsFalse(remaining.IsCompleted);
        tracker.Complete(record.Id, Failure(ShareErrorKind.Cancelled));
        await remaining.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [TestMethod]
    public void AdmissionBlockAppliesToOutgoingIncomingAndRetry()
    {
        var tracker = Create();
        var failed = Begin(tracker);
        tracker.Complete(failed.Id, Failure(ShareErrorKind.Timeout));
        tracker.BlockNewTransfers();
        Assert.Throws<InvalidOperationException>(() => Begin(tracker, 2));
        Assert.Throws<InvalidOperationException>(() => tracker.AddIncomingOffer(Offer()));
        Assert.Throws<InvalidOperationException>(() => tracker.RetryFrom(failed.Id, new(3)));
        tracker.AllowNewTransfers();
        var retry = tracker.RetryFrom(failed.Id, new(3));
        Assert.AreEqual(new OperationId(3), retry.OperationId);
        Assert.AreEqual(1, tracker.ActiveCount);
    }

    [TestMethod]
    public async Task ConcurrentProgressAndDuplicateTerminalsLeaveOneImmutableResult()
    {
        var repository = new Repository();
        var tracker = Create(repository);
        var record = Begin(tracker);
        tracker.BindTransfer(record.Id, Transfer);
        await Task.WhenAll(Enumerable.Range(0, 100).Select(value => Task.Run(() => tracker.ApplyProgress(new(Transfer, (ulong)value, 100)))));
        Assert.AreEqual(99UL, tracker.GetDeviceSnapshot(Device).Single().TransferredBytes);
        var idle = tracker.WaitForIdleAsync(default);
        await Task.WhenAll(Enumerable.Range(0, 100).Select(_ => Task.Run(() => tracker.Complete(new(Transfer, null)))));
        await idle.WaitAsync(TimeSpan.FromSeconds(5));
        await tracker.SavePendingAsync();
        Assert.AreEqual(1, repository.Writes.Count(value => value.Status == ActivityStatus.Completed));
        Assert.AreEqual(0, tracker.ActiveCount);
        Assert.AreEqual(ActivityStatus.Completed, repository.Records[record.Id].Status);
    }

    [TestMethod]
    public async Task SlowPersistenceIsOrderedButDoesNotHoldDrainOrSnapshotLock()
    {
        var repository = new Repository { Release = new(TaskCreationOptions.RunContinuationsAsynchronously) };
        var tracker = Create(repository);
        var record = Begin(tracker);
        await repository.Entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        tracker.BindTransfer(record.Id, Transfer);
        tracker.ApplyProgress(new(Transfer, 60, 100));
        var idle = tracker.WaitForIdleAsync(default);
        tracker.Complete(new(Transfer, null));
        await idle.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
        var save = tracker.SavePendingAsync();
        Assert.IsFalse(save.IsCompleted);
        repository.Release.SetResult();
        await save.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(ActivityStatus.Completed, repository.Records[record.Id].Status);
        Assert.AreEqual(1, repository.MaxConcurrentWrites);
    }

    [TestMethod]
    public async Task PersistenceFailureIsObservableAndLaterTerminalIsStillWritten()
    {
        var repository = new Repository { FailFirstWrite = true };
        var tracker = Create(repository);
        var record = Begin(tracker);
        tracker.Complete(record.Id, Failure(ShareErrorKind.Timeout));
        await Assert.ThrowsAsync<IOException>(() => tracker.SavePendingAsync());
        Assert.AreEqual(ActivityStatus.Failed, repository.Records[record.Id].Status);
        Assert.IsFalse(tracker.HasActiveTransfers);
    }

    [TestMethod]
    public async Task ReloadCannotOverwriteLiveStateOrCountHistoricalPendingAsActive()
    {
        var repository = new Repository();
        var tracker = Create(repository);
        var current = Begin(tracker);
        await tracker.SavePendingAsync();
        repository.Loading = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var load = tracker.LoadDeviceAsync(Device);
        tracker.Complete(current.Id, Failure(ShareErrorKind.Timeout));
        repository.Loading.SetResult(new[] { current });
        await load;
        Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status);
        var historical = Create(repository);
        await historical.LoadDeviceAsync(Device);
        Assert.IsFalse(historical.HasActiveTransfers);
    }

    [TestMethod]
    public async Task ReloadedTransferRetainsIdentityWhenAnOfferIsRepeated()
    {
        var repository = new Repository();
        var original = Create(repository);
        var received = original.AddIncomingOffer(Offer());
        original.Complete(new(Transfer, null));
        await original.SavePendingAsync();
        var reloaded = Create(repository);
        await reloaded.LoadDeviceAsync(Device);
        Assert.AreEqual(received.Id, reloaded.AddIncomingOffer(Offer()).Id);
        Assert.AreEqual(1, reloaded.GetDeviceSnapshot(Device).Count);
        Assert.IsFalse(reloaded.HasActiveTransfers);
        Assert.IsFalse(reloaded.Complete(Failure(ShareErrorKind.NetworkLost, Transfer)));
    }

    [TestMethod]
    public void ObservableSnapshotsAreDeviceScopedAndDoNotMutateOlderSnapshots()
    {
        var tracker = Create();
        var changes = 0;
        tracker.Changed += (_, _) => changes++;
        var record = Begin(tracker);
        var snapshot = tracker.GetDeviceSnapshot(Device);
        tracker.BeginOutgoing(new(Guid.NewGuid()), new(2), ContentKind.File, "other.txt", "text/plain", 20);
        tracker.Complete(record.Id, Failure(ShareErrorKind.Timeout));
        Assert.AreEqual(1, snapshot.Count);
        Assert.AreEqual(ActivityStatus.AwaitingPeer, snapshot.Single().Status);
        Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.AreEqual(3, changes);
    }

    private sealed class Clock : IClock { public DateTimeOffset UtcNow => Now; }

    // The real repository boundary is external storage; this in-memory double
    // preserves upsert/load semantics and exposes write ordering and payloads.
    private sealed class Repository : IActivityRepository
    {
        public Dictionary<ActivityId, ActivityRecord> Records { get; } = [];
        public List<ActivityRecord> Writes { get; } = [];
        public TaskCompletionSource Entered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource? Release { get; set; }
        public TaskCompletionSource<IReadOnlyList<ActivityRecord>>? Loading { get; set; }
        public bool FailFirstWrite { get; set; }
        public int LoadCalls { get; private set; }
        public int MaxConcurrentWrites { get; private set; }
        private int concurrentWrites;
        private int writeAttempts;
        public async Task UpsertAsync(ActivityRecord record, CancellationToken cancellationToken)
        {
            var concurrent = Interlocked.Increment(ref concurrentWrites);
            MaxConcurrentWrites = Math.Max(MaxConcurrentWrites, concurrent);
            try
            {
                Entered.TrySetResult();
                if (Release is { } release) await release.Task;
                if (Interlocked.Increment(ref writeAttempts) == 1 && FailFirstWrite) throw new IOException("storage unavailable");
                Writes.Add(record);
                Records[record.Id] = record;
            }
            finally { Interlocked.Decrement(ref concurrentWrites); }
        }
        public Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken)
        {
            LoadCalls++;
            return Loading?.Task ?? Task.FromResult<IReadOnlyList<ActivityRecord>>(Records.Values.Where(value => value.DeviceId == device).ToArray());
        }
        public Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<ActivityRecord>>(Records.Values.ToArray());
        public Task PruneAsync(DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, CancellationToken cancellationToken) => Task.CompletedTask;
    }
}
