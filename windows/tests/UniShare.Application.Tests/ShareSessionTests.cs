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
    [DataRow(false, false)]
    [DataRow(false, true)]
    [DataRow(true, false)]
    [DataRow(true, true)]
    public async Task DiscoveryFailureDuringActiveStartCannotBeOverwrittenAndNextGenerationCanRetry(bool resume, bool delayed)
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        if (resume)
        {
            await session.StartAsync();
            await session.PauseAsync();
        }
        drain.Calls.Clear();
        var entered = Signal();
        var returned = Signal();
        sdk.Starting = () =>
        {
            entered.SetResult();
            if (delayed)
                return returned.Task;
            sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private start diagnostic")));
            return Task.CompletedTask;
        };
        var start = resume ? session.ResumeAsync() : session.StartAsync();
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        if (delayed)
        {
            sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private start diagnostic")));
            returned.SetResult();
        }
        try
        {
            await Assert.ThrowsAsync<InvalidOperationException>(() => start);
            Assert.AreEqual(resume ? SessionState.Paused : SessionState.Faulted, session.State);
            Assert.IsFalse(drain.NewTransfersAllowed);
            Assert.IsFalse(drain.Calls.Contains("allow"));
            sdk.Starting = null;
            await session.ResumeAsync();
            Assert.AreEqual(SessionState.Running, session.State);
            Assert.IsTrue(drain.NewTransfersAllowed);
            CollectionAssert.AreEqual(resume ? new[] { "initialize", "start", "stop", "start", "stop", "start" }
                : new[] { "initialize", "start", "stop", "start" }, sdk.Calls);
        }
        finally { await session.ExitAsync(); }
    }

    [TestMethod]
    public async Task PauseAndResumeWithEmptyCatalogKeepTrustedDeviceOfflineAndNotTransferable()
    {
        var device = new DeviceSnapshot(new(Guid.NewGuid()), "Phone", DevicePlatform.Android,
            1, 0, System.Collections.Immutable.ImmutableArray.Create("192.0.2.10"), 9999,
            true, DateTimeOffset.Parse("2026-10-05T00:00:00Z"));
        var sdk = new FakeShareSdkClient
        {
            Devices = [device],
            Trusted = new HashSet<DeviceId> { device.Id }
        };
        var session = new ShareSession(sdk, new FakeTransferDrain());
        await session.InitializeAsync(new());
        await session.StartAsync();
        var entry = session.Devices.Devices.Single();
        session.Devices.SelectedDevice = entry;
        sdk.Emit(new ShareSdkEvent.ConnectionChanged(device.Id, ConnectionState.Connected, null));
        Assert.IsTrue(entry.CanTransfer);
        Assert.IsTrue(entry.IsConnected);

        // Native explicit Stop clears discovery and detaches event delivery;
        // there will be no offline/disconnected events to update the store.
        sdk.Stopping = () => { sdk.Devices = []; return Task.CompletedTask; };
        await session.PauseAsync();
        Assert.IsFalse(entry.IsOnline);
        Assert.IsFalse(entry.IsConnected);
        Assert.IsFalse(entry.CanTransfer);
        Assert.AreSame(entry, session.Devices.TrustedDevices.Single());
        Assert.AreSame(entry, session.Devices.SelectedDevice);

        await session.ResumeAsync();
        Assert.AreEqual(SessionState.Running, session.State);
        Assert.IsFalse(entry.IsOnline);
        Assert.IsFalse(entry.IsConnected);
        Assert.IsFalse(entry.CanTransfer);
        Assert.AreSame(entry, session.Devices.TrustedDevices.Single());
        Assert.AreSame(entry, session.Devices.SelectedDevice);
        await session.ExitAsync();
    }

    [TestMethod]
    public async Task FreshStartSnapshotRestoresDiscoveryOnSameEntryWithoutEventDelivery()
    {
        var device = new DeviceSnapshot(new(Guid.NewGuid()), "Phone", DevicePlatform.Android,
            1, 0, System.Collections.Immutable.ImmutableArray.Create("192.0.2.10"), 9999,
            true, DateTimeOffset.Parse("2026-10-05T00:00:00Z"));
        var sdk = new FakeShareSdkClient
        {
            Devices = [device],
            Trusted = new HashSet<DeviceId> { device.Id }
        };
        var session = new ShareSession(sdk, new FakeTransferDrain());
        await session.InitializeAsync(new());
        await session.StartAsync();
        var entry = session.Devices.Devices.Single();
        session.Devices.SelectedDevice = entry;
        sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        sdk.Stopping = () => { sdk.Devices = []; return Task.CompletedTask; };
        await session.PauseAsync();

        var updated = device with
        {
            Name = "Rediscovered phone",
            LastSeen = device.LastSeen.AddMinutes(1)
        };
        sdk.Starting = () => { sdk.Devices = [updated]; return Task.CompletedTask; };
        await session.ResumeAsync();
        Assert.AreSame(entry, session.Devices.Devices.Single());
        Assert.AreSame(entry, session.Devices.SelectedDevice);
        Assert.IsTrue(entry.IsOnline);
        Assert.IsTrue(entry.CanTransfer);
        Assert.IsFalse(entry.IsConnected);
        Assert.AreEqual("Rediscovered phone", entry.Name);
        Assert.AreEqual(updated.LastSeen, entry.Snapshot!.LastSeen);
        await session.ExitAsync();
    }

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
        async Task Activate()
        {
            await session.InitializeAsync(options);
            await session.StartAsync();
        }
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
    public async Task ExitQueuedDuringPausePreemptsDrainButWaitsForAcceptedStopWithoutDuplicateStop()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        drain.BeginTransfer();
        var stopEntered = Signal();
        var stopReleased = Signal();
        sdk.Stopping = () => { stopEntered.SetResult(); return stopReleased.Task; };
        var pause = session.PauseAsync();
        await drain.WaitEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        var exit = session.ExitAsync();
        await stopEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await pause.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsTrue(drain.HasActiveTransfers);
        Assert.IsFalse(exit.IsCompleted);
        drain.CompleteTransfer();
        stopReleased.SetResult();
        await Task.WhenAll(pause, exit);
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "dispose" }, sdk.Calls);
    }

    [TestMethod]
    public async Task CallerCancellationAfterPauseAdmissionStillDrainsAndDoesNotFault()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        drain.BeginTransfer();
        using var cancellation = new CancellationTokenSource();
        var pause = session.PauseAsync(cancellation.Token);
        await drain.WaitEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        cancellation.Cancel();
        Assert.IsFalse(pause.IsCompleted);
        Assert.AreEqual(SessionState.Pausing, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        Assert.IsFalse(sdk.Calls.Contains("stop"));
        drain.CompleteTransfer();
        await pause.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.AreEqual(SessionState.Paused, session.State);
        await session.ExitAsync();
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
