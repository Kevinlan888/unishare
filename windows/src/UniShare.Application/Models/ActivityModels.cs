namespace UniShare.Application.Models;

public readonly record struct ActivityId(Guid Value);
public enum ActivityStatus { AwaitingPeer, AwaitingDecision, Transferring, Completed, Rejected, Cancelled, Failed }
public enum ThemePolicy { System, Light, Dark }
public enum LanguagePolicy { System, SimplifiedChinese, English }

// Metadata only. Text, clipboard data and image bytes must stay outside this record.
public sealed record ActivityRecord(
    ActivityId Id,
    DeviceId DeviceId,
    TransferId? TransferId,
    OperationId? OperationId,
    ActivityId? RetryOf,
    ShareDirection Direction,
    ContentKind Kind,
    ShareOrigin Origin,
    string DisplayName,
    string MimeType,
    ulong ByteCount,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    DateTimeOffset? CompletedAt,
    ActivityStatus Status,
    ulong TransferredBytes,
    ShareErrorKind? ErrorKind,
    string? ReceivedFilePath);

public sealed record ActivityQuery(string? Text, DeviceId? Device, ActivityStatus? Status, ShareDirection? Direction);

public sealed record AppSettings
{
    public string DeviceName { get; init; } = Environment.MachineName;
    // Null asks infrastructure to resolve the user's Windows Downloads\UniShare folder.
    public string? ReceiveDirectory { get; init; }
    public int MaxActivitiesPerDevice { get; init; } = 500;
    public int RetentionDays { get; init; } = 30;
    public ThemePolicy Theme { get; init; } = ThemePolicy.System;
    public LanguagePolicy Language { get; init; } = LanguagePolicy.System;
    public bool NotificationsEnabled { get; init; } = true;
    public bool StartupEnabled { get; init; } = false;
    public WindowPlacement? Window { get; init; }
}

public sealed record WindowPlacement(int X, int Y, int Width, int Height, bool IsMaximized);
