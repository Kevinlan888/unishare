using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Streams;
using UniShare.App.Presentation;
using UniShare.Application.Models;
namespace UniShare.App.Controls;

public sealed partial class ImageActivityCard : UserControl, IDisposable
{
    private readonly ShellProjection model;
    private readonly ActivityProjection item;
    private readonly PropertyChangedSubscription subscription;
    private string? loadedPath;
    private byte[]? loadedBytes;
    private int imageGeneration;
    private bool disposed;
    private bool previewOpen;
    public ImageActivityCard(ShellProjection model, ActivityProjection item)
    {
        InitializeComponent();
        this.model = model;
        this.item = item;
        DataContext = item;
        VisualStateManager.GoToState(this, item.Direction == ShareDirection.Outgoing ? "Outgoing" : "Incoming", false);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, Strings.Get("Content." + item.Kind) + " " + item.StatusText);
        subscription = new PropertyChangedSubscription(item, Refresh);
        Refresh();
    }
    public void Dispose()
    {
        disposed = true;
        imageGeneration++;
        subscription.Dispose();
    }
    private void Refresh()
    {
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, Strings.Get("Content." + item.Kind) + " " + item.StatusText);
        StatusLabel.Visibility = item.Status == ActivityStatus.Completed ? Visibility.Collapsed : Visibility.Visible;
        DecisionPanel.Visibility = item.Status is ActivityStatus.AwaitingDecision or ActivityStatus.AwaitingPeer or ActivityStatus.Transferring or ActivityStatus.Failed ? Visibility.Visible : Visibility.Collapsed;
        AcceptButton.Visibility = RejectButton.Visibility = item.Status == ActivityStatus.AwaitingDecision ? Visibility.Visible : Visibility.Collapsed;
        CancelButton.Visibility = item.Status is ActivityStatus.AwaitingPeer or ActivityStatus.Transferring ? Visibility.Visible : Visibility.Collapsed;
        RetryButton.Visibility = item.Direction == ShareDirection.Outgoing && item.Status == ActivityStatus.Failed && model.CanSend ? Visibility.Visible : Visibility.Collapsed;
        ProgressPanel.Visibility = item.Status == ActivityStatus.Transferring ? Visibility.Visible : Visibility.Collapsed;
        MetricsPanel.Visibility = item.Status == ActivityStatus.Transferring && item.BytesPerSecond > 0 ? Visibility.Visible : Visibility.Collapsed;
        CompletedActionsPanel.Visibility = item.FileAvailable ? Visibility.Visible : Visibility.Collapsed;
        if (loadedPath != item.ImagePath || !ReferenceEquals(loadedBytes, item.ImagePreviewBytes))
        {
            loadedPath = item.ImagePath;
            loadedBytes = item.ImagePreviewBytes;
            _ = LoadThumbnailAsync(loadedPath, loadedBytes, ++imageGeneration);
        }
    }
    private async Task LoadThumbnailAsync(string? path, byte[]? bytes, int generation)
    {
        PreviewButton.Visibility = Visibility.Collapsed;
        Thumbnail.Source = null;
        var bitmap = await LoadImageAsync(path, bytes, 1024);
        if (disposed || generation != imageGeneration)
            return;
        Thumbnail.Source = bitmap;
        PreviewButton.Visibility = bitmap is null ? Visibility.Collapsed : Visibility.Visible;
    }
    private static async Task<BitmapImage?> LoadImageAsync(string? path, byte[]? bytes, int decodeWidth)
    {
        try
        {
            var bitmap = new BitmapImage { DecodePixelWidth = decodeWidth };
            if (bytes is { Length: > 0 })
            {
                using var stream = new InMemoryRandomAccessStream();
                using (var writer = new DataWriter(stream))
                {
                    writer.WriteBytes(bytes);
                    await writer.StoreAsync();
                    writer.DetachStream();
                }
                stream.Seek(0);
                await bitmap.SetSourceAsync(stream);
            }
            else if (path is not null)
            {
                var file = await StorageFile.GetFileFromPathAsync(path);
                using var stream = await file.OpenReadAsync();
                await bitmap.SetSourceAsync(stream);
            }
            else
                return null;
            return bitmap;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException or
            System.Runtime.InteropServices.COMException)
        {
            return null;
        }
    }
    private async void PreviewClick(object sender, RoutedEventArgs e)
    {
        if (previewOpen || disposed)
            return;
        previewOpen = true;
        try
        {
            var bitmap = await LoadImageAsync(item.ImagePath, item.ImagePreviewBytes, 0);
            if (bitmap is null || disposed || XamlRoot is null)
                return;
            var dialog = new ContentDialog
            {
                XamlRoot = XamlRoot,
                Title = item.Name,
                CloseButtonText = Strings.Get("Image.PreviewClose"),
                Content = new Image { Source = bitmap, Stretch = Stretch.Uniform,
                    MaxWidth = Math.Min(900, XamlRoot.Size.Width * .8),
                    MaxHeight = Math.Max(100, XamlRoot.Size.Height * .7) }
            };
            await dialog.ShowAsync();
        }
        finally { previewOpen = false; }
    }
    private void AcceptClick(object sender, RoutedEventArgs e) => model.Decide(item, ActivityStatus.Transferring);
    private void RejectClick(object sender, RoutedEventArgs e) => model.Decide(item, ActivityStatus.Rejected);
    private void CancelClick(object sender, RoutedEventArgs e) => model.Decide(item, ActivityStatus.Cancelled);
    private void RetryClick(object sender, RoutedEventArgs e) => model.Retry(item);
    private async void OpenLocationClick(object sender, RoutedEventArgs e) => await item.OpenAsync(true);
    private void RefreshFileAvailability(object sender, PointerRoutedEventArgs e) => item.RefreshFileAvailability();
    private async void OpenDoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (!item.FileAvailable)
            return;
        await item.OpenAsync(false);
        e.Handled = true;
    }
}
