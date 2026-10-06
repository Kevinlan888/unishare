using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using UniShare.Application.Models;
namespace UniShare.App.Features.Settings;
public sealed partial class SettingsPage : UserControl
{
 private readonly ShellProjection model;
 private bool initializing = true;
 private bool updatingStartup;
 public SettingsPage(ShellProjection model)
 {
     InitializeComponent(); this.model = model; DataContext=model;
     var settings = model.Runtime?.Preferences.Settings ?? new AppSettings();
     DemoSettingsInfo.IsOpen = model.IsDemo;
     DeviceNameSetting.Text = settings.DeviceName;
     ReceiveFolderSetting.Text = settings.ReceiveDirectory ?? string.Empty;
     NotificationSetting.IsOn = settings.NotificationsEnabled;
     updatingStartup = true;
     StartupChoice.IsEnabled = model.Runtime?.Startup.Available == true;
     StartupChoice.IsOn = model.Runtime?.Startup.Enabled == true;
     updatingStartup = false;
     ThemeChoice.SelectedIndex = settings.Theme switch { ThemePolicy.Light => 1, ThemePolicy.Dark => 2, _ => 0 };
     LanguageChoice.SelectedIndex = settings.Language switch { LanguagePolicy.English => 1, LanguagePolicy.SimplifiedChinese => 2, _ => 0 };
     initializing = false;
 }
 private async void StartupChanged(object sender, RoutedEventArgs e)
 {
     if (initializing || updatingStartup || model?.Runtime is not { } runtime) return;
     updatingStartup = true; StartupChoice.IsEnabled = false;
     try
     {
         var result = await runtime.SetStartupAsync(StartupChoice.IsOn);
         if (!result.Saved) ShowSaveError(result.ErrorResourceKey ?? "Settings.SaveFailed"); else SettingsSaveError.IsOpen = false;
     }
     catch { ShowSaveError("Settings.SaveFailed"); }
     finally { StartupChoice.IsOn = runtime.Startup.Enabled; StartupChoice.IsEnabled = runtime.Startup.Available; updatingStartup = false; }
 }
 private async void DeviceNameChanged(object sender, TextChangedEventArgs e)
 {
     if (initializing) return;
     var name = DeviceNameSetting.Text.Trim();
     await SaveAsync(value => value with { DeviceName = name });
 }
 private async void NotificationChanged(object sender, RoutedEventArgs e)
 {
     if (!initializing) await SaveAsync(value => value with { NotificationsEnabled = NotificationSetting.IsOn });
 }
 private async void ThemeChanged(object sender, SelectionChangedEventArgs e)
 {
     if (initializing) return;
     var theme = ThemeChoice.SelectedIndex switch { 1 => ThemePolicy.Light, 2 => ThemePolicy.Dark, _ => ThemePolicy.System };
     if(XamlRoot?.Content is FrameworkElement root) root.RequestedTheme = theme switch { ThemePolicy.Light => ElementTheme.Light, ThemePolicy.Dark => ElementTheme.Dark, _ => ElementTheme.Default };
     await SaveAsync(value => value with { Theme = theme });
 }
 private async void LanguageChanged(object sender, SelectionChangedEventArgs e)
 {
     if (initializing || XamlRoot is null || model.Runtime is not { } runtime) return;
     var language = LanguageChoice.SelectedIndex switch { 1 => LanguagePolicy.English, 2 => LanguagePolicy.SimplifiedChinese, _ => LanguagePolicy.System };
     runtime.ApplyLanguage(language);
     var result = await runtime.UpdateSettingsAsync(value => value with { Language = language });
     if (!result.Saved) model.Notice = Strings.Get(result.ErrorResourceKey ?? "Settings.SaveFailed");
 }
 private async Task<bool> SaveAsync(Func<AppSettings, AppSettings> update)
 {
     if (model.Runtime is not { } runtime) return true;
     var result = await runtime.UpdateSettingsAsync(update);
     if (result.Saved) { SettingsSaveError.IsOpen = false; return true; }
     ShowSaveError(result.ErrorResourceKey ?? "Settings.SaveFailed"); return false;
 }
 private void ShowSaveError(string key) { SettingsSaveError.Message = Strings.Get(key); SettingsSaveError.IsOpen = true; }
}
