using System.Globalization;
using NLog;
using NLog.Config;
using NLog.Targets;
using UniShare.Application.Models;
using UniShare.Infrastructure.Storage;

namespace UniShare.Infrastructure.Logging;

// This is the only boundary allowed to send diagnostic fields to NLog.
// In particular, exception messages, stacks, device names and paths are excluded.
public sealed class PrivacyDiagnosticSink
{
    private readonly Logger logger;
    private readonly SemaphoreSlim gate;

    public PrivacyDiagnosticSink(string? storageDirectory = null, long maxBytes = 1_048_576)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(maxBytes);
        var path = Path.Combine(AppStorage.DirectoryPath(storageDirectory), "diagnostics.log");
        gate = AppStorage.Gate(path);
        var target = new FileTarget("diagnostics")
        {
            FileName = path,
            Layout = "time=${date:format=o:universalTime=true} level=${level} ${message}",
            ArchiveAboveSize = maxBytes,
            MaxArchiveFiles = 2,
            KeepFileOpen = false,
            CreateDirs = true
        };
        var factory = new LogFactory();
        var configuration = new LoggingConfiguration(factory);
        configuration.AddRule(LogLevel.Info, LogLevel.Fatal, target);
        factory.Configuration = configuration;
        logger = factory.GetLogger("UniShare.Diagnostics");
    }

    public void WriteFailure(ShareFailure failure)
    {
        ArgumentNullException.ThrowIfNull(failure);
        Write(LogLevel.Error, failure.Kind, failure.PlatformError);
    }

    public void WriteException(ShareErrorKind category, Exception exception)
    {
        ArgumentNullException.ThrowIfNull(exception);
        Write(LogLevel.Error, category, exception.HResult);
    }

    public void WriteEvent(DiagnosticComponent component, DiagnosticStage stage, DiagnosticOutcome outcome,
        ShareFailure? failure = null, Exception? exception = null)
    {
        var category = failure?.Kind.ToString() ?? "none";
        var code = failure?.PlatformError ?? (long?)exception?.HResult;
        var type = exception?.GetType().Name ?? "none";
        var line = $"component={component} stage={stage} outcome={outcome} " +
            $"category={category} platform_code={code?.ToString(CultureInfo.InvariantCulture) ?? "none"} exception={type}";
        Write(outcome == DiagnosticOutcome.Failed ? LogLevel.Error : LogLevel.Info, line);
    }

    public void WriteNativeEvent(DiagnosticRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        var stage = Enum.IsDefined(record.Stage) ? record.Stage : NativeDiagnosticStage.Unknown;
        var outcome = Enum.IsDefined(record.Outcome) ? record.Outcome : NativeDiagnosticOutcome.Unknown;
        var direction = Enum.IsDefined(record.Direction) ? record.Direction : DiagnosticDirection.Unknown;
        var content = Enum.IsDefined(record.ContentKind) ? record.ContentKind : DiagnosticContentKind.Unknown;
        var category = record.Category is { } code && Enum.IsDefined(code) ? code.ToString() :
            record.Category is null ? "none" : nameof(ShareErrorKind.Unknown);
        var line = $"component=Sdk stage={stage} outcome={outcome} direction={direction} content={content} " +
            $"sequence={record.Sequence.ToString(CultureInfo.InvariantCulture)} category={category} " +
            $"platform_code={record.PlatformCode?.ToString(CultureInfo.InvariantCulture) ?? "none"}";
        Write(outcome == NativeDiagnosticOutcome.Failed ? LogLevel.Error : LogLevel.Info, line);
    }

    public void WriteTransferEvent(TransferDiagnosticRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        var stage = Enum.IsDefined(record.Stage) ? record.Stage : TransferDiagnosticStage.Unknown;
        var outcome = Enum.IsDefined(record.Outcome) ? record.Outcome : NativeDiagnosticOutcome.Unknown;
        var direction = Enum.IsDefined(record.Direction) ? record.Direction : DiagnosticDirection.Unknown;
        var content = Enum.IsDefined(record.ContentKind) ? record.ContentKind : DiagnosticContentKind.Unknown;
        var category = record.Category is { } code && Enum.IsDefined(code) ? code.ToString() :
            record.Category is null ? "none" : nameof(ShareErrorKind.Unknown);
        var line = $"component=App stage={stage} outcome={outcome} direction={direction} content={content} " +
            $"sequence={record.Sequence.ToString(CultureInfo.InvariantCulture)} category={category} " +
            $"platform_code={record.PlatformCode?.ToString(CultureInfo.InvariantCulture) ?? "none"}";
        Write(outcome == NativeDiagnosticOutcome.Failed ? LogLevel.Error : LogLevel.Info, line);
    }

    private void Write(LogLevel level, ShareErrorKind category, long? platformCode)
    {
        var name = Enum.IsDefined(category) ? category.ToString() : nameof(ShareErrorKind.Unknown);
        Write(level, $"category={name} platform_code={platformCode?.ToString(CultureInfo.InvariantCulture) ?? "none"}");
    }

    private void Write(LogLevel level, string line)
    {
        gate.Wait();
        try
        {
            logger.Log(level, line);
        }
        finally { gate.Release(); }
    }
}
