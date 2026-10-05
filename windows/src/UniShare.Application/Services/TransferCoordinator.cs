using System.Collections.Immutable;
using System.Text;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Policies;

namespace UniShare.Application.Services;

public sealed class TransferCoordinator
{
    private readonly IShareSdkClient sdk;
    private readonly ActivityTracker tracker;
    private readonly ITransferFiles files;
    private readonly Func<AppSettings> settings;
    private readonly object gate = new();
    // These sources never cross the activity repository boundary.
    private readonly Dictionary<ActivityId, Outgoing> outgoing = [];
    private readonly Dictionary<ActivityId, Incoming> incoming = [];
    private readonly HashSet<TransferId> incomingTransfers = [];
    private readonly HashSet<(RequestId Request, TransferId Transfer)> blockedOffers = [];

    public TransferCoordinator(IShareSdkClient sdk, ActivityTracker tracker, ITransferFiles files, Func<AppSettings> settings)
    {
        this.sdk = sdk ?? throw new ArgumentNullException(nameof(sdk));
        this.tracker = tracker ?? throw new ArgumentNullException(nameof(tracker));
        this.files = files ?? throw new ArgumentNullException(nameof(files));
        this.settings = settings ?? throw new ArgumentNullException(nameof(settings));
        sdk.EventReceived += OnEvent;
    }

    public Task<ActivityId> SendTextAsync(DeviceId device, string text, ShareOrigin origin, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (string.IsNullOrWhiteSpace(text)) throw new ArgumentException("Text is empty.", nameof(text));
        var preview = new ShareContent(ContentKind.Text, text, "text/plain", []);
        return StartOutgoingAsync(new(device, ContentKind.Text, origin, "Text", "text/plain", (ulong)Encoding.UTF8.GetByteCount(text), preview, null), null, cancellationToken);
    }

    public Task<ActivityId> SendFileAsync(DeviceId device, string path, ContentKind kind, ShareOrigin origin, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (kind is not (ContentKind.File or ContentKind.Image)) throw new ArgumentException("Select a file or image.", nameof(kind));
        ShareSource source;
        try
        {
            var file = new FileInfo(path);
            if (!file.Exists) throw new IOException();
            source = new(file.FullName, file.Name, kind == ContentKind.Image ? ImageMime(file.Extension) : "application/octet-stream", checked((ulong)file.Length));
        }
        catch (Exception error) when (IsSourceError(error)) { throw new IOException("Unable to open selected content."); }
        return StartOutgoingAsync(new(device, kind, origin, source.DisplayName, source.MimeType, source.ByteCount, null, source), null, cancellationToken);
    }

