using Microsoft.UI.Xaml.Controls;
using UniShare.App.Features.Share;
namespace UniShare.App.Features.Shell;
public sealed partial class ShellPage : UserControl, IDisposable
{
    private readonly SharePage share;
    public ShellPage(ShellProjection model) { InitializeComponent(); share = new SharePage(model); ShareHost.Content = share; }
    public void ShowDeviceSession() => share.ShowDeviceSession();
    public void Dispose() { share.Dispose(); ShareHost.Content = null; }
}
