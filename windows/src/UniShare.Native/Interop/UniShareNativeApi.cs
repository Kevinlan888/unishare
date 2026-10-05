using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace UniShare.Native.Interop;

internal sealed class UniShareNativeApi : IUniShareNativeApi
{
    public uint AbiVersion() => NativeMethods.usdk_abi_version();
    public NativeError ConfigInit(out NativeConfig config) => NativeMethods.usdk_config_init(1, out config);
    public NativeError CreatePlatform(NativeSpan directory, out ulong platform) => NativeMethods.usdk_windows_platform_create(directory, out platform);
    public NativeError ReleasePlatform(ulong platform) => NativeMethods.usdk_platform_release(platform);
    public NativeError Create(ulong platform, in NativeConfig config, nint callback, nint context, out ulong sdk, out ulong operation)
        => NativeMethods.usdk_create(platform, config, callback, context, out sdk, out operation);
    public NativeError Destroy(ulong sdk) => NativeMethods.usdk_destroy(sdk);
    public NativeError SetEventHandler(ulong sdk, nint callback, nint context) => NativeMethods.usdk_set_event_handler(sdk, callback, context);
    public NativeError GetDevices(ulong sdk, out nint devices) => NativeMethods.usdk_get_devices(sdk, out devices);
    public NativeError GetTrustedDevices(ulong sdk, out nint devices) => NativeMethods.usdk_get_trusted_devices(sdk, out devices);
    public NativeError Free(nint allocation) => NativeMethods.usdk_free(allocation);
    public NativeError RegisterSource(nint file, out ulong stream) => NativeMethods.usdk_windows_open_source(file, out stream);
    public NativeError RegisterDestination(nint file, out ulong stream) => NativeMethods.usdk_windows_open_destination(file, out stream);
    public NativeError ReleaseStream(ulong stream) => NativeMethods.usdk_stream_release(stream);

    public NativeError Submit(ulong sdk, in NativeSubmission value, nint callback, nint context, out ulong operation)
        => value.Command switch
        {
            NativeCommand.Start => NativeMethods.usdk_start(sdk, callback, context, out operation),
            NativeCommand.Stop => NativeMethods.usdk_stop(sdk, callback, context, out operation),
            NativeCommand.Connect => NativeMethods.usdk_connect(sdk, value.Device, callback, context, out operation),
            NativeCommand.ConfirmPairing => NativeMethods.usdk_confirm_pairing(sdk, value.Request, value.Matches, callback, context, out operation),
            NativeCommand.RejectPairing => NativeMethods.usdk_reject_pairing(sdk, value.Request, callback, context, out operation),
            NativeCommand.ForgetDevice => NativeMethods.usdk_forget_device(sdk, value.Device, callback, context, out operation),
            NativeCommand.SendText => NativeMethods.usdk_send_text(sdk, value.Device, value.Text, callback, context, out operation),
            NativeCommand.SendTextStream => NativeMethods.usdk_send_text_stream(sdk, value.Device, value.Source, callback, context, out operation),
            NativeCommand.SendImage => NativeMethods.usdk_send_image(sdk, value.Device, value.Source, callback, context, out operation),
            NativeCommand.SendFile => NativeMethods.usdk_send_file(sdk, value.Device, value.Source, callback, context, out operation),
            NativeCommand.Accept => NativeMethods.usdk_accept(sdk, value.Request, value.Stream, callback, context, out operation),
            NativeCommand.Reject => NativeMethods.usdk_reject(sdk, value.Request, callback, context, out operation),
            NativeCommand.Cancel => NativeMethods.usdk_cancel(sdk, value.Device, callback, context, out operation),
            NativeCommand.ClipboardRead => NativeMethods.usdk_clipboard_read(sdk, callback, context, out operation),
            _ => throw new ArgumentOutOfRangeException(nameof(value))
        };
}

internal static partial class WindowsNativeFile
{
    [LibraryImport("kernel32.dll", EntryPoint = "CreateFileW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    private static partial SafeFileHandle CreateFile(string path, uint access, uint share, nint security, uint creation, uint flags, nint template);

    internal static SafeFileHandle OpenDestination(string path)
    {
        // GENERIC_READ | GENERIC_WRITE | DELETE; all three sharing flags.
        // OPEN_ALWAYS preserves existing staging data so it can be rejected.
        var file = CreateFile(path, 0xc0010000, 7, 0, 4, 0x80, 0);
        if (file.IsInvalid)
        {
            var code = Marshal.GetLastPInvokeError();
            file.Dispose();
            throw new NativeShareSdkException(new(UniShare.Application.Models.ShareErrorKind.StorageFailed,
                "Unable to open staging file.", code));
        }
        try
        {
            if (RandomAccess.GetLength(file) != 0) throw new IOException("Staging file must be empty.");
            return file;
        }
        catch { file.Dispose(); throw; }
    }
}
