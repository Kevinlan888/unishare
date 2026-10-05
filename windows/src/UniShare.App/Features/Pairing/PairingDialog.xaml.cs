using Microsoft.UI.Xaml.Controls;
using UniShare.Application.ViewModels;
using UniShare.App.Presentation;

namespace UniShare.App.Features.Pairing;

public sealed partial class PairingDialog : ContentDialog, IDisposable
{
    private readonly PropertyChangedSubscription subscription;
    public PairingDialog(PairingViewModel model)
    {
        InitializeComponent(); DataContext = model;
        subscription = new(model, () =>
        {
            FailureText.Text = model.ErrorResourceKey is { } key ? Strings.Get(key) : "";
            if (model.PendingRequest is null) Hide();
        });
    }
    // Let the ViewModel command complete the current request before closing;
    // failed confirmation remains visible with its localized error.
    private async void MatchClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    { args.Cancel = true; await ((PairingViewModel)DataContext).ConfirmMatchesCommand.ExecuteAsync(null); }
    private async void MismatchClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    { args.Cancel = true; await ((PairingViewModel)DataContext).RejectCommand.ExecuteAsync(null); }
    public void Dispose() => subscription.Dispose();
}
