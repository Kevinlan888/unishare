using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace UniShare.Native.Interop;

// All handles are 64-bit registry tokens. Callers must keep SafeHandles alive
// around raw-token calls and root callback contexts until destroy completes.
internal static partial class NativeMethods
{
    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial uint usdk_abi_version();

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_config_init(uint version, out NativeConfig config);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_create(ulong platform, in NativeConfig config, nint callback, nint context, out ulong sdk, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_start(ulong sdk, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_stop(ulong sdk, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_clipboard_read(ulong sdk, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_destroy(ulong sdk);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_get_state(ulong sdk, out uint state);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_set_event_handler(ulong sdk, nint handler, nint context);

    [LibraryImport("unishare")]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_set_diagnostic_handler(ulong sdk, nint handler, nint context);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_get_devices(ulong sdk, out nint result);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_get_trusted_devices(ulong sdk, out nint result);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_connect(ulong sdk, NativeUuid device, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_forget_device(ulong sdk, NativeUuid device, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_confirm_pairing(ulong sdk, ulong request, uint codeMatches, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_reject_pairing(ulong sdk, ulong request, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_reject(ulong sdk, ulong request, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_send_text(ulong sdk, NativeUuid device, NativeSpan text, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_send_text_stream(ulong sdk, NativeUuid device, in NativeSource source, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_send_image(ulong sdk, NativeUuid device, in NativeSource source, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_send_file(ulong sdk, NativeUuid device, in NativeSource source, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_accept(ulong sdk, ulong request, ulong destination, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_cancel(ulong sdk, NativeUuid transfer, nint callback, nint context, out ulong operation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_platform_release(ulong token);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_stream_release(ulong token);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_free(nint allocation);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_windows_platform_create(NativeSpan directory, out ulong platform);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_windows_open_source(nint nativeHandle, out ulong stream);

    [LibraryImport("unishare", StringMarshalling = StringMarshalling.Utf8)]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    internal static partial NativeError usdk_windows_open_destination(nint nativeHandle, out ulong stream);

    // ABI includes clipboard_write, deliberately unbound: remote clipboard writes
    // are outside the Windows application's supported behavior.
}
