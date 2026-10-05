using UniShare.Application.Models;

namespace UniShare.Application.Abstractions;

public interface IShareSdkClient : IAsyncDisposable
{
    event EventHandler<ShareSdkEvent>? EventReceived;
    Task InitializeAsync(SdkOptions options, CancellationToken cancellationToken);
    Task StartAsync(CancellationToken cancellationToken);
    Task StopAsync(CancellationToken cancellationToken);
    IReadOnlyList<DeviceSnapshot> GetDevices();
    IReadOnlySet<DeviceId> GetTrustedDevices();
    Task ConnectAsync(DeviceId device, CancellationToken cancellationToken);
    Task ConfirmPairingAsync(RequestId request, bool matches, CancellationToken cancellationToken);
    Task RejectPairingAsync(RequestId request, CancellationToken cancellationToken);
    Task ForgetDeviceAsync(DeviceId device, CancellationToken cancellationToken);
    PendingSend BeginSendText(DeviceId device, string text);
    PendingSend BeginSendFile(DeviceId device, ContentKind kind, ShareSource source);
    Task AcceptAsync(RequestId request, string stagingPath, CancellationToken cancellationToken);
    Task RejectAsync(RequestId request, CancellationToken cancellationToken);
    Task CancelAsync(TransferId transfer, CancellationToken cancellationToken);
    Task<ShareContent> ReadClipboardAsync(CancellationToken cancellationToken);
}
