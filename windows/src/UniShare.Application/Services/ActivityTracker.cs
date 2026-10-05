using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

public sealed class ActivityTracker : ITransferDrain
{
    private readonly IActivityRepository repository;
    private readonly IClock clock;
    private readonly object gate = new();
    private readonly Dictionary<ActivityId, ActivityRecord> activities = [];
    private readonly Dictionary<TransferId, ActivityId> transfers = [];
    private readonly Dictionary<OperationId, ActivityId> operations = [];
    private readonly Dictionary<OperationId, TransferId> earlyRegistrations = [];
    private readonly Dictionary<TransferId, ulong> earlyProgress = [];
    private readonly Dictionary<ActivityId, ActivityId> aliases = [];
    private readonly Dictionary<DeviceId, int> loadingDevices = [];
    private readonly HashSet<ActivityId> deferredIncoming = [];
    // Process-only content is deliberately separate from the repository contract.
    private readonly Dictionary<ActivityId, ShareContent> previews = [];
    private readonly HashSet<ActivityId> active = [];
    private readonly List<Task> pendingWrites = [];
    private Task writeTail = Task.CompletedTask;
    private TaskCompletionSource idle = NewIdle();
    private bool blocked;

    public ActivityTracker(IActivityRepository repository, IClock clock)
    {
        this.repository = repository ?? throw new ArgumentNullException(nameof(repository));
        this.clock = clock ?? throw new ArgumentNullException(nameof(clock));
        idle.SetResult();
    }

    // Notifications signal that readers should obtain a fresh snapshot. No UI
    // collection or dispatcher is owned here, and callbacks run outside the gate.
    public event EventHandler? Changed;
    public int ActiveCount { get { lock (gate) return active.Count; } }
    public bool HasActiveTransfers => ActiveCount != 0;

    public IReadOnlyList<ActivityRecord> GetDeviceSnapshot(DeviceId device)
    {
        lock (gate) return activities.Values.Where(value => value.DeviceId == device && !deferredIncoming.Contains(value.Id))
            .OrderBy(value => value.CreatedAt).ThenBy(value => value.Id.Value).ToArray();
    }

    public ShareContent? GetPreview(ActivityId activity)
    {
        lock (gate) return previews.GetValueOrDefault(activity);
    }

    public ActivityRecord BeginOutgoing(DeviceId device, OperationId operation, ContentKind kind,
        string displayName, string mimeType, ulong byteCount, ShareOrigin origin = ShareOrigin.Direct,
        ShareContent? preview = null)
    {
        ActivityRecord record;
        lock (gate) record = BeginOutgoingCore(device, operation, kind, displayName, mimeType, byteCount, origin, preview, null);
        Changed?.Invoke(this, EventArgs.Empty);
        return record;
    }

    private ActivityRecord BeginOutgoingCore(DeviceId device, OperationId operation, ContentKind kind,
        string displayName, string mimeType, ulong byteCount, ShareOrigin origin, ShareContent? preview, ActivityId? retryOf)
    {
        RequireAdmission();
        if (operation.Value == 0) throw new ArgumentOutOfRangeException(nameof(operation));
        if (operations.ContainsKey(operation)) throw new InvalidOperationException("Operation is already tracked.");
        if (preview is not null && preview.Kind != kind) throw new ArgumentException("Preview kind does not match activity.", nameof(preview));
        var earlyTransfer = earlyRegistrations.TryGetValue(operation, out var registration) ? registration : (TransferId?)null;
        if (earlyTransfer is { } registered && transfers.ContainsKey(registered))
            throw new InvalidOperationException("Transfer is already assigned to another activity.");
        var now = clock.UtcNow;
        var record = new ActivityRecord(new(Guid.NewGuid()), device, earlyTransfer, operation, retryOf,
            ShareDirection.Outgoing, kind, origin, MetadataName(kind, displayName), mimeType, byteCount,
            now, now, null, ActivityStatus.AwaitingPeer, 0, null, null);
        if (earlyTransfer is { } progressing && earlyProgress.Remove(progressing, out var bytes))
            record = record with { Status = ActivityStatus.Transferring, TransferredBytes = Math.Min(bytes, byteCount) };
        AddActive(record);
        operations.Add(operation, record.Id);
        earlyRegistrations.Remove(operation);
        if (earlyTransfer is { } transfer) transfers.Add(transfer, record.Id);
        if (preview is not null) previews.Add(record.Id, preview);
        Save(record);
        return record;
    }

