using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Services;

public sealed class ActivityTracker : ITransferDrain
{
    private static readonly TimeSpan ProgressPublishInterval = TimeSpan.FromMilliseconds(200);
    private readonly IActivityRepository repository;
    private readonly IClock clock;
    private readonly object gate = new();
    private readonly Dictionary<ActivityId, ActivityRecord> activities = [];
    private readonly Dictionary<TransferId, ActivityId> transfers = [];
    private readonly Dictionary<OperationId, ActivityId> operations = [];
    private readonly Dictionary<OperationId, TransferId> earlyRegistrations = [];
    private readonly Dictionary<TransferId, ulong> earlyProgress = [];
    private readonly Dictionary<TransferId, DateTimeOffset> progressPublications = [];
    private readonly Dictionary<ActivityId, ActivityId> aliases = [];
    private readonly HashSet<DeviceId> loadingDevices = [];
    private readonly Dictionary<DeviceId, Task> historyLoads = [];
    private readonly HashSet<DeviceId> historyReady = [];
    private readonly HashSet<ActivityId> deferredIncoming = [];
    // Process-only content is deliberately separate from the repository contract.
    private readonly Dictionary<ActivityId, ShareContent> previews = [];
    private readonly HashSet<ActivityId> active = [];
    private readonly HashSet<OutgoingReservation> reservations = [];
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
    public int ActiveCount
    {
        get
        {
            lock (gate)
                return active.Count + reservations.Count;
        }
    }
    public bool HasActiveTransfers => ActiveCount != 0;

    public IReadOnlyList<ActivityRecord> GetDeviceSnapshot(DeviceId device)
    {
        lock (gate)
            return activities.Values.Where(value => value.DeviceId == device && !deferredIncoming.Contains(value.Id))
            .OrderBy(value => value.CreatedAt).ThenBy(value => value.Id.Value).ToArray();
    }

    public ShareContent? GetPreview(ActivityId activity)
    {
        lock (gate)
            return previews.GetValueOrDefault(ResolveActivity(activity));
    }

    public void PublishIncomingPreview(ActivityId activity, ShareContent preview)
    {
        ArgumentNullException.ThrowIfNull(preview);
        lock (gate)
        {
            activity = ResolveActivity(activity);
            var record = RequireActivity(activity);
            if (record.Direction != ShareDirection.Incoming || preview.Kind != record.Kind)
                throw new ArgumentException("Preview does not match incoming activity.", nameof(preview));
            previews[activity] = preview;
        }
        NotifyChanged();
    }

    public OutgoingReservation ReserveOutgoing()
    {
        lock (gate)
        {
            RequireAdmission();
            if (active.Count == 0 && reservations.Count == 0)
                idle = NewIdle();
            var reservation = new OutgoingReservation(this);
            reservations.Add(reservation);
            return reservation;
        }
    }

    private ActivityRecord CommitReservation(OutgoingReservation reservation, DeviceId device, OperationId operation,
        ContentKind kind, string name, string mime, ulong bytes, ShareOrigin origin, ShareContent? preview, ActivityId? retryOf)
    {
        ActivityRecord record;
        lock (gate)
        {
            if (!reservations.Contains(reservation))
                throw new InvalidOperationException("Outgoing reservation is already released.");
            if (retryOf is { } previousId)
            {
                var previous = RequireActivity(previousId);
                if (!ActivityTransition.IsRetryable(previous))
                    throw new InvalidOperationException("Activity is not retryable.");
                record = BeginOutgoingCore(previous.DeviceId, operation, previous.Kind, previous.DisplayName, previous.MimeType,
                    previous.ByteCount, previous.Origin, preview ?? previews.GetValueOrDefault(previousId), previous.Id, reserved: true);
            }
            else
                record = BeginOutgoingCore(device, operation, kind, name, mime, bytes, origin, preview, null, reserved: true);
            reservations.Remove(reservation);
            if (blocked && reservations.Count == 0)
            {
                earlyRegistrations.Clear();
                earlyProgress.Clear();
            }
        }
        NotifyChanged();
        return record;
    }

    private void AbortReservation(OutgoingReservation reservation)
    {
        lock (gate)
        {
            if (!reservations.Remove(reservation))
                return;
            if (blocked && reservations.Count == 0)
            {
                earlyRegistrations.Clear();
                earlyProgress.Clear();
            }
            if (active.Count == 0 && reservations.Count == 0)
            {
                if (blocked)
                    operations.Clear();
                idle.TrySetResult();
            }
        }
    }

