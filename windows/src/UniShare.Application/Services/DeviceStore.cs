using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

// Entry identity is stable. Consumers can only read atomically replaced metadata;
// the UI layer is responsible for dispatching its own binding notifications.
public sealed class DeviceEntry
{
    internal sealed record Metadata(DeviceSnapshot? Snapshot, bool Online, bool Trusted, bool Blocked, bool Connected);
    private Metadata metadata = new(null, false, false, false, false);
    internal DeviceEntry(DeviceId id) => Id = id;
    internal Metadata Data { get => Volatile.Read(ref metadata); set => Volatile.Write(ref metadata, value); }
    public DeviceId Id { get; }
    public DeviceSnapshot? Snapshot
    {
        get
        {
            var value = Data;
            return value.Snapshot is { } snapshot && snapshot.IsTrusted != value.Trusted
                ? snapshot with { IsTrusted = value.Trusted }
                : value.Snapshot;
        }
    }
    public string Name => Snapshot?.Name ?? Id.Value.ToString();
    public bool IsOnline => Data.Online;
    public bool IsTrusted => Data.Trusted;
    public bool IsBlocked => Data.Blocked;
    public bool IsConnected => Data.Connected;
    public bool CanTransfer { get { var value = Data; return value.Online && !value.Blocked; } }
}

public sealed class DeviceStore : IDisposable
{
    private readonly IShareSdkClient sdk;
    private readonly object gate = new();
    private readonly Dictionary<DeviceId, DeviceEntry> entries = [];
    private readonly Dictionary<DeviceId, long> identityVersions = [];
    private readonly HashSet<DeviceId> forgetting = [];
    private readonly HashSet<DeviceId> forgotten = [];
    private DeviceId? selected;
    private bool disposed;

    public DeviceStore(IShareSdkClient sdk)
    {
        this.sdk = sdk ?? throw new ArgumentNullException(nameof(sdk));
        sdk.EventReceived += OnEvent;
    }

