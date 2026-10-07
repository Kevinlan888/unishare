using Microsoft.UI.Xaml.Controls;
namespace UniShare.App.Features.Devices;

public sealed partial class DevicesPage : UserControl
{
    public DevicesPage(ShellProjection model)
    {
        InitializeComponent();
        DataContext = model;
    }
}
