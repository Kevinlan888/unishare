using Microsoft.UI.Xaml.Controls;
using UniShare.App.Features.Share;
namespace UniShare.App.Features.Shell;
public sealed partial class ShellPage : UserControl
{
    public ShellPage(ShellProjection model) { InitializeComponent(); ShareHost.Content = new SharePage(model); }
}