    public IReadOnlyList<DeviceEntry> Devices { get { lock (gate) return SortedEntries().ToArray(); } }
    public IReadOnlyList<DeviceEntry> TrustedDevices { get { lock (gate) return SortedEntries().Where(value => value.IsTrusted).ToArray(); } }
    public IReadOnlyList<DeviceEntry> NearbyDevices { get { lock (gate) return SortedEntries().Where(value => !value.IsTrusted).ToArray(); } }
    public DeviceEntry? SelectedDevice
    {
        get { lock (gate) return selected is { } id ? entries[id] : null; }
        set
        {
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this);
                if (value is not null && (!entries.TryGetValue(value.Id, out var entry) || !ReferenceEquals(entry, value)))
                    throw new ArgumentException("Selection must belong to this device store.", nameof(value));
                selected = value?.Id;
            }
        }
    }

    private IEnumerable<DeviceEntry> SortedEntries() => entries.Values
        .Where(value => value.IsOnline || value.IsTrusted || value.IsBlocked)
        .OrderByDescending(value => value.IsTrusted)
        .ThenBy(value => value.Name, StringComparer.OrdinalIgnoreCase)
        .ThenBy(value => value.Id.Value);

    private DeviceEntry GetOrAdd(DeviceId id)
    {
        if (!entries.TryGetValue(id, out var entry)) entries.Add(id, entry = new(id));
        return entry;
    }

    public void Refresh()
    {
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            // Synchronous SDK snapshots and event reconciliation share the store
            // gate. No lock is held across a native asynchronous operation.
            var devices = sdk.GetDevices();
            var trusted = sdk.GetTrustedDevices();
            var online = devices.Select(value => value.Id).ToHashSet();
            foreach (var device in devices) Update(device);
            foreach (var id in trusted) GetOrAdd(id);
            foreach (var entry in entries.Values)
            {
                var data = entry.Data;
                var isTrusted = trusted.Contains(entry.Id) && !forgotten.Contains(entry.Id);
                entry.Data = data with
                {
                    Online = online.Contains(entry.Id),
                    Trusted = forgetting.Contains(entry.Id) ? data.Trusted : isTrusted,
                    Connected = online.Contains(entry.Id) && data.Connected
                };
            }
        }
    }

    private void Update(DeviceSnapshot device)
    {
        var entry = GetOrAdd(device.Id);
        var data = entry.Data;
        entry.Data = data with
        {
            Snapshot = device,
            Online = true,
            Trusted = forgetting.Contains(device.Id) ? data.Trusted : device.IsTrusted && !forgotten.Contains(device.Id)
        };
    }

    private void OnEvent(object? sender, ShareSdkEvent value) => Apply(value);

    public void Apply(ShareSdkEvent value)
    {
        ArgumentNullException.ThrowIfNull(value);
        lock (gate)
        {
            if (disposed) return;
            switch (value)
            {
                case ShareSdkEvent.DeviceOnline online:
                    Update(online.Device);
                    break;
                case ShareSdkEvent.DeviceUpdated updated:
                    Update(updated.Device);
                    break;
                case ShareSdkEvent.DeviceOffline offline:
                    if (entries.TryGetValue(offline.Device, out var offlineEntry))
                        offlineEntry.Data = offlineEntry.Data with { Online = false, Connected = false };
                    break;
                case ShareSdkEvent.ConnectionChanged connection:
                    var connectedEntry = GetOrAdd(connection.Device);
                    connectedEntry.Data = connectedEntry.Data with { Connected = connection.State == ConnectionState.Connected };
                    if (connection.Failure?.Kind == ShareErrorKind.IdentityChanged) Block(connection.Device);
                    break;
                case ShareSdkEvent.IdentityChanged changed:
                    Block(changed.Device);
                    break;
                case ShareSdkEvent.PairingCompleted { Failure: null } paired:
                    // Completion/event queues are independent: even a successful
                    // pairing can arrive after a later forget has removed trust.
                    // Only the SDK's current persisted trust can clear the marker.
                    if (!forgetting.Contains(paired.Device) && sdk.GetTrustedDevices().Contains(paired.Device))
                    {
                        forgotten.Remove(paired.Device);
                        var pairedEntry = GetOrAdd(paired.Device);
                        pairedEntry.Data = pairedEntry.Data with { Trusted = true };
                    }
                    break;
                case ShareSdkEvent.PairingCompleted { Failure.Kind: ShareErrorKind.IdentityChanged } failed:
                    Block(failed.Device);
                    break;
            }
        }
    }

    private void Block(DeviceId id)
    {
        var entry = GetOrAdd(id);
        entry.Data = entry.Data with { Blocked = true, Connected = false };
        identityVersions[id] = identityVersions.GetValueOrDefault(id) + 1;
    }

    public async Task ForgetDeviceAsync(DeviceId id, CancellationToken cancellationToken = default)
    {
        long version;
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (!entries.ContainsKey(id)) throw new ArgumentException("Device is not known.", nameof(id));
            if (!forgetting.Add(id)) throw new InvalidOperationException("Device is already being forgotten.");
            version = identityVersions.GetValueOrDefault(id);
        }
        try
        {
            // Native cancellation abandons a wait, not the accepted operation.
            // Once submitted, reconcile its completion before clearing the guard.
            cancellationToken.ThrowIfCancellationRequested();
            await sdk.ForgetDeviceAsync(id, CancellationToken.None).ConfigureAwait(false);
            lock (gate)
            {
                forgotten.Add(id);
                var entry = entries[id];
                entry.Data = entry.Data with
                {
                    Trusted = false,
                    Blocked = identityVersions.GetValueOrDefault(id) != version,
                    Connected = false
                };
            }
        }
        finally { lock (gate) forgetting.Remove(id); }
    }

    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return;
            disposed = true;
            sdk.EventReceived -= OnEvent;
        }
    }
}
