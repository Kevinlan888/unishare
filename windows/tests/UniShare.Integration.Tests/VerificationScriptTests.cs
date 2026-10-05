using System.Diagnostics;
using System.Text.Json;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace UniShare.Integration.Tests;

[TestClass]
public sealed class VerificationScriptTests
{
    [TestMethod]
    public void NativeGateRejectsSkippedOrMissingTests()
    {
        using var fixture = new NativeGateFixture();
        var valid = fixture.Validate();
        Assert.AreEqual(0, valid.Exit, valid.Text);
        fixture.Write(NativeGateFixture.Names.Skip(1), NativeGateFixture.Names.Skip(1));
        var missing = fixture.Validate();
        Assert.AreNotEqual(0, missing.Exit);
        StringAssert.Contains(missing.Text, "Missing required native test");
        fixture.Write(NativeGateFixture.Names, NativeGateFixture.Names, skipped: true);
        var skipped = fixture.Validate();
        Assert.AreNotEqual(0, skipped.Exit);
        StringAssert.Contains(skipped.Text, "Failed or skipped required native test");
    }

    // Catches silent prefix fallback, false acceptance PASS, and leaked package payloads.
    [TestMethod]
    public void NativeBuildRejectsRelativeDependencyPrefixWithoutBuilding()
    {
        var result = Run("build-native.ps1", "-OpenSslRoot", "relative-prefix");
        Assert.AreNotEqual(0, result.Exit);
        StringAssert.Contains(result.Text, "OpenSslRoot must be an existing absolute");
    }

    [TestMethod]
    public void AcceptanceWithoutOperatorRecordsBlockedAndNeverPass()
    {
        string output = Path.Combine(Path.GetTempPath(), "unishare-acceptance-" + Guid.NewGuid() + ".json");
        try
        {
            var result = Run("run-acceptance.ps1", "-Output", output);
            Assert.AreEqual(2, result.Exit, result.Text);
            Assert.IsTrue(File.Exists(output), result.Text);
            using var document = JsonDocument.Parse(File.ReadAllText(output));
            var root = document.RootElement;
            Assert.AreEqual("BLOCKED", root.GetProperty("releaseGate").GetString());
            var rows = root.GetProperty("results").EnumerateArray().ToArray();
            Assert.IsTrue(rows.Length >= 90);
            Assert.IsTrue(rows.All(row => row.GetProperty("status").GetString() == "NOT_RUN"));
            Assert.IsTrue(rows.All(row => !string.IsNullOrWhiteSpace(row.GetProperty("reason").GetString())));
            CollectionAssert.IsSubsetOf(new[] { "Windows", "Android", "iOS" }, rows.Select(row => row.GetProperty("peer").GetString()).Distinct().ToArray());
        }
        finally { if (File.Exists(output)) File.Delete(output); }
    }

    [TestMethod]
    public void AcceptanceRejectsFabricatedPassWithoutProvenance()
    {
        string input = Path.Combine(Path.GetTempPath(), "unishare-evidence-" + Guid.NewGuid() + ".json");
        string output = input + ".out";
        try
        {
            File.WriteAllText(input, "{\"schemaVersion\":1,\"releaseGate\":\"PASS\",\"results\":[{\"status\":\"PASS\"}]}");
            var result = Run("run-acceptance.ps1", "-Output", output, "-Evidence", input);
            Assert.AreNotEqual(0, result.Exit, result.Text);
            Assert.IsFalse(File.Exists(output), "Invalid evidence must not be recorded.");
            StringAssert.Contains(result.Text, "Invalid acceptance evidence");
        }
        finally { File.Delete(input); File.Delete(output); }
    }

