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
    private readonly Action<TransferDiagnosticRecord>? diagnostic;
    private long nextDiagnosticSequence;
    private readonly object gate = new();
    private readonly CancellationTokenSource retirement = new();
    private bool retiring;
    private int owners;
    private TaskCompletionSource ownersIdle = CompletedSignal();
    private Task? retired;
    // These sources never cross the activity repository boundary.
    private readonly Dictionary<ActivityId, Outgoing> outgoing = [];
    private readonly Dictionary<ActivityId, Incoming> incoming = [];
    private readonly HashSet<TransferId> incomingTransfers = [];
    private readonly HashSet<(RequestId Request, TransferId Transfer)> blockedOffers = [];

    public TransferCoordinator(IShareSdkClient sdk, ActivityTracker tracker, ITransferFiles files, Func<AppSettings> settings,
        Action<TransferDiagnosticRecord>? diagnostic = null)
    {
        this.sdk = sdk ?? throw new ArgumentNullException(nameof(sdk));
        this.tracker = tracker ?? throw new ArgumentNullException(nameof(tracker));
        this.files = files ?? throw new ArgumentNullException(nameof(files));
        this.settings = settings ?? throw new ArgumentNullException(nameof(settings));
        this.diagnostic = diagnostic;
        sdk.EventReceived += OnEvent;
    }

    public Task<ActivityId> SendTextAsync(DeviceId device, string text, ShareOrigin origin, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (string.IsNullOrWhiteSpace(text))
            throw new ArgumentException("Text is empty.", nameof(text));
        var preview = new ShareContent(ContentKind.Text, text, "text/plain", []);
        return StartOutgoingAsync(new(device, ContentKind.Text, origin, "Text", "text/plain", (ulong)Encoding.UTF8.GetByteCount(text), preview, null), null, cancellationToken);
    }

    public Task<ActivityId> SendFileAsync(DeviceId device, string path, ContentKind kind, ShareOrigin origin, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (kind is not (ContentKind.File or ContentKind.Image))
            throw new ArgumentException("Select a file or image.", nameof(kind));
        var sequence = NextDiagnosticSequence();
        Report(TransferDiagnosticStage.SourceOpen, NativeDiagnosticOutcome.Began, DiagnosticDirection.Send, kind, sequence);
        ShareSource source;
        try
        {
            var file = new FileInfo(path);
            if (!file.Exists)
                throw new IOException();
            source = new(file.FullName, file.Name, kind == ContentKind.Image ? ImageMime(file.Extension) : "application/octet-stream", checked((ulong)file.Length));
        }
        catch (Exception error) when (IsSourceError(error))
        {
            Report(TransferDiagnosticStage.SourceOpen, NativeDiagnosticOutcome.Failed, DiagnosticDirection.Send, kind, sequence, ShareErrorKind.StorageFailed);
            throw new IOException("Unable to open selected content.");
        }
        Report(TransferDiagnosticStage.SourceOpen, NativeDiagnosticOutcome.Succeeded, DiagnosticDirection.Send, kind, sequence);
        return StartOutgoingAsync(new(device, kind, origin, source.DisplayName, source.MimeType, source.ByteCount, null, source), null, cancellationToken, sequence);
    }

    public async Task<ActivityId> SendClipboardAsync(DeviceId device, CancellationToken cancellationToken)
    {
        using var owner = Own();
        ShareContent content;
        try
        {
            Task<ShareContent> read;
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(retiring, this);
                // Keep the caller's token identity: eligibility revocation uses
                // CancelAsync and marks this token before scheduling callbacks.
                read = sdk.ReadClipboardAsync(cancellationToken);
            }
            content = await read.ConfigureAwait(false);
        }
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
        using var owner = Own();
        Incoming work;
        TaskCompletionSource completion;
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(retiring, this);
            work = RequireIncoming(activity);
            if (work.Acceptance is not null)
                return work.Acceptance;
            if (work.Phase != ReceivePhase.Pending || work.Cancelled || work.Rejecting || IsTerminal(Record(work).Status))
                throw new InvalidOperationException("Offer is already finished.");
            completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
            work.Acceptance = completion.Task;
            work.Phase = ReceivePhase.Preparing;
        }
        // Install the shared owner before any destination or Changed callback
        // can reenter; the body and notifications run outside this gate.
        _ = RunAcceptanceAsync(work, completion, cancellationToken, OwnContinuation());
        return completion.Task;
    }

    private async Task RunAcceptanceAsync(Incoming work, TaskCompletionSource completion, CancellationToken cancellationToken, IDisposable owner)
    {
        using var ownership = owner;
        try
        {
            if (!await ReceiveAsync(work, cancellationToken).ConfigureAwait(false))
            {
                completion.TrySetException(new IOException("Unable to prepare incoming content. Check the receive directory."));
                return;
            }
            completion.TrySetResult();
        }
        catch
        {
            TransferTerminal terminal;
            lock (gate)
            {
                terminal = work.PendingTerminal ?? (work.Cancelled || cancellationToken.IsCancellationRequested
                    ? Cancelled(work.Offer.TransferId) : Failed(work.Offer.TransferId));
                work.Phase = ReceivePhase.Finishing;
            }
            tracker.Complete(work.Id, terminal);
            lock (gate)
                work.Phase = ReceivePhase.Terminal;
            completion.TrySetException(new IOException("Unable to receive incoming content."));
        }
    }

    public async Task RejectAsync(ActivityId activity, CancellationToken cancellationToken)
    {
        using var owner = Own();
        cancellationToken.ThrowIfCancellationRequested();
        Incoming work;
        lock (gate)
        {
            work = RequireIncoming(activity);
            if (work.Acceptance is not null)
                throw new InvalidOperationException("Offer is already being received.");
            if (work.Phase is ReceivePhase.Finishing or ReceivePhase.Terminal || work.Rejecting || IsTerminal(Record(work).Status))
                return;
            work.Rejecting = true;
        }
        try
        {
            Task rejected;
            lock (gate)
            {
                if (retiring)
                    return;
                rejected = sdk.RejectAsync(work.Offer.RequestId, CancellationToken.None);
            }
            await rejected.ConfigureAwait(false);
            tracker.Complete(work.Id, new(work.Offer.TransferId, new(ShareErrorKind.TransferRejected, "Offer rejected.")));
        }
        catch (OperationCanceledException) { throw; }
        catch { tracker.Complete(work.Id, Failed(work.Offer.TransferId)); }
    }

    public async Task CancelAsync(ActivityId activity, CancellationToken cancellationToken)
    {
        using var owner = Own();
        cancellationToken.ThrowIfCancellationRequested();
        TransferId? transfer;
        Incoming? receive;
        Outgoing? send;
        lock (gate)
        {
            incoming.TryGetValue(activity, out receive);
            outgoing.TryGetValue(activity, out send);
            if (receive is null && send is null)
                throw new InvalidOperationException("Activity has no live transfer source.");
            if (receive is not null)
            {
                if (receive.Phase is ReceivePhase.Finishing or ReceivePhase.Terminal || IsTerminal(Record(receive).Status))
                    return;
                receive.Cancelled = true;
                receive.Cancellation.Cancel();
                transfer = receive.Offer.TransferId;
            }
            else
            {
                var record = FindSendRecord(activity, send!.Spec.Device);
                if (IsTerminal(record.Status))
                    return;
                send.Cancelled = true;
                transfer = record.TransferId;
            }
        }
        try
        {
            Task cancelled;
            lock (gate)
            {
                if (retiring)
                    return;
                cancelled = transfer is { } id ? sdk.CancelAsync(id, cancellationToken) : Task.CompletedTask;
            }
            await cancelled.ConfigureAwait(false);
        }
        catch (OperationCanceledException) { throw; }
        catch { throw new IOException("Unable to cancel content transfer."); }
        // An accepted native operation keeps ownership until its completion
        // task settles. A caller cancelling its wait cannot release its sink.
        if (receive is not null)
        {
            lock (gate)
            {
                if (receive.Phase != ReceivePhase.Pending)
                    return;
                receive.Phase = ReceivePhase.Finishing;
            }
            tracker.Complete(receive.Id, Cancelled(transfer));
            lock (gate)
                receive.Phase = ReceivePhase.Terminal;
        }
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

    private async Task<ActivityId> StartOutgoingAsync(SourceSpec spec, ActivityId? retryOf, CancellationToken cancellationToken, ulong sequence = 0)
    {
        if (sequence == 0)
            sequence = NextDiagnosticSequence();
        using var owner = Own();
        var callerCancellation = cancellationToken;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, retirement.Token);
        cancellationToken = linked.Token;
        cancellationToken.ThrowIfCancellationRequested();
        using var reservation = tracker.ReserveOutgoing();
        TransientSource? stage = null;
        try
        {
            if (spec.Kind == ContentKind.Image && spec.Source is null)
                stage = await files.StageClipboardImageAsync(spec.Preview!.ImageBytes.AsMemory(), spec.Mime, cancellationToken).ConfigureAwait(false);
            callerCancellation.ThrowIfCancellationRequested();
            cancellationToken.ThrowIfCancellationRequested();
            PendingSend pending;
            Report(TransferDiagnosticStage.CommandSubmit, NativeDiagnosticOutcome.Began, DiagnosticDirection.Send, spec.Kind, sequence);
            try
            {
                lock (gate)
                {
                    callerCancellation.ThrowIfCancellationRequested();
                    cancellationToken.ThrowIfCancellationRequested();
                    pending = spec.Kind == ContentKind.Text ? sdk.BeginSendText(spec.Device, spec.Preview!.Text!)
                        : sdk.BeginSendFile(spec.Device, spec.Kind, stage?.Source ?? spec.Source!);
                }
            }
            catch
            {
                Report(TransferDiagnosticStage.CommandSubmit, NativeDiagnosticOutcome.Failed, DiagnosticDirection.Send, spec.Kind, sequence, ShareErrorKind.StorageFailed);
                throw new IOException("Unable to submit content transfer.");
            }
            if (pending.OperationId.Value == 0)
            {
                Report(TransferDiagnosticStage.CommandSubmit, NativeDiagnosticOutcome.Failed, DiagnosticDirection.Send, spec.Kind, sequence, ShareErrorKind.InvalidInput);
                throw new IOException("Content transfer was not accepted.");
            }
            Report(TransferDiagnosticStage.CommandSubmit, NativeDiagnosticOutcome.Succeeded, DiagnosticDirection.Send, spec.Kind, sequence);
            ActivityRecord record;
            try
            {
                record = reservation.Commit(spec.Device, pending.OperationId, spec.Kind, spec.Name, spec.Mime, spec.Bytes, spec.Origin, spec.Preview, retryOf);
            }
            catch
            {
                // An invalid SDK identity must not release native source ownership.
                _ = RetireUntrackedAsync(pending.Completion, stage, OwnContinuation());
                stage = null;
                throw;
            }
            var work = new Outgoing(spec, stage, sequence);
            lock (gate)
                outgoing.Add(record.Id, work);
            stage = null;
            work.Cancellation = callerCancellation.Register(() => _ = RequestCancellationAsync(record.Id));
            _ = FinishOutgoingAsync(record.Id, work, pending.Completion, OwnContinuation());
            return record.Id;
        }
        finally { if (stage is not null) await stage.DisposeAsync().ConfigureAwait(false); }
    }

    private async Task FinishOutgoingAsync(ActivityId id, Outgoing work, Task<TransferTerminal> completion, IDisposable owner)
    {
        using var ownership = owner;
        TransferTerminal terminal;
        try
        {
            terminal = await completion.ConfigureAwait(false);
        }
        catch { terminal = Failed(null); }
        work.Cancellation.Dispose();
        try
        {
            if (work.Stage is not null)
                await work.Stage.DisposeAsync().ConfigureAwait(false);
        }
        catch { terminal = Failed(terminal.TransferId); }
        tracker.Complete(id, terminal);
        Report(TransferDiagnosticStage.Completion, terminal.Failure is null ? NativeDiagnosticOutcome.Succeeded : NativeDiagnosticOutcome.Failed,
            DiagnosticDirection.Send, work.Spec.Kind, work.Sequence, terminal.Failure?.Kind, terminal.Failure?.PlatformError);
    }
    private static async Task RetireUntrackedAsync(Task<TransferTerminal> completion, TransientSource? stage, IDisposable owner)
    {
        using var ownership = owner;
        try
        {
            await completion.ConfigureAwait(false);
        }
        catch { }
        finally { if (stage is not null) try { await stage.DisposeAsync().ConfigureAwait(false); } catch { } }
    }

    private async Task<bool> ReceiveAsync(Incoming work, CancellationToken cancellationToken)
    {
        TransientDestination? text = null;
        PreparedReceive? receive = null;
        TransferTerminal terminal = new(work.Offer.TransferId, null);
        string? finalPath = null;
        ShareContent? preview = null;
        var preparingDestination = false;
        var submittingAcceptance = false;
        var preparationFailed = false;
        var submitCancellation = false;
        using var cancellation = cancellationToken.Register(() => _ = RequestCancellationAsync(work.Id));
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, work.Cancellation.Token);
        var contentCancellation = linked.Token;
        try
        {
            if (work.Offer.Kind is not (ContentKind.Text or ContentKind.Image or ContentKind.File))
                throw new IOException("Unsupported incoming content.");
            if (work.Offer.Kind == ContentKind.Image && (!work.Offer.MimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase) ||
                work.Offer.ByteCount is 0 or > 100UL * 1024 * 1024))
                throw new IOException("Invalid incoming image metadata.");
            preparingDestination = true;
            Report(TransferDiagnosticStage.DestinationPrepare, NativeDiagnosticOutcome.Began,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence);
            if (work.Offer.Kind == ContentKind.Text)
                text = await files.CreateTextDestinationAsync(contentCancellation).ConfigureAwait(false);
            else
                receive = await files.CreateReceiveAsync(work.Offer, settings().ReceiveDirectory ?? string.Empty, contentCancellation).ConfigureAwait(false);
            preparingDestination = false;
            Report(TransferDiagnosticStage.DestinationPrepare, NativeDiagnosticOutcome.Succeeded,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence);
            contentCancellation.ThrowIfCancellationRequested();
            tracker.ApplyProgress(new(work.Offer.TransferId, 0, work.Offer.ByteCount));
            Task accepted;
            submittingAcceptance = true;
            Report(TransferDiagnosticStage.AcceptSubmit, NativeDiagnosticOutcome.Began,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence);
            lock (gate)
            {
                // A reentrant cancel during progress must retire the prepared
                // sink without submitting acceptance after the request ended.
                contentCancellation.ThrowIfCancellationRequested();
                if (retiring)
                    throw new OperationCanceledException();
                if (work.PendingTerminal is not null || IsTerminal(Record(work).Status))
                    throw new OperationCanceledException();
                work.Phase = ReceivePhase.Submitted;
                accepted = sdk.AcceptAsync(work.Offer.RequestId, text?.Path ?? receive!.StagingPath, CancellationToken.None);
            }
            await accepted.ConfigureAwait(false);
            submittingAcceptance = false;
            Report(TransferDiagnosticStage.AcceptSubmit, NativeDiagnosticOutcome.Succeeded,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence);
            terminal = await work.NativeCompletion.Task.ConfigureAwait(false);
            if (terminal.Failure is null && text is not null)
            {
                var bytes = await File.ReadAllBytesAsync(text.Path, contentCancellation).ConfigureAwait(false);
                var body = new UTF8Encoding(false, true).GetString(bytes);
                preview = new(ContentKind.Text, body, work.Offer.MimeType, []);
            }
            else if (terminal.Failure is null)
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
        catch (OperationCanceledException)
        {
            if (submittingAcceptance)
                Report(TransferDiagnosticStage.AcceptSubmit, NativeDiagnosticOutcome.Cancelled,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence);
            terminal = Cancelled(work.Offer.TransferId);
        }
        catch
        {
            if (preparingDestination)
                Report(TransferDiagnosticStage.DestinationPrepare, NativeDiagnosticOutcome.Failed,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence, ShareErrorKind.StorageFailed);
            else if (submittingAcceptance)
                Report(TransferDiagnosticStage.AcceptSubmit, NativeDiagnosticOutcome.Failed,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence, ShareErrorKind.StorageFailed);
            if (preparingDestination && work.Offer.Kind is ContentKind.Image or ContentKind.File)
                preparationFailed = true;
            else
                terminal = Failed(work.Offer.TransferId);
        }
        finally
        {
            try
            {
                if (text is not null)
                    await text.DisposeAsync().ConfigureAwait(false);
                if (receive is not null && finalPath is null)
                    await files.AbortAsync(receive).ConfigureAwait(false);
            }
            catch { preparationFailed = false; terminal = Failed(work.Offer.TransferId); finalPath = null; }
        }
        lock (gate)
        {
            // Cleanup has finished, but its owner still guards admission/control.
            // Rollback and cancellation/native termination arbitrate at this
            // single transition; no later caller can erase a winning terminal.
            if (finalPath is not null)
                terminal = new(work.Offer.TransferId, null);
            else if (work.PendingTerminal is { } pending)
                terminal = pending;
            else if (work.Cancelled || cancellationToken.IsCancellationRequested)
            {
                terminal = Cancelled(work.Offer.TransferId);
                // A caller-token callback may still be queued behind this gate.
                // Claim its native cancellation here before terminalizing, so
                // that callback cannot lose the request or submit it twice.
                submitCancellation = !work.Cancelled;
                work.Cancelled = true;
            }
            else if (preparationFailed)
            {
                work.Phase = ReceivePhase.Pending;
                work.Acceptance = null;
                return false; // Only a still-live Image/File prompt can repair its directory.
            }
            work.Phase = ReceivePhase.Finishing;
        }
        if (submitCancellation)
        {
            try
            {
                Task cancelled;
                lock (gate)
                    cancelled = retiring ? Task.CompletedTask : sdk.CancelAsync(work.Offer.TransferId, CancellationToken.None);
                await cancelled.ConfigureAwait(false);
            }
            catch { /* Terminal cancellation retains no native error payload. */ }
        }
        if (finalPath is not null)
        {
            // Atomic publication wins over a late cancellation. No read, file
            // cleanup or cancellable work can turn an exposed final into failure.
            try
            {
                if (preview is not null)
                    tracker.PublishIncomingPreview(work.Id, preview);
            }
            finally { tracker.Complete(work.Id, new(work.Offer.TransferId, null), finalPath); }
            lock (gate)
                work.Phase = ReceivePhase.Terminal;
            try
            {
                await receive!.DisposeAsync().ConfigureAwait(false);
            }
            catch { }
            Report(TransferDiagnosticStage.Completion, NativeDiagnosticOutcome.Succeeded,
                DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence);
            return true;
        }
        if (terminal.Failure is null && preview is not null)
        {
            tracker.PublishIncomingPreview(work.Id, preview);
        }
        tracker.Complete(work.Id, terminal, finalPath);
        lock (gate)
            work.Phase = ReceivePhase.Terminal;
        Report(TransferDiagnosticStage.Completion, terminal.Failure is null ? NativeDiagnosticOutcome.Succeeded :
            terminal.Failure.Kind == ShareErrorKind.Cancelled ? NativeDiagnosticOutcome.Cancelled : NativeDiagnosticOutcome.Failed,
            DiagnosticDirection.Receive, work.Offer.Kind, work.Sequence, terminal.Failure?.Kind, terminal.Failure?.PlatformError);
        return true;
    }

    private static async Task<byte[]> ReadImageAsync(string path, ulong byteCount, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.Asynchronous);
        if (stream.Length != (long)byteCount)
            throw new IOException("Incoming image length is invalid.");
        var bytes = new byte[checked((int)byteCount)];
        await stream.ReadExactlyAsync(bytes, cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        if (stream.ReadByte() != -1)
            throw new IOException("Incoming image length is invalid.");
        return bytes;
    }

    private void OnEvent(object? sender, ShareSdkEvent value)
    {
        if (value is ShareSdkEvent.TransferCompleted completed)
        {
            Incoming? prompt = null;
            var apply = false;
            lock (gate)
            {
                if (completed.Terminal.TransferId is { } transfer && incomingTransfers.Contains(transfer))
                {
                    var work = incoming.Values.FirstOrDefault(work => work.Offer.TransferId == transfer);
                    var terminal = new TransferTerminal(transfer, completed.Terminal.Failure is { } failure
                        ? new(failure.Kind, "Content transfer failed.", failure.PlatformError) : null);
                    if (work?.Phase == ReceivePhase.Submitted)
                    {
                        if (terminal.Failure is not null)
                            work.PendingTerminal ??= terminal;
                        work.NativeCompletion.TrySetResult(terminal);
                    }
                    else if (terminal.Failure is not null && work?.Phase == ReceivePhase.Preparing)
                    {
                        // The receive owner must retire its destination before
                        // releasing drain. Retain only kind + stable diagnostics.
                        work.PendingTerminal ??= terminal;
                    }
                    else if (terminal.Failure is not null && (work is null || work.Phase == ReceivePhase.Pending))
                    {
                        prompt = work;
                        if (prompt is not null)
                            prompt.Phase = ReceivePhase.Finishing;
                        apply = true;
                    }
                }
            }
            if (apply)
            {
                tracker.Apply(value);
                if (prompt is not null)
                    lock (gate)
                        prompt.Phase = ReceivePhase.Terminal;
            }
            return; // Accepted completion tasks own resource lifetimes.
        }
        using var owner = TryOwn();
        if (owner is null)
            return;
        if (value is ShareSdkEvent.OfferReceived offer)
        {
            lock (gate)
                incomingTransfers.Add(offer.Offer.TransferId);
            _ = AdmitOfferAsync(offer.Offer, OwnContinuation());
            return;
        }
        tracker.Apply(value);
        if (value is ShareSdkEvent.OutgoingTransferRegistered registered)
        {
            ActivityId? cancelled = null;
            lock (gate)
                foreach (var pair in outgoing)
                    if (pair.Value.Cancelled && FindSendRecord(pair.Key, pair.Value.Spec.Device).OperationId == registered.OperationId)
                    {
                        cancelled = pair.Key;
                        break;
                    }
            if (cancelled is { } activity)
                _ = RequestCancellationAsync(activity);
        }
    }
    private async Task AdmitOfferAsync(IncomingOffer offer, IDisposable owner)
    {
        using var ownership = owner;
        try
        {
            tracker.AddIncomingOffer(offer);
            await tracker.LoadDeviceAsync(offer.DeviceId).ConfigureAwait(false);
            var record = tracker.GetDeviceSnapshot(offer.DeviceId).Single(value => value.TransferId == offer.TransferId);
            lock (gate)
            {
                if (incoming.ContainsKey(record.Id) || IsTerminal(record.Status))
                    return;
                incoming.Add(record.Id, new(record.Id, offer, NextDiagnosticSequence()));
                if (retiring)
                    return;
            }
            bool autoAccept;
            lock (gate)
                autoAccept = !retiring && ReceivePolicy.Decide(sdk.GetTrustedDevices().Contains(offer.DeviceId), offer.Kind) == ReceiveDecision.AutoAccept;
            if (autoAccept)
                await AcceptAsync(record.Id, CancellationToken.None).ConfigureAwait(false);
        }
        catch (ActivityTracker.AdmissionBlockedException)
        {
            try
            {
                Task rejected;
                lock (gate)
                {
                    if (retiring || !blockedOffers.Add((offer.RequestId, offer.TransferId)))
                        return;
                    rejected = sdk.RejectAsync(offer.RequestId, CancellationToken.None);
                }
                await rejected.ConfigureAwait(false);
            }
            catch { /* Rejection failure is contained without storing/logging its payload. */ }
        }
        catch { /* No exception body or destination is retained or diagnosed. */ }
    }
    private async Task RequestCancellationAsync(ActivityId activity)
    {
        try
        {
            await CancelAsync(activity, CancellationToken.None).ConfigureAwait(false);
        }
        catch { }
    }

    // Admission closes before SDK stop. Every accepted producer, native
    // continuation and cleanup retains an independent lease, not tracker drain.
    // Undecided prompts have no native content owner and are retired locally.
    public void BeginRetirement()
    {
        Incoming[] receives;
        lock (gate)
        {
            if (retiring)
                return;
            retiring = true;
            tracker.BlockNewTransfers();
            receives = incoming.Values.ToArray();
            foreach (var work in receives)
                work.Cancelled = true;
        }
        retirement.Cancel();
        foreach (var work in receives)
            work.Cancellation.Cancel();
    }

    // The application owns SDK retirement and calls this AFTER Stop/Dispose.
    // Completion tasks, not cancellation or a timer, release native ownership.
    public Task RetireAsync()
    {
        BeginRetirement();
        lock (gate)
        {
            if (retired is not null)
                return retired;
            // AppLifecycle calls RetireAsync only after SDK Stop/Dispose has
            // drained native owners. Its event generation is already retired,
            // so accepted receives that saw no terminal event can now release.
            foreach (var work in incoming.Values.Where(work => work.Phase == ReceivePhase.Submitted))
            {
                var cancelled = Cancelled(work.Offer.TransferId);
                work.PendingTerminal ??= cancelled;
                work.NativeCompletion.TrySetResult(cancelled);
            }
            return retired = RetireCoreAsync();
        }
    }

    private async Task RetireCoreAsync()
    {
        Task idle;
        lock (gate)
            idle = ownersIdle.Task;
        await idle.ConfigureAwait(false);
        Incoming[] receives;
        Outgoing[] sends;
        lock (gate)
        {
            sdk.EventReceived -= OnEvent;
            receives = incoming.Values.ToArray();
            sends = outgoing.Values.ToArray();
        }
        foreach (var work in receives)
        {
            if (!IsTerminal(Record(work).Status))
                tracker.Complete(work.Id, Cancelled(work.Offer.TransferId));
            work.Cancellation.Dispose();
        }
        foreach (var work in sends)
            work.Cancellation.Dispose();
        lock (gate)
        {
            incoming.Clear();
            outgoing.Clear();
            incomingTransfers.Clear();
            blockedOffers.Clear();
        }
        retirement.Dispose();
    }

    private IDisposable Own() => TryOwn() ?? throw new ObjectDisposedException(nameof(TransferCoordinator));
    private IDisposable? TryOwn()
    {
        lock (gate)
            return retiring ? null : OwnContinuation();
    }
    private IDisposable OwnContinuation()
    {
        lock (gate)
        {
            if (owners++ == 0)
                ownersIdle = new(TaskCreationOptions.RunContinuationsAsynchronously);
            return new Owner(this);
        }
    }
    private void ReleaseOwner()
    {
        lock (gate)
            if (--owners == 0)
                ownersIdle.TrySetResult();
    }
    private static TaskCompletionSource CompletedSignal()
    {
        var signal = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        signal.SetResult();
        return signal;
    }
    private sealed class Owner(TransferCoordinator coordinator) : IDisposable
    {
        private TransferCoordinator? value = coordinator;
        public void Dispose() => Interlocked.Exchange(ref value, null)?.ReleaseOwner();
    }
    private Incoming RequireIncoming(ActivityId activity) => incoming.TryGetValue(activity, out var work) ? work : throw new InvalidOperationException("Incoming offer is unavailable in this process.");
    private ActivityRecord Record(Incoming work) => tracker.GetDeviceSnapshot(work.Offer.DeviceId).Single(value => value.Id == work.Id);
    private ActivityRecord FindSendRecord(ActivityId id, DeviceId device) => tracker.GetDeviceSnapshot(device).Single(value => value.Id == id);
    private static bool IsTerminal(ActivityStatus status) => status is ActivityStatus.Completed or ActivityStatus.Failed or ActivityStatus.Cancelled or ActivityStatus.Rejected;
    private static bool IsSourceError(Exception error) => error is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException;
    private ulong NextDiagnosticSequence() => (ulong)Interlocked.Increment(ref nextDiagnosticSequence);
    private void Report(TransferDiagnosticStage stage, NativeDiagnosticOutcome outcome, DiagnosticDirection direction,
        ContentKind content, ulong sequence, ShareErrorKind? category = null, long? platformCode = null)
    {
        if (diagnostic is null)
            return;
        try
        {
            diagnostic(new(stage, outcome, direction, content switch
            {
                ContentKind.Text => DiagnosticContentKind.Text,
                ContentKind.Image => DiagnosticContentKind.Image,
                ContentKind.File => DiagnosticContentKind.File,
                _ => DiagnosticContentKind.Unknown
            }, sequence, category, platformCode));
        }
        catch { /* Diagnostic delivery must not affect transfer state. */ }
    }
    private static TransferTerminal Failed(TransferId? transfer) => new(transfer, new(ShareErrorKind.StorageFailed, "Content transfer failed."));
    private static TransferTerminal Cancelled(TransferId? transfer) => new(transfer, new(ShareErrorKind.Cancelled, "Content transfer cancelled."));
    private static string ImageMime(string extension) => extension.ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".bmp" => "image/bmp",
        _ => "application/octet-stream"
    };
    private sealed record SourceSpec(DeviceId Device, ContentKind Kind, ShareOrigin Origin, string Name, string Mime, ulong Bytes, ShareContent? Preview, ShareSource? Source);
    private sealed class Outgoing(SourceSpec spec, TransientSource? stage, ulong sequence)
    {
        public SourceSpec Spec { get; } = spec; public TransientSource? Stage { get; } = stage;
        public ulong Sequence { get; } = sequence;
        public CancellationTokenRegistration Cancellation
        {
            get; set;
        }
        public bool Cancelled
        {
            get; set;
        }
    }
    private sealed class Incoming(ActivityId id, IncomingOffer offer, ulong sequence)
    {
        public ActivityId Id { get; } = id; public IncomingOffer Offer { get; } = offer;
        public ulong Sequence { get; } = sequence;
        public Task? Acceptance
        {
            get; set;
        }
        public bool Cancelled
        {
            get; set;
        }
        public bool Rejecting
        {
            get; set;
        }
        public ReceivePhase Phase
        {
            get; set;
        }
        public TransferTerminal? PendingTerminal
        {
            get; set;
        }
        public TaskCompletionSource<TransferTerminal> NativeCompletion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public CancellationTokenSource Cancellation { get; } = new();
        public string? PublishedPath
        {
            get; set;
        }
    }
    private enum ReceivePhase
    {
        Pending, Preparing, Submitted, Finishing, Terminal
    }
}
