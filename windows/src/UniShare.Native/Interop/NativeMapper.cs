using System.Collections.Immutable;
using System.Runtime.InteropServices;
using UniShare.Application.Models;

namespace UniShare.Native.Interop;

internal static class NativeMapper
{
    // Bounds are checked before pointer arithmetic or allocation, including nested spans.
    internal const ulong MaximumSpanBytes = 100UL * 1024 * 1024;
    internal const ulong MaximumArrayItems = 65536;

    internal static void RequireAbiVersion(uint version)
    {
        if (version != 1)
            throw new NotSupportedException("Only UniShare ABI v1 is supported.");
    }

    internal static Guid ReadUuid(byte[] bytes) => new(bytes, bigEndian: true);
    internal static byte[] WriteUuid(Guid value) => value.ToByteArray(bigEndian: true);
    internal static unsafe Guid ToGuid(NativeUuid value) => new(new ReadOnlySpan<byte>(value.Bytes, 16), bigEndian: true);
    internal static unsafe NativeUuid ToNativeUuid(Guid value)
    {
        NativeUuid result = default;
        value.TryWriteBytes(new Span<byte>(result.Bytes, 16), bigEndian: true, out _);
        return result;
    }

    internal static byte[] CopyBytes(nint pointer, ulong length)
    {
        if (length > MaximumSpanBytes)
            throw new ArgumentOutOfRangeException(nameof(length));
        if (length == 0)
            return [];
        if (pointer == 0)
            throw new ArgumentException("A nonempty native span requires storage.", nameof(pointer));
        var bytes = new byte[(int)length];
        Marshal.Copy(pointer, bytes, 0, bytes.Length);
        return bytes;
    }

    internal static string CopyText(nint pointer, ulong length) => NativeUtf8.Encoding.GetString(CopyBytes(pointer, length));
    internal static string Text(NativeSpan span) => CopyText(span.Data, span.Length);

    internal static string[] CopyAddresses(nint pointer, ulong count)
    {
        ValidateArray(pointer, count);
        var result = new string[(int)count];
        for (var i = 0; i < result.Length; i++)
            result[i] = Text(Marshal.PtrToStructure<NativeSpan>(pointer + checked(i * Marshal.SizeOf<NativeSpan>())));
        return result;
    }

    internal static void ValidateArray(nint pointer, ulong count)
    {
        if (count > MaximumArrayItems)
            throw new ArgumentOutOfRangeException(nameof(count));
        if (pointer == 0 && count != 0)
            throw new ArgumentException("A nonempty native array requires storage.", nameof(pointer));
    }

    internal static ShareFailure? MapError(uint code, bool hasPlatformError, long platformError)
    {
        if (code == 0)
            return null;
        var kind = code <= 14 ? (ShareErrorKind)code : ShareErrorKind.Unknown;
        // Fixed metadata-only message: never expose borrowed native payloads in diagnostics.
        return new ShareFailure(kind, kind == ShareErrorKind.Unknown ? "Unknown native error." : $"UniShare error: {kind}.", hasPlatformError ? platformError : null);
    }

    internal static ShareFailure? Failure(NativeError error) => MapError(error.Code, error.HasPlatformError != 0, error.PlatformError);
    internal static DiagnosticRecord Diagnostic(NativeDiagnosticEvent value) => new(
        value.Stage is >= 1 and <= 19 ? (NativeDiagnosticStage)value.Stage : NativeDiagnosticStage.Unknown,
        value.Outcome is >= 1 and <= 4 ? (NativeDiagnosticOutcome)value.Outcome : NativeDiagnosticOutcome.Unknown,
        value.Direction <= 2 ? (DiagnosticDirection)value.Direction : DiagnosticDirection.Unknown,
        value.ContentType <= 3 ? (DiagnosticContentKind)value.ContentType : DiagnosticContentKind.Unknown,
        value.Sequence,
        value.Error.Code == 0 ? null : value.Error.Code <= 14 ? (ShareErrorKind)value.Error.Code : ShareErrorKind.Unknown,
        value.Error.HasPlatformError != 0 ? value.Error.PlatformError : null);
    internal static ContentKind Kind(uint value) => value is >= 1 and <= 3 ? (ContentKind)value : throw new ArgumentOutOfRangeException(nameof(value));

    internal static DeviceSnapshot Device(NativeDevice value) => new(
        new DeviceId(ToGuid(value.Id)), Text(value.Name),
        value.Platform <= 2 ? (DevicePlatform)value.Platform : throw new ArgumentOutOfRangeException(nameof(value)),
        value.ProtocolMajor, value.ProtocolMinor, CopyAddresses(value.Addresses, value.AddressCount).ToImmutableArray(),
        value.Port, value.Paired != 0, DateTimeOffset.FromUnixTimeMilliseconds(value.LastSeenUnixMs));

    internal static ShareContent Content(NativeContent value) => new(Kind(value.Type),
        value.Type == 1 ? Text(value.Text) : null, Text(value.MimeType),
        value.Type == 2 ? CopyBytes(value.Image.Data, value.Image.Length).ToImmutableArray() : ImmutableArray<byte>.Empty);

    internal static TransferTerminal Terminal(NativeResult value)
    {
        var transfer = ToGuid(value.Transfer);
        return new(transfer == Guid.Empty ? null : new TransferId(transfer), Failure(value.Error));
    }

    internal static ShareSdkEvent Event(NativeEvent value) => value.Kind switch
    {
        1 => new ShareSdkEvent.DeviceOnline(Device(value.Device)),
        2 => new ShareSdkEvent.DeviceUpdated(Device(value.Device)),
        3 => new ShareSdkEvent.DeviceOffline(new(ToGuid(value.Device.Id))),
        4 => new ShareSdkEvent.DiscoveryFailed(Failure(value.Error) ?? new(ShareErrorKind.Unknown, "Discovery failed.")),
        5 => new ShareSdkEvent.DiscoveryStateChanged(value.State <= 3 ? (DiscoveryState)value.State : throw new ArgumentOutOfRangeException(nameof(value))),
        6 => new ShareSdkEvent.ConnectionChanged(new(ToGuid(value.Device.Id)), value.State <= 1 ? (ConnectionState)value.State : throw new ArgumentOutOfRangeException(nameof(value)), Failure(value.Error)),
        7 => new ShareSdkEvent.PairingRequested(new(value.Request), Device(value.Device), Text(value.VerificationCode)),
        8 => new ShareSdkEvent.PairingCompleted(new(value.Request), new(ToGuid(value.Device.Id)), Failure(value.Error)),
        9 => new ShareSdkEvent.IdentityChanged(new(ToGuid(value.Device.Id))),
        10 => new ShareSdkEvent.OfferReceived(new(new(value.Request), new(ToGuid(value.Transfer)), new(ToGuid(value.Device.Id)), Kind(value.ContentType), Text(value.DisplayName), Text(value.MimeType), value.ByteCount)),
        11 => new ShareSdkEvent.ProgressReceived(new(new(ToGuid(value.Transfer)), value.Transferred, value.ByteCount)),
        12 => new ShareSdkEvent.TransferCompleted(new(new TransferId(ToGuid(value.Transfer)), Failure(value.Error))),
        _ => throw new ArgumentOutOfRangeException(nameof(value), "Unknown native event kind.")
    };
}
