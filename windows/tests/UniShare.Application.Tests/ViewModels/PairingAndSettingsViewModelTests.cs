using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.ViewModels;

namespace UniShare.Application.Tests.ViewModels;

[TestClass]
public sealed class PairingAndSettingsViewModelTests
{
    private static Task<SettingsSaveResult> Autosave(SettingsViewModel vm, AppSettings settings) =>
        vm.AutosaveAsync(settings, CancellationToken.None);
    [TestMethod]
    [DataRow(false)]
    [DataRow(true)]
    public async Task QueuedReplacementBeforeUserInvocationCannotConfirmOrRejectReplacement(bool reject)
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var vm = new PairingViewModel(f.Sdk, f.Dispatcher);
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(1), device.Snapshot!, "111 111"));
        f.Dispatcher.Queue = true; f.Dispatcher.HoldCompletions = true;
        f.Sdk.Emit(new ShareSdkEvent.PairingRequested(new(2), device.Snapshot!, "222 222"));
        Assert.AreEqual(new RequestId(1), vm.PendingRequest!.Request);
        var action = (reject ? vm.RejectCommand : vm.ConfirmMatchesCommand).ExecuteAsync(null);
        f.Dispatcher.Drain(); f.Dispatcher.Queue = false; f.Dispatcher.HoldCompletions = false; f.Dispatcher.ReleaseCompletions();
        await action.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(0, f.Sdk.Calls.Count); Assert.AreEqual(new RequestId(2), vm.PendingRequest!.Request);
    }
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
    public async Task SaveValidatesImmutableCandidatesAndQueuesConcurrentAutosave()
    {
        using var f = new ViewModelFixture();
        var firstValidation = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var validations = 0;
        f.Ui.Validation = _ => Interlocked.Increment(ref validations) == 1 ? firstValidation.Task : Task.FromResult(true);
        var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        var firstSettings = vm.Settings with { Theme = ThemePolicy.Dark, ReceiveDirectory = "first" };
        var secondSettings = firstSettings with { Language = LanguagePolicy.English, ReceiveDirectory = "second" };
        var first = Autosave(vm, firstSettings); var second = Autosave(vm, secondSettings);
        firstValidation.SetResult(true); await Task.WhenAll(first, second);
        Assert.AreEqual(2, f.Settings.Saves); Assert.AreEqual(ThemePolicy.Dark, f.Settings.Value.Theme);
        Assert.AreEqual(LanguagePolicy.English, f.Settings.Value.Language);
    }

    [TestMethod]
    public async Task AutosaveReturnsValidationFailureInsteadOfReportingUnsavedSettingsAsSaved()
    {
        using var f = new ViewModelFixture(); f.Ui.Writable = false;
        var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        var result = await Autosave(vm, vm.Settings with { Theme = ThemePolicy.Dark, ReceiveDirectory = "unwritable" });
        Assert.IsFalse(result.Saved);
        Assert.AreEqual("Settings.ReceiveDirectoryNotWritable", result.ErrorResourceKey);
        Assert.AreEqual(0, f.Settings.Saves);
    }

    [TestMethod]
    public async Task AutosaveDoesNotBlockUnrelatedSettingWhenExistingReceiveDirectoryIsTemporarilyUnavailable()
    {
        using var f = new ViewModelFixture(); f.Ui.Writable = false;
        var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        var result = await Autosave(vm, vm.Settings with { Theme = ThemePolicy.Dark });
        Assert.IsTrue(result.Saved); Assert.AreEqual(1, f.Settings.Saves);
        Assert.AreEqual(ThemePolicy.Dark, f.Settings.Value.Theme);
    }

    [TestMethod]
    public async Task AutosavePersistsThreeOverlappingSnapshotsInInvocationOrderAndExitDrainsThem()
    {
        using var f = new ViewModelFixture();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Settings.Saving = async value => { if (value.DeviceName == "one") { entered.SetResult(); await release.Task; } };
        var vm = new SettingsViewModel(f.Settings, f.Ui, f.Dispatcher, f.Settings.Value);
        var first = Autosave(vm, vm.Settings with { DeviceName = "one" });
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var second = Autosave(vm, vm.Settings with { DeviceName = "two" });
        var third = Autosave(vm, vm.Settings with { DeviceName = "three" });
        IAppShutdown shutdown = vm; shutdown.BlockNewWork(); var drain = shutdown.RetireAsync();
        Assert.IsFalse(drain.IsCompleted); release.SetResult();
        await Task.WhenAll(first, second, third, drain).WaitAsync(TimeSpan.FromSeconds(5));
        CollectionAssert.AreEqual(new[] { "one", "two", "three" }, f.Settings.Saved.Select(value => value.DeviceName).ToArray());
        var rejected = await Autosave(vm, vm.Settings with { DeviceName = "late" });
        Assert.IsFalse(rejected.Saved); Assert.AreEqual("Settings.SaveFailed", rejected.ErrorResourceKey);
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
