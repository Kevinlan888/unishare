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
        ConfirmMatchesCommand = SnapshotCommand(() => PendingRequest, request => CompleteAsync(request!, true), IsCurrent, "Pairing.ConfirmFailed");
        RejectCommand = SnapshotCommand(() => PendingRequest, request => CompleteAsync(request!, false), IsCurrent, "Pairing.RejectFailed");
        sdk.EventReceived += OnEvent;
    }
    public ShareSdkEvent.PairingRequested? PendingRequest
    {
        get => Volatile.Read(ref pendingRequest);
        private set
        {
            if (ReferenceEquals(PendingRequest, value))
                return;
            Volatile.Write(ref pendingRequest, value);
            OnPropertyChanged();
            NotifyCommands();
        }
    }
    public IAsyncRelayCommand ConfirmMatchesCommand { get; }
    public IAsyncRelayCommand RejectCommand { get; }
    private bool IsCurrent(ShareSdkEvent.PairingRequested? request) => request is not null && ReferenceEquals(PendingRequest, request);
    private async Task CompleteAsync(ShareSdkEvent.PairingRequested request, bool matches)
    {
        if (matches)
            await sdk.ConfirmPairingAsync(request.Request, true, CancellationToken.None).ConfigureAwait(false);
        else
            await sdk.RejectPairingAsync(request.Request, CancellationToken.None).ConfigureAwait(false);
        await Dispatcher.InvokeAsync(() => { if (ReferenceEquals(PendingRequest, request)) PendingRequest = null; }).ConfigureAwait(false);
    }
    private void OnEvent(object? sender, ShareSdkEvent value) => Dispatcher.Post(() =>
    {
        if (disposed)
            return;
        if (value is ShareSdkEvent.PairingRequested requested)
            PendingRequest = requested;
        else if (value is ShareSdkEvent.PairingCompleted completed && PendingRequest?.Request == completed.Request)
            PendingRequest = null;
    });
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        sdk.EventReceived -= OnEvent;
    }
}
