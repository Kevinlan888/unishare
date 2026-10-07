using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
namespace UniShare.App.Features.Share;

public sealed partial class DeviceListPane : UserControl
{
    private readonly ShellProjection model;
    private DataTemplate RecentDeviceTemplate => (DataTemplate)Resources[nameof(RecentDeviceTemplate)];
    private DataTemplate NearbyDeviceTemplate => (DataTemplate)Resources[nameof(NearbyDeviceTemplate)];
    private DataTemplate CompactDeviceTemplate => (DataTemplate)Resources[nameof(CompactDeviceTemplate)];
    private Style CompactDeviceItemContainerStyle => (Style)Resources[nameof(CompactDeviceItemContainerStyle)];
    public event EventHandler<string>? Navigate;
    public event EventHandler? DeviceSelected;
    public DeviceListPane(ShellProjection model)
    {
        InitializeComponent();
        this.model = model;
        DataContext = model;
        RecentList.SelectedItem = model.SelectedDevice;
        EmptyHint.Visibility = model.Recent.Count + model.Nearby.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(RecentList, Strings.Get("Recent"));
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(NearbyList, Strings.Get("Nearby"));
    }
    private void NavigateClick(object sender, RoutedEventArgs e)
    {
        var button = (Button)sender;
        foreach (var item in Navigation.Children.OfType<Button>())
            item.Background = (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources[item == button ? "SelectedBackground" : "SubtleFillColorTransparentBrush"];
        Navigate?.Invoke(this, (string)button.Tag);
    }
    private void SelectDevice(object sender, ItemClickEventArgs e)
    {
        model.SelectedDevice = (DeviceProjection)e.ClickedItem;
        if (ReferenceEquals(sender, RecentList))
            NearbyList.SelectedItem = null;
        else
            RecentList.SelectedItem = null;
        DeviceSelected?.Invoke(this, EventArgs.Empty);
    }
    public void SetCompact(bool compact)
    {
        foreach (var label in new[] { ShareLabel, DevicesLabel, HistoryLabel, SettingsLabel, RecentHeading, NearbyHeading })
            label.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        foreach (var button in Navigation.Children.OfType<Button>())
            button.HorizontalContentAlignment = compact ? HorizontalAlignment.Center : HorizontalAlignment.Left;
        RecentList.ItemTemplate = compact ? CompactDeviceTemplate : RecentDeviceTemplate;
        NearbyList.ItemTemplate = compact ? CompactDeviceTemplate : NearbyDeviceTemplate;
        RecentList.ItemContainerStyle = compact ? CompactDeviceItemContainerStyle : null;
        NearbyList.ItemContainerStyle = compact ? CompactDeviceItemContainerStyle : null;
        EmptyHint.Visibility = !compact && model.Recent.Count + model.Nearby.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
}
