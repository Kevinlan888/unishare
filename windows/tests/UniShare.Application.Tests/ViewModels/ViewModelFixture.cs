using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.ViewModels;

namespace UniShare.Application.Tests.ViewModels;

internal sealed class ViewModelFixture : IDisposable
{
    public readonly TestSdk Sdk = new();
    public readonly TestRepository Repository = new();
    public readonly TestClock Clock = new();
    public readonly TestFiles Files = new();
    public readonly SettingsStore Settings = new();
    public readonly UiServices Ui = new();
    public readonly QueuedDispatcher Dispatcher = new();
    public readonly ActivityTracker Tracker;
    public readonly DeviceStore Devices;
    public readonly TransferCoordinator Coordinator;
    public ViewModelFixture()
    {
        Tracker = new(Repository, Clock); Devices = new(Sdk);
        Coordinator = new(Sdk, Tracker, Files, () => Settings.Value);
    }
    public DeviceEntry Online(bool trusted = true)
    {
        var snapshot = new DeviceSnapshot(new(Guid.NewGuid()), "Laptop", DevicePlatform.Windows, 1, 0, [], 42, trusted, Clock.UtcNow);
        Sdk.Devices.Add(snapshot); if (trusted) Sdk.Trusted.Add(snapshot.Id);
        Sdk.Emit(new ShareSdkEvent.DeviceOnline(snapshot));
        return Devices.Devices.Single(value => value.Id == snapshot.Id);
    }
    public DeviceSessionViewModel Session(DeviceEntry device) => new(device, Devices, Coordinator, Tracker, Sdk, Ui, Dispatcher);
    public ShellViewModel Shell() => new(Devices, Sdk, () => Task.CompletedTask, Session, Dispatcher);
    public ActivityRecord Record(DeviceId device, ActivityStatus status = ActivityStatus.Transferring, ulong bytes = 1000,
        ShareDirection direction = ShareDirection.Outgoing, string name = "report.txt") =>
        new(new(Guid.NewGuid()), device, new(Guid.NewGuid()), null, null, direction, ContentKind.File, ShareOrigin.Direct,
            name, "application/octet-stream", bytes, Clock.UtcNow, Clock.UtcNow, null, status, 0, null, null);
    public void Dispose() => Devices.Dispose();

