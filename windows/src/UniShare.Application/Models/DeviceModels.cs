using System.Collections.Immutable;

namespace UniShare.Application.Models;

public readonly record struct DeviceId(Guid Value);
public readonly record struct RequestId(ulong Value);
public readonly record struct OperationId(ulong Value);

public enum DevicePlatform { Windows = 0, Android = 1, Ios = 2 }
public enum DiscoveryState { Starting, Browsing, NetworkChanged, Stopped }
public enum ConnectionState { Connected, Disconnected }

public sealed record DeviceSnapshot(
    DeviceId Id,
    string Name,
    DevicePlatform Platform,
    ushort ProtocolMajor,
    ushort ProtocolMinor,
    ImmutableArray<string> Addresses,
    ushort Port,
    bool IsTrusted,
    DateTimeOffset LastSeen);

public sealed record SdkOptions
{
    public string DeviceName { get; init; } = Environment.MachineName;
    public uint MaxConnections { get; init; } = 8;
    public uint MaxPendingRequests { get; init; } = 4;
    public ulong MaxBufferBytes { get; init; } = 4UL * 1024 * 1024;
    public ulong MaxTextBytes { get; init; } = 8UL * 1024 * 1024;
    public ulong MaxImageBytes { get; init; } = 100UL * 1024 * 1024;
    public ulong MaxFileBytes { get; init; } = 20UL * 1024 * 1024 * 1024;
    public uint ControlFrameBytes { get; init; } = 64 * 1024;
    public uint DataChunkBytes { get; init; } = 256 * 1024;
    public uint RequestTimeoutSeconds { get; init; } = 60;
    public uint OfflineDebounceSeconds { get; init; } = 5;
}
