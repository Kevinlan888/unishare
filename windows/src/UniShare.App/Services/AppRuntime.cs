using System.Globalization;
using Microsoft.UI.Dispatching;
using UniShare.App.Presentation;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.ViewModels;
using UniShare.Infrastructure.Files;
using UniShare.Infrastructure.Logging;
using UniShare.Infrastructure.Storage;
using UniShare.Native;

namespace UniShare.App.Services;

// The host owns this one composition for the process, never a feature page.
public sealed class AppRuntime : IDisposable, IRuntimeProjection
{
    private readonly IShareSdkClient sdk;
    private readonly ActivityTracker tracker;
    private readonly TransferCoordinator transfers;
    private readonly JsonSettingsStore settingsStore = new();
    private readonly PrivacyDiagnosticSink diagnostics = new();
    private readonly DispatcherQueue dispatcher;
    private readonly DispatcherAdapter bindingDispatcher;
    private readonly Dictionary<DeviceId, DeviceSessionViewModel> sessionModels = [];
    private readonly ShellProjection model;
    private readonly AppNotificationService notifications;
    private readonly TrayIconService tray;
    private readonly AppEventBridge bridge;
    private readonly RuntimeProjectionAdapter projection;
    private readonly MainWindow window;
    private readonly TaskCompletionSource ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private bool disposed;
    public ShareSession Session { get; }
    public PairingViewModel Pairing { get; }
    public AppLifecycleCoordinator Lifecycle { get; }
    public StartupTaskService Startup { get; }
    public SettingsViewModel Preferences { get; }

