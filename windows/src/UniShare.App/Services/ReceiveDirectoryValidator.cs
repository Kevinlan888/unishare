namespace UniShare.App.Services;

public static class ReceiveDirectoryValidator
{
    public static Task<bool> ValidateAsync(string? path, Func<string?> defaultRoot, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        string? probe = null;
        try
        {
            var root = path ?? defaultRoot();
            if (string.IsNullOrWhiteSpace(root))
                return Task.FromResult(false);
            Directory.CreateDirectory(root);
            probe = Path.Combine(root, ".unishare-write-probe-" + Guid.NewGuid().ToString("N"));
            using (var stream = new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.WriteByte(0);
                stream.Flush(true);
            }
            File.Delete(probe);
            return Task.FromResult(true);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return Task.FromResult(false);
        }
        finally
        {
            if (probe is not null)
                try
                {
                    File.Delete(probe);
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
        }
    }
}
