using System.ComponentModel;
using System.Runtime.InteropServices;
using UniShare.Application.Abstractions;

namespace UniShare.App.Services;

// Created and destroyed on the WinUI dispatcher thread. This hidden private
// window owns the icon, menu messages and rooted WndProc in this process.
public sealed class Win32TrayPlatform : ITrayPlatform
{
    private const uint CallbackMessage = 0x8001;
    private readonly string className = "UniShare.Tray." + Guid.NewGuid().ToString("N");
    private readonly WndProc procedure;
    private readonly nint instance = GetModuleHandleW(null);
    private nint window;
    private ushort atom;
    private bool disposed;
    private bool iconAdded;
    private Action<TrayCommand>? callback;
    private NotifyIconData icon;
    private string open = "", pause = "", exit = "";
    private readonly uint taskbarCreated = RegisterWindowMessageW("TaskbarCreated");

    public Win32TrayPlatform() => procedure = ProcessMessage;
    public void Create(Action<TrayCommand> handler)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (window != 0)
            throw new InvalidOperationException("Tray window already exists.");
        callback = handler;
        var type = new WindowClass { Instance = instance, ClassName = className, Procedure = Marshal.GetFunctionPointerForDelegate(procedure) };
        atom = RegisterClassW(ref type);
        if (atom == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        window = CreateWindowExW(0, className, "", 0, 0, 0, 0, 0, 0, 0, instance, 0);
        if (window == 0)
            throw new Win32Exception(Marshal.GetLastWin32Error());
        icon = new()
        {
            Size = (uint)Marshal.SizeOf<NotifyIconData>(),
            Window = window,
            Id = 1,
            Flags = 1 | 2 | 4,
            Callback = CallbackMessage,
            Icon = LoadIconW(0, (nint)32512),
            Tooltip = "UniShare",
            Info = "",
            InfoTitle = ""
        };
        iconAdded = Shell_NotifyIconW(0, ref icon);
        if (!iconAdded)
            throw new Win32Exception(Marshal.GetLastWin32Error());
    }
    public void Update(TrayState state, string tooltip, string open, string pause, string exit)
    {
        if (disposed || window == 0)
            return;
        this.open = open;
        this.pause = pause;
        this.exit = exit;
        icon.Tooltip = tooltip.Length < 128 ? tooltip : tooltip[..127];
        icon.Icon = LoadIconW(0, (nint)(state switch
        {
            TrayState.Warning => 32515,
            TrayState.Pausing => 32516,
            TrayState.Paused => 32513,
            _ => 32512
        }));
        Shell_NotifyIconW(1, ref icon);
    }
    private nint ProcessMessage(nint hwnd, uint message, nuint wparam, nint lparam)
    {
        if (!disposed && message == taskbarCreated && iconAdded)
        {
            Shell_NotifyIconW(0, ref icon);
            return 0;
        }
        if (!disposed && message == CallbackMessage && wparam == 1)
        {
            if ((uint)lparam == 0x0203)
                callback?.Invoke(TrayCommand.Open);
            else if ((uint)lparam is 0x0205 or 0x007B)
                ShowMenu();
            return 0;
        }
        return DefWindowProcW(hwnd, message, wparam, lparam);
    }
    private void ShowMenu()
    {
        var menu = CreatePopupMenu();
        if (menu == 0)
            return;
        try
        {
            AppendMenuW(menu, 0, 1, open);
            AppendMenuW(menu, 0, 2, pause);
            AppendMenuW(menu, 0, 3, exit);
            GetCursorPos(out var point);
            SetForegroundWindow(window);
            var command = TrackPopupMenu(menu, 0x0100 | 0x0002, point.X, point.Y, 0, window, 0);
            PostMessageW(window, 0, 0, 0);
            if (command is >= 1 and <= 3)
                callback?.Invoke((TrayCommand)(command - 1));
        }
        finally { DestroyMenu(menu); }
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        callback = null;
        if (iconAdded)
        {
            Shell_NotifyIconW(2, ref icon);
            iconAdded = false;
        }
        if (window != 0)
        {
            DestroyWindow(window);
            window = 0;
        }
        if (atom != 0)
        {
            UnregisterClassW(className, instance);
            atom = 0;
        }
        GC.KeepAlive(procedure);
    }
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate nint WndProc(nint hwnd, uint message, nuint wparam, nint lparam);
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WindowClass
    {
        public uint Style;
        public nint Procedure;
        public int ClassExtra, WindowExtra;
        public nint Instance, Icon, Cursor, Background;
        [MarshalAs(UnmanagedType.LPWStr)] public string? MenuName;
        [MarshalAs(UnmanagedType.LPWStr)] public string ClassName;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public uint Size;
        public nint Window;
        public uint Id, Flags, Callback;
        public nint Icon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Tooltip;
        public uint State, StateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Info;
        public uint TimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string InfoTitle;
        public uint InfoFlags;
        public Guid Guid;
        public nint BalloonIcon;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X, Y;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern nint GetModuleHandleW(string? name);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern ushort RegisterClassW(ref WindowClass type);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool UnregisterClassW(string name, nint instance);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern nint CreateWindowExW(uint exStyle, string className, string name, uint style, int x, int y, int width, int height, nint parent, nint menu, nint instance, nint param);
    [DllImport("user32.dll")] private static extern bool DestroyWindow(nint hwnd);
    [DllImport("user32.dll")] private static extern nint DefWindowProcW(nint hwnd, uint message, nuint wparam, nint lparam);
    [DllImport("user32.dll")] private static extern nint LoadIconW(nint instance, nint resource);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern uint RegisterWindowMessageW(string message);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool Shell_NotifyIconW(uint message, ref NotifyIconData data);
    [DllImport("user32.dll")] private static extern nint CreatePopupMenu();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool AppendMenuW(nint menu, uint flags, nuint id, string label);
    [DllImport("user32.dll")] private static extern bool DestroyMenu(nint menu);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out Point point);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(nint hwnd);
    [DllImport("user32.dll")] private static extern uint TrackPopupMenu(nint menu, uint flags, int x, int y, int reserved, nint hwnd, nint rect);
    [DllImport("user32.dll")] private static extern bool PostMessageW(nint hwnd, uint message, nuint wparam, nint lparam);
}