    public sealed class OutgoingReservation : IDisposable
    {
        private readonly ActivityTracker owner;
        internal OutgoingReservation(ActivityTracker owner) => this.owner = owner;
        public ActivityRecord Commit(DeviceId device, OperationId operation, ContentKind kind, string displayName, string mimeType,
            ulong byteCount, ShareOrigin origin = ShareOrigin.Direct, ShareContent? preview = null, ActivityId? retryOf = null) =>
            owner.CommitReservation(this, device, operation, kind, displayName, mimeType, byteCount, origin, preview, retryOf);
        public void Dispose() => owner.AbortReservation(this);
    }

    public ActivityRecord BeginOutgoing(DeviceId device, OperationId operation, ContentKind kind,
        string displayName, string mimeType, ulong byteCount, ShareOrigin origin = ShareOrigin.Direct,
        ShareContent? preview = null)
    {
        ActivityRecord record;
        lock (gate)
            record = BeginOutgoingCore(device, operation, kind, displayName, mimeType, byteCount, origin, preview, null);
        NotifyChanged();
        return record;
    }

    private ActivityRecord BeginOutgoingCore(DeviceId device, OperationId operation, ContentKind kind,
        string displayName, string mimeType, ulong byteCount, ShareOrigin origin, ShareContent? preview, ActivityId? retryOf, bool reserved = false)
    {
        if (!reserved)
            RequireAdmission();
        if (operation.Value == 0)
            throw new ArgumentOutOfRangeException(nameof(operation));
        if (operations.ContainsKey(operation))
            throw new InvalidOperationException("Operation is already tracked.");
        if (preview is not null && preview.Kind != kind)
            throw new ArgumentException("Preview kind does not match activity.", nameof(preview));
        var earlyTransfer = earlyRegistrations.TryGetValue(operation, out var registration) ? registration : (TransferId?)null;
        if (earlyTransfer is { } registered && transfers.ContainsKey(registered))
            throw new InvalidOperationException("Transfer is already assigned to another activity.");
        var now = clock.UtcNow;
        var record = new ActivityRecord(new(Guid.NewGuid()), device, earlyTransfer, operation, retryOf,
            ShareDirection.Outgoing, kind, origin, MetadataName(kind, displayName), mimeType, byteCount,
            now, now, null, ActivityStatus.AwaitingPeer, 0, null, null);
        if (earlyTransfer is { } progressing && earlyProgress.Remove(progressing, out var bytes))
            record = record with
            {
                Status = ActivityStatus.Transferring,
                TransferredBytes = Math.Min(bytes, byteCount)
            };
        AddActive(record);
        operations.Add(operation, record.Id);
        earlyRegistrations.Remove(operation);
        if (earlyTransfer is { } transfer)
            transfers.Add(transfer, record.Id);
        if (preview is not null)
            previews.Add(record.Id, preview);
        Save(record);
        return record;
    }

