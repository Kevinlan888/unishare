using UniShare.Application.ViewModels;
using Windows.Storage;
using Windows.System;

namespace UniShare.App.Services;

internal sealed class WindowsViewModelFiles(Func<string?> receiveRoot) : IViewModelFiles
{
    public bool FileExists(string path) => File.Exists(path);
    public async Task OpenAsync(string path, bool location, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (location)
            SystemLaunch.EnsureSucceeded(await Launcher.LaunchFolderAsync(
                await StorageFolder.GetFolderFromPathAsync(Path.GetDirectoryName(path)!)));
        else
            SystemLaunch.EnsureSucceeded(await Launcher.LaunchFileAsync(await StorageFile.GetFileFromPathAsync(path)));
    }
    public Task<bool> ValidateReceiveDirectoryAsync(string? path, CancellationToken cancellationToken) =>
        ReceiveDirectoryValidator.ValidateAsync(path, receiveRoot, cancellationToken);
}