    public AppRuntime(MainWindow window, ShellProjection model, Action exit)
    {
        this.window = window;
        this.model = model;
        dispatcher = window.DispatcherQueue;
        bindingDispatcher = new(dispatcher);
        SettingsViewModel? preferences = null;
        Preferences = preferences = new(settingsStore, new WindowsViewModelFiles(() => preferences?.Settings.ReceiveDirectory), bindingDispatcher, new());
        sdk = model.IsDemo ? new DemoSdkClient() : new NativeShareSdkClient(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UniShare", "Identity"),
            (stage, outcome, failure, error) => TryLog(DiagnosticComponent.Sdk, stage, outcome, failure, error),
            record => { try { diagnostics.WriteNativeEvent(record); } catch { /* Logging is best-effort. */ } },
            record => { try { diagnostics.WriteTransferEvent(record); } catch { /* Logging is best-effort. */ } });
        tracker = new(new SqliteActivityRepository(), new SystemClock());
        Session = new(sdk, tracker);
        transfers = new(sdk, tracker, new ReceiveFileStore(), () => Preferences.Settings,
            record => { try { diagnostics.WriteTransferEvent(record); } catch { /* Logging is best-effort. */ } });
        Pairing = new(sdk, bindingDispatcher);
        Startup = new(new StartupTaskPlatform(), model.IsDemo);
        notifications = new(new AppNotificationPlatform(), Strings.Get, arguments => Dispatch(() => _ = ActivateNotificationAsync(arguments)));
        AppLifecycleCoordinator? lifecycle = null;
        tray = new(new Win32TrayPlatform(), Strings.Get, command =>
        {
            if (lifecycle is null)
                return;
            _ = command switch
            {
                TrayCommand.Open => lifecycle.ActivateAsync(),
                TrayCommand.TogglePause => Session.State is SessionState.Paused or SessionState.Faulted ? lifecycle.ResumeAsync() : lifecycle.PauseAsync(),
                _ => lifecycle.ExitAsync()
            };
        });
        var presence = new WindowController(window, tray, request => { if (Preferences.Settings.NotificationsEnabled) notifications.Show(request); },
            Dispatch, exit, Dispose);
        Lifecycle = lifecycle = new(Session, presence, new CompositeShutdown(new TransferShutdown(transfers, tracker), Preferences));
        bridge = new(sdk, Session, tracker, transfers, presence);
        model.Runtime = this;
        projection = new(tracker, this, Dispatch);
        sdk.EventReceived += OnSdkEvent;
        window.AttachLifecycle(Lifecycle, Pairing);
    }
    public async Task InitializeAsync()
    {
        var stage = DiagnosticStage.SettingsLoad;
        try
        {
            if (!model.IsDemo)
            {
                TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Began);
                Preferences.AcceptLoadedSettings(await settingsStore.LoadAsync(CancellationToken.None));
                window.ApplyTheme(Preferences.Settings.Theme);
                ApplyLanguage(Preferences.Settings.Language);
                TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Succeeded);
            }
            stage = DiagnosticStage.SdkInitialize;
            TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Began);
            await Session.InitializeAsync(new()
            {
                DeviceName = Preferences.Settings.DeviceName
            });
            TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Succeeded);
            stage = DiagnosticStage.SdkStart;
            TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Began);
            await Session.StartAsync();
            TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Succeeded);
            stage = DiagnosticStage.StartupTask;
            if (Preferences.Settings.StartupEnabled && Startup.Available)
                await Startup.SetEnabledAsync(true);
            projection.Refresh();
        }
        catch (Exception error)
        {
            TryLog(DiagnosticComponent.App, stage, DiagnosticOutcome.Failed,
                (error as NativeShareSdkException)?.Failure, error is NativeShareSdkException ? null : error);
            model.Notice = Strings.Get("Notification.SdkFailure");
            bridge.NotifySdkFailure();
        }
        finally { ready.TrySetResult(); }
    }

    private void TryLog(DiagnosticComponent component, DiagnosticStage stage, DiagnosticOutcome outcome,
        ShareFailure? failure = null, Exception? error = null)
    {
        try
        {
            diagnostics.WriteEvent(component, stage, outcome, failure, error);
        }
        catch { /* A diagnostics failure must not prevent sharing. */ }
    }
    public async Task ActivateNotificationAsync(string arguments)
    {
        await ready.Task;
        try
        {
            await bridge.ActivateAsync(arguments);
        }
        catch { model.Notice = Strings.Get("Transfer.RejectFailed"); }
    }
    public async Task<SettingsSaveResult> SetStartupAsync(bool enabled)
    {
        var actual = await Startup.SetEnabledAsync(enabled);
        return await UpdateSettingsAsync(value => value with { StartupEnabled = actual });
    }
    public async Task<SettingsSaveResult> UpdateSettingsAsync(Func<AppSettings, AppSettings> update)
    {
        ArgumentNullException.ThrowIfNull(update);
        var candidate = update(Preferences.Settings);
        Preferences.Settings = candidate;
        if (model.IsDemo)
            return new(true, null);
        var result = await Preferences.AutosaveAsync(candidate, CancellationToken.None);
        if (!result.Saved && result.ErrorResourceKey is { } key)
            model.Notice = Strings.Get(key);
        return result;
    }
    public void ApplyLanguage(LanguagePolicy language)
    {
        ((App)Microsoft.UI.Xaml.Application.Current).ChangeLanguage(
            LanguagePolicyResolver.Resolve(language, CultureInfo.CurrentUICulture.Name));
        tray.RefreshText();
        projection.Refresh();
    }
    private void Dispatch(Action action)
    {
        // Always queue, including on the UI thread, so cleanup and Exit retain
        // FIFO order and notification actions never reenter a native callback.
        if (!dispatcher.TryEnqueue(() => { action(); model.NotifyRuntimeState(); }))
            throw new InvalidOperationException("Application dispatcher is unavailable.");
    }
    private void OnSdkEvent(object? sender, ShareSdkEvent value)
    {
        if (!RuntimeProjectionAdapter.RequiresSdkRefresh(value))
            return;
        Dispatch(() =>
        {
            projection.Refresh();
            if (value is ShareSdkEvent.PairingCompleted { Failure: null } completed)
                SelectDevice(completed.Device);
            if (value is ShareSdkEvent.PairingCompleted { Failure: not null })
                model.Notice = Strings.Get("Pairing.ConfirmFailed");
        });
    }
    DeviceId? IRuntimeProjection.SelectedDeviceId => model.SelectedDevice?.Id;
    void IRuntimeProjection.RefreshDevices() => RefreshDevices();
    void IRuntimeProjection.RenderActivities(DeviceId device) => RenderActivities(device);
    private void RefreshDevices()
    {
        if (disposed || model.IsDemo)
            return;
        var selected = model.SelectedDevice?.Id;
        model.Recent.Clear();
        model.Nearby.Clear();
        foreach (var device in Session.Devices.Devices)
        {
            var projection = new DeviceProjection(device.Name, device.Snapshot?.Platform == DevicePlatform.Windows ? "\uE7F4" : "\uE8EA",
                Strings.Get(device.IsOnline ? "Online" : "Offline"), "", device.IsTrusted, device.IsOnline, device.Snapshot?.Platform.ToString() ?? "")
            {
                Id = device.Id,
                Blocked = device.IsBlocked
            };
            (device.IsTrusted ? model.Recent : model.Nearby).Add(projection);
        }
        if (selected is { } id)
        {
            model.SelectedDevice = model.Recent.Concat(model.Nearby).FirstOrDefault(value => value.Id == id);
            if (model.SelectedDevice?.Blocked == true)
                model.Notice = Strings.Get("IdentityWarning");
        }
        else if (model.SelectedDevice is not null)
            model.SelectedDevice = null;
        if (Session.State == SessionState.Running && model.Notice == Strings.Get("Loading"))
            model.Notice = null;
    }
    public void SelectDevice(DeviceId device)
    {
        if (disposed)
            return;
        var entry = Session.Devices.Devices.FirstOrDefault(value => value.Id == device && !value.IsBlocked);
        if (entry is null)
            return;
        Session.Devices.SelectedDevice = entry;
        var projection = model.Recent.Concat(model.Nearby).FirstOrDefault(value => value.Id == device);
        if (projection is null)
            return;
        model.SelectedDevice = projection;
        RenderActivities(device);
        _ = tracker.LoadDeviceAsync(device);
    }
    public void SelectionChanged(DeviceProjection? device)
    {
        if (model.IsDemo || device?.Id is not { } id)
            return;
        var entry = Session.Devices.Devices.FirstOrDefault(value => value.Id == id);
        if (entry is null)
            return;
        Session.Devices.SelectedDevice = entry;
        RenderActivities(id);
        _ = tracker.LoadDeviceAsync(id);
    }
    private void RenderActivities(DeviceId device)
    {
        var records = tracker.GetDeviceSnapshot(device);
        ActivityProjectionCollection.Synchronize(model.Activities,
            records.Select(record => ProjectActivity(record, includePreview: true)).ToArray());
        ActivityProjectionCollection.Synchronize(model.SharedFiles,
            RecentFileActivities.Select(records).Select(record => ProjectActivity(record, includePreview: false)).ToArray());
    }
    private ActivityProjection ProjectActivity(ActivityRecord record, bool includePreview) => new(
        record.Kind, record.Direction, record.Kind == ContentKind.Text ? Strings.Get("Content.Text") : record.DisplayName,
        includePreview ? tracker.GetPreview(record.Id)?.Text ?? "" : "", record.ByteCount.ToString(),
        record.CreatedAt.LocalDateTime.ToShortTimeString(), record.Status, record.Origin,
        ActivityProjection.ProgressPercentage(record.TransferredBytes, record.ByteCount))
    {
        Id = record.Id
    };
    private DeviceSessionViewModel? CurrentSession()
    {
        if (model.SelectedDevice?.Id is not { } id)
            return null;
        if (sessionModels.TryGetValue(id, out var existing))
            return existing;
        var device = Session.Devices.Devices.FirstOrDefault(value => value.Id == id);
        if (device is null)
            return null;
        var created = new DeviceSessionViewModel(device, Session.Devices, transfers, tracker, sdk, new WindowsViewModelFiles(() => Preferences.Settings.ReceiveDirectory), bindingDispatcher);
        sessionModels.Add(id, created);
        return created;
    }
    public Task SendTextAsync(string text) => RunAsync(async vm =>
    {
        vm.ComposerText = text;
        await vm.SendTextCommand.ExecuteAsync(null);
        if (vm.ErrorResourceKey is null && model.ComposerText == text)
            model.ComposerText = vm.ComposerText;
    });
    public Task SendClipboardAsync() => RunAsync(vm => vm.SendClipboardCommand.ExecuteAsync(null));
    public Task SendFileAsync(string path, ContentKind kind) => RunAsync(vm => (kind == ContentKind.Image ? vm.SendImageCommand : vm.SendFileCommand).ExecuteAsync(path));
    public Task ConnectAsync() => RunAsync(vm => vm.PairCommand.ExecuteAsync(null));
    public Task ForgetAsync() => RunAsync(vm => vm.ForgetCommand.ExecuteAsync(null), requireRunning: false);
    public async Task DecideAsync(ActivityProjection activity, ActivityStatus result)
    {
        if (activity.Id is not { } id)
            return;
        await RunAsync(vm => result switch
        {
            ActivityStatus.Transferring => vm.AcceptCommand.ExecuteAsync(id),
            ActivityStatus.Rejected => vm.RejectCommand.ExecuteAsync(id),
            ActivityStatus.Cancelled => vm.CancelCommand.ExecuteAsync(id),
            _ => Task.CompletedTask
        }, requireRunning: false);
    }
    public async Task RetryAsync(ActivityProjection activity)
    {
        if (activity.Id is not { } id)
            return;
        await RunAsync(vm => vm.RetryCommand.ExecuteAsync(id));
    }
    private async Task RunAsync(Func<DeviceSessionViewModel, Task> work, bool requireRunning = true)
    {
        if (requireRunning && Session.State != SessionState.Running || disposed)
            return;
        var vm = CurrentSession();
        if (vm is null)
            return;
        await work(vm);
        if (vm.ErrorResourceKey is { } key)
            model.Notice = Strings.Get(key);
        projection.Refresh();
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        projection.Dispose();
        sdk.EventReceived -= OnSdkEvent;
        foreach (var vm in sessionModels.Values)
            vm.Dispose();
        sessionModels.Clear();
        bridge.Dispose();
        Pairing.Dispose();
        notifications.Dispose();
        model.Runtime = null;
    }
    private sealed class SystemClock : IClock
    {
        public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
    }
    private sealed class CompositeShutdown(params IAppShutdown[] components) : IAppShutdown
    {
        public void BlockNewWork()
        {
            foreach (var component in components)
                component.BlockNewWork();
        }
        public Task RetireAsync() => Task.WhenAll(components.Select(component => component.RetireAsync()));
    }
    private sealed class DispatcherAdapter(DispatcherQueue queue) : IViewModelDispatcher
    {
        public void Post(Action action)
        {
            if (!queue.TryEnqueue(action.Invoke))
                throw new InvalidOperationException();
        }
        public Task InvokeAsync(Action action)
        {
            if (queue.HasThreadAccess)
            {
                action();
                return Task.CompletedTask;
            }
            var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            if (!queue.TryEnqueue(() => { try { action(); completion.SetResult(); } catch (Exception error) { completion.SetException(error); } }))
                completion.SetException(new InvalidOperationException());
            return completion.Task;
        }
    }
}
