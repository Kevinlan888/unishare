using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
namespace UniShare.App.Features.Settings;
public sealed partial class SettingsPage : UserControl
{
 public SettingsPage(ShellProjection model) { InitializeComponent(); DataContext=model; }
 private void ThemeChanged(object sender, SelectionChangedEventArgs e) { if(XamlRoot?.Content is FrameworkElement root) root.RequestedTheme=ThemeChoice.SelectedIndex switch {1=>ElementTheme.Light,2=>ElementTheme.Dark,_=>ElementTheme.Default}; }
 private void LanguageChanged(object sender, SelectionChangedEventArgs e)
 {
     if (XamlRoot is null) return;
     ((App)Microsoft.UI.Xaml.Application.Current).ChangeLanguage(LanguageChoice.SelectedIndex switch { 1 => "en-US", 2 => "zh-CN", _ => null });
 }
}
