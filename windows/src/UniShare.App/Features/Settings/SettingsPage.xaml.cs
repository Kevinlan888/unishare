using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
namespace UniShare.App.Features.Settings;
public sealed partial class SettingsPage : UserControl
{
 private readonly ShellProjection model;
 private bool updatingStartup;
 public SettingsPage(ShellProjection model)
 {
     InitializeComponent(); this.model = model; DataContext=model;
     updatingStartup = true;
     StartupChoice.IsEnabled = model.Runtime?.Startup.Available == true;
     StartupChoice.IsOn = model.Runtime?.Startup.Enabled == true;
     updatingStartup = false;
 }
 private async void StartupChanged(object sender, RoutedEventArgs e)
 {
     if (updatingStartup || model?.Runtime is not { } runtime) return;
     updatingStartup = true; StartupChoice.IsEnabled = false;
     try { await runtime.SetStartupAsync(StartupChoice.IsOn); }
     catch { model.Notice = Strings.Get("Settings.SaveFailed"); }
     finally { StartupChoice.IsOn = runtime.Startup.Enabled; StartupChoice.IsEnabled = runtime.Startup.Available; updatingStartup = false; }
 }
 private void ThemeChanged(object sender, SelectionChangedEventArgs e) { if(XamlRoot?.Content is FrameworkElement root) root.RequestedTheme=ThemeChoice.SelectedIndex switch {1=>ElementTheme.Light,2=>ElementTheme.Dark,_=>ElementTheme.Default}; }
 private void LanguageChanged(object sender, SelectionChangedEventArgs e)
 {
     if (XamlRoot is null) return;
     ((App)Microsoft.UI.Xaml.Application.Current).ChangeLanguage(LanguageChoice.SelectedIndex switch { 1 => "en-US", 2 => "zh-CN", _ => null });
 }
}