    internal sealed class TestClock : IClock { public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.Parse("2026-10-05T10:00:00Z"); }
    internal sealed class TestRepository : IActivityRepository
    {
        public List<ActivityRecord> Records { get; } = [];
        public ActivityQuery? LastQuery;
        public Task UpsertAsync(ActivityRecord record, CancellationToken token) { Records.RemoveAll(x => x.Id == record.Id); Records.Add(record); return Task.CompletedTask; }
        public Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken token) => Task.FromResult<IReadOnlyList<ActivityRecord>>(Records.Where(x => x.DeviceId == device).ToArray());
        public Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken token)
        {
            LastQuery = query;
            return Task.FromResult<IReadOnlyList<ActivityRecord>>(Records.Where(x =>
                (query.Text is null || x.DisplayName.Contains(query.Text, StringComparison.OrdinalIgnoreCase)) &&
                (query.Status is null || query.Status == x.Status) && (query.Direction is null || query.Direction == x.Direction)).ToArray());
        }
        public Task PruneAsync(DateTimeOffset now, int count, TimeSpan age, CancellationToken token) => Task.CompletedTask;
    }
    internal sealed class TestSdk : IShareSdkClient
    {
        public event EventHandler<ShareSdkEvent>? EventReceived;
        public List<DeviceSnapshot> Devices { get; } = [];
        public HashSet<DeviceId> Trusted { get; } = [];
        public List<string> Calls { get; } = [];
        public List<TaskCompletionSource<TransferTerminal>> Completions { get; } = [];
        public bool FailSend;
        public Func<Task>? Wait;
        public Func<Task>? Accepting;
        public Func<Task>? Cancelling;
        public bool ConfirmationWasDispatched;
        public Func<bool>? IsDispatching;
        public ShareContent Clipboard = new(ContentKind.Text, "clipboard", "text/plain", []);
        public CancellationToken ClipboardToken;
        public void Emit(ShareSdkEvent value) => EventReceived?.Invoke(this, value);
        public Task InitializeAsync(SdkOptions options, CancellationToken token) => Task.CompletedTask;
        public Task StartAsync(CancellationToken token) => Task.CompletedTask;
        public Task StopAsync(CancellationToken token) => Task.CompletedTask;
        public IReadOnlyList<DeviceSnapshot> GetDevices() => Devices;
        public IReadOnlySet<DeviceId> GetTrustedDevices() => Trusted;
        public Task ConnectAsync(DeviceId device, CancellationToken token) { Calls.Add("connect"); return Wait?.Invoke() ?? Task.CompletedTask; }
        public Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken token) { ConfirmationWasDispatched = IsDispatching?.Invoke() ?? false; Calls.Add($"confirm:{request.Value}:{matches}"); return Wait?.Invoke() ?? Task.CompletedTask; }
        public Task RejectPairingAsync(RequestId request, CancellationToken token) { Calls.Add($"pair-reject:{request.Value}"); return Wait?.Invoke() ?? Task.CompletedTask; }
        public Task ForgetDeviceAsync(DeviceId device, CancellationToken token) { Calls.Add("forget"); Trusted.Remove(device); return Task.CompletedTask; }
        public PendingSend BeginSendText(DeviceId device, string text) => Begin($"text:{text}");
        public PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source) => Begin($"{kind}:{source.Path}");
        private PendingSend Begin(string call)
        {
            if (FailSend) throw new IOException();
            Calls.Add(call); var completion = new TaskCompletionSource<TransferTerminal>(TaskCreationOptions.RunContinuationsAsynchronously);
            Completions.Add(completion); return new(new((ulong)Completions.Count), completion.Task);
        }
        public Task AcceptAsync(RequestId request, string path, CancellationToken token) { Calls.Add($"accept:{request.Value}"); return Accepting?.Invoke() ?? Wait?.Invoke() ?? Task.CompletedTask; }
        public Task RejectAsync(RequestId request, CancellationToken token) { Calls.Add($"reject:{request.Value}"); return Wait?.Invoke() ?? Task.CompletedTask; }
        public Task CancelAsync(TransferId transfer, CancellationToken token) { Calls.Add($"cancel:{transfer.Value}"); return Cancelling?.Invoke() ?? Wait?.Invoke() ?? Task.CompletedTask; }
        public async Task<ShareContent> ReadClipboardAsync(CancellationToken token) { ClipboardToken = token; Calls.Add("clipboard"); if (Wait is not null) await Wait(); return Clipboard; }
        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }
    internal sealed class TestFiles : ITransferFiles
    {
        public int Published;
        public int Aborted;
        public Func<CancellationToken, Task<TransientSource>>? Staging;
        public Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string root, CancellationToken token) => Task.FromResult<PreparedReceive>(new Receive());
        public Task<string> PublishAsync(PreparedReceive receive, CancellationToken token) { Published++; return Task.FromResult(receive.FinalPath); }
        public Task AbortAsync(PreparedReceive receive) { Aborted++; return Task.CompletedTask; }
        public Task<TransientDestination> CreateTextDestinationAsync(CancellationToken token) => throw new NotSupportedException();
        public Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mime, CancellationToken token) => Staging?.Invoke(token) ?? throw new NotSupportedException();
        internal sealed record StagedImage() : TransientSource(new ShareSource("private-stage", "clipboard.png", "image/png", 2))
        {
            public int Disposals;
            public override ValueTask DisposeAsync() { Disposals++; return ValueTask.CompletedTask; }
        }
        private sealed record Receive() : PreparedReceive("staging", "received") { public override ValueTask DisposeAsync() => ValueTask.CompletedTask; }
    }
    internal sealed class SettingsStore : ISettingsStore
    {
        public AppSettings Value = new() { ReceiveDirectory = "receive" };
        public int Saves;
        public Task<AppSettings> LoadAsync(CancellationToken token) => Task.FromResult(Value);
        public Task SaveAsync(AppSettings settings, CancellationToken token) { Saves++; Value = settings; return Task.CompletedTask; }
    }
    internal sealed class UiServices : IViewModelFiles
    {
        public bool Exists = true;
        public bool Writable = true;
        public Func<string?, Task<bool>>? Validation;
        public List<string> Opened { get; } = [];
        public bool FileExists(string path) => Exists;
        public Task OpenAsync(string path, bool location, CancellationToken token) { Opened.Add(path); return Task.CompletedTask; }
        public Task<bool> ValidateReceiveDirectoryAsync(string? path, CancellationToken token) => Validation?.Invoke(path) ?? Task.FromResult(Writable);
    }
    internal sealed class QueuedDispatcher : IViewModelDispatcher
    {
        public bool Queue;
        public bool HoldCompletions;
        public bool IsDispatching { get; private set; }
        private readonly object gate = new();
        private readonly Queue<Action> actions = new();
        private readonly List<(int Count, TaskCompletionSource Completion)> queueWaiters = [];
        private readonly List<TaskCompletionSource> completions = [];
        public void Post(Action action)
        {
            lock (gate)
            {
                if (Queue)
                {
                    actions.Enqueue(action);
                    foreach (var waiter in queueWaiters.Where(waiter => actions.Count >= waiter.Count).ToArray())
                    {
                        waiter.Completion.SetResult(); queueWaiters.Remove(waiter);
                    }
                }
                else Run(action);
            }
        }
        private void Run(Action action)
        {
            var previous = IsDispatching; IsDispatching = true;
            try { action(); } finally { IsDispatching = previous; }
        }
        public Task InvokeAsync(Action action)
        {
            var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Post(() =>
            {
                try
                {
                    action();
                    if (HoldCompletions) completions.Add(completion); else completion.SetResult();
                }
                catch (Exception error) { completion.SetException(error); }
            });
            return completion.Task;
        }
        public void Drain() { lock (gate) { while (actions.TryDequeue(out var action)) Run(action); } }
        public Task WaitForQueuedActionsAsync(int count)
        {
            lock (gate)
            {
                if (actions.Count >= count) return Task.CompletedTask;
                var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
                queueWaiters.Add((count, completion)); return completion.Task;
            }
        }
        public void ReleaseCompletions()
        {
            lock (gate) { foreach (var completion in completions) completion.SetResult(); completions.Clear(); }
        }
    }
}
