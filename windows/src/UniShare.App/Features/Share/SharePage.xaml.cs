using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using UniShare.App.Features.Devices;
using UniShare.App.Features.History;
using UniShare.App.Features.Settings;
namespace UniShare.App.Features.Share;
public sealed partial class SharePage : UserControl
{
    private readonly ShellProjection model;
    private readonly DeviceListPane deviceList;
    private readonly DeviceSessionView session;
    public SharePage(ShellProjection model)
    {
        InitializeComponent(); this.model = model;
        deviceList = new DeviceListPane(model); session = new DeviceSessionView(model);
        LeftHost.Content = deviceList; CenterHost.Content = session; RightHost.Content = new DeviceDetailsPane(model);
        deviceList.Navigate += (_, section) => CenterHost.Content = section switch
        {
            "Devices" => new DevicesPage(model),
            "History" => new HistoryPage(model),
            "Settings" => new SettingsPage(model),
            _ => session
        };
        deviceList.DeviceSelected += (_, _) => CenterHost.Content = session;
        SizeChanged += (_, args) =>
        {
            var state = args.NewSize.Width >= 1240 ? "Wide" : args.NewSize.Width >= 1060 ? "Medium" : "Compact";
            VisualStateManager.GoToState(this, state, false);
            deviceList.SetCompact(state == "Compact");
        };
    }
}
