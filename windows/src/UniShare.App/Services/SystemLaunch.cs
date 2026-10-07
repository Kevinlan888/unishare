namespace UniShare.App.Services;

internal static class SystemLaunch
{
    public static void EnsureSucceeded(bool launched)
    {
        if (!launched)
            throw new IOException("Windows could not launch the requested file or folder.");
    }
}
