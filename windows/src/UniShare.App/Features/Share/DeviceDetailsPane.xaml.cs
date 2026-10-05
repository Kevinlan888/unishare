using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
namespace UniShare.App.Features.Share;
public sealed partial class DeviceDetailsPane : UserControl
{
    private readonly ShellProjection model;
    public DeviceDetailsPane(ShellProjection model) { InitializeComponent(); this.model = model; DataContext = model; model.PropertyChanged += (_, args) => { if (args.PropertyName == nameof(model.SelectedDevice)) Refresh(); }; Refresh(); }
    private void Refresh() { DeviceStatus.Text = model.SelectedDevice is null ? Strings.Get("NoSelection") : Strings.Get(model.CanSend ? "Online" : "Offline"); TrustValue.Text = Strings.Get(model.SelectedDevice?.Trusted == true ? "Yes" : "No"); HeroOnlineDot.Visibility = model.CanSend ? Visibility.Visible : Visibility.Collapsed; }
    private void TextClick(object sender, RoutedEventArgs e)
    {
        // Focus the composer through the visual tree, without creating an operation.
        var root = XamlRoot.Content;
        FindComposer(root)?.Focus(FocusState.Programmatic);
    }
    private static TextBox? FindComposer(DependencyObject element)
    {
        if (element is TextBox { Name: "Composer" } composer) return composer;
        for (var i = 0; i < Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChildrenCount(element); i++)
            if (FindComposer(Microsoft.UI.Xaml.Media.VisualTreeHelper.GetChild(element, i)) is { } found) return found;
        return null;
    }
}
