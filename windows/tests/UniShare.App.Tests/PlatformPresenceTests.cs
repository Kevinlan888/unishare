using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.App.Services;

namespace UniShare.App.Tests;

[TestClass]
public sealed class PlatformPresenceTests
{
    private sealed class WindowPlatform : IWindowPlatform
    {
        public int Opens, Hides, Disposals;
        public UniShare.Application.Models.DeviceId? Device;
        public void Show(UniShare.Application.Models.DeviceId? device) { Opens++; Device = device; }
        public void Hide() => Hides++;
        public void Dispose() => Disposals++;
    }
    [TestMethod]
    public void ActivationResourcesRetireBeforeWindowCloseAndProcessExit()
    {
        var window = new WindowPlatform(); var tray = new TrayIconService(new TrayPlatform(), key => key, _ => { });
        var retired = 0;
        var presence = new WindowController(window, tray, _ => { }, action => action(),
            () => Assert.AreEqual(1, retired), () => { Assert.AreEqual(0, window.Disposals); retired++; });
        presence.DisposeResources(); presence.ExitApplication(); presence.DisposeResources();
        Assert.AreEqual(1, retired); Assert.AreEqual(1, window.Disposals);
    }

    [TestMethod]
    public async Task ReceiveDirectoryProbeUsesConfiguredDefaultAndLeavesNoContentOrProbeFiles()
    {
        var root = Path.Combine(Path.GetTempPath(), "UniShare-probe-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            Assert.IsTrue(await ReceiveDirectoryValidator.ValidateAsync(null, () => root, CancellationToken.None));
            Assert.AreEqual(0, Directory.GetFiles(root).Length);
            var blocker = Path.Combine(root, "file"); await File.WriteAllTextAsync(blocker, "test");
            Assert.IsFalse(await ReceiveDirectoryValidator.ValidateAsync(blocker, () => root, CancellationToken.None));
            Assert.AreEqual("test", await File.ReadAllTextAsync(blocker));
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [TestMethod]
    public void WindowControllerUsesOneWindowAndSerializesDisposeBeforeApplicationExit()
    {
        var window = new WindowPlatform(); var platform = new TrayPlatform(); var queue = new Queue<Action>(); var exits = 0;
        var tray = new TrayIconService(platform, key => key, _ => { });
        var presence = new WindowController(window, tray, _ => { }, queue.Enqueue, () => { Assert.AreEqual(1, window.Disposals); Assert.AreEqual(1, platform.Removed); exits++; });
        presence.ShowWindow(null); presence.HideWindow(); presence.ShowWindow(new(Guid.Parse("11111111-2222-3333-4444-555555555555")));
        while (queue.TryDequeue(out var action)) action();
        Assert.AreEqual(2, window.Opens); Assert.AreEqual(1, window.Hides); Assert.IsTrue(presence.IsWindowVisible);
        presence.DisposeResources(); presence.ExitApplication(); presence.DisposeResources(); presence.ExitApplication(); presence.ShowWindow(null);
        while (queue.TryDequeue(out var action)) action();
        Assert.AreEqual(1, window.Disposals); Assert.AreEqual(1, exits); Assert.AreEqual(2, window.Opens);
    }
    private sealed class NotificationPlatform : INotificationPlatform
    {
        public Action<string>? Callback;
        public List<string> Xml = [];
        public int Removed;
        public void Register(Action<string> callback) => Callback = callback;
        public void Show(string xml) => Xml.Add(xml);
        public void Dispose() => Removed++;
    }
    [TestMethod]
    public void NotificationRegistrationRoutesActivationAndDisposalIgnoresLateCallbacks()
    {
        var platform = new NotificationPlatform(); var activated = new List<string>();
        var service = new AppNotificationService(platform, key => key, activated.Add);
        service.Show(new("Notification.SdkFailure", null, null, [])); platform.Callback!("opaque");
        Assert.AreEqual(1, platform.Xml.Count); CollectionAssert.AreEqual(new[] { "opaque" }, activated);
        service.Dispose(); service.Dispose(); platform.Callback("late"); service.Show(new("Notification.Completed", null, null, []));
        Assert.AreEqual(1, activated.Count); Assert.AreEqual(1, platform.Xml.Count); Assert.AreEqual(1, platform.Removed);
    }
    private sealed class TrayPlatform : ITrayPlatform
    {
        public Action<TrayCommand>? Callback;
        public List<TrayState> States = [];
        public int Removed;
        public void Create(Action<TrayCommand> callback) => Callback = callback;
        public void Update(TrayState state, string tooltip, string open, string pause, string exit) => States.Add(state);
        public void Dispose() => Removed++;
    }
    [TestMethod]
    public void TrayRoutesCommandsAndRetiresCallbackAndIconExactlyOnce()
    {
        var platform = new TrayPlatform(); var commands = new List<TrayCommand>();
        var tray = new TrayIconService(platform, key => key, commands.Add);
        tray.SetState(TrayState.Pausing); tray.SetState(TrayState.Paused);
        platform.Callback!(TrayCommand.Open); platform.Callback(TrayCommand.TogglePause);
        CollectionAssert.AreEqual(new[] { TrayCommand.Open, TrayCommand.TogglePause }, commands);
        CollectionAssert.AreEqual(new[] { TrayState.Normal, TrayState.Pausing, TrayState.Paused }, platform.States);
        tray.Dispose(); tray.Dispose(); platform.Callback(TrayCommand.Exit); tray.SetState(TrayState.Warning);
        Assert.AreEqual(2, commands.Count); Assert.AreEqual(1, platform.Removed); Assert.AreEqual(3, platform.States.Count);
    }
    private sealed class StartupPlatform(bool packaged) : IStartupTaskPlatform
    {
        public bool HasPackageIdentity => packaged;
        public int Changes;
        public bool Allowed = true;
        public Task<bool> SetEnabledAsync(bool enabled) { Changes++; return Task.FromResult(enabled && Allowed); }
    }
    [TestMethod]
    public async Task StartupIsDefaultOffAndUnpackagedOrDemoRequestsCannotEnableIt()
    {
        var unpackaged = new StartupPlatform(false); var service = new StartupTaskService(unpackaged, false);
        Assert.IsFalse(service.Enabled); Assert.IsFalse(service.Available); Assert.IsFalse(await service.SetEnabledAsync(true));
        Assert.AreEqual(0, unpackaged.Changes);
        var packaged = new StartupPlatform(true); var demo = new StartupTaskService(packaged, true);
        Assert.IsFalse(await demo.SetEnabledAsync(true)); Assert.AreEqual(0, packaged.Changes);
        var real = new StartupTaskService(packaged, false); Assert.IsFalse(real.Enabled);
        Assert.IsTrue(await real.SetEnabledAsync(true)); Assert.IsTrue(real.Enabled);
        Assert.IsFalse(await real.SetEnabledAsync(false)); Assert.IsFalse(real.Enabled);
        packaged.Allowed = false; Assert.IsFalse(await real.SetEnabledAsync(true)); Assert.IsFalse(real.Enabled);
    }
}
