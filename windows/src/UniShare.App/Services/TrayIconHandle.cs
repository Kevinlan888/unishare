using System.ComponentModel;
using System.Runtime.InteropServices;

namespace UniShare.App.Services;

internal sealed class TrayIconHandle : IDisposable
{
    private const uint ImageIcon = 1;
    private const uint LoadFromFile = 0x10;
    private const int SmallIconWidth = 49;
    private const int SmallIconHeight = 50;

    public TrayIconHandle(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var dpi = GetDpiForSystem();
        var width = GetSystemMetricsForDpi(SmallIconWidth, dpi);
        var height = GetSystemMetricsForDpi(SmallIconHeight, dpi);
        Value = LoadImageW(0, Path.GetFullPath(path), ImageIcon, width, height, LoadFromFile);
        if (Value == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    public nint Value { get; private set; }

    public void Dispose()
    {
        var value = Value;
        Value = 0;
        if (value != 0)
            DestroyIcon(value);
    }

    [DllImport("user32.dll")] private static extern uint GetDpiForSystem();
    [DllImport("user32.dll")] private static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern nint LoadImageW(nint instance, string name, uint type, int width, int height, uint flags);
    [DllImport("user32.dll")] private static extern bool DestroyIcon(nint icon);
}
