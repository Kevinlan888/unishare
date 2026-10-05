using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.Tests.Fakes;

internal sealed class FakeShareSdkClient : IShareSdkClient
{
    public event EventHandler<ShareSdkEvent>? EventReceived;
    public List<string> Calls { get; } = [];
    public IReadOnlyList<DeviceSnapshot> Devices { get; set; } = [];
    public IReadOnlySet<DeviceId> Trusted { get; set; } = new HashSet<DeviceId>();
    public Func<Task>? Initializing { get; set; }
    public Func<Task>? Starting { get; set; }
    public Func<Task>? Stopping { get; set; }
    public Func<Task>? Forgetting { get; set; }
    public SdkOptions? Options { get; private set; }
    public void Emit(ShareSdkEvent value) => EventReceived?.Invoke(this, value);
    public Task InitializeAsync(SdkOptions options, CancellationToken cancellationToken)
    {
        Calls.Add("initialize");
        Options = options;
        return Initializing?.Invoke() ?? Task.CompletedTask;
    }
    public Task StartAsync(CancellationToken cancellationToken)
    {
        Calls.Add("start");
        return Starting?.Invoke() ?? Task.CompletedTask;
    }
    public Task StopAsync(CancellationToken cancellationToken)
    {
        Calls.Add("stop");
        return Stopping?.Invoke() ?? Task.CompletedTask;
    }
    public ValueTask DisposeAsync() { Calls.Add("dispose"); return ValueTask.CompletedTask; }
    public IReadOnlyList<DeviceSnapshot> GetDevices() => Devices;
    public IReadOnlySet<DeviceId> GetTrustedDevices() => Trusted;
    public async Task ForgetDeviceAsync(DeviceId device, CancellationToken cancellationToken)
    {
        Calls.Add("forget");
        if (Forgetting is not null) await Forgetting();
        Trusted = Trusted.Where(id => id != device).ToHashSet();
        Devices = Devices.Select(value => value.Id == device ? value with { IsTrusted = false } : value).ToArray();
    }
    public Task ConnectAsync(DeviceId device, CancellationToken cancellationToken) => throw new NotSupportedException();
    public Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken cancellationToken) => throw new NotSupportedException();
    public Task RejectPairingAsync(RequestId request, CancellationToken cancellationToken) => throw new NotSupportedException();
    public PendingSend BeginSendText(DeviceId device, string text) => throw new NotSupportedException();
    public PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source) => throw new NotSupportedException();
    public Task AcceptAsync(RequestId request, string stagingPath, CancellationToken cancellationToken) => throw new NotSupportedException();
    public Task RejectAsync(RequestId request, CancellationToken cancellationToken) => throw new NotSupportedException();
    public Task CancelAsync(TransferId transfer, CancellationToken cancellationToken) => throw new NotSupportedException();
    public Task<ShareContent> ReadClipboardAsync(CancellationToken cancellationToken) => throw new NotSupportedException();
}
