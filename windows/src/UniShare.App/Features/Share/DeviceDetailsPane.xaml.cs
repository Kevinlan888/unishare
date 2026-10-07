using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using UniShare.App.Presentation;
namespace UniShare.App.Features.Share;

public sealed partial class DeviceDetailsPane : UserControl, IDisposable
{
    private readonly ShellProjection model;
    private readonly ViewSubscriptions subscriptions;
    public DeviceDetailsPane(ShellProjection model)
    {
        InitializeComponent();
        this.model = model;
        DataContext = model;
        subscriptions = new(model, property => { if (property == nameof(model.SelectedDevice)) Refresh(); });
        model.SharedFiles.CollectionChanged += OnSharedFilesChanged;
        Refresh();
    }
    public void Dispose()
    {
        model.SharedFiles.CollectionChanged -= OnSharedFilesChanged;
        subscriptions.Dispose();
        DataContext = null;
    }
    private void OnSharedFilesChanged(object? sender, System.Collections.Specialized.NotifyCollectionChangedEventArgs e) => RefreshSharedFiles();
    private void RefreshSharedFiles() => NoSharedFiles.Visibility = model.SharedFiles.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    private void Refresh()
    {
        DeviceStatus.Text = model.SelectedDevice is null ? Strings.Get("NoSelection") : Strings.Get(model.CanSend ? "Online" : "Offline");
        TrustValue.Text = Strings.Get(model.SelectedDevice?.Trusted == true ? "Yes" : "No");
        HeroOnlineDot.Visibility = model.CanSend ? Visibility.Visible : Visibility.Collapsed;
        RefreshSharedFiles();
    }
    private void TextClick(object sender, RoutedEventArgs e)
    {
        // Focus the composer through the visual tree, without creating an operation.
        var root = XamlRoot.Content;
        FindComposer(root)?.Focus(FocusState.Programmatic);
    }
    private static TextBox? FindComposer(DependencyObject element)
    {
        if (element is TextBox { Name: "Composer" } composer)
            return composer;
        for (var i = 0; i < Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(element); i++)
            if (FindComposer(Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(element, i)) is { } found)
                return found;
        return null;
    }
}
