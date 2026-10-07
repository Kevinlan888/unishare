using Microsoft.Win32.SafeHandles;

namespace UniShare.Native.Interop;

internal static class WindowsStreamFactory
{
    // Registration duplicates the OS handle. A successful call consumes the
    // caller's original; a rejected call leaves it owned by the caller.
    internal static SafeStreamHandle RegisterSource(SafeFileHandle file) => Register(file, destination: false);
    internal static SafeStreamHandle RegisterDestination(SafeFileHandle file) => Register(file, destination: true);

    private static SafeStreamHandle Register(SafeFileHandle file, bool destination)
    {
        ArgumentNullException.ThrowIfNull(file);
        NativeMapper.RequireAbiVersion(NativeMethods.usdk_abi_version());
        var retained = false;
        ulong token;
        NativeError error;
        try
        {
            file.DangerousAddRef(ref retained);
            if (file.IsInvalid)
                throw new ArgumentException("A live file handle is required.", nameof(file));
            error = destination
                ? NativeMethods.usdk_windows_open_destination(file.DangerousGetHandle(), out token)
                : NativeMethods.usdk_windows_open_source(file.DangerousGetHandle(), out token);
        }
        finally { if (retained) file.DangerousRelease(); }
        if (NativeMapper.Failure(error) is { } failure)
            throw new IOException(failure.Message);
        if (token == 0)
            throw new IOException("Native stream registration returned an invalid token.");
        file.Dispose();
        return new SafeStreamHandle(token);
    }
}
