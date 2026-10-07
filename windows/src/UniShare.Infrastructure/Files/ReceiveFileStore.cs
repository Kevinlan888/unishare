using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Infrastructure.Files;

public sealed class ReceiveFileStore : ITransferFiles
{
    private readonly TransientContentStore transient;
    public ReceiveFileStore() : this(new TransientContentStore()) { }
    public ReceiveFileStore(TransientContentStore transient) => this.transient = transient ?? throw new ArgumentNullException(nameof(transient));

    public Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string receiveRoot, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(offer);
        cancellationToken.ThrowIfCancellationRequested();
        var path = string.Empty;
        try
        {
            var root = Path.GetFullPath(receiveRoot);
            Directory.CreateDirectory(root);
            path = Path.Combine(root, ".unishare-partial-" + Guid.NewGuid().ToString("N"));
            using var stream = PrivateContentFile.Create(path);
            return Task.FromResult<PreparedReceive>(new Receive(path, root, Sanitize(offer.DisplayName)));
        }
        catch (Exception error) when (PrivateContentFile.IsFileError(error))
        {
            if (path.Length != 0)
                PrivateContentFile.Delete(path);
            throw new IOException("Unable to prepare received file.");
        }
    }

    public Task<string> PublishAsync(PreparedReceive receive, CancellationToken cancellationToken)
    {
        if (receive is not Receive owned)
            throw new ArgumentException("Receive destination is not owned by this store.", nameof(receive));
        lock (owned.Gate)
        {
            if (owned.Published is { } published)
                return Task.FromResult(published);
            string? movedDestination = null;
            try
            {
                for (var index = 0; ; index++)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var component = index == 0 ? owned.Name : Path.GetFileNameWithoutExtension(owned.Name) + " (" + index + ")" + Path.GetExtension(owned.Name);
                    var destination = Path.Combine(owned.Root, component);
                    // Move without overwrite is the atomic arbiter, including
                    // concurrent processes creating the same final name.
                    try
                    {
                        File.Move(owned.StagingPath, destination, overwrite: false);
                        movedDestination = destination;
                        if (OperatingSystem.IsWindows())
                            File.SetAttributes(destination, File.GetAttributes(destination) & ~FileAttributes.Hidden);
                        owned.Published = destination;
                        return Task.FromResult(destination);
                    }
                    catch (IOException) when (File.Exists(owned.StagingPath) && (File.Exists(destination) || Directory.Exists(destination))) { }
                }
            }
            catch (Exception error) when (PrivateContentFile.IsFileError(error) || error is OperationCanceledException)
            {
                // The partial stays hidden during all collision retries. A
                // failure to finalize attributes rolls back only our own move.
                if (movedDestination is not null)
                    PrivateContentFile.Delete(movedDestination);
                PrivateContentFile.Delete(owned.StagingPath);
                if (error is OperationCanceledException)
                    throw;
                throw new IOException("Unable to publish received file.");
            }
        }
    }
    public Task AbortAsync(PreparedReceive receive) => receive.DisposeAsync().AsTask();
    public Task<TransientDestination> CreateTextDestinationAsync(CancellationToken cancellationToken) => transient.CreateTextDestinationAsync(cancellationToken);
    public Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mimeType, CancellationToken cancellationToken) => transient.StageClipboardImageAsync(bytes, mimeType, cancellationToken);

    private static string Sanitize(string name)
    {
        var value = new string(name.Select(c => char.IsControl(c) || "<>:\"/\\|?*".Contains(c) ? '_' : c).ToArray()).TrimEnd(' ', '.');
        if (string.IsNullOrWhiteSpace(value))
            value = "Received file";
        if (value.Length > 160)
            value = value[..160].TrimEnd(' ', '.');
        var stem = value.Split('.')[0].TrimEnd(' ');
        if (new[] { "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$" }.Contains(stem, StringComparer.OrdinalIgnoreCase) ||
            (stem.Length == 4 && (stem.StartsWith("COM", StringComparison.OrdinalIgnoreCase) || stem.StartsWith("LPT", StringComparison.OrdinalIgnoreCase)) && "123456789¹²³".Contains(stem[3])))
            value = "_" + value;
        return value;
    }
    private sealed record Receive(string Value, string Root, string Name) : PreparedReceive(Value, string.Empty)
    {
        public object Gate { get; } = new();
        public string? Published
        {
            get; set;
        }
        public override ValueTask DisposeAsync()
        {
            lock (Gate)
                PrivateContentFile.Delete(StagingPath);
            return ValueTask.CompletedTask;
        }
    }
}
