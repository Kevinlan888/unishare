using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.Tests.Fakes;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class ShareSessionTests
{
    private static TaskCompletionSource Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    [TestMethod]
    public async Task ConcurrentWindowActivationInitializesAndStartsOneSdk()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        var entered = Signal();
        var release = Signal();
        sdk.Initializing = () => { entered.SetResult(); return release.Task; };
        var options = new SdkOptions { DeviceName = "Desktop" };
        async Task Activate() { await session.InitializeAsync(options); await session.StartAsync(); }
        var first = Activate();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var second = Activate();
        release.SetResult();
        await Task.WhenAll(first, second);
        CollectionAssert.AreEqual(new[] { "initialize", "start" }, sdk.Calls);
        Assert.AreSame(options, sdk.Options);
        Assert.AreEqual(SessionState.Running, session.State);
        Assert.IsTrue(drain.NewTransfersAllowed);
        await session.ExitAsync();
    }

    [TestMethod]
    public async Task PauseBlocksThenDrainsBeforeStopAndRepeatedActivationDoesNotResume()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        drain.Calls.Clear();
        drain.BeginTransfer();
        var pause = session.PauseAsync();
        await drain.WaitEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(SessionState.Pausing, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        CollectionAssert.AreEqual(new[] { "block", "wait-blocked" }, drain.Calls);
        Assert.IsFalse(sdk.Calls.Contains("stop"));
        drain.CompleteTransfer();
        await pause;
        Assert.AreEqual(SessionState.Paused, session.State);
        await session.InitializeAsync(new());
        await session.StartAsync();
        Assert.AreEqual(SessionState.Paused, session.State);
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop" }, sdk.Calls);
        await session.ExitAsync();
    }

    [TestMethod]
    public async Task ConcurrentResumeStartsOnlyOnceAndExitStopsAndDisposesOnlyOnce()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        await session.PauseAsync();
        var entered = Signal();
        var release = Signal();
        sdk.Starting = () => { entered.SetResult(); return release.Task; };
        var first = session.ResumeAsync();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var second = session.ResumeAsync();
        Assert.IsFalse(drain.NewTransfersAllowed);
        release.SetResult();
        await Task.WhenAll(first, second);
        Assert.AreEqual(SessionState.Running, session.State);
        await Task.WhenAll(session.ExitAsync(), session.ExitAsync());
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "start", "stop", "dispose" }, sdk.Calls);
        Assert.AreEqual(SessionState.Exiting, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        await Assert.ThrowsAsync<ObjectDisposedException>(() => session.ResumeAsync());
    }

    [TestMethod]
    public async Task ResumeFailureRemainsPausedWithTransfersBlockedAndCanRetry()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        await session.PauseAsync();
        sdk.Starting = () => Task.FromException(new InvalidOperationException("start failed"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => session.ResumeAsync());
        Assert.AreEqual(SessionState.Paused, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        sdk.Starting = null;
        await session.ResumeAsync();
        Assert.AreEqual(SessionState.Running, session.State);
        Assert.IsTrue(drain.NewTransfersAllowed);
        await session.ExitAsync();
    }

    [TestMethod]
    public async Task ExitQueuedDuringPauseWaitsForDrainWithoutDuplicateStop()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        drain.BeginTransfer();
        var pause = session.PauseAsync();
        await drain.WaitEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var exit = session.ExitAsync();
        Assert.IsFalse(exit.IsCompleted);
        drain.CompleteTransfer();
        await Task.WhenAll(pause, exit);
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "dispose" }, sdk.Calls);
    }

    [TestMethod]
    public async Task StopFailureFaultsAndExitStillDisposesExactlyOnce()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        sdk.Stopping = () => Task.FromException(new InvalidOperationException("stop failed"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => session.PauseAsync());
        Assert.AreEqual(SessionState.Faulted, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        await Assert.ThrowsAsync<InvalidOperationException>(() => session.ExitAsync());
        await session.ExitAsync();
        Assert.AreEqual(1, sdk.Calls.Count(value => value == "dispose"));
        Assert.AreEqual(SessionState.Exiting, session.State);
    }

    [TestMethod]
    public async Task FailedInitializationFaultsAndExitDisposesWithoutStop()
    {
        var sdk = new FakeShareSdkClient { Initializing = () => Task.FromException(new InvalidOperationException("init failed")) };
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await Assert.ThrowsAsync<InvalidOperationException>(() => session.InitializeAsync(new()));
        Assert.AreEqual(SessionState.Faulted, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        await session.ExitAsync();
        CollectionAssert.AreEqual(new[] { "initialize", "dispose" }, sdk.Calls);
    }

    [TestMethod]
    public async Task UnexpectedSdkStopFaultsAndBlocksTransfersWhileDeviceEventsUseSharedStore()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        var id = new DeviceId(Guid.NewGuid());
        sdk.Trusted = new HashSet<DeviceId> { id };
        await session.InitializeAsync(new());
        Assert.AreEqual(id, session.Devices.TrustedDevices.Single().Id);
        await session.StartAsync();
        sdk.Emit(new ShareSdkEvent.DiscoveryStateChanged(DiscoveryState.Stopped));
        Assert.AreEqual(SessionState.Faulted, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        await session.ExitAsync();
        sdk.Emit(new ShareSdkEvent.IdentityChanged(new(Guid.NewGuid())));
        Assert.AreEqual(1, session.Devices.Devices.Count);
    }

    [TestMethod]
    public async Task CancelledQueuedResumeCannotRestartAfterExit()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        var release = Signal();
        var entered = Signal();
        sdk.Stopping = () => { entered.SetResult(); return release.Task; };
        var exit = session.ExitAsync();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using var cancellation = new CancellationTokenSource();
        var resume = session.ResumeAsync(cancellation.Token);
        cancellation.Cancel();
        await Assert.ThrowsAsync<OperationCanceledException>(() => resume);
        release.SetResult();
        await exit;
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "dispose" }, sdk.Calls);
    }

    [TestMethod]
    public async Task CancellationAfterStartSubmissionDoesNotReleaseLifecycleGateEarly()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        var entered = Signal();
        var release = Signal();
        sdk.Starting = () => { entered.SetResult(); return release.Task; };
        using var cancellation = new CancellationTokenSource();
        var start = session.StartAsync(cancellation.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        cancellation.Cancel();
        var exit = session.ExitAsync();
        Assert.IsFalse(exit.IsCompleted);
        Assert.IsFalse(sdk.Calls.Contains("stop"));
        release.SetResult();
        await Task.WhenAll(start, exit);
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "dispose" }, sdk.Calls);
    }
}
