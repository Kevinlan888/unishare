using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Services;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;

namespace UniShare.App.Tests;

[TestClass]
public sealed class NotificationPayloadTests
{
    private static readonly DeviceId Device = new(Guid.Parse("11111111-2222-3333-4444-555555555555"));
    private sealed class Presence : IAppPresence
    {
        public bool IsWindowVisible
        {
            get; set;
        }
        public List<DeviceId?> Opened = [];
        public List<AppNotificationRequest> Notices = [];
        public TrayState State;
        public void ShowWindow(DeviceId? device) => Opened.Add(device);
        public void HideWindow()
        {
        }
        public void SetTrayState(TrayState state) => State = state;
        public void ShowNotification(AppNotificationRequest request) => Notices.Add(request);
        public void DisposeResources()
        {
        }
        public void ExitApplication()
        {
        }
    }
    private static IncomingOffer Offer(ContentKind kind, ulong request = 42) => new(new(request), new(Guid.NewGuid()), Device, kind, "SECRET-file.txt", "text/plain", 123);
    private static NotificationRouter Router(Presence presence, List<RequestId> rejected, Func<RequestId, bool>? pending = null) =>
        new(presence, id => id == Device, pending ?? (_ => true), request => { rejected.Add(request); return Task.CompletedTask; });

    [TestMethod]
    [DataRow(ContentKind.Image)]
    [DataRow(ContentKind.File)]
    [DataRow(ContentKind.Text)]
    public void HiddenDecisionOfferPayloadIncludesOnlyOpaqueIdsAndTwoDecisionActions(ContentKind kind)
    {
        var presence = new Presence();
        using var router = Router(presence, []);
        router.OnOffer(Offer(kind), false);
        var notice = presence.Notices.Single();
        CollectionAssert.AreEqual(new[] { NotificationAction.Reject, NotificationAction.ViewAndAccept }, notice.Actions.ToArray());
        var xml = NotificationPayload.BuildXml(notice, key => key);
        StringAssert.Contains(xml, "request=42");
        StringAssert.Contains(xml, "11111111-2222-3333-4444-555555555555");
        Assert.IsFalse(xml.Contains("SECRET"));
        Assert.IsFalse(xml.Contains("text/plain"));
        Assert.AreEqual(2, System.Xml.Linq.XDocument.Parse(xml).Descendants("action").Count());
    }

    [TestMethod]
    public async Task ClickingOfferBodyViewsTheSameLiveDecisionWithoutAccepting()
    {
        var presence = new Presence();
        using var router = Router(presence, []);
        router.OnOffer(Offer(ContentKind.File), false);
        var xml = System.Xml.Linq.XDocument.Parse(NotificationPayload.BuildXml(presence.Notices.Single(), key => key));
        await router.ActivateAsync(xml.Root!.Attribute("launch")!.Value);
        CollectionAssert.AreEqual(new DeviceId?[] { Device }, presence.Opened);
    }

    [TestMethod]
    public async Task RejectCompletesOnceWithoutOpeningAndViewOnlyOpensCorrectDevice()
    {
        var presence = new Presence();
        var rejected = new List<RequestId>();
        using var router = Router(presence, rejected);
        router.OnOffer(Offer(ContentKind.File), false);
        await router.ActivateAsync("action=Reject&device=11111111-2222-3333-4444-555555555555&request=42");
        await router.ActivateAsync("action=Reject&device=11111111-2222-3333-4444-555555555555&request=42");
        CollectionAssert.AreEqual(new[] { new RequestId(42) }, rejected);
        Assert.AreEqual(0, presence.Opened.Count);
        router.OnOffer(Offer(ContentKind.Image, 43), false);
        await router.ActivateAsync("action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=43");
        await router.ActivateAsync("action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=43");
        CollectionAssert.AreEqual(new DeviceId?[] { Device }, presence.Opened);
        Assert.AreEqual(1, rejected.Count);
    }

    [TestMethod]
    public async Task MalformedUnknownMismatchedLateAndDisposedActivationAreInert()
    {
        var presence = new Presence();
        var rejected = new List<RequestId>();
        var pending = true;
        var router = Router(presence, rejected, _ => pending);
        router.OnOffer(Offer(ContentKind.File), false);
        foreach (var value in new[] { "", "action=Accept&device=11111111-2222-3333-4444-555555555555&request=42", "action=ViewAndAccept&device=00000000-0000-0000-0000-000000000000&request=42", "action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=99", "action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=42&request=42", "action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=42&content=secret" })
            await router.ActivateAsync(value);
        pending = false;
        await router.ActivateAsync("action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=42");
        pending = true;
        router.Dispose();
        router.Dispose();
        await router.ActivateAsync("action=ViewAndAccept&device=11111111-2222-3333-4444-555555555555&request=42");
        Assert.AreEqual(0, presence.Opened.Count);
        Assert.AreEqual(0, rejected.Count);
    }

    [TestMethod]
    public void TrustedTextNoticeOccursOnlyAfterCompletionAndIsContentFreeWhileUntrustedTextPrompts()
    {
        var presence = new Presence();
        using var router = Router(presence, []);
        var trusted = Offer(ContentKind.Text);
        router.OnOffer(trusted, true);
        Assert.AreEqual(0, presence.Notices.Count);
        router.OnCompleted(trusted.TransferId, true);
        Assert.AreEqual("Notification.Completed", presence.Notices.Single().BodyResourceKey);
        Assert.AreEqual(0, presence.Notices.Single().Actions.Count);
        var xml = NotificationPayload.BuildXml(presence.Notices.Single(), key => key);
        Assert.IsFalse(xml.Contains("SECRET"));
        Assert.IsFalse(xml.Contains("request="));
        router.OnCompleted(trusted.TransferId, true);
        Assert.AreEqual(1, presence.Notices.Count);
        router.OnOffer(Offer(ContentKind.Text, 43), false);
        Assert.AreEqual(2, presence.Notices.Count);
        Assert.AreEqual(2, presence.Notices[1].Actions.Count);
    }

    [TestMethod]
    public void VisibleOffersDoNotNotifyAndSdkFailureShowsWarningWithoutNativeDetails()
    {
        var presence = new Presence { IsWindowVisible = true };
        using var router = Router(presence, []);
        router.OnOffer(Offer(ContentKind.File), false);
        Assert.AreEqual(0, presence.Notices.Count);
        router.OnSdkFailure();
        Assert.AreEqual(TrayState.Warning, presence.State);
        Assert.AreEqual("Notification.SdkFailure", presence.Notices.Single().BodyResourceKey);
    }
}