    public ActivityRecord AddIncomingOffer(IncomingOffer offer, ShareOrigin origin = ShareOrigin.Direct)
    {
        ArgumentNullException.ThrowIfNull(offer);
        ActivityRecord record;
        lock (gate)
        {
            if (transfers.TryGetValue(offer.TransferId, out var existing))
            {
                record = activities[existing];
                if (record.Direction != ShareDirection.Incoming || record.DeviceId != offer.DeviceId)
                    throw new InvalidOperationException("Transfer is already assigned to another activity.");
                return record;
            }
            RequireAdmission();
            var now = clock.UtcNow;
            record = new(new(Guid.NewGuid()), offer.DeviceId, offer.TransferId, null, null,
                ShareDirection.Incoming, offer.Kind, origin, MetadataName(offer.Kind, offer.DisplayName),
                offer.MimeType, offer.ByteCount, now, now, null, ActivityStatus.AwaitingDecision, 0, null, null);
            AddActive(record);
            // Until history returns, an offer may be a replay of an existing
            // durable transfer. Do not publish or persist its provisional ID.
            if (loadingDevices.ContainsKey(offer.DeviceId)) deferredIncoming.Add(record.Id);
            transfers.Add(offer.TransferId, record.Id);
            Save(record);
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return record;
    }

    public bool BindTransfer(OperationId operation, TransferId transfer)
    {
        bool changed;
        lock (gate)
        {
            if (operations.TryGetValue(operation, out var activity)) changed = BindCore(activity, transfer);
            else
            {
                if (operation.Value == 0) throw new ArgumentOutOfRangeException(nameof(operation));
                if (blocked) return false;
                if (transfers.ContainsKey(transfer) ||
                    (earlyRegistrations.TryGetValue(operation, out var existing) && existing != transfer) ||
                    earlyRegistrations.Any(value => value.Key != operation && value.Value == transfer))
                    throw new InvalidOperationException("Transfer is already assigned to another activity.");
                earlyRegistrations[operation] = transfer;
                return false;
            }
        }
        if (changed) Changed?.Invoke(this, EventArgs.Empty);
        return changed;
    }

    public bool BindTransfer(ActivityId activity, TransferId transfer)
    {
        bool changed;
        lock (gate) changed = BindCore(activity, transfer);
        if (changed) Changed?.Invoke(this, EventArgs.Empty);
        return changed;
    }

    private bool BindCore(ActivityId activity, TransferId transfer)
    {
        activity = ResolveActivity(activity);
        var record = RequireActivity(activity);
        if (ActivityTransition.IsTerminal(record.Status)) return false;
        ValidateBinding(record, transfer);
        if (record.TransferId == transfer) return false;
        transfers[transfer] = activity;
        Save(record with { TransferId = transfer, UpdatedAt = UpdateTime(record) });
        return true;
    }

    private void ValidateBinding(ActivityRecord record, TransferId transfer)
    {
        if ((record.TransferId is { } registered && registered != transfer) ||
            (transfers.TryGetValue(transfer, out var owner) && owner != record.Id))
            throw new InvalidOperationException("Transfer is already assigned to another activity.");
    }

    public bool ApplyProgress(TransferProgress progress)
    {
        ArgumentNullException.ThrowIfNull(progress);
        lock (gate)
        {
            if (!transfers.TryGetValue(progress.TransferId, out var id))
            {
                if (earlyRegistrations.ContainsValue(progress.TransferId))
                    earlyProgress[progress.TransferId] = Math.Max(earlyProgress.GetValueOrDefault(progress.TransferId), progress.TransferredBytes);
                return false;
            }
            var record = activities[id];
            if (!active.Contains(id) || ActivityTransition.IsTerminal(record.Status)) return false;
            var bytes = Math.Max(record.TransferredBytes, Math.Min(progress.TransferredBytes, record.ByteCount));
            if (record.Status == ActivityStatus.Transferring && bytes == record.TransferredBytes) return false;
            if (record.Status != ActivityStatus.Transferring && !ActivityTransition.CanTransition(record.Status, ActivityStatus.Transferring)) return false;
            Save(record with { Status = ActivityStatus.Transferring, TransferredBytes = bytes, UpdatedAt = UpdateTime(record) });
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    // No transfer ID means the result is ambiguous. The operation's completion
    // owner must use the ActivityId overload; never guess the most recent send.
    public bool Complete(TransferTerminal terminal, string? receivedFilePath = null)
    {
        ArgumentNullException.ThrowIfNull(terminal);
        if (terminal.TransferId is not { } transfer) throw new ArgumentException("A transfer ID or explicit activity ID is required.", nameof(terminal));
        bool changed;
        lock (gate) changed = transfers.TryGetValue(transfer, out var id) && CompleteCore(id, terminal, receivedFilePath);
        if (changed) Changed?.Invoke(this, EventArgs.Empty);
        return changed;
    }

    public bool Complete(ActivityId activity, TransferTerminal terminal, string? receivedFilePath = null)
    {
        ArgumentNullException.ThrowIfNull(terminal);
        bool changed;
        lock (gate) changed = CompleteCore(activity, terminal, receivedFilePath);
        if (changed) Changed?.Invoke(this, EventArgs.Empty);
        return changed;
    }

    private bool CompleteCore(ActivityId activity, TransferTerminal terminal, string? receivedFilePath)
    {
        activity = ResolveActivity(activity);
        var record = RequireActivity(activity);
        var status = ActivityTransition.TerminalStatus(terminal.Failure);
        if (!active.Contains(activity) || !ActivityTransition.CanTransition(record.Status, status)) return false;
        if (terminal.TransferId is { } transfer)
        {
            ValidateBinding(record, transfer);
            transfers[transfer] = activity;
        }
        var now = UpdateTime(record);
        Save(record with
        {
            TransferId = terminal.TransferId ?? record.TransferId,
            Status = status,
            UpdatedAt = now,
            CompletedAt = now,
            TransferredBytes = status == ActivityStatus.Completed ? record.ByteCount : record.TransferredBytes,
            ErrorKind = terminal.Failure?.Kind,
            ReceivedFilePath = status == ActivityStatus.Completed && record.Direction == ShareDirection.Incoming && record.Kind == ContentKind.File
                ? receivedFilePath : null
        });
        active.Remove(activity);
        if (record.OperationId is { } operation) earlyRegistrations.Remove(operation);
        if (active.Count == 0)
        {
            if (blocked) operations.Clear();
            idle.TrySetResult();
        }
        return true;
    }

    public bool CanRetry(ActivityId activity)
    {
        lock (gate) return activities.TryGetValue(activity, out var record) && ActivityTransition.IsRetryable(record);
    }

    public ActivityRecord RetryFrom(ActivityId activity, OperationId operation, ShareContent? preview = null)
    {
        ActivityRecord retry;
        lock (gate)
        {
            var previous = RequireActivity(activity);
            if (!ActivityTransition.IsRetryable(previous)) throw new InvalidOperationException("Activity is not retryable.");
            retry = BeginOutgoingCore(previous.DeviceId, operation, previous.Kind, previous.DisplayName, previous.MimeType,
                previous.ByteCount, previous.Origin, preview ?? previews.GetValueOrDefault(activity), previous.Id);
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return retry;
    }

    public void Apply(ShareSdkEvent value)
    {
        ArgumentNullException.ThrowIfNull(value);
        switch (value)
        {
            case ShareSdkEvent.OfferReceived offer: AddIncomingOffer(offer.Offer); break;
            case ShareSdkEvent.OutgoingTransferRegistered registered: BindTransfer(registered.OperationId, registered.TransferId); break;
            case ShareSdkEvent.ProgressReceived progress: ApplyProgress(progress.Progress); break;
            case ShareSdkEvent.TransferCompleted { Terminal.TransferId: not null } completed: ApplyNativeTerminal(completed.Terminal); break;
            case ShareSdkEvent.DiscoveryStateChanged { State: DiscoveryState.Stopped }:
                lock (gate)
                {
                    // The SDK retires old-generation event delivery at Stop.
                    // Unadmitted registrations and process operation IDs cannot
                    // be reused to bind an activity in the next generation.
                    earlyRegistrations.Clear();
                    earlyProgress.Clear();
                    operations.Clear();
                }
                break;
        }
    }

    private void ApplyNativeTerminal(TransferTerminal terminal)
    {
        bool changed;
        lock (gate)
        {
            var transfer = terminal.TransferId!.Value;
            earlyProgress.Remove(transfer);
            foreach (var operation in earlyRegistrations.Where(value => value.Value == transfer).Select(value => value.Key).ToArray())
                earlyRegistrations.Remove(operation);
            if (!transfers.TryGetValue(transfer, out var activity)) return;
            // Native success means bytes arrived, not that publication/read of
            // received content succeeded. The receive owner finalizes incoming
            // success after publication, or reports its storage/read failure.
            if (terminal.Failure is null && activities[activity].Direction == ShareDirection.Incoming) return;
            changed = CompleteCore(activity, terminal, null);
        }
        if (changed) Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken = default)
    {
        var changed = false;
        lock (gate) loadingDevices[device] = loadingDevices.GetValueOrDefault(device) + 1;
        try
        {
            var loaded = await repository.LoadDeviceAsync(device, cancellationToken).ConfigureAwait(false);
            lock (gate)
            {
                foreach (var record in loaded)
                {
                    if (record.DeviceId != device || activities.ContainsKey(record.Id)) continue;
                    if (record.TransferId is { } transfer && transfers.TryGetValue(transfer, out var owner))
                    {
                        // A known live record wins over stale history. A replay
                        // admitted during this load instead adopts the durable
                        // identity, retaining an alias for its receive owner.
                        if (!deferredIncoming.Contains(owner)) continue;
                        var replay = activities[owner];
                        if (replay.DeviceId != device || replay.Direction != record.Direction) continue;
                        aliases[owner] = record.Id;
                        activities.Remove(owner);
                        deferredIncoming.Remove(owner);
                        var wasActive = active.Remove(owner);
                        var canonical = ActivityTransition.IsTerminal(record.Status) ? record : record with
                        {
                            Status = replay.Status == ActivityStatus.AwaitingDecision && record.Status == ActivityStatus.Transferring
                                ? record.Status : replay.Status,
                            TransferredBytes = Math.Max(record.TransferredBytes, Math.Min(replay.TransferredBytes, record.ByteCount)),
                            UpdatedAt = replay.UpdatedAt > record.UpdatedAt ? replay.UpdatedAt : record.UpdatedAt,
                            CompletedAt = replay.CompletedAt,
                            ErrorKind = replay.ErrorKind,
                            ReceivedFilePath = replay.ReceivedFilePath
                        };
                        activities.Add(canonical.Id, canonical);
                        deferredIncoming.Add(canonical.Id);
                        transfers[transfer] = canonical.Id;
                        if (wasActive && !ActivityTransition.IsTerminal(canonical.Status)) active.Add(canonical.Id);
                    }
                    else
                    {
                        // Historical pending records are not live native work.
                        // No payload or process operation index is reconstructed.
                        activities.Add(record.Id, record);
                        if (record.TransferId is { } registered) transfers.Add(registered, record.Id);
                    }
                    changed = true;
                }
            }
        }
        finally
        {
            lock (gate)
            {
                if (--loadingDevices[device] == 0)
                {
                    loadingDevices.Remove(device);
                    foreach (var id in deferredIncoming.Where(id => activities[id].DeviceId == device).ToArray())
                    {
                        deferredIncoming.Remove(id);
                        Save(activities[id]);
                        changed = true;
                    }
                }
                if (active.Count == 0)
                {
                    if (blocked) operations.Clear();
                    idle.TrySetResult();
                }
            }
            if (changed) Changed?.Invoke(this, EventArgs.Empty);
        }
    }

    public void BlockNewTransfers()
    {
        lock (gate)
        {
            blocked = true;
            earlyRegistrations.Clear();
            earlyProgress.Clear();
            // Explicit SDK Stop suppresses its event generation. Session pause
            // therefore retires operation mappings through block-and-drain too.
            if (active.Count == 0) operations.Clear();
        }
    }
    public void AllowNewTransfers() { lock (gate) blocked = false; }

    public Task WaitForIdleAsync(CancellationToken cancellationToken)
    {
        lock (gate) return idle.Task.WaitAsync(cancellationToken);
    }

    // Covers every write accepted before this call. Failed writes stay visible
    // on later flushes; successful writes can be retired without losing failures.
    public async Task SavePendingAsync(CancellationToken cancellationToken = default)
    {
        Task[] writes;
        lock (gate) writes = pendingWrites.ToArray();
        try { await Task.WhenAll(writes).WaitAsync(cancellationToken).ConfigureAwait(false); }
        finally { lock (gate) pendingWrites.RemoveAll(value => value.IsCompletedSuccessfully); }
    }

    private void Save(ActivityRecord record)
    {
        activities[record.Id] = record;
        if (deferredIncoming.Contains(record.Id)) return;
        var previous = writeTail;
        writeTail = Task.Run(async () =>
        {
            // One failed write must not prevent later metadata from reaching
            // storage. Its fault remains in pendingWrites for the flush caller.
            try { await previous.ConfigureAwait(false); }
            catch { }
            await repository.UpsertAsync(record, CancellationToken.None).ConfigureAwait(false);
        });
        pendingWrites.Add(writeTail);
    }

    private ActivityId ResolveActivity(ActivityId activity)
    {
        while (aliases.TryGetValue(activity, out var canonical)) activity = canonical;
        return activity;
    }
    private ActivityRecord RequireActivity(ActivityId activity) => activities.TryGetValue(ResolveActivity(activity), out var record)
        ? record : throw new ArgumentException("Activity is not known.", nameof(activity));
    private void RequireAdmission()
    {
        if (blocked) throw new InvalidOperationException("New transfers are blocked.");
    }
    private void AddActive(ActivityRecord record)
    {
        if (active.Count == 0) idle = NewIdle();
        activities.Add(record.Id, record);
        active.Add(record.Id);
    }
    private DateTimeOffset UpdateTime(ActivityRecord record) => clock.UtcNow > record.UpdatedAt ? clock.UtcNow : record.UpdatedAt;
    private static TaskCompletionSource NewIdle() => new(TaskCreationOptions.RunContinuationsAsynchronously);
    private static string MetadataName(ContentKind kind, string displayName) => kind switch
    {
        ContentKind.Text => "Text",
        ContentKind.Image => "Image",
        _ => Path.GetFileName(displayName.Replace('\\', '/'))
    };
}
