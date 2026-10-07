using System.Collections.Immutable;

namespace UniShare.Application.Models;

public readonly record struct TransferId(Guid Value);

public enum ContentKind
{
    Text = 1, Image = 2, File = 3
}
public enum ShareDirection
{
    Incoming, Outgoing
}
public enum ShareOrigin
{
    Direct, Clipboard
}
public enum ReceiveDecision
{
    Prompt, AutoAccept
}

public enum ShareErrorKind
{
    Unknown = 0,
    PermissionDenied = 1,
    DiscoveryFailed = 2,
    VersionMismatch = 3,
    PairingRejected = 4,
    AuthenticationFailed = 5,
    IdentityChanged = 6,
    TransferRejected = 7,
    Cancelled = 8,
    Timeout = 9,
    NetworkLost = 10,
    StorageFailed = 11,
    IntegrityFailed = 12,
    InvalidInput = 13,
    ResourceLimit = 14
}

public sealed record IncomingOffer(
    RequestId RequestId,
    TransferId TransferId,
    DeviceId DeviceId,
    ContentKind Kind,
    string DisplayName,
    string MimeType,
    ulong ByteCount);

public sealed record TransferProgress(TransferId TransferId, ulong TransferredBytes, ulong TotalBytes);

// A send may fail before the SDK registers a transfer ID.
public sealed record TransferTerminal(TransferId? TransferId, ShareFailure? Failure);
public sealed record ShareFailure(ShareErrorKind Kind, string Message, long? PlatformError = null);

// Payloads belong only to the current process, never to ActivityRecord.
public sealed record ShareContent(ContentKind Kind, string? Text, string MimeType, ImmutableArray<byte> ImageBytes);
public sealed record ShareSource(string Path, string DisplayName, string MimeType, ulong ByteCount);
public sealed record PendingSend(OperationId OperationId, Task<TransferTerminal> Completion);

// Infrastructure owns cleanup; disposing releases temporary storage asynchronously.
// The application boundary deliberately exposes paths rather than native handles.
public abstract record PreparedReceive(string StagingPath, string FinalPath) : IAsyncDisposable
{
    public abstract ValueTask DisposeAsync();
}

public abstract record TransientDestination(string Path) : IAsyncDisposable
{
    public abstract ValueTask DisposeAsync();
}

public abstract record TransientSource(ShareSource Source) : IAsyncDisposable
{
    public string Path => Source.Path;
    public abstract ValueTask DisposeAsync();
}

// The private constructor and private-protected discriminator close the hierarchy.
// The discriminator also prevents external records using the generated copy constructor.
public abstract record ShareSdkEvent
{
    private ShareSdkEvent()
    {
    }
    private protected abstract byte EventCase { get; }

    public sealed record DeviceOnline(DeviceSnapshot Device) : ShareSdkEvent
    {
        private protected override byte EventCase => 1;
    }
    public sealed record DeviceUpdated(DeviceSnapshot Device) : ShareSdkEvent
    {
        private protected override byte EventCase => 2;
    }
    public sealed record DeviceOffline(DeviceId Device) : ShareSdkEvent
    {
        private protected override byte EventCase => 3;
    }
    public sealed record DiscoveryFailed(ShareFailure Failure) : ShareSdkEvent
    {
        private protected override byte EventCase => 4;
    }
    public sealed record DiscoveryStateChanged(DiscoveryState State) : ShareSdkEvent
    {
        private protected override byte EventCase => 5;
    }
    public sealed record ConnectionChanged(DeviceId Device, ConnectionState State, ShareFailure? Failure) : ShareSdkEvent
    {
        private protected override byte EventCase => 6;
    }
    public sealed record PairingRequested(RequestId Request, DeviceSnapshot Device, string VerificationCode) : ShareSdkEvent
    {
        private protected override byte EventCase => 7;
    }
    public sealed record PairingCompleted(RequestId Request, DeviceId Device, ShareFailure? Failure) : ShareSdkEvent
    {
        private protected override byte EventCase => 8;
    }
    public sealed record IdentityChanged(DeviceId Device) : ShareSdkEvent
    {
        private protected override byte EventCase => 9;
    }
    public sealed record OfferReceived(IncomingOffer Offer) : ShareSdkEvent
    {
        private protected override byte EventCase => 10;
    }
    public sealed record OutgoingTransferRegistered(OperationId OperationId, TransferId TransferId) : ShareSdkEvent
    {
        private protected override byte EventCase => 11;
    }
    public sealed record ProgressReceived(TransferProgress Progress) : ShareSdkEvent
    {
        private protected override byte EventCase => 12;
    }
    public sealed record TransferCompleted(TransferTerminal Terminal) : ShareSdkEvent
    {
        private protected override byte EventCase => 13;
    }
}
