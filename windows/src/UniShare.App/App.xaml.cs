using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.Globalization;
using UniShare.App.Services;
namespace UniShare.App;

public partial class App : Microsoft.UI.Xaml.Application
{
    public MainWindow? MainWindow
    {
        get; private set;
    }
    private AppRuntime? runtime;
    private AppInstance? instance;
    public App()
    {
        UnhandledException += (_, args) => DemoSnapshotService.WriteFailure(args.Exception);
        var args = Environment.GetCommandLineArgs();
        if (args.Contains("--en-US"))
            ApplicationLanguages.PrimaryLanguageOverride = "en-US";
        if (args.Contains("--zh-CN"))
            ApplicationLanguages.PrimaryLanguageOverride = "zh-CN";
        try
        {
            InitializeComponent();
        }
        catch (Exception error) { DemoSnapshotService.WriteFailure(error); throw; }
    }
    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            if (runtime is not null)
            {
                await runtime.Lifecycle.ActivateAsync();
                return;
            }
            instance = AppInstance.FindOrRegisterForKey("UniShare.Windows");
            if (!instance.IsCurrent)
            {
                await instance.RedirectActivationToAsync(AppInstance.GetCurrent().GetActivatedEventArgs());
                Exit();
                return;
            }
            instance.Activated += OnActivated;
            var model = CompositionRoot.Create(Environment.GetCommandLineArgs());
            MainWindow = new MainWindow(model);
            runtime = CompositionRoot.CreateRuntime(MainWindow, model, () => { instance.Activated -= OnActivated; instance.UnregisterKey(); Exit(); });
            // Notification service is registered before querying App SDK activation.
            var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
            if (activation.Kind != ExtendedActivationKind.AppNotification && activation.Kind != ExtendedActivationKind.StartupTask)
                await runtime.Lifecycle.ActivateAsync();
            await runtime.InitializeAsync();
            if (activation.Kind == ExtendedActivationKind.AppNotification && activation.Data is AppNotificationActivatedEventArgs notification)
                await runtime.ActivateNotificationAsync(notification.Argument);
        }
        catch (Exception error) { DemoSnapshotService.WriteFailure(error); Exit(); }
    }
    private void OnActivated(object? sender, AppActivationArguments args)
    {
        MainWindow?.DispatcherQueue.TryEnqueue(async () =>
        {
            if (runtime is null)
                return;
            if (args.Kind == ExtendedActivationKind.AppNotification && args.Data is AppNotificationActivatedEventArgs notification)
                await runtime.ActivateNotificationAsync(notification.Argument);
            else if (args.Kind != ExtendedActivationKind.StartupTask)
                await runtime.Lifecycle.ActivateAsync();
        });
    }
    public void ChangeLanguage(string? language)
    {
        Microsoft.Windows.Globalization.ApplicationLanguages.PrimaryLanguageOverride = language ?? string.Empty;
        MainWindow?.RefreshLanguage();
    }
}
