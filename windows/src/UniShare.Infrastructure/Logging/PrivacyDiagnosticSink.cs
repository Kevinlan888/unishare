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

    public PrivacyDiagnosticSink(string? storageDirectory = null)
    {
        directory = AppStorage.DirectoryPath(storageDirectory);
        path = Path.Combine(directory, "diagnostics.log");
        gate = AppStorage.Gate(path);
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

    private void Write(ShareErrorKind category, long? platformCode)
    {
        var name = Enum.IsDefined(category) ? category.ToString() : nameof(ShareErrorKind.Unknown);
        var line = $"category={name} platform_code={platformCode?.ToString(CultureInfo.InvariantCulture) ?? "none"}";
        if (writer is not null)
        {
            lock (writer) { writer.WriteLine(line); writer.Flush(); }
            return;
        }
        gate!.Wait();
        try
        {
            Directory.CreateDirectory(directory!);
            using var output = new StreamWriter(path!, append: true);
            output.WriteLine(line);
        }
        finally { gate.Release(); }
    }
}
