using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;
using UniShare.Application.Models;

namespace UniShare.App.Tests;

[TestClass]
public sealed class PresentationStateTests
{
    [TestMethod]
    [DataRow(true, true, false, true, true)]
    [DataRow(true, false, false, true, false)]
    [DataRow(false, true, false, true, false)]
    [DataRow(true, true, true, true, false)]
    [DataRow(true, true, false, false, false)]
    public void SendingRequiresAnOnlineTrustedUnblockedDeviceAndRunningSession(
        bool online, bool trusted, bool blocked, bool running, bool expected)
        => Assert.AreEqual(expected, PresentationState.SendAvailable(online, trusted, blocked, running));

    [TestMethod]
    [DataRow(false, false, false, "NoSelection")]
    [DataRow(true, true, false, "Pairing.Required")]
    [DataRow(true, false, false, "EmptyActivities")]
    [DataRow(true, false, true, null)]
    public void SessionHintPrioritizesPairingBeforeEmptyActivityState(
        bool hasDevice, bool canPair, bool hasActivities, string? expected)
        => Assert.AreEqual(expected, PresentationState.SessionHintResourceKey(hasDevice, canPair, hasActivities));

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