    public async Task<ActivityId> SendClipboardAsync(DeviceId device, CancellationToken cancellationToken)
    {
        ShareContent content;
        try { content = await sdk.ReadClipboardAsync(cancellationToken).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw; }
        catch { throw new IOException("Unable to read local clipboard."); }
        if (content.Kind == ContentKind.Text)
            return await SendTextAsync(device, content.Text ?? string.Empty, ShareOrigin.Clipboard, cancellationToken).ConfigureAwait(false);
        if (content.Kind != ContentKind.Image || content.ImageBytes.IsDefaultOrEmpty || !content.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Clipboard has no supported content.");
        return await StartOutgoingAsync(new(device, ContentKind.Image, ShareOrigin.Clipboard, "Image", content.MimeType,
            (ulong)content.ImageBytes.Length, content, null), null, cancellationToken).ConfigureAwait(false);
    }

    public Task AcceptAsync(ActivityId activity, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Incoming work;
        TaskCompletionSource completion;
        lock (gate)
        {
            work = RequireIncoming(activity);
            if (work.Acceptance is not null) return work.Acceptance;
            if (work.Cancelled || work.Rejecting || IsTerminal(Record(work).Status)) throw new InvalidOperationException("Offer is already finished.");
            completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            work.Acceptance = completion.Task;
        }
        // Install the shared owner before any destination or Changed callback
        // can reenter; the body and notifications run outside this gate.
        _ = RunAcceptanceAsync(work, completion, cancellationToken);
        return completion.Task;
    }

    private async Task RunAcceptanceAsync(Incoming work, TaskCompletionSource completion, CancellationToken cancellationToken)
    {
        try
        {
            if (!await ReceiveAsync(work, cancellationToken).ConfigureAwait(false))
            {
                lock (gate) if (work.Acceptance == completion.Task) work.Acceptance = null;
                completion.TrySetException(new IOException("Unable to prepare incoming content. Check the receive directory."));
                return;
            }
            completion.TrySetResult();
        }
        catch
        {
            tracker.Complete(work.Id, Failed(work.Offer.TransferId));
            completion.TrySetException(new IOException("Unable to receive incoming content."));
        }
    }

    public async Task RejectAsync(ActivityId activity, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        Incoming work;
        lock (gate)
        {
            work = RequireIncoming(activity);
            if (work.Acceptance is not null) throw new InvalidOperationException("Offer is already being received.");
            if (work.Rejecting || IsTerminal(Record(work).Status)) return;
            work.Rejecting = true;
        }
        try
        {
            await sdk.RejectAsync(work.Offer.RequestId, CancellationToken.None).ConfigureAwait(false);
            tracker.Complete(work.Id, new(work.Offer.TransferId, new(ShareErrorKind.TransferRejected, "Offer rejected.")));
        }
        catch (OperationCanceledException) { throw; }
        catch { tracker.Complete(work.Id, Failed(work.Offer.TransferId)); }
    }

    public async Task CancelAsync(ActivityId activity, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TransferId? transfer;
        Incoming? receive;
        Outgoing? send;
        lock (gate)
        {
            incoming.TryGetValue(activity, out receive); outgoing.TryGetValue(activity, out send);
            if (receive is null && send is null) throw new InvalidOperationException("Activity has no live transfer source.");
            if (receive is not null)
            {
                if (IsTerminal(Record(receive).Status)) return;
                receive.Cancelled = true; receive.Cancellation.Cancel(); transfer = receive.Offer.TransferId;
            }
            else
            {
                var record = FindSendRecord(activity, send!.Spec.Device);
                if (IsTerminal(record.Status)) return;
                send.Cancelled = true; transfer = record.TransferId;
            }
        }
        try { if (transfer is { } id) await sdk.CancelAsync(id, cancellationToken).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw; }
        catch { throw new IOException("Unable to cancel content transfer."); }
        // An accepted native operation keeps ownership until its completion
        // task settles. A caller cancelling its wait cannot release its sink.
        if (receive is not null && receive.Acceptance is null)
            tracker.Complete(receive.Id, Cancelled(transfer));
    }

    public Task<ActivityId> RetryAsync(ActivityId activity, CancellationToken cancellationToken)
    {
        Outgoing previous;
        lock (gate)
        {
            if (!outgoing.TryGetValue(activity, out previous!) || !tracker.CanRetry(activity))
                throw new InvalidOperationException("Retry source is unavailable in this process.");
        }
        if (previous.Spec.Source is { } source)
        {
            try
            {
                if (!File.Exists(source.Path) || checked((ulong)new FileInfo(source.Path).Length) != source.ByteCount)
                    throw new InvalidOperationException("Retry source is unavailable in this process.");
            }
            catch (Exception error) when (IsSourceError(error)) { throw new InvalidOperationException("Retry source is unavailable in this process."); }
        }
        return StartOutgoingAsync(previous.Spec, activity, cancellationToken);
    }

    private async Task<ActivityId> StartOutgoingAsync(SourceSpec spec, ActivityId? retryOf, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        using var reservation = tracker.ReserveOutgoing();
        TransientSource? stage = null;
        try
        {
            if (spec.Kind == ContentKind.Image && spec.Source is null)
                stage = await files.StageClipboardImageAsync(spec.Preview!.ImageBytes.AsMemory(), spec.Mime, cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            PendingSend pending;
            try
            {
                pending = spec.Kind == ContentKind.Text ? sdk.BeginSendText(spec.Device, spec.Preview!.Text!)
                    : sdk.BeginSendFile(spec.Device, spec.Kind, stage?.Source ?? spec.Source!);
            }
            catch { throw new IOException("Unable to submit content transfer."); }
            if (pending.OperationId.Value == 0) throw new IOException("Content transfer was not accepted.");
            ActivityRecord record;
            try
            {
                record = reservation.Commit(spec.Device, pending.OperationId, spec.Kind, spec.Name, spec.Mime, spec.Bytes, spec.Origin, spec.Preview, retryOf);
            }
            catch
            {
                // An invalid SDK identity must not release native source ownership.
                _ = RetireUntrackedAsync(pending.Completion, stage);
                stage = null;
                throw;
            }
            var work = new Outgoing(spec, stage);
            lock (gate) outgoing.Add(record.Id, work);
            stage = null;
            work.Cancellation = cancellationToken.Register(() => _ = RequestCancellationAsync(record.Id));
            _ = FinishOutgoingAsync(record.Id, work, pending.Completion);
            return record.Id;
        }
        finally { if (stage is not null) await stage.DisposeAsync().ConfigureAwait(false); }
    }

    private async Task FinishOutgoingAsync(ActivityId id, Outgoing work, Task<TransferTerminal> completion)
    {
        TransferTerminal terminal;
        try { terminal = await completion.ConfigureAwait(false); }
        catch { terminal = Failed(null); }
        work.Cancellation.Dispose();
        try { if (work.Stage is not null) await work.Stage.DisposeAsync().ConfigureAwait(false); }
        catch { terminal = Failed(terminal.TransferId); }
        tracker.Complete(id, terminal);
    }
    private static async Task RetireUntrackedAsync(Task<TransferTerminal> completion, TransientSource? stage)
    {
        try { await completion.ConfigureAwait(false); } catch { }
        finally { if (stage is not null) try { await stage.DisposeAsync().ConfigureAwait(false); } catch { } }
    }

    private async Task<bool> ReceiveAsync(Incoming work, CancellationToken cancellationToken)
    {
        TransientDestination? text = null; PreparedReceive? receive = null;
        TransferTerminal terminal = new(work.Offer.TransferId, null);
        string? finalPath = null;
        ShareContent? preview = null;
        var preparingDestination = false;
        var preparationFailed = false;
        using var cancellation = cancellationToken.Register(() => _ = RequestCancellationAsync(work.Id));
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, work.Cancellation.Token);
        var contentCancellation = linked.Token;
        try
        {
            if (work.Offer.Kind is not (ContentKind.Text or ContentKind.Image or ContentKind.File)) throw new IOException("Unsupported incoming content.");
            if (work.Offer.Kind == ContentKind.Image && (!work.Offer.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase) ||
                work.Offer.ByteCount is 0 or > 100UL * 1024 * 1024)) throw new IOException("Invalid incoming image metadata.");
            preparingDestination = true;
            if (work.Offer.Kind == ContentKind.Text) text = await files.CreateTextDestinationAsync(contentCancellation).ConfigureAwait(false);
            else receive = await files.CreateReceiveAsync(work.Offer, settings().ReceiveDirectory ?? string.Empty, contentCancellation).ConfigureAwait(false);
            preparingDestination = false;
            contentCancellation.ThrowIfCancellationRequested();
            tracker.ApplyProgress(new(work.Offer.TransferId, 0, work.Offer.ByteCount));
            Task accepted;
            lock (gate)
            {
                // A reentrant cancel during progress must retire the prepared
                // sink without submitting acceptance after the request ended.
                contentCancellation.ThrowIfCancellationRequested();
                if (IsTerminal(Record(work).Status)) throw new OperationCanceledException();
                accepted = sdk.AcceptAsync(work.Offer.RequestId, text?.Path ?? receive!.StagingPath, CancellationToken.None);
            }
            await accepted.ConfigureAwait(false);
            lock (gate) if (work.Cancelled || IsTerminal(Record(work).Status)) throw new OperationCanceledException();
            if (text is not null)
            {
                var bytes = await File.ReadAllBytesAsync(text.Path, contentCancellation).ConfigureAwait(false);
                var body = new UTF8Encoding(false, true).GetString(bytes);
                preview = new(ContentKind.Text, body, work.Offer.MimeType, []);
            }
            else
            {
                if (work.Offer.Kind == ContentKind.Image)
                {
                    var bytes = await ReadImageAsync(receive!.StagingPath, work.Offer.ByteCount, contentCancellation).ConfigureAwait(false);
                    preview = new(ContentKind.Image, null, work.Offer.MimeType, ImmutableArray.CreateRange(bytes));
                }
                finalPath = await files.PublishAsync(receive!, contentCancellation).ConfigureAwait(false);
                work.PublishedPath = finalPath;
            }
        }
        catch (OperationCanceledException) { terminal = Cancelled(work.Offer.TransferId); }
        catch
        {
            if (preparingDestination) preparationFailed = true;
            else terminal = Failed(work.Offer.TransferId);
        }
        finally
        {
            try
            {
                if (text is not null) await text.DisposeAsync().ConfigureAwait(false);
                if (receive is not null && finalPath is null) await files.AbortAsync(receive).ConfigureAwait(false);
            }
            catch { preparationFailed = false; terminal = Failed(work.Offer.TransferId); finalPath = null; }
        }
        if (preparationFailed)
        {
            lock (gate)
            {
                if (!work.Cancelled) return false; // The native request remains pending and can be retried/rejected.
            }
            terminal = Cancelled(work.Offer.TransferId);
        }
        if (finalPath is not null)
        {
            // Atomic publication wins over a late cancellation. No read, file
            // cleanup or cancellable work can turn an exposed final into failure.
            try { if (preview is not null) tracker.PublishIncomingPreview(work.Id, preview); }
            finally { tracker.Complete(work.Id, new(work.Offer.TransferId, null), finalPath); }
            try { await receive!.DisposeAsync().ConfigureAwait(false); } catch { }
            return true;
        }
        if (terminal.Failure is null && preview is not null)
        {
            if (contentCancellation.IsCancellationRequested) terminal = Cancelled(work.Offer.TransferId);
            else tracker.PublishIncomingPreview(work.Id, preview);
        }
        tracker.Complete(work.Id, terminal, finalPath);
        return true;
    }

