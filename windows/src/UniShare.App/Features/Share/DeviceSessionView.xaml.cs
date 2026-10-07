using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using UniShare.App.Controls;
using UniShare.App.Presentation;
using UniShare.Application.Models;
namespace UniShare.App.Features.Share;

public sealed partial class DeviceSessionView : UserControl, IDisposable
{
    private readonly ShellProjection model;
    private readonly ViewSubscriptions subscriptions;
    private readonly DeviceDetailsPane details;
    private bool disposed;
    public DeviceSessionView(ShellProjection model)
    {
        InitializeComponent();
        this.model = model;
        DataContext = model;
        details = new DeviceDetailsPane(model);
        DetailsFlyout.Content = details;
        subscriptions = new(model, property => { if (property is nameof(model.SelectedDevice) or nameof(model.Notice)) Render(); },
            model.Activities, Render, RetireCards);
        Render();
    }
    private void Render()
    {
        if (disposed)
            return;
        OnlineStatus.Text = model.SelectedDevice is null ? "" : Strings.Get(model.CanSend ? "Online" : "Offline");
        StateHint.Text = model.SelectedDevice is null ? Strings.Get("NoSelection") : model.Activities.Count == 0 ? Strings.Get("EmptyActivities") : "";
        StateHint.Visibility = string.IsNullOrEmpty(StateHint.Text) ? Visibility.Collapsed : Visibility.Visible;
        NoticeBar.Message = model.Notice ?? (model.SelectedDevice is { Online: false } ? Strings.Get("Offline") : "");
        NoticeBar.IsOpen = !string.IsNullOrEmpty(NoticeBar.Message);
        RetireCards();
        foreach (var item in model.Activities.Where(a => string.IsNullOrWhiteSpace(SearchBox.Text) || (a.Name + " " + a.Text).Contains(SearchBox.Text, StringComparison.CurrentCultureIgnoreCase)))
        {
            UserControl card = item.Status == ActivityStatus.AwaitingDecision ? new IncomingOfferCard(model, item) : item.Kind switch
            {
                ContentKind.Image => new ImageActivityCard(model, item),
                ContentKind.File => new FileActivityCard(model, item),
                _ => new TextActivityCard(model, item)
            };
            card.HorizontalAlignment = item.Direction == ShareDirection.Outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left;
            card.MaxWidth = item.Kind == ContentKind.Image ? 400 : 380;
            ActivityHost.Children.Add(card);
        }
    }
    private void RetireCards()
    {
        foreach (var card in ActivityHost.Children.OfType<IDisposable>())
            card.Dispose();
        ActivityHost.Children.Clear();
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        subscriptions.Dispose();
        details.Dispose();
        DetailsFlyout.Content = null;
        DataContext = null;
    }
    private void SearchClick(object sender, RoutedEventArgs e)
    {
        SearchBox.Visibility = SearchBox.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        if (SearchBox.Visibility == Visibility.Visible)
            SearchBox.Focus(FocusState.Programmatic);
    }
    private void SearchChanged(object sender, TextChangedEventArgs e)
    {
        if (model is not null)
            Render();
    }
    private void SendAccelerator(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        if (model.SendTextCommand.CanExecute(null))
            model.SendTextCommand.Execute(null);
        args.Handled = true;
    }
    private void ComposerDragOver(object sender, DragEventArgs e)
    {
        e.AcceptedOperation = model.CanSend && e.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems) ? Windows.ApplicationModel.DataTransfer.DataPackageOperation.Copy : Windows.ApplicationModel.DataTransfer.DataPackageOperation.None;
        e.Handled = true;
    }
    private async void ComposerDrop(object sender, DragEventArgs e)
    {
        await model.DropOneFileAsync(e.DataView);
        e.Handled = true;
    }
}
