using UniShare.Application.Models;

namespace UniShare.App.Presentation;

internal static class PresentationState
{
    public static bool ClipboardMarkerVisible(ShareDirection direction, ShareOrigin origin)
        => direction == ShareDirection.Outgoing && origin == ShareOrigin.Clipboard;
    public static double OnlineIndicatorOpacity(bool online) => online ? 1 : 0;
    public static string StatusResourceKey(bool online, bool connected = false)
        => connected ? "Connected" : online ? "Online" : "OfflineStatus";
    public static bool SendAvailable(bool online, bool connected, bool trusted, bool blocked, bool running)
        => (online || connected) && trusted && !blocked && running;
    public static string? SessionHintResourceKey(bool hasDevice, bool canPair, bool hasActivities)
        => !hasDevice ? "NoSelection" : canPair ? "Pairing.Required" : !hasActivities ? "EmptyActivities" : null;
}
