using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.ViewModels;

public sealed class SettingsViewModel : CommandViewModel
{
    private readonly IViewModelFiles files;
    private AppSettings settings;
    private string? validationResourceKey;

    public SettingsViewModel(ISettingsStore store, IViewModelFiles files, IViewModelDispatcher dispatcher, AppSettings settings) : base(dispatcher)
    {
        this.files = files; this.settings = settings;
        SaveCommand = Command(async () =>
        {
            var candidate = Settings;
            if (!await ValidateAsync(candidate.ReceiveDirectory, CancellationToken.None).ConfigureAwait(false)) return;
            // Save the immutable candidate actually validated, even if the user
            // edits Settings while the host's directory probe is running.
            await store.SaveAsync(candidate, CancellationToken.None).ConfigureAwait(false);
        }, () => true, "Settings.SaveFailed");
    }
    public AppSettings Settings { get => settings; set => SetProperty(ref settings, value); }
    public string? ValidationResourceKey { get => validationResourceKey; private set => SetProperty(ref validationResourceKey, value); }
    public IAsyncRelayCommand SaveCommand { get; }
    public Task<bool> ValidateReceiveDirectoryAsync(CancellationToken cancellationToken = default) => ValidateAsync(Settings.ReceiveDirectory, cancellationToken);
    private async Task<bool> ValidateAsync(string? path, CancellationToken cancellationToken)
    {
        bool valid;
        try { valid = await files.ValidateReceiveDirectoryAsync(path, cancellationToken).ConfigureAwait(false); }
        catch (OperationCanceledException) { throw; }
        catch { valid = false; }
        await Dispatcher.InvokeAsync(() => ValidationResourceKey = valid ? null : "Settings.ReceiveDirectoryNotWritable").ConfigureAwait(false);
        return valid;
    }
}
