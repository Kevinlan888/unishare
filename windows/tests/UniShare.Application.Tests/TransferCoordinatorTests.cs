using System.Collections.Immutable;
using System.Text;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class TransferCoordinatorTests
{
    [TestMethod]
    public async Task TransferBoundariesUseLocalSequenceAndNeverCarrySensitiveValues()
    {
        var entries = new List<TransferDiagnosticRecord>();
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files();
        var coordinator = new TransferCoordinator(sdk, tracker, files,
            () => new AppSettings { ReceiveDirectory = "private-directory" }, entries.Add);
        await Assert.ThrowsAsync<IOException>(async () => await coordinator.SendFileAsync(Device,
            "C:\\private\\missing-secret.txt", ContentKind.File, ShareOrigin.Direct, default));
        Assert.IsTrue(entries.Any(value => value is { Stage: TransferDiagnosticStage.SourceOpen,
            Outcome: NativeDiagnosticOutcome.Failed, Category: ShareErrorKind.StorageFailed }));
        entries.Clear();
        var id = await coordinator.SendTextAsync(Device, "private body", ShareOrigin.Direct, default);
        sdk.Finish(new(null, null));
        await WaitUntil(() => entries.Any(value => value is { Stage: TransferDiagnosticStage.Completion,
            Outcome: NativeDiagnosticOutcome.Succeeded }));
        Assert.IsTrue(entries.Any(value => value is { Stage: TransferDiagnosticStage.CommandSubmit,
            Outcome: NativeDiagnosticOutcome.Succeeded }));
        Assert.AreEqual(1, entries.Select(value => value.Sequence).Distinct().Count());
        Assert.IsFalse(string.Join(" ", entries).Contains("private", StringComparison.OrdinalIgnoreCase));
        entries.Clear();
        files.CanPrepareReceive = _ => false;
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File)));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 2);
        await Assert.ThrowsAsync<IOException>(async () => await coordinator.AcceptAsync(
            tracker.GetDeviceSnapshot(Device).Single(value => value.Id != id).Id, default));
        Assert.IsTrue(entries.Any(value => value is { Stage: TransferDiagnosticStage.DestinationPrepare,
            Outcome: NativeDiagnosticOutcome.Failed, Category: ShareErrorKind.StorageFailed }));
        Assert.IsFalse(string.Join(" ", entries).Contains("private-directory", StringComparison.Ordinal));
    }

    [TestMethod]
    public async Task FailedSubmitAndAcceptHaveTerminalBoundaryEvents()
    {
        var entries = new List<TransferDiagnosticRecord>();
        var sdk = new Sdk { ThrowSend = true }; var tracker = Tracker();
        var coordinator = new TransferCoordinator(sdk, tracker, new Files(), () => new AppSettings(), entries.Add);
        await Assert.ThrowsAsync<IOException>(async () => await coordinator.SendTextAsync(Device, "secret body", ShareOrigin.Direct, default));
        Assert.IsTrue(entries.Any(value => value is { Stage: TransferDiagnosticStage.CommandSubmit,
            Outcome: NativeDiagnosticOutcome.Failed }));
        entries.Clear(); sdk.ThrowSend = false;
        var id = await coordinator.SendTextAsync(Device, "secret body", ShareOrigin.Direct, default);
        sdk.Finish(new(null, new(ShareErrorKind.NetworkLost, "secret message")));
        await WaitUntil(() => entries.Any(value => value is { Stage: TransferDiagnosticStage.Completion,
            Outcome: NativeDiagnosticOutcome.Failed, Category: ShareErrorKind.NetworkLost }));
        entries.Clear();
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File)));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 2);
        await coordinator.AcceptAsync(tracker.GetDeviceSnapshot(Device).Single(value => value.Id != id).Id, default);
        Assert.IsTrue(entries.Any(value => value is { Stage: TransferDiagnosticStage.AcceptSubmit,
            Outcome: NativeDiagnosticOutcome.Succeeded }));
        Assert.IsTrue(entries.Any(value => value is { Stage: TransferDiagnosticStage.Completion,
            Outcome: NativeDiagnosticOutcome.Succeeded }));
        Assert.IsFalse(string.Join(" ", entries).Contains("secret", StringComparison.Ordinal));
    }
    private static readonly DeviceId Device = new(Guid.NewGuid());
    private static TransferCoordinator Coordinator(Sdk sdk, ActivityTracker tracker, Files files)
    {
        return new(sdk, tracker, files, () => new AppSettings { ReceiveDirectory = "configured-receive" });
    }

    [TestMethod]
    public async Task EmptyTextAndUnsupportedClipboardCreateNoActivity()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files();
        var coordinator = Coordinator(sdk, tracker, files);
        foreach (var text in new[] { "", "   " })
            await Assert.ThrowsAsync<ArgumentException>(async () => await coordinator.SendTextAsync(Device, text, ShareOrigin.Direct, CancellationToken.None));
        foreach (var content in new[] { new ShareContent(ContentKind.Text, "", "text/plain", []), new ShareContent(ContentKind.File, null, "", []), new ShareContent(ContentKind.Image, null, "image/png", []) })
        {
            sdk.Clipboard = content;
            await Assert.ThrowsAsync<ArgumentException>(async () => await coordinator.SendClipboardAsync(Device, CancellationToken.None));
        }
        Assert.AreEqual(0, tracker.GetDeviceSnapshot(Device).Count);
        Assert.AreEqual(0, sdk.Sends.Count);
    }

    [TestMethod]
    public async Task TextAndClipboardTextUseOrdinaryTextRouteAndKeepBodyProcessLocal()
    {
        var repo = new Repository(); var tracker = Tracker(repo); var sdk = new Sdk();
        var coordinator = Coordinator(sdk, tracker, new Files());
        sdk.Clipboard = new(ContentKind.Text, "private body", "text/plain", []);
        ActivityId id = await coordinator.SendClipboardAsync(Device, CancellationToken.None);
        Assert.AreEqual("text:private body", sdk.Sends.Single());
        Assert.AreEqual("private body", tracker.GetPreview(id)!.Text);
        sdk.Finish(new(null, null)); await tracker.WaitForIdleAsync(default); await tracker.SavePendingAsync();
        var record = repo.Records[id];
        Assert.AreEqual(ShareOrigin.Clipboard, record.Origin);
        Assert.AreEqual("Text", record.DisplayName);
        Assert.IsNull(record.ReceivedFilePath);
        Assert.IsFalse(record.ToString().Contains("private body"));
    }

    [TestMethod]
    public async Task SelectedImageAndFileUseMatchingRouteWithoutPersistingSourcePath()
    {
        var path = Path.GetTempFileName();
        try
        {
            await File.WriteAllBytesAsync(path, [1, 2, 3]);
            foreach (var kind in new[] { ContentKind.Image, ContentKind.File })
            {
                var sdk = new Sdk(); var tracker = Tracker(); var coordinator = Coordinator(sdk, tracker, new Files());
                ActivityId id = await coordinator.SendFileAsync(Device, path, kind, ShareOrigin.Direct, CancellationToken.None);
                Assert.AreEqual(kind + ":" + path, sdk.Sends.Single());
                sdk.Finish(new(null, null)); await tracker.WaitForIdleAsync(default);
                Assert.IsFalse(tracker.GetDeviceSnapshot(Device).Single().ToString().Contains(path));
                Assert.IsTrue(File.Exists(path));
            }
        }
        finally { File.Delete(path); }
    }

    [TestMethod]
    public async Task ClipboardImageStagesOrdinaryImageAndDeletesForEveryTerminalOutcome()
    {
        foreach (var error in new ShareErrorKind?[] { null, ShareErrorKind.TransferRejected, ShareErrorKind.Cancelled, ShareErrorKind.NetworkLost })
        {
            var sdk = new Sdk { Clipboard = new(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(1, 2)) };
            var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
            ActivityId id = await coordinator.SendClipboardAsync(Device, CancellationToken.None);
            Assert.AreEqual("Image:" + files.Source!.Path, sdk.Sends.Single());
            sdk.Finish(new(null, error is { } kind ? new(kind, "private failure path") : null));
            await tracker.WaitForIdleAsync(default);
            await WaitUntil(() => files.Source.Deleted);
            Assert.AreEqual(ShareOrigin.Clipboard, tracker.GetDeviceSnapshot(Device).Single().Origin);
        }
    }

    [TestMethod]
    public async Task ClipboardImageSynchronousSubmissionFailureDeletesStage()
    {
        var sdk = new Sdk { Clipboard = new(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(1)), ThrowSend = true };
        var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        await Assert.ThrowsAsync<IOException>(async () => await coordinator.SendClipboardAsync(Device, CancellationToken.None));
        Assert.IsTrue(files.Source!.Deleted);
    }

    [TestMethod]
    public async Task TrustedTextAutoAcceptsDecodesUtf8DeletesAndPublishesBeforeCompletion()
    {
        var repo = new Repository(); var tracker = Tracker(repo); var sdk = new Sdk { Trusted = new HashSet<DeviceId> { Device } };
        var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        sdk.Accept = async path => await File.WriteAllTextAsync(path, "秘密 text", new UTF8Encoding(false));
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text)));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Any(r => r.Status == ActivityStatus.Completed));
        var record = tracker.GetDeviceSnapshot(Device).Single();
        Assert.AreEqual("秘密 text", tracker.GetPreview(record.Id)!.Text);
        Assert.IsTrue(files.Destination!.Deleted);
        await tracker.SavePendingAsync();
        Assert.IsNull(repo.Records[record.Id].ReceivedFilePath);
        Assert.IsFalse(repo.Records[record.Id].ToString().Contains("秘密"));
        Assert.IsFalse(repo.Records[record.Id].ToString().Contains(files.Destination.Path));
    }

    [TestMethod]
    public async Task TrustedImageFileAndEveryUntrustedKindPrompt()
    {
        foreach (var trusted in new[] { false, true })
        foreach (var kind in new[] { ContentKind.Text, ContentKind.Image, ContentKind.File })
        {
            if (trusted && kind == ContentKind.Text) continue;
            var sdk = new Sdk { Trusted = trusted ? new HashSet<DeviceId> { Device } : new HashSet<DeviceId>() };
            var tracker = Tracker(); var coordinator = Coordinator(sdk, tracker, new Files());
            sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(kind)));
            await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            Assert.AreEqual(ActivityStatus.AwaitingDecision, tracker.GetDeviceSnapshot(Device).Single().Status);
            Assert.AreEqual(0, sdk.AcceptCount);
        }
    }

    [TestMethod]
    public async Task IncomingNativeSuccessWaitsForPublicationAndStorageFailureNeverCompletes()
    {
        foreach (var fail in new[] { false, true })
        {
            var sdk = new Sdk(); var tracker = Tracker(); var files = new Files { FailPublish = fail, PublishGate = new(TaskCreationOptions.RunContinuationsAsynchronously) };
            var coordinator = Coordinator(sdk, tracker, files); var offer = Offer(ContentKind.File);
            sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            var record = tracker.GetDeviceSnapshot(Device).Single();
            Task accept = coordinator.AcceptAsync(record.Id, CancellationToken.None);
            await files.PublishEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            sdk.Emit(new ShareSdkEvent.TransferCompleted(new(offer.TransferId, null)));
            Assert.AreNotEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
            files.PublishGate.SetResult(); await accept;
            var finished = tracker.GetDeviceSnapshot(Device).Single();
            Assert.AreEqual(fail ? ActivityStatus.Failed : ActivityStatus.Completed, finished.Status);
            Assert.AreEqual(fail ? null : "published.txt", finished.ReceivedFilePath);
            Assert.IsTrue(files.Receive!.Deleted);
        }
    }

    [TestMethod]
    public async Task AcceptanceFailureAndInvalidUtf8DeleteTextAndLeaveFailedSummary()
    {
        foreach (var malformed in new[] { false, true })
        {
            var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
            sdk.Accept = malformed ? path => File.WriteAllBytesAsync(path, [0xff]) : _ => throw new IOException("private body C:\\secret");
            sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            var id = tracker.GetDeviceSnapshot(Device).Single().Id;
            await coordinator.AcceptAsync(id, CancellationToken.None);
            Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status);
            Assert.IsTrue(files.Destination!.Deleted);
            Assert.IsNull(tracker.GetPreview(id));
        }
    }

    [TestMethod]
    public async Task RejectAndCancelUseOfferIdentityAndLeaveTerminalSummary()
    {
        foreach (var reject in new[] { false, true })
        {
            var sdk = new Sdk(); var tracker = Tracker(); var coordinator = Coordinator(sdk, tracker, new Files()); var offer = Offer(ContentKind.File);
            sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            var id = tracker.GetDeviceSnapshot(Device).Single().Id;
            if (reject) await coordinator.RejectAsync(id, CancellationToken.None); else await coordinator.CancelAsync(id, CancellationToken.None);
            Assert.AreEqual(reject ? ActivityStatus.Rejected : ActivityStatus.Cancelled, tracker.GetDeviceSnapshot(Device).Single().Status);
            Assert.AreEqual(reject ? "reject:" + offer.RequestId : "cancel:" + offer.TransferId, sdk.Commands.Single());
        }
    }

    [TestMethod]
    public async Task RetryPreservesMetadataOriginAndFailsPredictablyAfterReload()
    {
        var repo = new Repository(); var tracker = Tracker(repo); var sdk = new Sdk(); var coordinator = Coordinator(sdk, tracker, new Files());
        ActivityId id = await coordinator.SendTextAsync(Device, "retry body", ShareOrigin.Clipboard, CancellationToken.None);
        sdk.Finish(new(null, new(ShareErrorKind.Timeout, "timeout"))); await tracker.WaitForIdleAsync(default);
        ActivityId retry = await coordinator.RetryAsync(id, CancellationToken.None);
        var record = tracker.GetDeviceSnapshot(Device).Single(r => r.Id == retry);
        Assert.AreEqual(id, record.RetryOf); Assert.AreEqual(ShareOrigin.Clipboard, record.Origin);
        sdk.Finish(new(null, new(ShareErrorKind.Timeout, "timeout"))); await tracker.WaitForIdleAsync(default); await tracker.SavePendingAsync();
        var reloaded = Tracker(repo); await reloaded.LoadDeviceAsync(Device);
        var newCoordinator = Coordinator(new Sdk(), reloaded, new Files());
        await Assert.ThrowsAsync<InvalidOperationException>(async () => await newCoordinator.RetryAsync(id, CancellationToken.None));
        Assert.AreEqual(2, reloaded.GetDeviceSnapshot(Device).Count);
    }

    [TestMethod]
    public async Task CancellingBeforeOutgoingRegistrationDefersNativeCancelAndOwnsStageUntilCompletion()
    {
        var sdk = new Sdk { Clipboard = new(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(1)) };
        var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        using var cancellation = new CancellationTokenSource();
        ActivityId id = await coordinator.SendClipboardAsync(Device, cancellation.Token);
        cancellation.Cancel();
        Assert.IsFalse(files.Source!.Deleted);
        var transfer = new TransferId(Guid.NewGuid());
        sdk.Emit(new ShareSdkEvent.OutgoingTransferRegistered(new(1), transfer));
        await WaitUntil(() => sdk.Commands.Count == 1);
        Assert.AreEqual("cancel:" + transfer, sdk.Commands.Single());
        sdk.Finish(new(transfer, new(ShareErrorKind.Cancelled, "cancelled")));
        await tracker.WaitForIdleAsync(default);
        Assert.IsTrue(files.Source.Deleted);
        Assert.AreEqual(ActivityStatus.Cancelled, tracker.GetDeviceSnapshot(Device).Single().Status);
    }

    [TestMethod]
    public async Task CancelDuringPublicationPreventsFinalExposureAndCleansReceive()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files { PublishGate = new(TaskCreationOptions.RunContinuationsAsynchronously) };
        var coordinator = Coordinator(sdk, tracker, files);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        Task acceptance = coordinator.AcceptAsync(id, CancellationToken.None);
        await files.PublishEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await coordinator.CancelAsync(id, CancellationToken.None);
        files.PublishGate.SetResult(); await acceptance;
        Assert.AreEqual(ActivityStatus.Cancelled, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.IsNull(tracker.GetDeviceSnapshot(Device).Single().ReceivedFilePath);
        Assert.IsTrue(files.Receive!.Deleted);
    }

    [TestMethod]
    public async Task CancelledAcceptedNativeWaitRetainsSinkUntilNativeCompletionAndNeverPublishes()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        sdk.Accept = async _ => { entered.SetResult(); await completion.Task; };
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        using var cancellation = new CancellationTokenSource();
        Task acceptance = coordinator.AcceptAsync(id, cancellation.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5)); cancellation.Cancel();
        await WaitUntil(() => sdk.Commands.Count == 1);
        Assert.IsFalse(files.Destination!.Deleted); Assert.IsFalse(acceptance.IsCompleted);
        completion.SetResult(); await acceptance;
        Assert.IsTrue(files.Destination.Deleted); Assert.IsNull(tracker.GetPreview(id));
        Assert.AreEqual(ActivityStatus.Cancelled, tracker.GetDeviceSnapshot(Device).Single().Status);
    }

    [TestMethod]
    public async Task NativeFailureBeforeAcceptanceTerminatesPromptWithoutCreatingDestination()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        var offer = Offer(ContentKind.File); sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        sdk.Emit(new ShareSdkEvent.TransferCompleted(new(offer.TransferId, new(ShareErrorKind.NetworkLost, "private network path"))));
        Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.IsNull(files.Receive);
    }

    [TestMethod]
    public async Task RepeatedAcceptAndOfferCreateOneReceiveAndOneNativeAcceptance()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files { PublishGate = new(TaskCreationOptions.RunContinuationsAsynchronously) };
        var coordinator = Coordinator(sdk, tracker, files); var offer = Offer(ContentKind.File);
        sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); sdk.Emit(new ShareSdkEvent.OfferReceived(offer));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        Task first = coordinator.AcceptAsync(id, CancellationToken.None); Task second = coordinator.AcceptAsync(id, CancellationToken.None);
        await files.PublishEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreSame(first, second); Assert.AreEqual(1, sdk.AcceptCount);
        files.PublishGate.SetResult(); await first;
        Assert.AreEqual(1, tracker.GetDeviceSnapshot(Device).Count);
    }

    [TestMethod]
    public async Task ClipboardImageRetryRestagesAvailableProcessBytesWithSameMetadata()
    {
        var sdk = new Sdk { Clipboard = new(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(1, 2, 3)) };
        var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        ActivityId id = await coordinator.SendClipboardAsync(Device, CancellationToken.None);
        var previous = files.Source!; sdk.Finish(new(null, new(ShareErrorKind.Timeout, "timeout"))); await tracker.WaitForIdleAsync(default);
        ActivityId retry = await coordinator.RetryAsync(id, CancellationToken.None);
        Assert.IsTrue(previous.Deleted); Assert.AreNotEqual(previous.Path, files.Source!.Path);
        Assert.AreEqual(3UL, files.Source.Source.ByteCount);
        var record = tracker.GetDeviceSnapshot(Device).Single(r => r.Id == retry);
        Assert.AreEqual(ShareOrigin.Clipboard, record.Origin); Assert.AreEqual(id, record.RetryOf);
        sdk.Finish(new(null, null)); await tracker.WaitForIdleAsync(default); Assert.IsTrue(files.Source.Deleted);
    }

    [TestMethod]
    public async Task PauseBetweenSubmissionAndReturnCommitsAdmittedOperationAndDrainsIt()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var coordinator = Coordinator(sdk, tracker, new Files());
        sdk.OnSending = () => tracker.BlockNewTransfers();
        ActivityId id = await coordinator.SendTextAsync(Device, "admitted", ShareOrigin.Direct, CancellationToken.None);
        var idle = tracker.WaitForIdleAsync(default); Assert.IsFalse(idle.IsCompleted);
        Assert.AreEqual(id, tracker.GetDeviceSnapshot(Device).Single().Id);
        sdk.Finish(new(null, null)); await idle.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [TestMethod]
    public async Task BlockedAdmissionDoesNotStageOrSubmitAndZeroOperationRejectionReleasesDrain()
    {
        var sdk = new Sdk { Clipboard = new(ContentKind.Image, null, "image/png", ImmutableArray.Create<byte>(1)) };
        var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        tracker.BlockNewTransfers();
        await Assert.ThrowsAsync<InvalidOperationException>(async () => await coordinator.SendClipboardAsync(Device, CancellationToken.None));
        Assert.IsNull(files.Source); Assert.AreEqual(0, sdk.Sends.Count);
        tracker.AllowNewTransfers(); sdk.ZeroOperation = true;
        var error = await Assert.ThrowsAsync<IOException>(async () => await coordinator.SendClipboardAsync(Device, CancellationToken.None));
        Assert.IsFalse(error.ToString().Contains("private source")); Assert.IsTrue(files.Source!.Deleted);
        await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(0, tracker.GetDeviceSnapshot(Device).Count);
    }

    [TestMethod]
    public async Task RejectInFlightPreventsAcceptanceAndRedactsSdkControlErrors()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        var rejection = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        sdk.Rejecting = () => rejection.Task;
        Task reject = coordinator.RejectAsync(id, CancellationToken.None);
        await Assert.ThrowsAsync<InvalidOperationException>(async () => await coordinator.AcceptAsync(id, CancellationToken.None));
        rejection.SetResult(); await reject; Assert.IsNull(files.Receive);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 2);
        id = tracker.GetDeviceSnapshot(Device).Single(r => r.Status == ActivityStatus.AwaitingDecision).Id;
        sdk.Cancelling = () => throw new IOException("private body C:\\secret\\destination.txt");
        var error = await Assert.ThrowsAsync<IOException>(async () => await coordinator.CancelAsync(id, CancellationToken.None));
        Assert.IsFalse(error.ToString().Contains("private body")); Assert.IsFalse(error.ToString().Contains("C:\\secret"));
    }

    [TestMethod]
    public async Task TextPreviewIsExposedOnlyAfterTransientDeletion()
    {
        var sdk = new Sdk { Trusted = new HashSet<DeviceId> { Device } }; var tracker = Tracker(); var files = new Files();
        var coordinator = Coordinator(sdk, tracker, files);
        sdk.Accept = path => File.WriteAllTextAsync(path, "private body");
        var published = false; var publishedBeforeCleanup = false;
        tracker.Changed += (_, _) =>
        {
            var record = tracker.GetDeviceSnapshot(Device).SingleOrDefault();
            if (record is not null && tracker.GetPreview(record.Id) is not null)
            {
                publishedBeforeCleanup |= !files.Destination!.Deleted;
                published = true;
            }
        };
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text)));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Any(r => IsFinished(r.Status)));
        Assert.IsTrue(published);
        Assert.IsFalse(publishedBeforeCleanup, "Do not expose decoded content before temporary cleanup.");
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
    }

    [TestMethod]
    public async Task IncomingImagePublishesFileAndProcessPreviewWithoutPersistingImageOrPath()
    {
        var repo = new Repository(); var tracker = Tracker(repo); var sdk = new Sdk(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        sdk.Accept = path => File.WriteAllBytesAsync(path, [1, 2, 3]);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Image) with { MimeType = "image/png", ByteCount = 3 }));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id; await coordinator.AcceptAsync(id, default);
        Assert.IsNotNull(files.Receive); Assert.IsNull(files.Destination); Assert.IsTrue(files.PublishEntered.Task.IsCompleted);
        var preview = tracker.GetPreview(id); Assert.IsNotNull(preview);
        CollectionAssert.AreEqual(new byte[] { 1, 2, 3 }, preview.ImageBytes.ToArray());
        Assert.AreEqual(ContentKind.Image, preview.Kind); Assert.AreEqual("image/png", preview.MimeType);
        await tracker.SavePendingAsync(); Assert.AreEqual(ActivityStatus.Completed, repo.Records[id].Status);
        Assert.IsNull(repo.Records[id].ReceivedFilePath); Assert.IsFalse(repo.Records[id].ToString().Contains("published.txt"));
    }

    [TestMethod]
    public async Task IncomingImageReadPublicationAndInvalidMetadataFailuresAbortAndExposeNoPreview()
    {
        foreach (var reason in new[] { "read", "publish", "mime", "size", "length" })
        {
            var sdk = new Sdk(); var tracker = Tracker(); var files = new Files { FailPublish = reason == "publish" }; var coordinator = Coordinator(sdk, tracker, files);
            sdk.Accept = reason == "read" ? path => { File.Delete(path); return Task.CompletedTask; } : path => File.WriteAllBytesAsync(path, [1, 2, 3]);
            var offer = Offer(ContentKind.Image) with { MimeType = reason == "mime" ? "text/plain" : "image/png", ByteCount = reason == "size" ? 101UL * 1024 * 1024 : reason == "length" ? 4UL : 3UL };
            sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            var id = tracker.GetDeviceSnapshot(Device).Single().Id; await coordinator.AcceptAsync(id, default);
            Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status, reason);
            Assert.IsNull(tracker.GetPreview(id)); Assert.IsNull(tracker.GetDeviceSnapshot(Device).Single().ReceivedFilePath);
            Assert.IsTrue(files.Receive is null || files.Receive.Deleted);
        }
    }

    [TestMethod]
    public void BlockedIncomingOfferIsRejectedExactlyOnceWithoutActivityEvenIfRejectionFails()
    {
        foreach (var rejectionFails in new[] { false, true })
        {
            var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
            var existing = tracker.BeginOutgoing(Device, new(99), ContentKind.Text, "Text", "text/plain", 1);
            tracker.BlockNewTransfers();
            if (rejectionFails) sdk.Rejecting = () => throw new IOException("private rejection C:\\secret\\file");
            var offer = Offer(ContentKind.File);
            try
            {
                sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); sdk.Emit(new ShareSdkEvent.OfferReceived(offer));
                Assert.AreEqual(1, sdk.Commands.Count);
                Assert.AreEqual("reject:" + offer.RequestId, sdk.Commands.Single());
                Assert.AreEqual(existing.Id, tracker.GetDeviceSnapshot(Device).Single().Id);
                Assert.IsNull(files.Receive);
            }
            finally { tracker.Complete(existing.Id, new(null, null)); }
        }
    }

    [TestMethod]
    public async Task PreparationFailureLeavesPendingDecisionAndAllowsReject()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files { CanPrepareReceive = _ => false };
        var coordinator = Coordinator(sdk, tracker, files); sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File)));
        await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1); var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        Exception? error = null;
        try { await coordinator.AcceptAsync(id, default); } catch (Exception failure) { error = failure; }
        Assert.AreEqual(ActivityStatus.AwaitingDecision, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.IsInstanceOfType<IOException>(error); Assert.IsFalse(error!.ToString().Contains("private destination"));
        Assert.AreEqual(0, sdk.AcceptCount); Assert.IsNull(files.Receive); Assert.IsTrue(tracker.HasActiveTransfers);
        await coordinator.RejectAsync(id, default);
        Assert.AreEqual(ActivityStatus.Rejected, tracker.GetDeviceSnapshot(Device).Single().Status);
        await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
    }

    [TestMethod]
    public async Task AcceptanceRetryReadsCorrectedLiveSettingsAfterPreparationFailure()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files { CanPrepareReceive = root => root == "repaired-directory" };
        var current = new AppSettings { ReceiveDirectory = "broken-directory" };
        var coordinator = new TransferCoordinator(sdk, tracker, files, () => current);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        await Assert.ThrowsAsync<IOException>(() => coordinator.AcceptAsync(id, default));
        Assert.AreEqual(ActivityStatus.AwaitingDecision, tracker.GetDeviceSnapshot(Device).Single().Status);
        current = current with { ReceiveDirectory = "repaired-directory" };
        await coordinator.AcceptAsync(id, default);
        CollectionAssert.AreEqual(new[] { "broken-directory", "repaired-directory" }, files.ReceiveRoots.ToArray());
        Assert.AreEqual(1, sdk.AcceptCount); Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.IsTrue(files.Receive!.Deleted);
    }

    [TestMethod]
    public async Task ReentrantAcceptSharesOwnershipBeforeProgressNotification()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id; var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        sdk.Accept = _ => release.Task;
        Task? reentrant = null; var entered = false;
        tracker.Changed += (_, _) =>
        {
            if (!entered && tracker.GetDeviceSnapshot(Device).Single().Status == ActivityStatus.Transferring)
            { entered = true; reentrant = coordinator.AcceptAsync(id, default); }
        };
        var first = coordinator.AcceptAsync(id, default);
        try { Assert.AreSame(first, reentrant); Assert.AreEqual(1, sdk.AcceptCount); Assert.AreEqual(1, files.ReceiveRoots.Count); }
        finally { release.SetResult(); await first; if (reentrant is not null) await reentrant; }
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
    }

    [TestMethod]
    public async Task ReentrantRejectCannotConflictWithReservedAcceptance()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id; var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        sdk.Accept = _ => release.Task; Task? rejection = null; var entered = false;
        tracker.Changed += (_, _) =>
        {
            if (!entered && tracker.GetDeviceSnapshot(Device).Single().Status == ActivityStatus.Transferring)
            { entered = true; rejection = coordinator.RejectAsync(id, default); }
        };
        var acceptance = coordinator.AcceptAsync(id, default);
        try
        {
            Assert.IsNotNull(rejection); await Assert.ThrowsAsync<InvalidOperationException>(() => rejection);
            Assert.AreEqual(0, sdk.Commands.Count); Assert.AreEqual(1, sdk.AcceptCount);
        }
        finally { release.SetResult(); await acceptance; }
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
    }

    [TestMethod]
    public async Task ReentrantCancelCannotReleaseDrainThenSubmitAcceptance()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        var offer = Offer(ContentKind.File);
        sdk.Cancelling = () => { sdk.Emit(new ShareSdkEvent.TransferCompleted(new(offer.TransferId, new(ShareErrorKind.Cancelled, "cancelled")))); return Task.CompletedTask; };
        sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id; Task? cancellation = null; ActivityStatus? insideStatus = null; var entered = false;
        tracker.Changed += (_, _) =>
        {
            if (!entered && tracker.GetDeviceSnapshot(Device).Single().Status == ActivityStatus.Transferring)
            {
                entered = true; cancellation = coordinator.CancelAsync(id, default);
                insideStatus = tracker.GetDeviceSnapshot(Device).Single().Status;
            }
        };
        await coordinator.AcceptAsync(id, default); if (cancellation is not null) await cancellation;
        Assert.AreEqual(ActivityStatus.Transferring, insideStatus);
        Assert.AreEqual(0, sdk.AcceptCount); Assert.AreEqual(ActivityStatus.Cancelled, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.IsTrue(files.Receive!.Deleted); await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
    }

    [TestMethod]
    public async Task ThrowingChangedHandlerCannotStrandAcceptedOutgoingOwnership()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        tracker.Changed += (_, _) => throw new IOException("private observer payload"); var observed = 0;
        tracker.Changed += (_, _) => Interlocked.Increment(ref observed);
        ActivityId? id = null; Exception? error = null;
        try { id = await coordinator.SendTextAsync(Device, "body", ShareOrigin.Direct, default); } catch (Exception failure) { error = failure; }
        sdk.Finish(new(null, null));
        Assert.IsNull(error); Assert.IsNotNull(id);
        await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
        // Idle is released under the tracker lock; Changed runs outside it.
        await WaitUntil(() => Volatile.Read(ref observed) >= 2);
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
    }

    [TestMethod]
    public async Task ThrowingPreviewNotificationCannotPreventIncomingTextCompletion()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id;
        sdk.Accept = path => File.WriteAllTextAsync(path, "body");
        tracker.Changed += (_, _) => { if (tracker.GetPreview(id) is not null) throw new IOException("private observer payload"); };
        var previewObserved = false; tracker.Changed += (_, _) => previewObserved |= tracker.GetPreview(id) is not null;
        Exception? error = null; try { await coordinator.AcceptAsync(id, default); } catch (Exception failure) { error = failure; }
        Assert.AreEqual(ActivityStatus.Completed, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.IsNull(error); Assert.IsTrue(previewObserved); Assert.IsTrue(files.Destination!.Deleted);
        await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
    }

    [TestMethod]
    public async Task NativeFailureDuringPreparationWinsAfterFaultCleanupAndSettlesSharedAcceptance()
    {
        foreach (var kind in new[] { ContentKind.File, ContentKind.Image })
        {
            var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
            var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            files.PrepareReceive = async _ => { entered.SetResult(); await release.Task; throw new IOException("private preparation path"); };
            var offer = Offer(kind) with { MimeType = kind == ContentKind.Image ? "image/png" : "application/octet-stream" };
            sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            var id = tracker.GetDeviceSnapshot(Device).Single().Id; var terminals = 0;
            tracker.Changed += (_, _) => { if (IsFinished(tracker.GetDeviceSnapshot(Device).Single().Status)) terminals++; };
            var acceptance = coordinator.AcceptAsync(id, default); await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.AreSame(acceptance, coordinator.AcceptAsync(id, default));
            var drain = tracker.WaitForIdleAsync(default);
            sdk.Emit(new ShareSdkEvent.TransferCompleted(new(offer.TransferId, new(ShareErrorKind.NetworkLost, "private native destination"))));
            Assert.AreEqual(1, tracker.ActiveCount); Assert.IsFalse(drain.IsCompleted); Assert.IsFalse(files.Receive!.Deleted);
            release.SetResult();
            Exception? error = null; try { await acceptance; } catch (Exception failure) { error = failure; }
            Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status, kind.ToString());
            Assert.AreEqual(ShareErrorKind.NetworkLost, tracker.GetDeviceSnapshot(Device).Single().ErrorKind);
            Assert.IsNull(error); Assert.IsTrue(acceptance.IsCompletedSuccessfully); Assert.IsTrue(files.Receive.Deleted);
            Assert.IsFalse(File.Exists(files.Receive.StagingPath));
            Assert.AreEqual(0, sdk.AcceptCount); Assert.AreEqual(0, tracker.ActiveCount); await drain.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.AreEqual(1, terminals); Assert.IsFalse(tracker.GetDeviceSnapshot(Device).Single().ToString().Contains("private"));
            sdk.Emit(new ShareSdkEvent.TransferCompleted(new(offer.TransferId, new(ShareErrorKind.NetworkLost, "private late failure"))));
            await coordinator.CancelAsync(id, default); Assert.AreEqual(1, terminals);
        }
    }

    [TestMethod]
    public async Task CallerCancellationAtPreparationRollbackCannotClearOwnerAndStrandDrain()
    {
        var sdk = new Sdk(); var tracker = Tracker(); var files = new Files(); var coordinator = Coordinator(sdk, tracker, files);
        using var entered = new ManualResetEventSlim(); using var release = new ManualResetEventSlim(); using var leaving = new ManualResetEventSlim();
        using var cancellation = new CancellationTokenSource();
        files.CanPrepareReceive = _ => { entered.Set(); if (!release.Wait(TimeSpan.FromSeconds(5))) throw new TimeoutException(); leaving.Set(); return false; };
        sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.File))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
        var id = tracker.GetDeviceSnapshot(Device).Single().Id; var terminals = 0;
        tracker.Changed += (_, _) => { if (IsFinished(tracker.GetDeviceSnapshot(Device).Single().Status)) Interlocked.Increment(ref terminals); };
        Task? acceptance = null; Exception? threadError = null;
        var accepting = new Thread(() => { try { acceptance = coordinator.AcceptAsync(id, cancellation.Token); } catch (Exception error) { threadError = error; } });
        var cancelling = new Thread(() => { try { cancellation.Cancel(); } catch (Exception error) { threadError = error; } });
        // Only the scheduling gate is inspected: all assertions use public tasks,
        // tracker state and SDK calls. Queue rollback first, cancellation second;
        // cancellation-registration disposal keeps its callback inside ownership.
        var schedulingGate = typeof(TransferCoordinator).GetField("gate", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!.GetValue(coordinator)!;
        accepting.Start(); Assert.IsTrue(entered.Wait(TimeSpan.FromSeconds(5)));
        var shared = coordinator.AcceptAsync(id, default); var drain = tracker.WaitForIdleAsync(default);
        Monitor.Enter(schedulingGate);
        try
        {
            release.Set(); Assert.IsTrue(leaving.Wait(TimeSpan.FromSeconds(5)));
            Assert.IsTrue(SpinWait.SpinUntil(() => (accepting.ThreadState & ThreadState.WaitSleepJoin) != 0, TimeSpan.FromSeconds(5)));
            cancelling.Start();
            Assert.IsTrue(SpinWait.SpinUntil(() => (cancelling.ThreadState & ThreadState.WaitSleepJoin) != 0, TimeSpan.FromSeconds(5)));
        }
        finally { Monitor.Exit(schedulingGate); release.Set(); }
        Assert.IsTrue(accepting.Join(TimeSpan.FromSeconds(5))); Assert.IsTrue(cancelling.Join(TimeSpan.FromSeconds(5))); Assert.IsNull(threadError);
        Exception? acceptanceError = null; try { await shared; } catch (Exception error) { acceptanceError = error; }
        Assert.AreEqual(ActivityStatus.Cancelled, tracker.GetDeviceSnapshot(Device).Single().Status);
        Assert.AreSame(shared, acceptance); Assert.IsNull(acceptanceError); Assert.IsTrue(shared.IsCompletedSuccessfully);
        Assert.AreEqual(0, sdk.AcceptCount); Assert.IsNull(files.Receive); Assert.AreEqual(1, sdk.Commands.Count);
        Assert.AreEqual(1, terminals); Assert.AreEqual(0, tracker.ActiveCount); await drain.WaitAsync(TimeSpan.FromSeconds(5));
        await coordinator.CancelAsync(id, default); Assert.AreEqual(1, terminals); Assert.AreEqual(1, sdk.Commands.Count);
    }

    [TestMethod]
    public async Task TextTransientStagingFailureProducesFailedSummaryAndReleasesAutoAcceptDrain()
    {
        foreach (var trusted in new[] { false, true })
        {
            var sdk = new Sdk { Trusted = trusted ? new HashSet<DeviceId> { Device } : new HashSet<DeviceId>() };
            var tracker = Tracker(); var files = new Files { FailTextDestination = true }; var coordinator = Coordinator(sdk, tracker, files);
            var terminals = 0; tracker.Changed += (_, _) => { if (tracker.GetDeviceSnapshot(Device).Any(record => IsFinished(record.Status))) terminals++; };
            sdk.Emit(new ShareSdkEvent.OfferReceived(Offer(ContentKind.Text))); await WaitUntil(() => tracker.GetDeviceSnapshot(Device).Count == 1);
            var id = tracker.GetDeviceSnapshot(Device).Single().Id;
            Exception? error = null; if (!trusted) try { await coordinator.AcceptAsync(id, default); } catch (Exception failure) { error = failure; }
            Assert.AreEqual(ActivityStatus.Failed, tracker.GetDeviceSnapshot(Device).Single().Status, trusted.ToString());
            Assert.AreEqual(ShareErrorKind.StorageFailed, tracker.GetDeviceSnapshot(Device).Single().ErrorKind);
            Assert.IsNull(error); Assert.AreEqual(1, terminals); Assert.AreEqual(0, sdk.AcceptCount); Assert.IsTrue(files.Destination!.Deleted);
            Assert.IsTrue(coordinator.AcceptAsync(id, default).IsCompletedSuccessfully); Assert.IsFalse(File.Exists(files.Destination.Path));
            Assert.IsNull(tracker.GetPreview(id)); Assert.AreEqual(0, tracker.ActiveCount);
            Assert.IsFalse(tracker.GetDeviceSnapshot(Device).Single().ToString().Contains("private"));
            await tracker.WaitForIdleAsync(default).WaitAsync(TimeSpan.FromSeconds(5));
        }
    }

    private static bool IsFinished(ActivityStatus status) => status is ActivityStatus.Completed or ActivityStatus.Failed or ActivityStatus.Rejected or ActivityStatus.Cancelled;

    private static ActivityTracker Tracker(Repository? repository = null) => new(repository ?? new(), new Clock());
    private static IncomingOffer Offer(ContentKind kind) => new(new(7), new(Guid.NewGuid()), Device, kind, "offer.txt", "text/plain", 12);
    private static async Task WaitUntil(Func<bool> predicate)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        while (!predicate()) await Task.Delay(10, timeout.Token);
    }
    private sealed class Clock : IClock { public DateTimeOffset UtcNow => DateTimeOffset.UtcNow; }
    private sealed class Repository : IActivityRepository
    {
        public Dictionary<ActivityId, ActivityRecord> Records { get; } = [];
        public Task UpsertAsync(ActivityRecord record, CancellationToken cancellationToken) { lock (Records) Records[record.Id] = record; return Task.CompletedTask; }
        public Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken) { lock (Records) return Task.FromResult<IReadOnlyList<ActivityRecord>>(Records.Values.ToArray()); }
        public Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken cancellationToken) => LoadDeviceAsync(Device, cancellationToken);
        public Task PruneAsync(DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, CancellationToken cancellationToken) => Task.CompletedTask;
    }
    public sealed class Sdk : IShareSdkClient
    {
        public event EventHandler<ShareSdkEvent>? EventReceived;
        public List<string> Sends { get; } = []; public List<string> Commands { get; } = [];
        public IReadOnlySet<DeviceId> Trusted { get; set; } = new HashSet<DeviceId>();
        public ShareContent Clipboard { get; set; } = new(ContentKind.Text, "", "", []);
        public Func<string, Task>? Accept { get; set; } public int AcceptCount { get; private set; } public bool ThrowSend { get; set; }
        public Action? OnSending { get; set; } public bool ZeroOperation { get; set; }
        public Func<Task>? Rejecting { get; set; } public Func<Task>? Cancelling { get; set; }
        public Func<Task>? Stopping { get; set; }
        public List<string> LifecycleCalls { get; } = [];
        private TaskCompletionSource<TransferTerminal> completion = new(TaskCreationOptions.RunContinuationsAsynchronously); private ulong next;
        public void Emit(ShareSdkEvent value) => EventReceived?.Invoke(this, value);
        public void Finish(TransferTerminal terminal) => completion.SetResult(terminal);
        private PendingSend Send(string route) { if (ThrowSend) throw new IOException("private source"); Sends.Add(route); OnSending?.Invoke(); completion = new(TaskCreationOptions.RunContinuationsAsynchronously); return ZeroOperation ? new(default, Task.FromResult(new TransferTerminal(null, new(ShareErrorKind.NetworkLost, "private source")))) : new(new(++next), completion.Task); }
        public PendingSend BeginSendText(DeviceId device, string text) => Send("text:" + text);
        public PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source) => Send(kind + ":" + source.Path);
        public async Task AcceptAsync(RequestId request, string stagingPath, CancellationToken cancellationToken) { AcceptCount++; if (Accept is not null) await Accept(stagingPath); }
        public Task RejectAsync(RequestId request, CancellationToken cancellationToken) { Commands.Add("reject:" + request); return Rejecting?.Invoke() ?? Task.CompletedTask; }
        public Task CancelAsync(TransferId transfer, CancellationToken cancellationToken) { Commands.Add("cancel:" + transfer); return Cancelling?.Invoke() ?? Task.CompletedTask; }
        public Task<ShareContent> ReadClipboardAsync(CancellationToken cancellationToken) => Task.FromResult(Clipboard);
        public IReadOnlySet<DeviceId> GetTrustedDevices() => Trusted;
        public IReadOnlyList<DeviceSnapshot> GetDevices() => [];
        public Task InitializeAsync(SdkOptions options, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task StartAsync(CancellationToken cancellationToken) => Task.CompletedTask;
        public Task StopAsync(CancellationToken cancellationToken) { LifecycleCalls.Add("stop"); return Stopping?.Invoke() ?? Task.CompletedTask; }
        public Task ConnectAsync(DeviceId device, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task RejectPairingAsync(RequestId request, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task ForgetDeviceAsync(DeviceId device, CancellationToken cancellationToken) => Task.CompletedTask;
        public ValueTask DisposeAsync() { LifecycleCalls.Add("dispose"); return ValueTask.CompletedTask; }
    }
    public sealed class Files : ITransferFiles
    {
        public Source? Source { get; private set; } public Destination? Destination { get; private set; } public Receive? Receive { get; private set; }
        public bool FailPublish { get; set; } public TaskCompletionSource? PublishGate { get; set; }
        public Func<string, bool>? CanPrepareReceive { get; set; } public List<string> ReceiveRoots { get; } = [];
        public Func<CancellationToken, Task>? PrepareReceive { get; set; } public bool FailTextDestination { get; set; }
        public TaskCompletionSource PublishEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public async Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string receiveRoot, CancellationToken cancellationToken) { ReceiveRoots.Add(receiveRoot); if (CanPrepareReceive is not null && !CanPrepareReceive(receiveRoot)) throw new IOException("private destination " + receiveRoot); Receive = new(Path.GetTempFileName()); try { if (PrepareReceive is not null) await PrepareReceive(cancellationToken); return Receive; } catch { await Receive.DisposeAsync(); throw; } }
        public async Task<string> PublishAsync(PreparedReceive receive, CancellationToken cancellationToken) { PublishEntered.TrySetResult(); if (PublishGate is not null) await PublishGate.Task; cancellationToken.ThrowIfCancellationRequested(); if (FailPublish) throw new IOException("private destination"); return "published.txt"; }
        public Task AbortAsync(PreparedReceive receive) => receive.DisposeAsync().AsTask();
        public Task<TransientDestination> CreateTextDestinationAsync(CancellationToken cancellationToken) { Destination = new(Path.GetTempFileName()); if (FailTextDestination) { Destination.DisposeAsync().GetAwaiter().GetResult(); throw new IOException("private text destination"); } return Task.FromResult<TransientDestination>(Destination); }
        public Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mimeType, CancellationToken cancellationToken) { Source = new(new(Path.GetTempFileName(), "Image", mimeType, (ulong)bytes.Length)); return Task.FromResult<TransientSource>(Source); }
    }
    public sealed record Source(ShareSource Value) : TransientSource(Value) { public bool Deleted { get; private set; } public override ValueTask DisposeAsync() { File.Delete(Path); Deleted = true; return ValueTask.CompletedTask; } }
    public sealed record Destination(string Value) : TransientDestination(Value) { public bool Deleted { get; private set; } public override ValueTask DisposeAsync() { File.Delete(Path); Deleted = true; return ValueTask.CompletedTask; } }
    public sealed record Receive(string Value) : PreparedReceive(Value, "published.txt") { public bool Deleted { get; private set; } public override ValueTask DisposeAsync() { File.Delete(StagingPath); Deleted = true; return ValueTask.CompletedTask; } }
}