    public ActivityRecord AddIncomingOffer(IncomingOffer offer, ShareOrigin origin = ShareOrigin.Direct)
    {
        ArgumentNullException.ThrowIfNull(offer);
        // Repository upserts are by local ID and provide no targeted deletion.
        // Establish durable transfer identity before any incoming ID is written,
        // even when an offer arrives before the UI explicitly requests history.
        _ = StartHistoryLoad(offer.DeviceId, refresh: false);
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
            if (!historyReady.Contains(offer.DeviceId) || loadingDevices.Contains(offer.DeviceId))
                deferredIncoming.Add(record.Id);
            transfers.Add(offer.TransferId, record.Id);
            Save(record);
        }
        NotifyChanged();
        return record;
    }

    public bool BindTransfer(OperationId operation, TransferId transfer)
    {
        bool changed;
        lock (gate)
        {
            if (operations.TryGetValue(operation, out var activity))
                changed = BindCore(activity, transfer);
            else
            {
                if (operation.Value == 0)
                    throw new ArgumentOutOfRangeException(nameof(operation));
                if (blocked && reservations.Count == 0)
                    return false;
                if (transfers.ContainsKey(transfer) ||
                    (earlyRegistrations.TryGetValue(operation, out var existing) && existing != transfer) ||
                    earlyRegistrations.Any(value => value.Key != operation && value.Value == transfer))
                    throw new InvalidOperationException("Transfer is already assigned to another activity.");
                earlyRegistrations[operation] = transfer;
                return false;
            }
        }
        if (changed)
            NotifyChanged();
        return changed;
    }

    public bool BindTransfer(ActivityId activity, TransferId transfer)
    {
        bool changed;
        lock (gate)
            changed = BindCore(activity, transfer);
        if (changed)
            NotifyChanged();
        return changed;
    }

    private bool BindCore(ActivityId activity, TransferId transfer)
    {
        activity = ResolveActivity(activity);
        var record = RequireActivity(activity);
        if (ActivityTransition.IsTerminal(record.Status))
            return false;
        ValidateBinding(record, transfer);
        if (record.TransferId == transfer)
            return false;
        transfers[transfer] = activity;
        Save(record with
        {
            TransferId = transfer,
            UpdatedAt = UpdateTime(record)
        });
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
        var publish = false;
        lock (gate)
        {
            if (!transfers.TryGetValue(progress.TransferId, out var id))
            {
                if (earlyRegistrations.ContainsValue(progress.TransferId))
                    earlyProgress[progress.TransferId] = Math.Max(earlyProgress.GetValueOrDefault(progress.TransferId), progress.TransferredBytes);
                return false;
            }
            var record = activities[id];
            if (!active.Contains(id) || ActivityTransition.IsTerminal(record.Status))
                return false;
            var bytes = Math.Max(record.TransferredBytes, Math.Min(progress.TransferredBytes, record.ByteCount));
            if (record.Status == ActivityStatus.Transferring && bytes == record.TransferredBytes)
                return false;
            if (record.Status != ActivityStatus.Transferring && !ActivityTransition.CanTransition(record.Status, ActivityStatus.Transferring))
                return false;
            var updated = record with
            {
                Status = ActivityStatus.Transferring,
                TransferredBytes = bytes,
                UpdatedAt = UpdateTime(record)
            };
            publish = record.Status != ActivityStatus.Transferring || bytes == record.ByteCount ||
                !progressPublications.TryGetValue(progress.TransferId, out var previous) ||
                updated.UpdatedAt - previous >= ProgressPublishInterval;
            activities[id] = updated;
            if (publish)
            {
                progressPublications[progress.TransferId] = updated.UpdatedAt;
                Save(updated);
            }
        }
        if (publish)
            NotifyChanged();
        return true;
    }

    // No transfer ID means the result is ambiguous. The operation's completion
    // owner must use the ActivityId overload; never guess the most recent send.
    public bool Complete(TransferTerminal terminal, string? receivedFilePath = null)
    {
        ArgumentNullException.ThrowIfNull(terminal);
        if (terminal.TransferId is not { } transfer)
            throw new ArgumentException("A transfer ID or explicit activity ID is required.", nameof(terminal));
        bool changed;
        lock (gate)
            changed = transfers.TryGetValue(transfer, out var id) && CompleteCore(id, terminal, receivedFilePath);
        if (changed)
            NotifyChanged();
        return changed;
    }

    public bool Complete(ActivityId activity, TransferTerminal terminal, string? receivedFilePath = null)
    {
        ArgumentNullException.ThrowIfNull(terminal);
        bool changed;
        lock (gate)
            changed = CompleteCore(activity, terminal, receivedFilePath);
        if (changed)
            NotifyChanged();
        return changed;
    }

    private bool CompleteCore(ActivityId activity, TransferTerminal terminal, string? receivedFilePath)
    {
        activity = ResolveActivity(activity);
        var record = RequireActivity(activity);
        var status = ActivityTransition.TerminalStatus(terminal.Failure);
        if (!active.Contains(activity) || !ActivityTransition.CanTransition(record.Status, status))
            return false;
        if (terminal.TransferId is { } transfer)
        {
            ValidateBinding(record, transfer);
            transfers[transfer] = activity;
            progressPublications.Remove(transfer);
        }
        if (record.TransferId is { } registered)
            progressPublications.Remove(registered);
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
        if (record.OperationId is { } operation)
            earlyRegistrations.Remove(operation);
        if (active.Count == 0 && reservations.Count == 0)
        {
            if (blocked)
                operations.Clear();
            idle.TrySetResult();
        }
        return true;
    }

    public bool CanRetry(ActivityId activity)
    {
        lock (gate)
            return activities.TryGetValue(activity, out var record) && ActivityTransition.IsRetryable(record);
    }

    public ActivityRecord RetryFrom(ActivityId activity, OperationId operation, ShareContent? preview = null)
    {
        ActivityRecord retry;
        lock (gate)
        {
            var previous = RequireActivity(activity);
            if (!ActivityTransition.IsRetryable(previous))
                throw new InvalidOperationException("Activity is not retryable.");
            retry = BeginOutgoingCore(previous.DeviceId, operation, previous.Kind, previous.DisplayName, previous.MimeType,
                previous.ByteCount, previous.Origin, preview ?? previews.GetValueOrDefault(activity), previous.Id);
        }
        NotifyChanged();
        return retry;
    }

    public void Apply(ShareSdkEvent value)
    {
        ArgumentNullException.ThrowIfNull(value);
        switch (value)
        {
            case ShareSdkEvent.OfferReceived offer:
                AddIncomingOffer(offer.Offer);
                break;
            case ShareSdkEvent.OutgoingTransferRegistered registered:
                BindTransfer(registered.OperationId, registered.TransferId);
                break;
            case ShareSdkEvent.ProgressReceived progress:
                ApplyProgress(progress.Progress);
                break;
            case ShareSdkEvent.TransferCompleted { Terminal.TransferId: not null } completed:
                ApplyNativeTerminal(completed.Terminal);
                break;
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
            if (!transfers.TryGetValue(transfer, out var activity))
                return;
            // Native success means bytes arrived, not that publication/read of
            // received content succeeded. The receive owner finalizes incoming
            // success after publication, or reports its storage/read failure.
            if (terminal.Failure is null && activities[activity].Direction == ShareDirection.Incoming)
                return;
            changed = CompleteCore(activity, terminal, null);
        }
        if (changed)
            NotifyChanged();
    }

    public Task LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken = default) =>
        StartHistoryLoad(device, refresh: true).WaitAsync(cancellationToken);

    private Task StartHistoryLoad(DeviceId device, bool refresh)
    {
        TaskCompletionSource completion;
        lock (gate)
        {
            if (historyLoads.TryGetValue(device, out var loading) && !loading.IsCompleted)
                return loading;
            if (!refresh && historyReady.Contains(device))
                return Task.CompletedTask;
            completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            historyLoads[device] = completion.Task;
            loadingDevices.Add(device);
        }
        // Start after releasing the gate; even synchronously completing storage
        // cannot run under the tracker lock. Explicit loads join this same task.
        _ = FinishHistoryLoadAsync(device, completion);
        return completion.Task;
    }

    private async Task FinishHistoryLoadAsync(DeviceId device, TaskCompletionSource completion)
    {
        try
        {
            await LoadHistoryAsync(device).ConfigureAwait(false);
            completion.TrySetResult();
        }
        catch (Exception error) { completion.TrySetException(error); }
    }

    private async Task LoadHistoryAsync(DeviceId device)
    {
        var changed = false;
        var succeeded = false;
        try
        {
            // Cancelling a caller's wait cannot release unverified provisional
            // identities into storage. Accepted hydration owns reconciliation.
            var loaded = await repository.LoadDeviceAsync(device, CancellationToken.None).ConfigureAwait(false);
            lock (gate)
            {
                foreach (var record in loaded)
                {
                    if (record.DeviceId != device || activities.ContainsKey(record.Id))
                        continue;
                    if (record.TransferId is { } transfer && transfers.TryGetValue(transfer, out var owner))
                    {
                        // A known live record wins over stale history. A replay
                        // admitted during this load instead adopts the durable
                        // identity, retaining an alias for its receive owner.
                        if (!deferredIncoming.Contains(owner))
                            continue;
                        var replay = activities[owner];
                        if (replay.DeviceId != device || replay.Direction != record.Direction)
                            continue;
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
                        if (previews.Remove(owner, out var preview))
                            previews[canonical.Id] = preview;
                        deferredIncoming.Add(canonical.Id);
                        transfers[transfer] = canonical.Id;
                        if (wasActive && !ActivityTransition.IsTerminal(canonical.Status))
                            active.Add(canonical.Id);
                    }
                    else
                    {
                        // Historical pending records are not live native work.
                        // No payload or process operation index is reconstructed.
                        activities.Add(record.Id, record);
                        if (record.TransferId is { } registered)
                            transfers.Add(registered, record.Id);
                    }
                    changed = true;
                }
                succeeded = true;
            }
        }
        finally
        {
            lock (gate)
            {
                loadingDevices.Remove(device);
                if (succeeded)
                {
                    historyReady.Add(device);
                    foreach (var id in deferredIncoming.Where(id => activities[id].DeviceId == device).ToArray())
                    {
                        deferredIncoming.Remove(id);
                        Save(activities[id]);
                        changed = true;
                    }
                }
                else
                    historyReady.Remove(device);
                if (active.Count == 0 && reservations.Count == 0)
                {
                    if (blocked)
                        operations.Clear();
                    idle.TrySetResult();
                }
            }
            if (changed)
                NotifyChanged();
        }
    }

    public void BlockNewTransfers()
    {
        lock (gate)
        {
            blocked = true;
            if (reservations.Count == 0)
            {
                earlyRegistrations.Clear();
                earlyProgress.Clear();
            }
            // Explicit SDK Stop suppresses its event generation. Session pause
            // therefore retires operation mappings through block-and-drain too.
            if (active.Count == 0 && reservations.Count == 0)
                operations.Clear();
        }
    }
    public void AllowNewTransfers()
    {
        lock (gate)
            blocked = false;
    }

    public Task WaitForIdleAsync(CancellationToken cancellationToken)
    {
        lock (gate)
            return idle.Task.WaitAsync(cancellationToken);
    }

    // Covers every write accepted before this call. Failed writes stay visible
    // on later flushes; successful writes can be retired without losing failures.
    public async Task SavePendingAsync(CancellationToken cancellationToken = default)
    {
        Task[] loads;
        lock (gate)
            loads = deferredIncoming.Select(id => activities[id].DeviceId).Distinct()
            .Select(device => historyLoads[device]).ToArray();
        try
        {
            // Deferred incoming work was accepted before the flush too. Its
            // canonical writes are queued only after history readiness, and a
            // failed read must surface rather than persist a second local ID.
            await Task.WhenAll(loads).WaitAsync(cancellationToken).ConfigureAwait(false);
            Task[] writes;
            lock (gate)
                writes = pendingWrites.ToArray();
            await Task.WhenAll(writes).WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        finally { lock (gate) pendingWrites.RemoveAll(value => value.IsCompletedSuccessfully); }
    }

    private void Save(ActivityRecord record)
    {
        activities[record.Id] = record;
        if (deferredIncoming.Contains(record.Id))
            return;
        var previous = writeTail;
        writeTail = Task.Run(async () =>
        {
            // One failed write must not prevent later metadata from reaching
            // storage. Its fault remains in pendingWrites for the flush caller.
            try
            {
                await previous.ConfigureAwait(false);
            }
            catch { }
            await repository.UpsertAsync(record, CancellationToken.None).ConfigureAwait(false);
        });
        pendingWrites.Add(writeTail);
    }

    private ActivityId ResolveActivity(ActivityId activity)
    {
        while (aliases.TryGetValue(activity, out var canonical))
            activity = canonical;
        return activity;
    }
    private ActivityRecord RequireActivity(ActivityId activity) => activities.TryGetValue(ResolveActivity(activity), out var record)
        ? record : throw new ArgumentException("Activity is not known.", nameof(activity));
    private void RequireAdmission()
    {
        if (blocked)
            throw new AdmissionBlockedException();
    }
    public sealed class AdmissionBlockedException : InvalidOperationException
    {
        internal AdmissionBlockedException() : base("New transfers are blocked.") { }
    }
    private void NotifyChanged()
    {
        var handlers = Changed;
        if (handlers is null)
            return;
        foreach (EventHandler handler in handlers.GetInvocationList())
        {
            try
            {
                handler(this, EventArgs.Empty);
            }
            catch { /* Observer faults cannot own transfer state or expose payloads. */ }
        }
    }
    private void AddActive(ActivityRecord record)
    {
        if (active.Count == 0 && reservations.Count == 0)
            idle = NewIdle();
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
