using System.Runtime.InteropServices;

namespace UniShare.Native.Interop;

[StructLayout(LayoutKind.Sequential)]
internal unsafe struct NativeUuid { internal fixed byte Bytes[16]; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeSpan { internal nint Data; internal ulong Length; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeError { internal uint Code; internal uint HasPlatformError; internal long PlatformError; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeContent { internal uint Type; internal NativeSpan Text; internal NativeSpan MimeType; internal NativeSpan Image; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeConfig
{
    internal uint AbiVersion, StructSize;
    internal NativeSpan DeviceName;
    internal uint MaxConnections, MaxPendingRequests;
    internal ulong MaxBufferBytes, MaxTextBytes, MaxImageBytes, MaxFileBytes;
    internal uint ControlFrameBytes, DataChunkBytes, RequestTimeoutSeconds, OfflineDebounceSeconds;
}
[StructLayout(LayoutKind.Sequential)]
internal struct NativeDevice
{
    internal NativeUuid Id;
    internal NativeSpan Name;
    internal uint Platform;
    internal ushort ProtocolMajor, ProtocolMinor, Port, Paired;
    internal long LastSeenUnixMs;
    internal nint Addresses;
    internal ulong AddressCount;
}
[StructLayout(LayoutKind.Sequential)]
internal struct NativeDevices { internal nint Items; internal ulong Count; }
[StructLayout(LayoutKind.Sequential)]
internal unsafe struct NativeTrustedDevice { internal NativeUuid Id; internal fixed byte PublicKeyFingerprint[32]; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeTrustedDevices { internal nint Items; internal ulong Count; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeResult { internal ulong Operation; internal NativeError Error; internal NativeUuid Transfer; internal NativeContent Content; }
[StructLayout(LayoutKind.Sequential)]
internal struct NativeEvent
{
    internal uint Kind, State;
    internal NativeError Error;
    internal ulong Request;
    internal NativeUuid Transfer;
    internal NativeDevice Device;
    internal NativeSpan VerificationCode;
    internal uint ContentType;
    internal NativeSpan DisplayName, MimeType;
    internal ulong ByteCount, Transferred;
}
[StructLayout(LayoutKind.Sequential)]
internal struct NativeSource { internal ulong Stream; internal NativeSpan DisplayName, MimeType; internal ulong ByteCount; }
