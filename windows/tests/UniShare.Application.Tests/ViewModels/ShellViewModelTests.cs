using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.ViewModels;

namespace UniShare.Application.Tests.ViewModels;

[TestClass]
public sealed class ShellViewModelTests
{
    [TestMethod]
    public void ShareAndStableSelectionSurviveTrustAndOfflineUpdates()
    {
        using var f = new ViewModelFixture(); var device = f.Online(false); using var shell = f.Shell();
        Assert.AreEqual(ShellSection.Share, shell.SelectedSection);
        shell.SelectedDevice = device; var session = shell.SelectedSession;
        shell.SelectedDevice = device; Assert.AreSame(session, shell.SelectedSession);
        Assert.AreEqual(1, shell.Devices.Nearby.Count);
        f.Sdk.Emit(new ShareSdkEvent.DeviceUpdated(device.Snapshot! with { IsTrusted = true }));
        Assert.AreEqual(1, shell.Devices.Trusted.Count); Assert.AreEqual(0, shell.Devices.Nearby.Count);
        Assert.AreSame(device, shell.SelectedDevice); Assert.AreSame(session, shell.SelectedSession);
        f.Sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        Assert.IsFalse(session!.SendFileCommand.CanExecute("file"));
        Assert.AreSame(session, shell.SelectedSession);
    }

    [TestMethod]
    public void SdkBindingUpdatesAreDispatchedAndDiscoveryBannerIsRetryable()
    {
        using var f = new ViewModelFixture(); using var shell = f.Shell(); f.Dispatcher.Queue = true;
        f.Sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.PermissionDenied, "raw private message")));
        Assert.IsNull(shell.DiscoveryBanner); f.Dispatcher.Drain();
        Assert.AreEqual("Discovery.PermissionDenied", shell.DiscoveryBanner!.ResourceKey);
        Assert.IsTrue(shell.RetryDiscoveryCommand.CanExecute(null));
    }

    [TestMethod]
    public async Task DiscoveryRetryDoesNotRunConcurrentlyAndClearsBannerOnSuccess()
    {
        using var f = new ViewModelFixture(); var gate = new TaskCompletionSource(); int retries = 0;
        using var shell = new ShellViewModel(f.Devices, f.Sdk, () => { retries++; return gate.Task; }, f.Session, f.Dispatcher);
        f.Sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private")));
        var first = shell.RetryDiscoveryCommand.ExecuteAsync(null);
        await shell.RetryDiscoveryCommand.ExecuteAsync(null); Assert.AreEqual(1, retries);
        gate.SetResult(); await first; Assert.IsNull(shell.DiscoveryBanner);
    }

    [TestMethod]
    public async Task IdentityBlockExposesOnlyForgetThenRepair()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var shell = f.Shell(); shell.SelectedDevice = device;
        f.Sdk.Emit(new ShareSdkEvent.IdentityChanged(device.Id)); var vm = shell.SelectedSession!;
        Assert.IsTrue(vm.IdentityChanged); Assert.IsTrue(vm.ForgetCommand.CanExecute(null));
        Assert.IsFalse(vm.PairCommand.CanExecute(null)); Assert.IsFalse(vm.SendClipboardCommand.CanExecute(null));
        Assert.IsFalse(vm.AcceptCommand.CanExecute(new ActivityId(Guid.NewGuid())));
        await vm.ForgetCommand.ExecuteAsync(null); Assert.IsTrue(vm.PairCommand.CanExecute(null));
        Assert.AreEqual(0, shell.Devices.Trusted.Count); Assert.AreEqual(1, shell.Devices.Nearby.Count);
        await vm.PairCommand.ExecuteAsync(null); CollectionAssert.AreEqual(new[] { "forget", "connect" }, f.Sdk.Calls);
    }

    [TestMethod]
    public void DeviceRowNotifiesMetadataChangesWithoutLosingRowOrSessionIdentity()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); using var shell = f.Shell(); shell.SelectedDevice = device;
        var session = shell.SelectedSession; var row = shell.Devices.Trusted.Single(); var changed = new List<string?>();
        row.PropertyChanged += (_, args) => changed.Add(args.PropertyName);
        f.Sdk.Emit(new ShareSdkEvent.DeviceUpdated(device.Snapshot! with { Name = "Renamed" }));
        Assert.AreSame(row, shell.Devices.Trusted.Single()); Assert.AreSame(device, row.Entry);
        Assert.AreEqual("Renamed", row.Name); CollectionAssert.Contains(changed, "Name"); Assert.AreSame(session, shell.SelectedSession);
    }
}
