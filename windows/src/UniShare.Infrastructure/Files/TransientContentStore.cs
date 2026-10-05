using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Principal;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Infrastructure.Files;

public sealed class TransientContentStore : ITransferFiles
{
    private readonly string root;
    public TransientContentStore(string? directory = null) => root = directory ??
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UniShare", "Transient");

    public async Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mimeType, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (bytes.IsEmpty || !mimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Clipboard has no supported image.");
        var path = string.Empty;
        try
        {
            Directory.CreateDirectory(root);
            path = Path.Combine(root, ".unishare-transient-" + Guid.NewGuid().ToString("N"));
            await using (var stream = PrivateContentFile.Create(path))
            {
                await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            cancellationToken.ThrowIfCancellationRequested();
            return new ImageSource(new(path, "Image", mimeType, (ulong)bytes.Length));
        }
        catch (Exception error) when (PrivateContentFile.IsFileError(error) || error is OperationCanceledException)
        {
            if (path.Length != 0) PrivateContentFile.Delete(path);
            if (error is OperationCanceledException) throw;
            throw new IOException("Unable to stage clipboard image.");
        }
    }

    public Task<TransientDestination> CreateTextDestinationAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var path = string.Empty;
        try
        {
            Directory.CreateDirectory(root);
            path = Path.Combine(root, ".unishare-transient-" + Guid.NewGuid().ToString("N"));
            using var stream = PrivateContentFile.Create(path);
            return Task.FromResult<TransientDestination>(new TextDestination(path));
        }
        catch (Exception error) when (PrivateContentFile.IsFileError(error))
        {
            if (path.Length != 0) PrivateContentFile.Delete(path);
            throw new IOException("Unable to prepare text destination.");
        }
    }

    public Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string receiveRoot, CancellationToken cancellationToken) =>
        new ReceiveFileStore(this).CreateReceiveAsync(offer, receiveRoot, cancellationToken);
    public Task<string> PublishAsync(PreparedReceive receive, CancellationToken cancellationToken) => new ReceiveFileStore(this).PublishAsync(receive, cancellationToken);
    public Task AbortAsync(PreparedReceive receive) => receive.DisposeAsync().AsTask();

    private sealed record TextDestination(string Value) : TransientDestination(Value)
    {
        public override ValueTask DisposeAsync() { PrivateContentFile.Delete(Path); return ValueTask.CompletedTask; }
    }
    private sealed record ImageSource(ShareSource Value) : TransientSource(Value)
    {
        public override ValueTask DisposeAsync() { PrivateContentFile.Delete(Path); return ValueTask.CompletedTask; }
    }
}

// ACL is installed by the create operation, before any content is written.
internal static class PrivateContentFile
{
    public static FileStream Create(string path)
    {
        FileStream stream;
        if (OperatingSystem.IsWindows()) stream = CreateWindows(path);
        else stream = new FileStream(path, new FileStreamOptions
        {
            Mode = FileMode.CreateNew, Access = FileAccess.ReadWrite, Share = FileShare.ReadWrite | FileShare.Delete,
            Options = FileOptions.Asynchronous, UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite
        });
        try
        {
            if (OperatingSystem.IsWindows()) File.SetAttributes(path, File.GetAttributes(path) | FileAttributes.Hidden);
            return stream;
        }
        catch { stream.Dispose(); Delete(path); throw; }
    }
    [SupportedOSPlatform("windows")]
    private static FileStream CreateWindows(string path)
    {
        using var identity = WindowsIdentity.GetCurrent();
        var user = identity.User ?? throw new IOException("Unable to resolve current user.");
        var security = new FileSecurity(); security.SetOwner(user); security.SetAccessRuleProtection(true, false);
        security.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl, AccessControlType.Allow));
        return new FileInfo(path).Create(FileMode.CreateNew, FileSystemRights.FullControl,
            FileShare.ReadWrite | FileShare.Delete, 4096, FileOptions.None, security);
    }
    public static bool IsFileError(Exception error) => error is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException;
    public static void Delete(string path)
    {
        try { File.Delete(path); }
        catch (Exception error) when (IsFileError(error)) { throw new IOException("Unable to remove temporary content."); }
    }
}
