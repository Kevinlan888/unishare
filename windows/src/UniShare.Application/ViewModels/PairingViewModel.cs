using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.ViewModels;

public sealed class PairingViewModel : CommandViewModel, IDisposable
{
    private readonly IShareSdkClient sdk;
    private ShareSdkEvent.PairingRequested? pendingRequest;
    private bool disposed;

    public PairingViewModel(IShareSdkClient sdk, IViewModelDispatcher dispatcher) : base(dispatcher)
    {
        this.sdk = sdk;
        ConfirmMatchesCommand = Command(() => CompleteAsync(true), () => PendingRequest is not null, "Pairing.ConfirmFailed");
        RejectCommand = Command(() => CompleteAsync(false), () => PendingRequest is not null, "Pairing.RejectFailed");
        sdk.EventReceived += OnEvent;
    }
    public ShareSdkEvent.PairingRequested? PendingRequest
    {
        get => pendingRequest;
        private set { if (SetProperty(ref pendingRequest, value)) NotifyCommands(); }
    }
    public IAsyncRelayCommand ConfirmMatchesCommand { get; }
    public IAsyncRelayCommand RejectCommand { get; }
    private async Task CompleteAsync(bool matches)
    {
        var request = PendingRequest!;
        if (matches) await sdk.ConfirmPairingAsync(request.Request, true, CancellationToken.None).ConfigureAwait(false);
        else await sdk.RejectPairingAsync(request.Request, CancellationToken.None).ConfigureAwait(false);
        await Dispatcher.InvokeAsync(() => { if (ReferenceEquals(PendingRequest, request)) PendingRequest = null; }).ConfigureAwait(false);
    }
    private void OnEvent(object? sender, ShareSdkEvent value) => Dispatcher.Post(() =>
    {
        if (disposed) return;
        if (value is ShareSdkEvent.PairingRequested requested) PendingRequest = requested;
        else if (value is ShareSdkEvent.PairingCompleted completed && PendingRequest?.Request == completed.Request) PendingRequest = null;
    });
    public void Dispose() { if (disposed) return; disposed = true; sdk.EventReceived -= OnEvent; }
}
