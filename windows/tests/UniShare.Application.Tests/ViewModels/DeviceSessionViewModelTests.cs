using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;

namespace UniShare.Application.Tests.ViewModels;

[TestClass]
public sealed class DeviceSessionViewModelTests
{
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
