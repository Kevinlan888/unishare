using System.Runtime.InteropServices;
using System.Text.Json;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Infrastructure.Storage;

public sealed class JsonSettingsStore : ISettingsStore
{
    private readonly string directory;
    private readonly string path;
    private readonly SemaphoreSlim gate;
    private readonly Func<string> downloadsDirectory;

    public JsonSettingsStore(string? storageDirectory = null, Func<string>? downloadsDirectoryResolver = null)
    {
        directory = AppStorage.DirectoryPath(storageDirectory);
        path = Path.Combine(directory, "settings.json");
        gate = AppStorage.Gate(path);
        downloadsDirectory = downloadsDirectoryResolver ?? ResolveDownloads;
    }

    public async Task<AppSettings> LoadAsync(CancellationToken cancellationToken)
    {
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var settings = await ReadAsync(path, cancellationToken).ConfigureAwait(false) ??
                await ReadAsync(path + ".bak", cancellationToken).ConfigureAwait(false) ?? new AppSettings();
            return settings.ReceiveDirectory is null
                ? settings with
                {
                    ReceiveDirectory = Path.Combine(downloadsDirectory(), "UniShare")
                }
                : settings;
        }
        finally { gate.Release(); }
    }

    public async Task SaveAsync(AppSettings settings, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(settings);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Directory.CreateDirectory(directory);
            var temporary = path + ".tmp";
            await using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None,
                4096, FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, settings, cancellationToken: cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }
            cancellationToken.ThrowIfCancellationRequested();
            if (File.Exists(path))
            {
                // A recovered backup must not be overwritten with a corrupt primary.
                var prior = await ReadAsync(path, cancellationToken).ConfigureAwait(false);
                File.Replace(temporary, path, prior is null ? null : path + ".bak");
            }
            else
                File.Move(temporary, path);
        }
        finally { gate.Release(); }
    }

    private static async Task<AppSettings?> ReadAsync(string file, CancellationToken cancellationToken)
    {
        if (!File.Exists(file))
            return null;
        try
        {
            await using var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read);
            return await JsonSerializer.DeserializeAsync<AppSettings>(stream, cancellationToken: cancellationToken).ConfigureAwait(false);
        }
        catch (JsonException) { return null; }
        catch (FileNotFoundException) { return null; }
    }

    private static string ResolveDownloads()
    {
        if (!OperatingSystem.IsWindows())
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        var downloads = new Guid("374DE290-123F-4565-9164-39C4925E467B");
        var result = SHGetKnownFolderPath(in downloads, 0, IntPtr.Zero, out var folder);
        try
        {
            if (result < 0)
                Marshal.ThrowExceptionForHR(result);
            return Marshal.PtrToStringUni(folder) ?? throw new IOException("Downloads folder could not be resolved.");
        }
        finally { if (folder != IntPtr.Zero) Marshal.FreeCoTaskMem(folder); }
    }

    [DllImport("shell32.dll", ExactSpelling = true)]
    private static extern int SHGetKnownFolderPath(in Guid folderId, uint flags, IntPtr token, out IntPtr path);
}
