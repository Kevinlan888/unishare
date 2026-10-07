using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.Tests.Fakes;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class AppLifecycleCoordinatorTests
{
    private sealed class Presence : IAppPresence
    {
        public bool IsWindowVisible { get; private set; } = true;
        public TrayState State;
        public List<string> Calls = [];
        public List<AppNotificationRequest> Notices = [];
        public void ShowWindow(DeviceId? device)
        {
            IsWindowVisible = true;
            Calls.Add("show:" + device);
        }
        public void HideWindow()
        {
            IsWindowVisible = false;
            Calls.Add("hide");
        }
        public void SetTrayState(TrayState state) => State = state;
        public void ShowNotification(AppNotificationRequest request) => Notices.Add(request);
        public void DisposeResources() => Calls.Add("resources");
        public void ExitApplication() => Calls.Add("exit");
    }
    private static async Task<(AppLifecycleCoordinator Coordinator, ShareSession Session, FakeShareSdkClient Sdk, FakeTransferDrain Drain, Presence Presence)> CreateAsync()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var presence = new Presence();
        var session = new ShareSession(sdk, drain);
        await session.InitializeAsync(new());
        await session.StartAsync();
        return (new(session, presence), session, sdk, drain, presence);
    }

    [TestMethod]
    public async Task DiscoveryFailureInsideResumeLeavesPausedWarningAndResumeRetries()
    {
        var x = await CreateAsync();
        await x.Coordinator.PauseAsync();
        x.Sdk.Starting = () =>
        {
            x.Sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private start diagnostic")));
            return Task.CompletedTask;
        };
        try
        {
            await x.Coordinator.ResumeAsync();
            Assert.AreEqual(SessionState.Paused, x.Session.State);
            Assert.AreEqual(TrayState.Warning, x.Presence.State);
            Assert.IsFalse(x.Drain.NewTransfersAllowed);
            Assert.AreEqual("Notification.SdkFailure", x.Presence.Notices.Single().BodyResourceKey);
            x.Sdk.Starting = null;
            await x.Coordinator.ResumeAsync();
            Assert.AreEqual(SessionState.Running, x.Session.State);
            Assert.AreEqual(TrayState.Normal, x.Presence.State);
        }
        finally { await x.Coordinator.ExitAsync(); }
    }

    [TestMethod]
    public async Task CloseHidesAndRepeatedActivationKeepsTheRunningSession()
    {
        var x = await CreateAsync();
        x.Coordinator.OnWindowCloseRequested();
        Assert.IsFalse(x.Presence.IsWindowVisible);
        Assert.AreEqual(SessionState.Running, x.Session.State);
        await Task.WhenAll(x.Coordinator.ActivateAsync(), x.Coordinator.ActivateAsync());
        Assert.IsTrue(x.Presence.IsWindowVisible);
        CollectionAssert.AreEqual(new[] { "initialize", "start" }, x.Sdk.Calls);
        await x.Coordinator.ExitAsync();
    }

    [TestMethod]
    public async Task PauseBlocksAdmissionAndWaitsForDrainBeforeSdkStop()
    {
        var x = await CreateAsync();
        x.Drain.BeginTransfer();
        var pause = x.Coordinator.PauseAsync();
        await x.Drain.WaitEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.IsFalse(x.Drain.NewTransfersAllowed);
        Assert.AreEqual(TrayState.Pausing, x.Presence.State);
        Assert.IsFalse(x.Sdk.Calls.Contains("stop"));
        x.Drain.CompleteTransfer();
        await pause;
        Assert.AreEqual(TrayState.Paused, x.Presence.State);
        Assert.AreEqual(SessionState.Paused, x.Session.State);
        await x.Coordinator.ActivateAsync();
        Assert.AreEqual(SessionState.Paused, x.Session.State);
        await x.Coordinator.ExitAsync();
    }

    [TestMethod]
    public async Task ResumeFailureKeepsPauseAndSurfacesGenericWarning()
    {
        var x = await CreateAsync();
        await x.Coordinator.PauseAsync();
        x.Sdk.Starting = () => Task.FromException(new IOException("sensitive native detail"));
        await x.Coordinator.ResumeAsync();
        Assert.AreEqual(SessionState.Paused, x.Session.State);
        Assert.AreEqual(TrayState.Warning, x.Presence.State);
        Assert.IsFalse(x.Drain.NewTransfersAllowed);
        Assert.AreEqual("Notification.SdkFailure", x.Presence.Notices.Single().BodyResourceKey);
        await x.Coordinator.ExitAsync();
    }

    [TestMethod]
    public async Task PauseAcceptedBehindDelayedResumeNeverAllowsNewTransfers()
    {
        var x = await CreateAsync();
        await x.Coordinator.PauseAsync();
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        x.Sdk.Starting = () => release.Task;
        var resume = x.Coordinator.ResumeAsync();
        x.Drain.Calls.Clear();
        var pause = x.Coordinator.PauseAsync();
        Assert.IsFalse(x.Drain.NewTransfersAllowed);
        release.SetResult();
        await Task.WhenAll(resume, pause);
        Assert.IsFalse(x.Drain.Calls.Contains("allow"));
        Assert.AreEqual(SessionState.Paused, x.Session.State);
        Assert.AreEqual(TrayState.Paused, x.Presence.State);
        await x.Coordinator.ExitAsync();
    }

    [TestMethod]
    public async Task PauseBeforeInitializationRemainsIntentWhenAutomaticStartupRuns()
    {
        var sdk = new FakeShareSdkClient();
        var drain = new FakeTransferDrain();
        var presence = new Presence();
        var session = new ShareSession(sdk, drain);
        var coordinator = new AppLifecycleCoordinator(session, presence);
        await coordinator.PauseAsync();
        await session.InitializeAsync(new());
        await session.StartAsync();
        Assert.AreEqual(SessionState.Paused, session.State);
        Assert.IsFalse(drain.NewTransfersAllowed);
        Assert.IsFalse(sdk.Calls.Contains("start"));
        await coordinator.ResumeAsync();
        Assert.AreEqual(SessionState.Running, session.State);
        Assert.IsTrue(drain.NewTransfersAllowed);
        await coordinator.ExitAsync();
    }

    [TestMethod]
    public async Task DiscoveryFailureFaultsAndBlocksBeforeWarningResumeRestartsSameSdk()
    {
        var x = await CreateAsync();
        x.Sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private diagnostic")));
        Assert.AreEqual(SessionState.Faulted, x.Session.State);
        Assert.IsFalse(x.Drain.NewTransfersAllowed);
        await x.Coordinator.ResumeAsync();
        Assert.AreEqual(SessionState.Running, x.Session.State);
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "start" }, x.Sdk.Calls);
        await x.Coordinator.ExitAsync();
    }

    [TestMethod]
    public async Task ExitQueuedDuringResumeDoesNotRepaintTrayAsRunning()
    {
        var x = await CreateAsync();
        await x.Coordinator.PauseAsync();
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        x.Sdk.Starting = () => release.Task;
        var resume = x.Coordinator.ResumeAsync();
        var exit = x.Coordinator.ExitAsync();
        release.SetResult();
        await Task.WhenAll(resume, exit);
        Assert.AreNotEqual(TrayState.Normal, x.Presence.State);
    }

    [TestMethod]
    public async Task ExitDuringAcceptedResumeNeverReopensTransferAdmission()
    {
        var x = await CreateAsync();
        await x.Coordinator.PauseAsync();
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        x.Sdk.Starting = () => release.Task;
        var resume = x.Coordinator.ResumeAsync();
        x.Drain.Calls.Clear();
        var exit = x.Coordinator.ExitAsync();
        release.SetResult();
        await Task.WhenAll(resume, exit);
        Assert.IsFalse(x.Drain.Calls.Contains("allow"), "A resumed SDK must not admit work once explicit exit was requested.");
        Assert.AreEqual(SessionState.Exiting, x.Session.State);
        Assert.IsFalse(x.Drain.NewTransfersAllowed);
    }

    [TestMethod]
    public async Task StopFailureStillRetiresDisposedSdkBeforeResourcesAndProcessExit()
    {
        var x = await CreateAsync();
        x.Sdk.Stopping = () => Task.FromException(new IOException("private stop detail"));
        await x.Coordinator.ExitAsync();
        await x.Coordinator.ExitAsync();
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "dispose" }, x.Sdk.Calls);
        CollectionAssert.AreEqual(new[] { "resources", "exit" }, x.Presence.Calls);
    }

    [TestMethod]
    public async Task RepeatedExitWaitsForStopThenDisposesResourcesThenExitsAndIgnoresActivation()
    {
        var x = await CreateAsync();
        var stopped = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        x.Sdk.Stopping = () => stopped.Task;
        var first = x.Coordinator.ExitAsync();
        var second = x.Coordinator.ExitAsync();
        Assert.IsFalse(x.Drain.NewTransfersAllowed);
        Assert.IsFalse(x.Presence.Calls.Contains("resources"));
        await x.Coordinator.ActivateAsync();
        x.Coordinator.OnWindowCloseRequested();
        stopped.SetResult();
        await Task.WhenAll(first, second);
        CollectionAssert.AreEqual(new[] { "initialize", "start", "stop", "dispose" }, x.Sdk.Calls);
        CollectionAssert.AreEqual(new[] { "resources", "exit" }, x.Presence.Calls);
        await x.Coordinator.ExitAsync();
        await x.Coordinator.ActivateAsync();
        Assert.AreEqual(2, x.Presence.Calls.Count);
    }
}
