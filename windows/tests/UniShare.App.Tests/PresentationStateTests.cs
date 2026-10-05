using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;
using UniShare.Application.Models;

namespace UniShare.App.Tests;

[TestClass]
public sealed class PresentationStateTests
{
    [TestMethod]
    [DataRow(ShareDirection.Incoming, ShareOrigin.Clipboard, false)]
    [DataRow(ShareDirection.Outgoing, ShareOrigin.Clipboard, true)]
    [DataRow(ShareDirection.Incoming, ShareOrigin.Direct, false)]
    [DataRow(ShareDirection.Outgoing, ShareOrigin.Direct, false)]
    public void ClipboardMarkerIsOnlyVisibleOnLocalOutgoingClipboard(ShareDirection direction, ShareOrigin origin, bool expected)
        => Assert.AreEqual(expected, PresentationState.ClipboardMarkerVisible(direction, origin));

    [TestMethod]
    [DataRow(true, 1.0, "Online")]
    [DataRow(false, 0.0, "OfflineStatus")]
    public void PresenceSelectsMatchingVisualAndAccessibleState(bool online, double opacity, string key)
    {
        Assert.AreEqual(opacity, PresentationState.OnlineIndicatorOpacity(online));
        Assert.AreEqual(key, PresentationState.StatusResourceKey(online));
    }
}
