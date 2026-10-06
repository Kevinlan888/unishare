using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using UniShare.Native.Interop;

namespace UniShare.Native.Tests;

// The DLL boundary is substituted; real managed marshaling, files, registries and
// dispatch remain under test. No native callbacks are retained as managed delegates.
internal sealed class FakeNativeApi : IUniShareNativeApi
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void Callback(nint context, nint data);
    internal sealed record Call(ulong Operation, NativeCommand Command, nint Callback, nint Context);
    internal ConcurrentDictionary<ulong, Call> Calls { get; } = new();
    internal List<string> Ownership { get; } = [];
    internal uint Version = 1;
    internal NativeError NextError;
    internal NativeError RegistrationError;
    internal bool ZeroOperation;
    internal Action<Call>? DuringSubmit;
    internal Action? DuringDestroy;
    internal Action<nint>? DuringRegistration;
    internal Action<nint>? FreeAllocation;
    internal nint DeviceRoot, TrustedRoot;
    internal string? CopiedText;
    internal NativeConfig Config;
    internal NativeCommand? LastCommand;
    internal nint SourceHandle, DestinationHandle;
    internal ulong LastStream;
    internal int DestroyCount;
    internal nint EventCallback, EventContext;
    internal nint DiagnosticCallback, DiagnosticContext;
    internal Exception? DiagnosticRegistrationException;
    internal List<(nint Callback, nint Context)> DiagnosticRegistrations { get; } = [];
    internal (nint Callback, nint Context) LastRegistration;
    [ThreadStatic] internal static bool OnNativeStack;
    private long next;

    public uint AbiVersion() => Version;
    public NativeError ConfigInit(out NativeConfig config)
    { config = new() { AbiVersion = 1, StructSize = (uint)Marshal.SizeOf<NativeConfig>() }; return default; }
    public NativeError CreatePlatform(NativeSpan directory, out ulong platform) { platform = 77; return default; }
    public NativeError ReleasePlatform(ulong platform) { Ownership.Add("platform released"); return default; }
    public NativeError Create(ulong platform, in NativeConfig config, nint callback, nint context, out ulong sdk, out ulong operation)
    {
        Config = config;
        CopiedText = NativeMapper.Text(config.DeviceName);
        var error = Add(NativeCommand.Start, callback, context, out operation);
        sdk = error.Code == 0 ? 99UL : 0;
        return error;
    }
    public NativeError Submit(ulong sdk, in NativeSubmission submission, nint callback, nint context, out ulong operation)
    {
        LastCommand = submission.Command;
        if (submission.Command == NativeCommand.SendText) CopiedText = NativeMapper.Text(submission.Text);
        if (submission.Source.Stream != 0 || submission.Stream != 0)
        {
            LastStream = submission.Source.Stream != 0 ? submission.Source.Stream : submission.Stream;
            Ownership.Add("submitted");
        }
        return Add(submission.Command, callback, context, out operation);
    }
    private NativeError Add(NativeCommand command, nint callback, nint context, out ulong operation)
    {
        var error = NextError; NextError = default;
        operation = error.Code != 0 || ZeroOperation ? 0 : (ulong)Interlocked.Increment(ref next);
        ZeroOperation = false;
        if (operation != 0)
        {
            var call = new Call(operation, command, callback, context);
            Calls[operation] = call;
            DuringSubmit?.Invoke(call);
        }
        return error;
    }
    public NativeError Destroy(ulong sdk)
    { DuringDestroy?.Invoke(); DestroyCount++; Calls.Clear(); EventCallback = 0; DiagnosticCallback = 0; return default; }
    public NativeError SetEventHandler(ulong sdk, nint callback, nint context)
    {
        EventCallback = callback; EventContext = context;
        if (callback != 0) LastRegistration = (callback, context);
        return default;
    }
    public NativeError SetDiagnosticHandler(ulong sdk, nint callback, nint context)
    {
        if (DiagnosticRegistrationException is not null) throw DiagnosticRegistrationException;
        DiagnosticCallback = callback; DiagnosticContext = context;
        DiagnosticRegistrations.Add((callback, context));
        return default;
    }
    internal void EmitDiagnostic(NativeDiagnosticEvent value) => Invoke(DiagnosticCallback, DiagnosticContext, value);
    public NativeError GetDevices(ulong sdk, out nint devices) { devices = DeviceRoot; return default; }
    public NativeError GetTrustedDevices(ulong sdk, out nint devices) { devices = TrustedRoot; return default; }
    public NativeError Free(nint allocation) { FreeAllocation?.Invoke(allocation); return default; }
    public NativeError RegisterSource(nint file, out ulong stream)
    { SourceHandle = file; return Register(file, "source registered", 88, out stream); }
    public NativeError RegisterDestination(nint file, out ulong stream)
    { DestinationHandle = file; return Register(file, "destination registered", 89, out stream); }
    private NativeError Register(nint file, string label, ulong token, out ulong stream)
    {
        DuringRegistration?.Invoke(file);
        Ownership.Add(label);
        stream = RegistrationError.Code == 0 ? token : 0;
        return RegistrationError;
    }
    public NativeError ReleaseStream(ulong stream) { Ownership.Add("stream released"); return default; }
    internal void Complete(ulong operation, NativeError error = default, Guid transfer = default, NativeContent content = default)
    {
        var call = Calls[operation];
        Invoke(call.Callback, call.Context, new NativeResult
        { Operation = operation, Error = error, Transfer = NativeMapper.ToNativeUuid(transfer), Content = content });
    }
    internal void Emit(NativeEvent value) => Emit(LastRegistration, value);
    internal static void Emit((nint Callback, nint Context) registration, NativeEvent value)
        => Invoke(registration.Callback, registration.Context, value);
    private static void Invoke<T>(nint callback, nint context, T value) where T : struct
    {
        var storage = Marshal.AllocHGlobal(Marshal.SizeOf<T>());
        try
        {
            Marshal.StructureToPtr(value, storage, false);
            OnNativeStack = true;
            Marshal.GetDelegateForFunctionPointer<Callback>(callback)(context, storage);
        }
        finally { OnNativeStack = false; Marshal.FreeHGlobal(storage); }
    }
    internal Call Latest => Calls.Values.MaxBy(call => call.Operation)!;
}
