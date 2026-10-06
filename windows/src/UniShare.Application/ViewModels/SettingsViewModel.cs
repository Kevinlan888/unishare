using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.ViewModels;

public sealed record SettingsSaveResult(bool Saved, string? ErrorResourceKey);

public sealed class SettingsViewModel : CommandViewModel, IAppShutdown
{
    private const string SaveFailed = "Settings.SaveFailed";
    private const string ReceiveDirectoryNotWritable = "Settings.ReceiveDirectoryNotWritable";
    private readonly ISettingsStore store;
    private readonly IViewModelFiles files;
    private readonly object saveGate = new();
    private Task saveTail = Task.CompletedTask;
    private bool acceptingSaves = true;
    private AppSettings settings;
    private AppSettings persistedSettings;
    private string? validationResourceKey;

    public SettingsViewModel(ISettingsStore store, IViewModelFiles files, IViewModelDispatcher dispatcher, AppSettings settings) : base(dispatcher)
    {
        this.store = store; this.files = files; this.settings = persistedSettings = settings;
        SaveCommand = Command(async () =>
        {
            var result = await EnqueueSave(Settings, validateUnchangedDirectory: true, CancellationToken.None).ConfigureAwait(false);
            if (!result.Saved && result.ErrorResourceKey == SaveFailed) throw new IOException("Settings could not be saved.");
        }, () => true, SaveFailed);
    }
    public AppSettings Settings { get => settings; set => SetProperty(ref settings, value); }
    public string? ValidationResourceKey { get => validationResourceKey; private set => SetProperty(ref validationResourceKey, value); }
    public IAsyncRelayCommand SaveCommand { get; }
    public void AcceptLoadedSettings(AppSettings value)
    {
        ArgumentNullException.ThrowIfNull(value);
        lock (saveGate)
        {
            if (!saveTail.IsCompleted) throw new InvalidOperationException("Settings cannot be replaced while a save is pending.");
            Settings = persistedSettings = value;
        }
    }
    public Task<SettingsSaveResult> AutosaveAsync(AppSettings candidate, CancellationToken cancellationToken = default) =>
        EnqueueSave(candidate, validateUnchangedDirectory: false, cancellationToken);
    private Task<SettingsSaveResult> EnqueueSave(AppSettings candidate, bool validateUnchangedDirectory, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        lock (saveGate)
        {
            if (!acceptingSaves) return Task.FromResult(new SettingsSaveResult(false, SaveFailed));
            var operation = SaveAfterAsync(saveTail, candidate, validateUnchangedDirectory, cancellationToken);
            saveTail = ObserveAsync(operation);
            return operation;
        }
    }
    public void BlockNewWork() { lock (saveGate) acceptingSaves = false; }
    public Task RetireAsync() { lock (saveGate) return saveTail; }
    public Task<bool> ValidateReceiveDirectoryAsync(CancellationToken cancellationToken = default) => ValidateAsync(Settings.ReceiveDirectory, cancellationToken);
    private async Task<SettingsSaveResult> SaveAfterAsync(Task predecessor, AppSettings candidate, bool validateUnchangedDirectory, CancellationToken cancellationToken)
    {
        await predecessor.ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        if ((validateUnchangedDirectory || !string.Equals(candidate.ReceiveDirectory, persistedSettings.ReceiveDirectory, StringComparison.OrdinalIgnoreCase)) &&
            !await ValidateAsync(candidate.ReceiveDirectory, cancellationToken).ConfigureAwait(false))
            return new(false, ReceiveDirectoryNotWritable);
        try
        {
            await store.SaveAsync(candidate, cancellationToken).ConfigureAwait(false);
            persistedSettings = candidate;
            return new(true, null);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch { return new(false, SaveFailed); }
    }
    private static async Task ObserveAsync(Task operation)
    {
        try { await operation.ConfigureAwait(false); }
        catch { /* Each caller observes its own cancellation; later saves must still run. */ }
    }
    private async Task<bool> ValidateAsync(string? path, CancellationToken cancellationToken)
    {
        bool valid;
        try { valid = await files.ValidateReceiveDirectoryAsync(path, cancellationToken).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw; }
        catch { valid = false; }
        await Dispatcher.InvokeAsync(() => ValidationResourceKey = valid ? null : ReceiveDirectoryNotWritable).ConfigureAwait(false);
        return valid;
    }
}
