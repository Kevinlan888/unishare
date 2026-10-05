using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.ViewModels;

namespace UniShare.Application.Tests.ViewModels;

[TestClass]
public sealed class PairingAndSettingsViewModelTests
{
    [TestMethod]
    public async Task QueuedAdmissionConfirmsAOnDispatcherEvenWhenBReplacesItBeforeContinuation()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = new PairingViewModel(f.Sdk, f.Dispatcher);
        f.Sdk.IsDispatching = () => f.Dispatcher.IsDispatching;
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(1), device.Snapshot!, "111 111"));
        f.Dispatcher.Queue = true; f.Dispatcher.HoldCompletions = true;
        var confirmation = vm.ConfirmMatchesCommand.ExecuteAsync(null);
        Assert.AreEqual(0, f.Sdk.Calls.Count);
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(2), device.Snapshot!, "222 222"));
        f.Dispatcher.Drain(); f.Dispatcher.Queue = false; f.Dispatcher.HoldCompletions = false; f.Dispatcher.ReleaseCompletions();
        await confirmation.WaitAsync(TimeSpan.FromSeconds(5));
        CollectionAssert.AreEqual(new[] { "confirm:1:True" }, f.Sdk.Calls);
        Assert.IsTrue(f.Sdk.ConfirmationWasDispatched); Assert.AreEqual(new RequestId(2), vm.PendingRequest!.Request);
    }
    [TestMethod]
    public async Task SaveValidatesImmutableCandidateAndRejectsConcurrentSave()
    {
        using var f = new ViewModelFixture(); var gate = new TaskCompletionSource<bool>(); f.Ui.Validation = _ => gate.Task;
        var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        vm.Settings = vm.Settings with { ReceiveDirectory = "validated" }; var first = vm.SaveCommand.ExecuteAsync(null);
        vm.Settings = vm.Settings with { ReceiveDirectory = "not-validated" }; await vm.SaveCommand.ExecuteAsync(null);
        Assert.IsFalse(vm.SaveCommand.CanExecute(null)); gate.SetResult(true); await first;
        Assert.AreEqual(1, f.Settings.Saves); Assert.AreEqual("validated", f.Settings.Value.ReceiveDirectory);
    }

    [TestMethod]
    public async Task DirectoryProbeFailureIsSemanticAndNeverSaves()
    {
        using var f = new ViewModelFixture(); f.Ui.Validation = _ => throw new UnauthorizedAccessException("private path");
        var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        await vm.SaveCommand.ExecuteAsync(null); Assert.AreEqual(0, f.Settings.Saves);
        Assert.AreEqual("Settings.ReceiveDirectoryNotWritable", vm.ValidationResourceKey);
    }
    [TestMethod]
    public async Task PairingPreservesCodeSpacingAndGuardsConfirmAgainstConcurrentReject()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = new PairingViewModel(f.Sdk, f.Dispatcher);
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(17), device.Snapshot!, "12  34 56"));
        Assert.AreEqual("12  34 56", vm.PendingRequest!.VerificationCode);
        var gate = new TaskCompletionSource(); f.Sdk.Wait = () => gate.Task;
        var first = vm.ConfirmMatchesCommand.ExecuteAsync(null); await vm.ConfirmMatchesCommand.ExecuteAsync(null); await vm.RejectCommand.ExecuteAsync(null);
        CollectionAssert.AreEqual(new[] { "confirm:17:True" }, f.Sdk.Calls); gate.SetResult(); await first;
        Assert.IsNull(vm.PendingRequest);
        f.Sdk.Wait = null; f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(18), device.Snapshot!, "001 002"));
        await vm.RejectCommand.ExecuteAsync(null); Assert.AreEqual("pair-reject:18", f.Sdk.Calls.Last());
    }

    [TestMethod]
    public async Task NewPairingRequestIsNotClearedByOlderConfirmationCompletion()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = new PairingViewModel(f.Sdk, f.Dispatcher);
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(1), device.Snapshot!, "111 111"));
        var gate = new TaskCompletionSource(); f.Sdk.Wait = () => gate.Task; var first = vm.ConfirmMatchesCommand.ExecuteAsync(null);
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(2), device.Snapshot!, "222 222")); gate.SetResult(); await first;
        Assert.AreEqual(new RequestId(2), vm.PendingRequest!.Request);
    }

    [TestMethod]
    public async Task UnwritableReceiveDirectoryNeverSavesAndValidDirectoryDoes()
    {
        using var f = new ViewModelFixture(); var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        vm.Settings = vm.Settings with { ReceiveDirectory = "unwritable" }; f.Ui.Writable = false;
        Assert.IsFalse(await vm.ValidateReceiveDirectoryAsync()); await vm.SaveCommand.ExecuteAsync(null);
        Assert.AreEqual(0, f.Settings.Saves); Assert.AreEqual("Settings.ReceiveDirectoryNotWritable", vm.ValidationResourceKey);
        f.Ui.Writable = true; await vm.SaveCommand.ExecuteAsync(null); Assert.AreEqual(1, f.Settings.Saves);
        Assert.AreEqual("unwritable", f.Settings.Value.ReceiveDirectory); Assert.IsNull(vm.ValidationResourceKey);
    }
}
