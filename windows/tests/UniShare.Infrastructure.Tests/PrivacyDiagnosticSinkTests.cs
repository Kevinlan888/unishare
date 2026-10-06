using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Infrastructure.Logging;

namespace UniShare.Infrastructure.Tests;

[TestClass]
public sealed class PrivacyDiagnosticSinkTests
{
    [TestMethod]
    public void NativeEventWritesOnlyFixedFieldsAndRotates()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path, maxBytes: 220);
        for (var i = 0; i < 6; i++)
            sink.WriteNativeEvent(new(NativeDiagnosticStage.Transfer, NativeDiagnosticOutcome.Failed,
                DiagnosticDirection.Send, DiagnosticContentKind.File, (ulong)i, ShareErrorKind.StorageFailed, -5));
        var logs = Directory.GetFiles(directory.Path, "diagnostics*");
        Assert.IsTrue(logs.Length is >= 1 and <= 2);
        var text = string.Join("\n", logs.Select(File.ReadAllText));
        StringAssert.Contains(text, "component=Sdk stage=Transfer outcome=Failed");
        StringAssert.Contains(text, "direction=Send content=File");
        Assert.IsFalse(text.Contains("C:\\private", StringComparison.Ordinal));
    }
    [TestMethod]
    public void FailureLogsFixedCategoryAndPlatformCodeWithoutSensitiveMessage()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path);
        sink.WriteFailure(new(ShareErrorKind.StorageFailed, "clipboard body image payload C:\\private\\source.txt\r\nINJECTED", -2147024891));
        var log = File.ReadAllText(System.IO.Path.Combine(directory.Path, "diagnostics.log"));
        StringAssert.Contains(log, "StorageFailed");
        StringAssert.Contains(log, "-2147024891");
        foreach (var sensitive in new[] { "clipboard", "body", "payload", "C:\\private", "source.txt", "INJECTED" })
            Assert.IsFalse(log.Contains(sensitive, StringComparison.Ordinal));
    }

    [TestMethod]
    public void ExceptionLoggingDropsMessageDataAndStackPaths()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path);
        var exception = new IOException("clipboard secret C:\\private\\payload.txt");
        exception.Data["body"] = "SECRET";
        sink.WriteException(ShareErrorKind.StorageFailed, exception);
        var log = File.ReadAllText(System.IO.Path.Combine(directory.Path, "diagnostics.log"));
        StringAssert.Contains(log, "StorageFailed");
        StringAssert.Contains(log, exception.HResult.ToString(System.Globalization.CultureInfo.InvariantCulture));
        Assert.IsFalse(log.Contains("private", StringComparison.Ordinal));
        Assert.IsFalse(log.Contains("SECRET", StringComparison.Ordinal));
    }

    [TestMethod]
    public void UnknownCategoryAndConcurrentFileWritesStayFixedAndPrivate()
    {
        using var directory = new TemporaryDirectory();
        var sinks = Enumerable.Range(0, 20).Select(_ => new PrivacyDiagnosticSink(directory.Path)).ToArray();
        Parallel.ForEach(sinks, sink => sink.WriteFailure(new((ShareErrorKind)99999, "secret", null)));
        var lines = File.ReadAllLines(System.IO.Path.Combine(directory.Path, "diagnostics.log"));
        Assert.AreEqual(20, lines.Length);
        Assert.IsTrue(lines.All(line => line.Contains("category=Unknown platform_code=none", StringComparison.Ordinal)));
    }

    [TestMethod]
    public void StartupEventRecordsPhaseAndCodeWithoutExceptionMessage()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path);
        var error = new IOException("secret C:\\private\\file.txt");
        sink.WriteEvent(DiagnosticComponent.App, DiagnosticStage.SdkStart, DiagnosticOutcome.Failed, exception: error);
        var line = File.ReadAllText(System.IO.Path.Combine(directory.Path, "diagnostics.log"));
        StringAssert.Contains(line, "component=App stage=SdkStart outcome=Failed");
        StringAssert.Contains(line, "exception=IOException");
        StringAssert.Contains(line, $"platform_code={error.HResult}");
        Assert.IsFalse(line.Contains("private", StringComparison.Ordinal));
        Assert.IsFalse(line.Contains("secret", StringComparison.Ordinal));
    }

    [TestMethod]
    public void EventsHaveSeverityMatchingOutcome()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path);
        sink.WriteEvent(DiagnosticComponent.Sdk, DiagnosticStage.SdkInitialize, DiagnosticOutcome.Succeeded);
        sink.WriteEvent(DiagnosticComponent.Sdk, DiagnosticStage.SdkStart, DiagnosticOutcome.Failed);
        var lines = File.ReadAllLines(System.IO.Path.Combine(directory.Path, "diagnostics.log"));
        Assert.AreEqual(2, lines.Length);
        StringAssert.Contains(lines[0], "level=Info");
        StringAssert.Contains(lines[1], "level=Error");
    }

    [TestMethod]
    public void FileLogRotatesBeforeItExceedsSizeLimit()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path, maxBytes: 180);
        for (var i = 0; i < 10; i++)
            sink.WriteEvent(DiagnosticComponent.Sdk, DiagnosticStage.SdkStart, DiagnosticOutcome.Succeeded);
        var current = System.IO.Path.Combine(directory.Path, "diagnostics.log");
        var files = Directory.GetFiles(directory.Path, "diagnostics*");
        Assert.IsTrue(files.Length is >= 1 and <= 2);
        Assert.IsTrue(files.Any(path => path != current));
        Assert.IsTrue(files.All(path => new FileInfo(path).Length <= 320));
    }
}