    [TestMethod]
    public void PackageInspectionRejectsPrivateFilesAndChangedNativeDll()
    {
        string temporary = Path.Combine(Path.GetTempPath(), "unishare-package-policy-" + Guid.NewGuid());
        string package = Path.Combine(temporary, "package"), native = Path.Combine(temporary, "native");
        Directory.CreateDirectory(package); Directory.CreateDirectory(native);
        try
        {
            foreach (string name in new[] { "unishare.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll" })
            {
                File.WriteAllText(Path.Combine(native, name), "fixture-" + name);
                File.Copy(Path.Combine(native, name), Path.Combine(package, name));
            }
            File.WriteAllText(Path.Combine(package, "UniShare.App.exe"), "fixture");
            File.WriteAllText(Path.Combine(package, "resources.pri"), "fixture");
            Directory.CreateDirectory(Path.Combine(package, "Assets"));
            File.WriteAllText(Path.Combine(package, "Assets", "StoreLogo.png"), "fixture");
            Directory.CreateDirectory(Path.Combine(package, "licenses"));
            File.WriteAllText(Path.Combine(package, "licenses", "OpenSSL-NOTICES.txt"), "notice fixture");
            File.WriteAllText(Path.Combine(package, "AppxManifest.xml"), """
                <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10" xmlns:desktop="http://schemas.microsoft.com/appx/manifest/desktop/windows10">
                <Identity ProcessorArchitecture="x64"/><Dependencies><TargetDeviceFamily MinVersion="10.0.17763.0"/></Dependencies>
                <Resources><Resource Language="en-US"/><Resource Language="zh-CN"/></Resources>
                <Applications><Application Executable="UniShare.App.exe"><Extensions><desktop:Extension Category="windows.startupTask"><desktop:StartupTask Enabled="false"/></desktop:Extension><desktop:Extension Category="windows.toastNotificationActivation"/></Extensions></Application></Applications></Package>
                """);
            string dump = Path.Combine(temporary, "resources.xml");
            const string compiledResources = """
                <PriInfo><NamedResource uri="ms-resource://UniShare/Resources/SendClipboard">
                <Candidate><QualifierSet><Qualifier name="Language" value="en-US"/></QualifierSet><Value>Send Clipboard</Value></Candidate>
                <Candidate><QualifierSet><Qualifier name="Language" value="zh-CN"/></QualifierSet><Value>发送剪贴板</Value></Candidate>
                </NamedResource></PriInfo>
                """;
            File.WriteAllText(dump, compiledResources);
            string[] arguments = ["-PackageRoot", package, "-NativeRoot", native, "-ResourceDump", dump];
            var valid = Run("inspect-package.ps1", arguments);
            Assert.AreEqual(0, valid.Exit, valid.Text);
            File.WriteAllText(dump, "<PriInfo><Qualifier name=\"Language\" value=\"en-US\"/><Qualifier name=\"Language\" value=\"zh-CN\"/></PriInfo>");
            var frameworkOnly = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, frameworkOnly.Exit);
            StringAssert.Contains(frameworkOnly.Text, "Missing compiled application language");
            File.WriteAllText(dump, compiledResources);
            File.WriteAllText(Path.Combine(package, "Assets", "README.md"), "private source notes");
            var sourceNotes = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, sourceNotes.Exit);
            StringAssert.Contains(sourceNotes.Text, "Forbidden package payload");
            File.Delete(Path.Combine(package, "Assets", "README.md"));
            File.WriteAllText(Path.Combine(package, "UniShare.App.runtimeconfig.json"), "{\"privatePath\":\"C:\\\\Users\\\\operator\\\\identity\"}");
            var absolutePath = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, absolutePath.Exit);
            StringAssert.Contains(absolutePath.Text, "Absolute filesystem path in package metadata");
            File.Delete(Path.Combine(package, "UniShare.App.runtimeconfig.json"));
            File.WriteAllText(Path.Combine(package, "activity.sqlite"), "private");
            var privateFile = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, privateFile.Exit);
            StringAssert.Contains(privateFile.Text, "Forbidden package payload");
            File.Delete(Path.Combine(package, "activity.sqlite"));
            File.WriteAllText(Path.Combine(package, "unishare.dll"), "changed");
            var changed = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, changed.Exit);
            StringAssert.Contains(changed.Text, "Native hash mismatch");
            File.Copy(Path.Combine(native, "unishare.dll"), Path.Combine(package, "unishare.dll"), overwrite: true);
            string manifest = Path.Combine(package, "AppxManifest.xml");
            File.WriteAllText(manifest, File.ReadAllText(manifest).Replace("Category=\"windows.startupTask\"", "Category=\"windows.startupTask\" Executable=\"$targetnametoken$.exe\""));
            var token = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, token.Exit);
            StringAssert.Contains(token.Text, "Executable declaration must reference UniShare.App.exe");
        }
        finally { Directory.Delete(temporary, recursive: true); }
    }

    internal static (int Exit, string Text) Run(string name, params string[] arguments)
    {
        var start = new ProcessStartInfo("powershell.exe") { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, WorkingDirectory = Path.GetTempPath() };
        foreach (string argument in new[] { "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", Path.Combine(NativeAbiSmokeTests.RepoRoot, "windows", "scripts", name) }.Concat(arguments))
            start.ArgumentList.Add(argument);
        using var process = Process.Start(start)!;
        var stdout = process.StandardOutput.ReadToEndAsync();
        var stderr = process.StandardError.ReadToEndAsync();
        Assert.IsTrue(process.WaitForExit(60000), "Script exceeded one minute.");
        return (process.ExitCode, stdout.GetAwaiter().GetResult() + stderr.GetAwaiter().GetResult());
    }
}
