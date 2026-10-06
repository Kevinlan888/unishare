using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Infrastructure.Logging;

namespace UniShare.Infrastructure.Tests;

[TestClass]
public sealed class PrivacyDiagnosticSinkTests
{
    [TestMethod]
    public void FailureLogsFixedCategoryAndPlatformCodeWithoutSensitiveMessage()
    {
        using var writer = new StringWriter();
        var sink = new PrivacyDiagnosticSink(writer);
        sink.WriteFailure(new(ShareErrorKind.StorageFailed, "clipboard body image payload C:\\private\\source.txt\r\nINJECTED", -2147024891));
        var log = writer.ToString();
        StringAssert.Contains(log, "StorageFailed");
        StringAssert.Contains(log, "-2147024891");
        foreach (var sensitive in new[] { "clipboard", "body", "payload", "C:\\private", "source.txt", "INJECTED" })
            Assert.IsFalse(log.Contains(sensitive, StringComparison.Ordinal));
    }

    [TestMethod]
    public void ExceptionLoggingDropsMessageDataAndStackPaths()
    {
        using var writer = new StringWriter();
        var sink = new PrivacyDiagnosticSink(writer);
        var exception = new IOException("clipboard secret C:\\private\\payload.txt");
        exception.Data["body"] = "SECRET";
        sink.WriteException(ShareErrorKind.StorageFailed, exception);
        StringAssert.Contains(writer.ToString(), "StorageFailed");
        StringAssert.Contains(writer.ToString(), exception.HResult.ToString(System.Globalization.CultureInfo.InvariantCulture));
        Assert.IsFalse(writer.ToString().Contains("private", StringComparison.Ordinal));
        Assert.IsFalse(writer.ToString().Contains("SECRET", StringComparison.Ordinal));
    }

    [TestMethod]
    public void UnknownCategoryAndConcurrentFileWritesStayFixedAndPrivate()
    {
        using var directory = new TemporaryDirectory();
        var sinks = Enumerable.Range(0, 20).Select(_ => new PrivacyDiagnosticSink(directory.Path)).ToArray();
        Parallel.ForEach(sinks, sink => sink.WriteFailure(new((ShareErrorKind)99999, "secret", null)));
        var lines = File.ReadAllLines(System.IO.Path.Combine(directory.Path, "diagnostics.log"));
        Assert.AreEqual(20, lines.Length);
        Assert.IsTrue(lines.All(line => line == "category=Unknown platform_code=none"));
    }

    [TestMethod]
    public void StartupEventRecordsPhaseAndCodeWithoutExceptionMessage()
    {
        using var writer = new StringWriter();
        var sink = new PrivacyDiagnosticSink(writer);
        var error = new IOException("secret C:\\private\\file.txt");
        sink.WriteEvent(DiagnosticComponent.App, DiagnosticStage.SdkStart, DiagnosticOutcome.Failed, exception: error);
        var line = writer.ToString();
        StringAssert.Contains(line, "component=App stage=SdkStart outcome=Failed");
        StringAssert.Contains(line, "exception=IOException");
        StringAssert.Contains(line, $"platform_code={error.HResult}");
        Assert.IsFalse(line.Contains("private", StringComparison.Ordinal));
        Assert.IsFalse(line.Contains("secret", StringComparison.Ordinal));
    }

    [TestMethod]
    public void FileLogRotatesBeforeItExceedsSizeLimit()
    {
        using var directory = new TemporaryDirectory();
        var sink = new PrivacyDiagnosticSink(directory.Path, maxBytes: 180);
        for (var i = 0; i < 10; i++)
            sink.WriteEvent(DiagnosticComponent.Sdk, DiagnosticStage.SdkStart, DiagnosticOutcome.Succeeded);
        var current = System.IO.Path.Combine(directory.Path, "diagnostics.log");
        var previous = current + ".1";
        Assert.IsTrue(File.Exists(previous));
        Assert.IsTrue(new FileInfo(current).Length <= 180);
        Assert.IsTrue(new FileInfo(previous).Length <= 180);
    }
}
