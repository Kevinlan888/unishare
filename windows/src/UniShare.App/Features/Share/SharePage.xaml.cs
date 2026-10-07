using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using UniShare.App.Features.Devices;
using UniShare.App.Features.History;
using UniShare.App.Features.Settings;
using UniShare.App.Presentation;
namespace UniShare.App.Features.Share;

public sealed partial class SharePage : UserControl, IDisposable
{
    private readonly ShellProjection model;
    private readonly DeviceListPane deviceList;
    private readonly DeviceSessionView session;
    private readonly DeviceDetailsPane details;
    private readonly ShareWidthStateTracker widthState = new();
    public void ShowDeviceSession() => CenterHost.Content = session;
    public SharePage(ShellProjection model)
    {
        InitializeComponent();
        this.model = model;
        deviceList = new DeviceListPane(model);
        session = new DeviceSessionView(model);
        details = new DeviceDetailsPane(model);
        LeftHost.Content = deviceList;
        CenterHost.Content = session;
        RightHost.Content = details;
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
            var state = widthState.ChangeFor(args.NewSize.Width);
            if (state is null)
                return;
            VisualStateManager.GoToState(this, state, false);
            deviceList.SetCompact(state == "Compact");
        };
    }
    public void Dispose()
    {
        session.Dispose();
        details.Dispose();
        LeftHost.Content = null;
        CenterHost.Content = null;
        RightHost.Content = null;
    }
}
