using System.Diagnostics;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
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
    public void UnsignedPackageIdentityUsesTheRequiredUnsignedNamespaceMarker()
    {
        string temporary = Path.Combine(Path.GetTempPath(), "unishare-unsigned-manifest-" + Guid.NewGuid());
        Directory.CreateDirectory(temporary);
        string manifest = Path.Combine(temporary, "AppxManifest.xml");
        try
        {
            File.WriteAllText(manifest, """
                <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10">
                  <Identity Name="UniShare" Publisher="CN=UniShare" Version="1.0.0.0" ProcessorArchitecture="x64"/>
                </Package>
                """);

            var first = Run("set-unsigned-package-identity.ps1", "-ManifestPath", manifest);
            Assert.AreEqual(0, first.Exit, first.Text);
            var document = System.Xml.Linq.XDocument.Load(manifest);
            string? publisher = document.Root?.Elements().Single(element => element.Name.LocalName == "Identity").Attribute("Publisher")?.Value;
            Assert.AreEqual("CN=UniShare, OID.2.25.311729368913984317654407730594956997722=1", publisher);

            var second = Run("set-unsigned-package-identity.ps1", "-ManifestPath", manifest);
            Assert.AreEqual(0, second.Exit, second.Text);
            Assert.AreEqual(1, File.ReadAllText(manifest).Split("OID.2.25.311729368913984317654407730594956997722=1").Length - 1);
        }
        finally { Directory.Delete(temporary, recursive: true); }
    }

    [TestMethod]
    public void SigningCertificateResolutionRequiresCurrentUserPrivateKey()
    {
        string temporary = Path.Combine(Path.GetTempPath(), "unishare-signing-certificate-" + Guid.NewGuid());
        Directory.CreateDirectory(temporary);
        string thumbprintPath = Path.Combine(temporary, "thumbprint.txt");
        string certificatePath = Path.Combine(temporary, "public.cer");
        string? thumbprint = null;
        try
        {
            using var key = RSA.Create(2048);
            var request = new CertificateRequest("CN=UniShare", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: true));
            using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddDays(1));
            const string password = "UniShare integration test";
            using var privateCertificate = X509CertificateLoader.LoadPkcs12(
                generated.Export(X509ContentType.Pfx, password),
                password,
                X509KeyStorageFlags.PersistKeySet | X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
            thumbprint = privateCertificate.Thumbprint;
            File.WriteAllText(thumbprintPath, thumbprint);
            File.WriteAllBytes(certificatePath, privateCertificate.Export(X509ContentType.Cert));
            using (var store = new X509Store(StoreName.My, StoreLocation.CurrentUser))
            {
                store.Open(OpenFlags.ReadWrite);
                store.Add(privateCertificate);
            }

            var resolved = Run("resolve-signing-certificate.ps1", "-ThumbprintPath", thumbprintPath);
            Assert.AreEqual(0, resolved.Exit, resolved.Text);
            StringAssert.Contains(resolved.Text, thumbprint);

            RemoveCertificate(thumbprint);
            using var publicCertificate = X509CertificateLoader.LoadCertificateFromFile(certificatePath);
            using (var store = new X509Store(StoreName.My, StoreLocation.CurrentUser))
            {
                store.Open(OpenFlags.ReadWrite);
                store.Add(publicCertificate);
            }

            var publicOnly = Run("resolve-signing-certificate.ps1", "-ThumbprintPath", thumbprintPath);
            Assert.AreNotEqual(0, publicOnly.Exit, publicOnly.Text);
            StringAssert.Contains(publicOnly.Text, "private key");
        }
        finally
        {
            if (!string.IsNullOrWhiteSpace(thumbprint))
                RemoveCertificate(thumbprint);
            Directory.Delete(temporary, recursive: true);
        }
    }

    [TestMethod]
    public void PackageInspectionRejectsPrivateFilesAndChangedNativeDll()
    {
        string temporary = Path.Combine(Path.GetTempPath(), "unishare-package-policy-" + Guid.NewGuid());
        string package = Path.Combine(temporary, "package"), native = Path.Combine(temporary, "native");
        Directory.CreateDirectory(package);
        Directory.CreateDirectory(native);
        try
        {
            foreach (string name in new[] { "unishare.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll" })
            {
                File.WriteAllText(Path.Combine(native, name), "fixture-" + name);
                File.Copy(Path.Combine(native, name), Path.Combine(package, name));
            }
            File.WriteAllText(Path.Combine(package, "UniShare.App.exe"), "fixture");
            File.WriteAllText(Path.Combine(package, "NLog.dll"), "fixture");
            File.WriteAllText(Path.Combine(package, "resources.pri"), "fixture");
            Directory.CreateDirectory(Path.Combine(package, "Assets"));
            File.WriteAllText(Path.Combine(package, "Assets", "StoreLogo.png"), "fixture");
            Directory.CreateDirectory(Path.Combine(package, "licenses"));
            File.WriteAllText(Path.Combine(package, "licenses", "OpenSSL-NOTICES.txt"), "notice fixture");
            File.WriteAllText(Path.Combine(package, "licenses", "NLog-LICENSE.txt"), "license fixture");
            File.WriteAllText(Path.Combine(package, "AppxManifest.xml"), """
                <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10" xmlns:desktop="http://schemas.microsoft.com/appx/manifest/desktop/windows10">
                <Identity Publisher="CN=UniShare" ProcessorArchitecture="x64"/><Dependencies><TargetDeviceFamily MinVersion="10.0.17763.0"/></Dependencies>
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
            File.Delete(Path.Combine(package, "licenses", "NLog-LICENSE.txt"));
            var noNlogLicense = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, noNlogLicense.Exit);
            StringAssert.Contains(noNlogLicense.Text, "Missing package payload");
            File.WriteAllText(Path.Combine(package, "licenses", "NLog-LICENSE.txt"), "license fixture");
            File.Delete(Path.Combine(package, "NLog.dll"));
            var noNlog = Run("inspect-package.ps1", arguments);
            Assert.AreNotEqual(0, noNlog.Exit);
            StringAssert.Contains(noNlog.Text, "Missing package payload");
            File.WriteAllText(Path.Combine(package, "NLog.dll"), "fixture");
            var unsignedWithoutMarker = Run("inspect-package.ps1", arguments.Concat(["-Unsigned"]).ToArray());
            Assert.AreNotEqual(0, unsignedWithoutMarker.Exit);
            StringAssert.Contains(unsignedWithoutMarker.Text, "unsigned namespace marker");
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

    private static void RemoveCertificate(string thumbprint)
    {
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite);
        foreach (var certificate in store.Certificates.Find(X509FindType.FindByThumbprint, thumbprint, validOnly: false))
            store.Remove(certificate);
    }
}
