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
        lock (gate) return activities.Values.Where(value => value.DeviceId == device)
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
        var now = clock.UtcNow;
        var record = new ActivityRecord(new(Guid.NewGuid()), device, null, operation, retryOf,
            ShareDirection.Outgoing, kind, origin, MetadataName(kind, displayName), mimeType, byteCount,
            now, now, null, ActivityStatus.AwaitingPeer, 0, null, null);
        AddActive(record);
        operations.Add(operation, record.Id);
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
            transfers.Add(offer.TransferId, record.Id);
            Save(record);
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return record;
    }

    public bool BindTransfer(OperationId operation, TransferId transfer)
    {
        bool changed;
        lock (gate) changed = operations.TryGetValue(operation, out var activity) && BindCore(activity, transfer);
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
            if (!transfers.TryGetValue(progress.TransferId, out var id)) return false;
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
        if (active.Count == 0) idle.TrySetResult();
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
            case ShareSdkEvent.TransferCompleted { Terminal.TransferId: not null } completed: Complete(completed.Terminal); break;
        }
    }

    public async Task LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken = default)
    {
        var loaded = await repository.LoadDeviceAsync(device, cancellationToken).ConfigureAwait(false);
        var changed = false;
        lock (gate)
        {
            foreach (var record in loaded)
            {
                // History never overwrites a live record or resurrects native
                // operations. Rehydration cannot create preview side-table data.
                if (record.DeviceId != device || activities.ContainsKey(record.Id)) continue;
                activities.Add(record.Id, record);
                // Transfer IDs survive reload and deduplicate delayed offers.
                // Operation IDs are process scoped and must not be rehydrated.
                if (record.TransferId is { } transfer) transfers.TryAdd(transfer, record.Id);
                changed = true;
            }
        }
        if (changed) Changed?.Invoke(this, EventArgs.Empty);
    }

    public void BlockNewTransfers() { lock (gate) blocked = true; }
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

    private ActivityRecord RequireActivity(ActivityId activity) => activities.TryGetValue(activity, out var record)
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
