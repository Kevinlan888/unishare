using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.App.Services;

// Deterministic visual mode never loads native code or changes device trust.
internal sealed class DemoSdkClient : IShareSdkClient
{
    public event EventHandler<ShareSdkEvent>? EventReceived { add { } remove { } }
    public Task InitializeAsync(SdkOptions options, CancellationToken token) => Task.CompletedTask;
    public Task StartAsync(CancellationToken token) => Task.CompletedTask;
    public Task StopAsync(CancellationToken token) => Task.CompletedTask;
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    public IReadOnlyList<DeviceSnapshot> GetDevices() => [];
    public IReadOnlySet<DeviceId> GetTrustedDevices() => new HashSet<DeviceId>();
    public Task ConnectAsync(DeviceId device, CancellationToken token) => Task.CompletedTask;
    public Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken token) => Task.CompletedTask;
    public Task RejectPairingAsync(RequestId request, CancellationToken token) => Task.CompletedTask;
    public Task ForgetDeviceAsync(DeviceId device, CancellationToken token) => Task.CompletedTask;
    public PendingSend BeginSendText(DeviceId device, string text) => throw new NotSupportedException();
    public PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source) => throw new NotSupportedException();
    public Task AcceptAsync(RequestId request, string path, CancellationToken token) => throw new NotSupportedException();
    public Task RejectAsync(RequestId request, CancellationToken token) => throw new NotSupportedException();
    public Task CancelAsync(TransferId transfer, CancellationToken token) => throw new NotSupportedException();
    public Task<ShareContent> ReadClipboardAsync(CancellationToken token) => throw new NotSupportedException();
}
