using Microsoft.UI.Xaml;
using Microsoft.UI.Windowing;
using UniShare.App.Features.Shell;
using Windows.Graphics;
namespace UniShare.App;
public sealed partial class MainWindow : Window
{
    public void RebuildShell() => ShellHost.Content = new ShellPage(CompositionRoot.Create(Environment.GetCommandLineArgs()));
    public MainWindow(ShellProjection model)
    {
        InitializeComponent();
        Title = Strings.Get("AppTitle");
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(TitleBar);
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 960;
            presenter.PreferredMinimumHeight = 640;
        }
        AppWindow.Resize(new SizeInt32(1440, 900));
        var args = Environment.GetCommandLineArgs();
        if (args.Contains("--compact")) AppWindow.Resize(new SizeInt32(960, 640));
        if (args.Contains("--capture-size")) AppWindow.Resize(new SizeInt32(1536, 1024));
        Root.RequestedTheme = args.Contains("--dark") ? ElementTheme.Dark : args.Contains("--light") ? ElementTheme.Light : ElementTheme.Default;
        ShellHost.Content = new ShellPage(model);
        var snapshotIndex = Array.IndexOf(args, "--snapshot");
        if (model.IsDemo && snapshotIndex >= 0 && snapshotIndex + 1 < args.Length)
        {
            var timer = DispatcherQueue.CreateTimer();
            timer.Interval = TimeSpan.FromSeconds(2);
            timer.IsRepeating = false;
            timer.Tick += async (_, _) =>
            {
                try { await DemoSnapshotService.CaptureAsync(Root, args[snapshotIndex + 1]); }
                catch (Exception error)
                {
                    // Capture diagnostics contain no activity text or file payload.
                    await System.IO.File.WriteAllTextAsync(args[snapshotIndex + 1] + ".error.txt", error.ToString());
                }
                finally { Close(); }
            };
            timer.Start();
        }
    }
}
