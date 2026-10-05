namespace UniShare.Native.Interop;

internal enum NativeCommand
{
    Start, Stop, Connect, ConfirmPairing, RejectPairing, ForgetDevice,
    SendText, SendTextStream, SendImage, SendFile, Accept, Reject, Cancel, ClipboardRead
}

// Input spans are borrowed through Submit only; callbacks use the frozen cdecl ABI.
internal readonly record struct NativeSubmission(
    NativeCommand Command, NativeUuid Device = default, ulong Request = 0,
    uint Matches = 0, NativeSpan Text = default, NativeSource Source = default,
    ulong Stream = 0);

internal interface IUniShareNativeApi
{
    uint AbiVersion();
    NativeError ConfigInit(out NativeConfig config);
    NativeError CreatePlatform(NativeSpan directory, out ulong platform);
    NativeError ReleasePlatform(ulong platform);
    NativeError Create(ulong platform, in NativeConfig config, nint callback, nint context, out ulong sdk, out ulong operation);
    NativeError Submit(ulong sdk, in NativeSubmission submission, nint callback, nint context, out ulong operation);
    NativeError Destroy(ulong sdk);
    NativeError SetEventHandler(ulong sdk, nint callback, nint context);
    NativeError GetDevices(ulong sdk, out nint devices);
    NativeError GetTrustedDevices(ulong sdk, out nint devices);
    NativeError Free(nint allocation);
    NativeError RegisterSource(nint file, out ulong stream);
    NativeError RegisterDestination(nint file, out ulong stream);
    NativeError ReleaseStream(ulong stream);
}
