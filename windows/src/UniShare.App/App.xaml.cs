using Microsoft.UI.Xaml;
using Microsoft.Windows.Globalization;
namespace UniShare.App;
public partial class App : Microsoft.UI.Xaml.Application
{
    private Window? window;
    public App()
    {
        UnhandledException += (_, args) => DemoSnapshotService.WriteFailure(args.Exception);
        var args = Environment.GetCommandLineArgs();
        if (args.Contains("--en-US")) ApplicationLanguages.PrimaryLanguageOverride = "en-US";
        if (args.Contains("--zh-CN")) ApplicationLanguages.PrimaryLanguageOverride = "zh-CN";
        try { InitializeComponent(); }
        catch (Exception error) { DemoSnapshotService.WriteFailure(error); throw; }
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            window = new MainWindow(CompositionRoot.Create(Environment.GetCommandLineArgs()));
            window.Activate();
        }
        catch (Exception error) { DemoSnapshotService.WriteFailure(error); Exit(); }
    }
    public void ChangeLanguage(string? language)
    {
        Microsoft.Windows.Globalization.ApplicationLanguages.PrimaryLanguageOverride = language!;
        if (window is MainWindow main) main.RebuildShell();
    }
}
