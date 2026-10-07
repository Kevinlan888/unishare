using System.Runtime.InteropServices;
using System.Text;

namespace UniShare.Native.Interop;

// Owns explicitly length-delimited input storage for a synchronous submission.
internal sealed class NativeUtf8 : IDisposable
{
    internal static readonly UTF8Encoding Encoding = new(false, true);
    private nint allocation;
    internal NativeSpan Span { get; }

    internal NativeUtf8(string value)
    {
        var bytes = Encoding.GetBytes(value);
        if (bytes.Length != 0)
        {
            allocation = Marshal.AllocHGlobal(bytes.Length);
            Marshal.Copy(bytes, 0, allocation, bytes.Length);
        }
        Span = new NativeSpan { Data = allocation, Length = (ulong)bytes.Length };
    }

    public void Dispose()
    {
        var pointer = Interlocked.Exchange(ref allocation, 0);
        if (pointer != 0)
            Marshal.FreeHGlobal(pointer);
    }
}