    private static async Task<byte[]> ReadImageAsync(string path, ulong byteCount, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.Asynchronous);
        if (stream.Length != (long)byteCount) throw new IOException("Incoming image length is invalid.");
        var bytes = new byte[checked((int)byteCount)];
        await stream.ReadExactlyAsync(bytes, cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        if (stream.ReadByte() != -1) throw new IOException("Incoming image length is invalid.");
        return bytes;
    }

    private void OnEvent(object? sender, ShareSdkEvent value)
    {
        if (value is ShareSdkEvent.OfferReceived offer)
        {
            lock (gate) incomingTransfers.Add(offer.Offer.TransferId);
            _ = AdmitOfferAsync(offer.Offer); return;
        }
        if (value is ShareSdkEvent.TransferCompleted completed)
        {
            lock (gate)
            {
                // Incoming prompts have no accepted operation completion task.
                // Their remote failure therefore terminates through the event.
                if (completed.Terminal.Failure is not null && completed.Terminal.TransferId is { } transfer && incomingTransfers.Contains(transfer) &&
                    !incoming.Values.Any(work => work.Offer.TransferId == transfer && work.Acceptance is not null))
                    tracker.Apply(value);
            }
            return; // Accepted completion tasks own resource lifetimes.
        }
        tracker.Apply(value);
        if (value is ShareSdkEvent.OutgoingTransferRegistered registered)
        {
            ActivityId? cancelled = null;
            lock (gate)
                foreach (var pair in outgoing)
                    if (pair.Value.Cancelled && FindSendRecord(pair.Key, pair.Value.Spec.Device).OperationId == registered.OperationId) { cancelled = pair.Key; break; }
            if (cancelled is { } activity) _ = RequestCancellationAsync(activity);
        }
    }
    private async Task AdmitOfferAsync(IncomingOffer offer)
    {
        try
        {
            tracker.AddIncomingOffer(offer);
            await tracker.LoadDeviceAsync(offer.DeviceId).ConfigureAwait(false);
            var record = tracker.GetDeviceSnapshot(offer.DeviceId).Single(value => value.TransferId == offer.TransferId);
            lock (gate)
            {
                if (incoming.ContainsKey(record.Id) || IsTerminal(record.Status)) return;
                incoming.Add(record.Id, new(record.Id, offer));
            }
            if (ReceivePolicy.Decide(sdk.GetTrustedDevices().Contains(offer.DeviceId), offer.Kind) == ReceiveDecision.AutoAccept)
                await AcceptAsync(record.Id, CancellationToken.None).ConfigureAwait(false);
        }
        catch (ActivityTracker.AdmissionBlockedException)
        {
            lock (gate) if (!blockedOffers.Add((offer.RequestId, offer.TransferId))) return;
            try { await sdk.RejectAsync(offer.RequestId, CancellationToken.None).ConfigureAwait(false); }
            catch { /* Rejection failure is contained without storing/logging its payload. */ }
        }
        catch { /* No exception body or destination is retained or diagnosed. */ }
    }
    private async Task RequestCancellationAsync(ActivityId activity)
    {
        try { await CancelAsync(activity, CancellationToken.None).ConfigureAwait(false); } catch { }
    }
    private Incoming RequireIncoming(ActivityId activity) => incoming.TryGetValue(activity, out var work) ? work : throw new InvalidOperationException("Incoming offer is unavailable in this process.");
    private ActivityRecord Record(Incoming work) => tracker.GetDeviceSnapshot(work.Offer.DeviceId).Single(value => value.Id == work.Id);
    private ActivityRecord FindSendRecord(ActivityId id, DeviceId device) => tracker.GetDeviceSnapshot(device).Single(value => value.Id == id);
    private static bool IsTerminal(ActivityStatus status) => status is ActivityStatus.Completed or ActivityStatus.Failed or ActivityStatus.Cancelled or ActivityStatus.Rejected;
    private static bool IsSourceError(Exception error) => error is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException;
    private static TransferTerminal Failed(TransferId? transfer) => new(transfer, new(ShareErrorKind.StorageFailed, "Content transfer failed."));
    private static TransferTerminal Cancelled(TransferId? transfer) => new(transfer, new(ShareErrorKind.Cancelled, "Content transfer cancelled."));
    private static string ImageMime(string extension) => extension.ToLowerInvariant() switch
    { ".png" => "image/png", ".jpg" or ".jpeg" => "image/jpeg", ".gif" => "image/gif", ".webp" => "image/webp", ".bmp" => "image/bmp", _ => "application/octet-stream" };
    private sealed record SourceSpec(DeviceId Device, ContentKind Kind, ShareOrigin Origin, string Name, string Mime, ulong Bytes, ShareContent? Preview, ShareSource? Source);
    private sealed class Outgoing(SourceSpec spec, TransientSource? stage)
    {
        public SourceSpec Spec { get; } = spec; public TransientSource? Stage { get; } = stage;
        public CancellationTokenRegistration Cancellation { get; set; } public bool Cancelled { get; set; }
    }
    private sealed class Incoming(ActivityId id, IncomingOffer offer)
    {
        public ActivityId Id { get; } = id; public IncomingOffer Offer { get; } = offer;
        public Task? Acceptance { get; set; } public bool Cancelled { get; set; } public bool Rejecting { get; set; }
        public CancellationTokenSource Cancellation { get; } = new();
        public string? PublishedPath { get; set; }
    }
}
