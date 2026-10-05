using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;

namespace UniShare.Application.Tests.ViewModels;

[TestClass]
public sealed class DeviceSessionViewModelTests
{
    [TestMethod]
    [DataRow(false, false)]
    [DataRow(false, true)]
    [DataRow(true, false)]
    [DataRow(true, true)]
    public async Task UnsafeEventRevokesPreparationBeforeAnyBindingDispatch(bool image, bool identityChanged)
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = f.Session(device);
        var read = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var staged = new TaskCompletionSource<TransientSource>(TaskCreationOptions.RunContinuationsAsynchronously);
        var source = new ViewModelFixture.TestFiles.StagedImage();
        if (image) { f.Sdk.Clipboard = new(ContentKind.Image, null, "image/png", [1, 2]); f.Files.Staging = _ => staged.Task; }
        else f.Sdk.Wait = () => read.Task;
        var sending = vm.SendClipboardCommand.ExecuteAsync(null);
        f.Dispatcher.Queue = true;
        if (identityChanged) f.Sdk.Emit(new ShareSdkEvent.IdentityChanged(device.Id)); else f.Sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        Assert.IsTrue(f.Sdk.ClipboardToken.IsCancellationRequested);
        if (image) staged.SetResult(source); else read.SetResult();
        // Wait for the continuation's binding notification, but deliberately do
        // not run the queued unsafe-event notification before checking SDK state.
        await f.Dispatcher.WaitForQueuedActionsAsync(2).WaitAsync(TimeSpan.FromSeconds(5));
        try
        {
            Assert.AreEqual(0, f.Sdk.Completions.Count);
            Assert.AreEqual(0, f.Tracker.GetDeviceSnapshot(device.Id).Count);
            if (image) { Assert.AreEqual(1, source.Disposals); Assert.IsFalse(f.Tracker.HasActiveTransfers); }
        }
        finally { f.Dispatcher.Queue = false; f.Dispatcher.Drain(); await sending.WaitAsync(TimeSpan.FromSeconds(5)); }
    }
    [TestMethod]
    public async Task UnsafeGenerationRetiresStagedClipboardImageEvenIfDeviceIsOnlineAgainBeforeDispatch()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = f.Session(device);
        f.Sdk.Clipboard = new(ContentKind.Image, null, "image/png", [1, 2]);
        var staging = new TaskCompletionSource<UniShare.Application.Models.TransientSource>(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Files.Staging = _ => staging.Task;
        var sending = vm.SendClipboardCommand.ExecuteAsync(null); Assert.IsTrue(f.Tracker.HasActiveTransfers);
        f.Dispatcher.Queue = true; f.Sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        f.Sdk.Emit(new ShareSdkEvent.DeviceOnline(device.Snapshot!)); f.Dispatcher.Drain(); f.Dispatcher.Queue = false;
        var image = new ViewModelFixture.TestFiles.StagedImage(); staging.SetResult(image); await sending;
        Assert.AreEqual(1, image.Disposals); Assert.AreEqual(0, f.Sdk.Completions.Count);
        Assert.AreEqual(0, vm.Activities.Count); Assert.IsFalse(f.Tracker.HasActiveTransfers);
        Assert.IsTrue(vm.SendClipboardCommand.CanExecute(null));
    }

    [TestMethod]
    public async Task UnsafeEventCancelsAcceptedSendWithoutReleasingCoordinatorOwnershipEarly()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = f.Session(device); vm.ComposerText = "accepted";
        await vm.SendTextCommand.ExecuteAsync(null); var transfer = new TransferId(Guid.NewGuid());
        f.Sdk.Emit(new ShareSdkEvent.OutgoingTransferRegistered(new(1), transfer));
        var cancelled = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Sdk.Cancelling = () => { cancelled.SetResult(); return Task.CompletedTask; };
        f.Sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        await cancelled.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(1, f.Sdk.Calls.Count(x => x.StartsWith("cancel:")));
        Assert.IsTrue(f.Tracker.HasActiveTransfers); Assert.IsFalse(f.Sdk.Completions.Single().Task.IsCompleted);
        f.Sdk.Completions.Single().SetResult(new(transfer, new(ShareErrorKind.Cancelled, "private")));
        await f.Tracker.WaitForIdleAsync(default);
        Assert.AreEqual(ActivityStatus.Cancelled, f.Tracker.GetDeviceSnapshot(device.Id).Single().Status);
        Assert.AreEqual(1, f.Tracker.GetDeviceSnapshot(device.Id).Count);
    }
    [TestMethod]
    public async Task CancelRunsDuringManualAcceptanceButDoesNotReleaseReceiveBeforeNativeCompletion()
    {
        using var f = new ViewModelFixture(); var device = f.Online(false); using var vm = f.Session(device);
        var offer = new IncomingOffer(new(71), new(Guid.NewGuid()), device.Id, ContentKind.File, "large.bin", "application/octet-stream", 100);
        f.Sdk.Emit(new ShareSdkEvent.OfferReceived(offer)); var id = vm.Activities.Single().Id;
        var native = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancel = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Sdk.Accepting = () => native.Task; f.Sdk.Cancelling = () => cancel.Task;
        var acceptance = vm.AcceptCommand.ExecuteAsync(id);
        Assert.IsFalse(acceptance.IsCompleted); Assert.IsTrue(vm.CancelCommand.CanExecute(id));
        var cancellation = vm.CancelCommand.ExecuteAsync(id);
        await vm.CancelCommand.ExecuteAsync(id); await vm.AcceptCommand.ExecuteAsync(id); await vm.RejectCommand.ExecuteAsync(id);
        Assert.AreEqual(1, f.Sdk.Calls.Count(x => x.StartsWith("cancel:")));
        Assert.AreEqual(1, f.Sdk.Calls.Count(x => x == "accept:71")); Assert.IsFalse(f.Sdk.Calls.Contains("reject:71"));
        cancel.SetResult(); await cancellation;
        Assert.IsFalse(acceptance.IsCompleted); Assert.AreEqual(0, f.Files.Aborted); Assert.AreEqual(0, f.Files.Published);
        Assert.IsTrue(f.Tracker.HasActiveTransfers); Assert.IsFalse(vm.CancelCommand.CanExecute(id));
        native.SetResult(); await acceptance; Assert.AreEqual(1, f.Files.Aborted); Assert.AreEqual(0, f.Files.Published);
        Assert.AreEqual(ActivityStatus.Cancelled, vm.Activities.Single().Record.Status); Assert.IsFalse(f.Tracker.HasActiveTransfers);
    }

    [TestMethod]
    [DataRow(false)]
    [DataRow(true)]
    public async Task UnsafeDeviceEventRevokesPendingClipboardBeforeSubmission(bool identityChanged)
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = f.Session(device);
        var read = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously); f.Sdk.Wait = () => read.Task;
        var sending = vm.SendClipboardCommand.ExecuteAsync(null); Assert.AreEqual("clipboard", f.Sdk.Calls.Single());
        f.Dispatcher.Queue = true;
        if (identityChanged) f.Sdk.Emit(new ShareSdkEvent.IdentityChanged(device.Id)); else f.Sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        f.Dispatcher.Drain(); f.Dispatcher.Queue = false;
        read.SetResult(); await sending;
        Assert.AreEqual(1, f.Sdk.Calls.Count); Assert.AreEqual(0, f.Tracker.GetDeviceSnapshot(device.Id).Count);
        Assert.IsFalse(vm.SendClipboardCommand.CanExecute(null));
        if (identityChanged) await vm.ForgetCommand.ExecuteAsync(null);
        f.Sdk.Emit(new ShareSdkEvent.DeviceOnline(device.Snapshot!)); f.Sdk.Wait = null;
        await vm.SendClipboardCommand.ExecuteAsync(null); Assert.AreEqual("text:clipboard", f.Sdk.Calls.Last());
    }
    [TestMethod]
    public async Task CancellationRemainsDisabledUntilOutgoingNativeCompletion()
    {
        using var f = new ViewModelFixture(); using var vm = f.Session(f.Online()); vm.ComposerText = "cancel me";
        await vm.SendTextCommand.ExecuteAsync(null); var item = vm.Activities.Single(); var transfer = new TransferId(Guid.NewGuid());
        f.Sdk.Emit(new ShareSdkEvent.OutgoingTransferRegistered(new(1), transfer));
        await vm.CancelCommand.ExecuteAsync(item.Id); await vm.CancelCommand.ExecuteAsync(item.Id);
        Assert.IsFalse(vm.CancelCommand.CanExecute(item.Id)); Assert.AreEqual(1, f.Sdk.Calls.Count(x => x.StartsWith("cancel:")));
        f.Sdk.Completions.Single().SetResult(new(transfer, new(ShareErrorKind.Cancelled, "private"))); await f.Tracker.WaitForIdleAsync(default);
        Assert.AreSame(item, vm.Activities.Single()); Assert.AreEqual(ActivityStatus.Cancelled, item.Record.Status);
    }
    [TestMethod]
    public async Task ComposerClearsOnlyAcceptedSubmissionAndPreservesFailure()
    {
        using var f = new ViewModelFixture(); using var vm = f.Session(f.Online()); vm.ComposerText = "hello";
        f.Sdk.FailSend = true; await vm.SendTextCommand.ExecuteAsync(null);
        Assert.AreEqual("hello", vm.ComposerText); Assert.AreEqual("Transfer.SubmissionFailed", vm.ErrorResourceKey);
        f.Sdk.FailSend = false; await vm.SendTextCommand.ExecuteAsync(null);
        Assert.AreEqual("", vm.ComposerText); Assert.AreEqual(1, vm.Activities.Count);
        CollectionAssert.AreEqual(new[] { "text:hello" }, f.Sdk.Calls);
    }

    [TestMethod]
    public async Task SendGateRejectsRepeatedAndCrossCommandExecutionWhileAwaitingClipboard()
    {
        using var f = new ViewModelFixture(); using var vm = f.Session(f.Online()); vm.ComposerText = "draft";
        var gate = new TaskCompletionSource(); f.Sdk.Wait = () => gate.Task;
        var first = vm.SendClipboardCommand.ExecuteAsync(null);
        Assert.IsFalse(vm.SendTextCommand.CanExecute(null));
        await vm.SendClipboardCommand.ExecuteAsync(null); await vm.SendTextCommand.ExecuteAsync(null);
        Assert.AreEqual(1, f.Sdk.Calls.Count); gate.SetResult(); await first;
        Assert.AreEqual("draft", vm.ComposerText); CollectionAssert.AreEqual(new[] { "clipboard", "text:clipboard" }, f.Sdk.Calls);
    }

    [TestMethod]
    public async Task DragDropAcceptsExactlyOneFileAndImageUsesImageRoute()
    {
        using var f = new ViewModelFixture(); using var vm = f.Session(f.Online()); var path = Path.GetTempFileName();
        try
        {
            await vm.DropFilesAsync([]); await vm.DropFilesAsync([path, path]); Assert.AreEqual(0, f.Sdk.Calls.Count);
            await vm.DropFilesAsync([path]); await vm.SendImageCommand.ExecuteAsync(path);
            CollectionAssert.AreEqual(new[] { "File:" + path, "Image:" + path }, f.Sdk.Calls);
        }
        finally { File.Delete(path); }
    }

    [TestMethod]
    public async Task OfflineAndBlockedCommandsCannotSubmitEvenThroughExecuteAsync()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = f.Session(device); vm.ComposerText = "draft";
        f.Sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id)); vm.Refresh();
        Assert.IsFalse(vm.SendTextCommand.CanExecute(null)); await vm.SendTextCommand.ExecuteAsync(null);
        Assert.AreEqual(0, f.Sdk.Calls.Count); Assert.AreEqual("draft", vm.ComposerText);
    }

    [TestMethod]
    public async Task AcceptRejectCancelAndRetryUseOneCoordinatorInvocationAndStatusGates()
    {
        using var f = new ViewModelFixture(); var device = f.Online(false); using var vm = f.Session(device);
        var offer = new IncomingOffer(new(7), new(Guid.NewGuid()), device.Id, ContentKind.File, "report", "application/octet-stream", 100);
        f.Sdk.Emit(new ShareSdkEvent.OfferReceived(offer));
        var incoming = vm.Activities.Single(); var gate = new TaskCompletionSource(); f.Sdk.Wait = () => gate.Task;
        var accept = vm.AcceptCommand.ExecuteAsync(incoming.Id); await vm.AcceptCommand.ExecuteAsync(incoming.Id);
        await vm.RejectCommand.ExecuteAsync(incoming.Id); Assert.AreEqual(1, f.Sdk.Calls.Count);
        gate.SetResult(); await accept; f.Sdk.Wait = null;
        Assert.IsFalse(vm.CancelCommand.CanExecute(incoming.Id));
        var cancelled = offer with { RequestId = new(9), TransferId = new(Guid.NewGuid()) };
        f.Sdk.Emit(new ShareSdkEvent.OfferReceived(cancelled));
        var cancelledId = vm.Activities.Single(x => x.Record.TransferId == cancelled.TransferId).Id;
        await vm.CancelCommand.ExecuteAsync(cancelledId); await vm.CancelCommand.ExecuteAsync(cancelledId);
        Assert.AreEqual(1, f.Sdk.Calls.Count(x => x.StartsWith("cancel:")));
        var rejected = offer with { RequestId = new(8), TransferId = new(Guid.NewGuid()) };
        f.Sdk.Emit(new ShareSdkEvent.OfferReceived(rejected)); var id = vm.Activities.Single(x => x.Record.TransferId == rejected.TransferId).Id;
        await vm.RejectCommand.ExecuteAsync(id); await vm.RejectCommand.ExecuteAsync(id);
        Assert.AreEqual(1, f.Sdk.Calls.Count(x => x == "reject:8"));
        vm.ComposerText = "retry me"; await vm.SendTextCommand.ExecuteAsync(null);
        f.Sdk.Completions.Single().SetResult(new(null, new(ShareErrorKind.NetworkLost, "private")));
        await f.Tracker.WaitForIdleAsync(default); vm.Refresh();
        var failed = vm.Activities.Single(x => x.Record.Status == ActivityStatus.Failed);
        await vm.RetryCommand.ExecuteAsync(failed.Id);
        Assert.AreEqual(2, f.Sdk.Calls.Count(x => x == "text:retry me"));
        Assert.AreEqual(1, vm.Activities.Count(x => x.Record.RetryOf == failed.Id));
    }
}
