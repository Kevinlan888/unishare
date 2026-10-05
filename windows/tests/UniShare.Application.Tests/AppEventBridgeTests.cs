using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.Tests.ViewModels;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class AppEventBridgeTests
{
    private sealed class Presence : IAppPresence
    {
        public bool IsWindowVisible => false;
        public List<AppNotificationRequest> Notices = [];
        public List<DeviceId?> Opened = [];
        public TrayState State;
        public void ShowWindow(DeviceId? device) => Opened.Add(device);
        public void HideWindow() { }
        public void SetTrayState(TrayState state) => State = state;
        public void ShowNotification(AppNotificationRequest request) => Notices.Add(request);
        public void DisposeResources() { }
        public void ExitApplication() { }
    }
    [TestMethod]
    public async Task OutgoingCompletionAlsoProducesGenericNoticeAndRoutesToItsDevice()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); var presence = new Presence();
        await using var session = new ShareSession(f.Sdk, f.Tracker); await session.InitializeAsync(new()); await session.StartAsync();
        using var bridge = new AppEventBridge(f.Sdk, session, f.Tracker, f.Coordinator, presence);
        var sent = await f.Coordinator.SendTextAsync(device.Id, "PRIVATE content", ShareOrigin.Clipboard, CancellationToken.None);
        Assert.AreEqual(0, presence.Notices.Count);
        f.Tracker.Complete(sent, new(new(Guid.NewGuid()), null));
        Assert.AreEqual("Notification.Completed", presence.Notices.Single().BodyResourceKey);
        await bridge.ActivateAsync($"action=Open&device={device.Id.Value:D}");
        CollectionAssert.AreEqual(new DeviceId?[] { device.Id }, presence.Opened);
    }

    [TestMethod]
    public async Task BridgeRejectsThroughRealTransferCoordinatorAndRetiresDecision()
    {
        using var f = new ViewModelFixture(); var device = f.Online(false); var presence = new Presence();
        await using var session = new ShareSession(f.Sdk, f.Tracker); await session.InitializeAsync(new()); await session.StartAsync();
        using var bridge = new AppEventBridge(f.Sdk, session, f.Tracker, f.Coordinator, presence);
        var offer = new IncomingOffer(new(42), new(Guid.NewGuid()), device.Id, ContentKind.File, "private.txt", "application/octet-stream", 12);
        f.Sdk.Emit(new ShareSdkEvent.OfferReceived(offer));
        Assert.AreEqual(2, presence.Notices.Single().Actions.Count);
        await bridge.ActivateAsync($"action=Reject&device={device.Id.Value:D}&request=42");
        Assert.AreEqual(ActivityStatus.Rejected, f.Tracker.GetDeviceSnapshot(device.Id).Single().Status);
        Assert.AreEqual(0, presence.Opened.Count);
        await bridge.ActivateAsync($"action=ViewAndAccept&device={device.Id.Value:D}&request=42");
        Assert.AreEqual(0, presence.Opened.Count);
        CollectionAssert.AreEqual(new[] { "reject:42" }, f.Sdk.Calls);
        bridge.Dispose(); f.Sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private")));
        Assert.AreNotEqual(TrayState.Warning, presence.State);
    }
    [TestMethod]
    public async Task BridgeNotifiesTerminalActivityRatherThanPrematureNativeTransferSuccess()
    {
        using var f = new ViewModelFixture(); var device = f.Online(); var presence = new Presence();
        await using var session = new ShareSession(f.Sdk, f.Tracker); await session.InitializeAsync(new()); await session.StartAsync();
        using var bridge = new AppEventBridge(f.Sdk, session, f.Tracker, f.Coordinator, presence);
        var offer = new IncomingOffer(new(42), new(Guid.NewGuid()), device.Id, ContentKind.File, "secret.txt", "application/octet-stream", 12);
        f.Sdk.Emit(new ShareSdkEvent.OfferReceived(offer));
        f.Sdk.Emit(new ShareSdkEvent.TransferCompleted(new(offer.TransferId, null)));
        Assert.AreEqual(1, presence.Notices.Count);
        var activity = f.Tracker.GetDeviceSnapshot(device.Id).Single();
        f.Tracker.Complete(activity.Id, new(offer.TransferId, null));
        Assert.AreEqual(2, presence.Notices.Count); Assert.AreEqual("Notification.Completed", presence.Notices[1].BodyResourceKey);
        f.Sdk.Emit(new ShareSdkEvent.DiscoveryFailed(new(ShareErrorKind.DiscoveryFailed, "private")));
        Assert.AreEqual(TrayState.Warning, presence.State);
    }
}
