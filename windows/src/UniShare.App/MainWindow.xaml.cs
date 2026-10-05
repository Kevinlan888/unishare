using Microsoft.UI.Xaml;
using Microsoft.UI.Windowing;
using UniShare.App.Features.Shell;
using Windows.Graphics;
using UniShare.App.Services;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.ViewModels;
using UniShare.App.Features.Pairing;
using System.ComponentModel;
namespace UniShare.App;
public sealed partial class MainWindow : Window, IWindowPlatform
{
    private readonly ShellProjection model;
    private AppLifecycleCoordinator? lifecycle;
    private PairingViewModel? pairing;
    private PairingDialog? pairingDialog;
    private bool shuttingDown;
    private readonly TaskCompletionSource rootReady = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public void RebuildShell()
    {
        (ShellHost.Content as IDisposable)?.Dispose();
        ShellHost.Content = new ShellPage(model);
    }
    public void AttachLifecycle(AppLifecycleCoordinator lifecycle, PairingViewModel pairing)
    {
        this.lifecycle = lifecycle; this.pairing = pairing;
        pairing.PropertyChanged += OnPairingChanged;
    }
    private async void OnPairingChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName != nameof(PairingViewModel.PendingRequest) || pairing?.PendingRequest is null || pairingDialog is not null || shuttingDown) return;
        await lifecycle!.ActivateAsync();
        if (Root.XamlRoot is null) await rootReady.Task;
        if (pairingDialog is not null || pairing?.PendingRequest is null || shuttingDown) return;
        using var dialog = new PairingDialog(pairing) { XamlRoot = Root.XamlRoot };
        pairingDialog = dialog;
        try { await dialog.ShowAsync(); }
        finally { pairingDialog = null; }
    }
    public void Show(DeviceId? device)
    {
        if (shuttingDown) return;
        if (device is { } id) { model.Runtime?.SelectDevice(id); (ShellHost.Content as ShellPage)?.ShowDeviceSession(); }
        AppWindow.Show(); Activate();
    }
    public void Hide() { if (!shuttingDown) AppWindow.Hide(); }
    public void Dispose()
    {
        if (shuttingDown) return; shuttingDown = true;
        if (pairing is not null) pairing.PropertyChanged -= OnPairingChanged;
        pairingDialog?.Hide(); (ShellHost.Content as IDisposable)?.Dispose(); ShellHost.Content = null; Close();
    }
    public MainWindow(ShellProjection model)
    {
        this.model = model;
        InitializeComponent();
        Root.Loaded += (_, _) => rootReady.TrySetResult();
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
        AppWindow.Closing += (_, args) => { if (!shuttingDown) { args.Cancel = true; lifecycle?.OnWindowCloseRequested(); } };
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
                finally { if (lifecycle is not null) await lifecycle.ExitAsync(); else Close(); }
            };
            timer.Start();
        }
    }
}
