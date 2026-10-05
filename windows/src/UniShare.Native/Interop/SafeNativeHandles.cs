using System.Runtime.InteropServices;

namespace UniShare.Native.Interop;

// ABI tokens are unsigned 64-bit integers, not pointers. SafeHandle's pointer
// storage is used only on x64 and preserves every token bit (including bit 63).
internal abstract class SafeNativeHandle : SafeHandle
{
    protected SafeNativeHandle(ulong token) : base(0, ownsHandle: true)
    {
        if (IntPtr.Size != 8) throw new PlatformNotSupportedException("UniShare native interop requires x64.");
        SetHandle(unchecked((nint)token));
    }

    public override bool IsInvalid => handle == 0;
    internal ulong Token => unchecked((ulong)handle);
}

internal sealed class SafeSdkHandle(ulong token) : SafeNativeHandle(token)
{
    protected override bool ReleaseHandle() => NativeMethods.usdk_destroy(Token).Code == 0;
}

internal sealed class SafePlatformHandle(ulong token) : SafeNativeHandle(token)
{
    protected override bool ReleaseHandle() => NativeMethods.usdk_platform_release(Token).Code == 0;
}

internal sealed class SafeStreamHandle(ulong token) : SafeNativeHandle(token)
{
    protected override bool ReleaseHandle() => NativeMethods.usdk_stream_release(Token).Code == 0;
}
