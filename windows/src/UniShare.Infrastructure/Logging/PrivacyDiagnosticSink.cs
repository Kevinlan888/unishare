using System.Globalization;
using UniShare.Application.Models;
using UniShare.Infrastructure.Storage;

namespace UniShare.Infrastructure.Logging;

public sealed class PrivacyDiagnosticSink
{
    private readonly TextWriter? writer;
    private readonly string? directory;
    private readonly string? path;
    private readonly SemaphoreSlim? gate;
    private readonly long maxBytes;

    public PrivacyDiagnosticSink(string? storageDirectory = null, long maxBytes = 1_048_576)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maxBytes);
        directory = AppStorage.DirectoryPath(storageDirectory);
        path = Path.Combine(directory, "diagnostics.log");
        gate = AppStorage.Gate(path);
        this.maxBytes = maxBytes;
    }

    // The caller owns this writer. Only fixed diagnostic fields are sent to it.
    public PrivacyDiagnosticSink(TextWriter writer) => this.writer = writer ?? throw new ArgumentNullException(nameof(writer));

    public void WriteFailure(ShareFailure failure)
    {
        ArgumentNullException.ThrowIfNull(failure);
        Write(failure.Kind, failure.PlatformError);
    }

    public void WriteException(ShareErrorKind category, Exception exception)
    {
        ArgumentNullException.ThrowIfNull(exception);
        Write(category, exception.HResult);
    }

    public void WriteEvent(DiagnosticComponent component, DiagnosticStage stage, DiagnosticOutcome outcome,
        ShareFailure? failure = null, Exception? exception = null)
    {
        var category = failure?.Kind.ToString() ?? "none";
        var code = failure?.PlatformError ?? (long?)exception?.HResult;
        var type = exception?.GetType().Name ?? "none";
        var line = $"time={DateTimeOffset.UtcNow:O} component={component} stage={stage} outcome={outcome} " +
            $"category={category} platform_code={code?.ToString(CultureInfo.InvariantCulture) ?? "none"} exception={type}";
        WriteLine(line);
    }

    private void Write(ShareErrorKind category, long? platformCode)
    {
        var name = Enum.IsDefined(category) ? category.ToString() : nameof(ShareErrorKind.Unknown);
        var line = $"category={name} platform_code={platformCode?.ToString(CultureInfo.InvariantCulture) ?? "none"}";
        WriteLine(line);
    }

    private void WriteLine(string line)
    {
        if (writer is not null)
        {
            lock (writer) { writer.WriteLine(line); writer.Flush(); }
            return;
        }
        gate!.Wait();
        try
        {
            Directory.CreateDirectory(directory!);
            var length = File.Exists(path) ? new FileInfo(path!).Length : 0;
            var incoming = System.Text.Encoding.UTF8.GetByteCount(line + Environment.NewLine);
            if (length > 0 && length + incoming > maxBytes)
            {
                File.Move(path!, path! + ".1", overwrite: true);
            }
            using var output = new StreamWriter(path!, append: true);
            output.WriteLine(line);
        }
        finally { gate.Release(); }
    }
}
