using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace UniShare.Integration.Tests;

[TestClass]
public sealed class AcceptanceImportReviewTests
{
    // These are synthetic importer fixtures, never physical acceptance evidence.
    [TestMethod]
    public void CompleteObservedMatrixRequiresDistinctCorrectPhysicalPlatforms()
    {
        using var fixture = new AcceptanceFixture();
        Assert.AreEqual(0, fixture.Import().Exit, "A correctly bound complete synthetic matrix must import.");
        foreach (var (peer, ids) in new (string, string[])[] {
            ("Windows", ["windows-a", "android"]), ("Windows", ["windows-a", "windows-a"]),
            ("Android", ["windows-a", "windows-b"]), ("iOS", ["windows-a", "android"]),
            ("Local", ["android"]), ("Local", ["missing"]), ("Local", ["windows-a", "windows-a"]) })
        {
            fixture.Reset();
            fixture.FirstRow(peer)["deviceIds"] = new JsonArray(ids.Select(id => JsonValue.Create(id)).ToArray());
            var result = fixture.Import();
            Assert.AreNotEqual(0, result.Exit, $"Accepted {peer}: {string.Join(',', ids)}");
            Assert.IsFalse(File.Exists(fixture.Output), result.Text);
        }
    }

    [TestMethod]
    public void ObservedRowsRejectEmptyAndWhitespaceEvidenceReferences()
    {
        using var fixture = new AcceptanceFixture();
        foreach (string status in new[] { "PASS", "FAIL" })
        foreach (string evidence in new[] { "", "   " })
        {
            fixture.Reset();
            fixture.FirstRow("Android")["status"] = status;
            fixture.FirstRow("Android")["evidence"] = new JsonArray(evidence);
            var result = fixture.Import();
            Assert.AreNotEqual(0, result.Exit, $"Accepted empty evidence ({status}).");
            Assert.IsFalse(File.Exists(fixture.Output));
        }
    }

    [TestMethod]
    public void EveryObservedImportBindsAnActualMsixAndItsNonzeroExactHash()
    {
        using var fixture = new AcceptanceFixture();
        var absent = fixture.Import(bindPackage: false);
        Assert.AreNotEqual(0, absent.Exit, "All PASS cannot import without -Package.");
        foreach (string hash in new[] { new string('0', 64), new string('A', 64), "", "not-a-hash" })
        {
            fixture.Reset(); fixture.Record["provenance"]!["packageSha256"] = hash;
            var rejected = fixture.Import();
            Assert.AreNotEqual(0, rejected.Exit, $"Accepted hash {hash}.");
            Assert.IsFalse(File.Exists(fixture.Output));
        }
        fixture.Reset();
        foreach (string path in new[] { fixture.Package + ".missing.msix", fixture.DirectoryPath, fixture.NonMsix, fixture.FakeMsix })
        {
            var rejected = fixture.Import(package: path);
            Assert.AreNotEqual(0, rejected.Exit, $"Accepted package {path}.");
            Assert.IsFalse(File.Exists(fixture.Output));
        }
        fixture.Reset(); fixture.FirstRow("Android")["status"] = "FAIL";
        Assert.AreNotEqual(0, fixture.Import(bindPackage: false).Exit);
        Assert.IsFalse(File.Exists(fixture.Output));
        var validFailure = fixture.Import();
        Assert.AreEqual(1, validFailure.Exit, validFailure.Text);
        Assert.IsTrue(File.Exists(fixture.Output), "A valid observed FAIL must be recorded, with FAIL gate.");
    }
}

internal sealed class AcceptanceFixture : IDisposable
{
    internal string DirectoryPath { get; } = Path.Combine(Path.GetTempPath(), "unishare-acceptance-fixture-" + Guid.NewGuid());
    internal string Package => Path.Combine(DirectoryPath, "fixture.msix");
    internal string Output => Path.Combine(DirectoryPath, "output.json");
    internal string NonMsix => Path.Combine(DirectoryPath, "fixture.txt");
    internal string FakeMsix => Path.Combine(DirectoryPath, "fake.msix");
    internal JsonObject Record { get; private set; } = null!;
    internal AcceptanceFixture()
    {
        System.IO.Directory.CreateDirectory(DirectoryPath);
        using (var archive = ZipFile.Open(Package, ZipArchiveMode.Create))
        {
            foreach (var (name, xml) in new[] {
                ("AppxManifest.xml", "<Package xmlns=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10\"><Identity Name=\"Synthetic.UniShare.Test\" Publisher=\"CN=Synthetic\" Version=\"1.0.0.0\" ProcessorArchitecture=\"x64\"/></Package>"),
                ("AppxBlockMap.xml", "<BlockMap xmlns=\"http://schemas.microsoft.com/appx/2010/blockmap\" HashMethod=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>"),
                ("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>") })
            { using var writer = new StreamWriter(archive.CreateEntry(name).Open()); writer.Write(xml); }
        }
        File.Copy(Package, NonMsix); File.WriteAllText(FakeMsix, "A filename does not make this MSIX.");
        Reset();
    }
    internal void Reset()
    {
        Record = JsonNode.Parse(File.ReadAllText(Path.Combine(NativeAbiSmokeTests.RepoRoot, "tests", "interop", "windows-ui-results-2026-10-05.json")))!.AsObject();
        Assert.AreEqual(192, Record["results"]!.AsArray().Count);
        Record["provenance"]!["operator"] = "Synthetic test operator — not physical evidence";
        Record["provenance"]!["packageSha256"] = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(Package)));
        Record["provenance"]!["devices"] = new JsonArray(new[] { ("windows-a", "Windows"), ("windows-b", "Windows"), ("android", "Android"), ("ios", "iOS") }.Select(device => (JsonNode)new JsonObject {
            ["id"] = device.Item1, ["platform"] = device.Item2, ["model"] = "Synthetic test fixture", ["osVersion"] = "test", ["appVersion"] = "test" }).ToArray());
        foreach (JsonNode? row in Record["results"]!.AsArray())
        {
            string peer = row!["peer"]!.GetValue<string>();
            row["status"] = "PASS"; row["observedAtUtc"] = "2026-10-05T12:00:00Z"; row["evidence"] = new JsonArray("synthetic://test-fixture/transcript");
            row["deviceIds"] = new JsonArray(peer switch { "Windows" => ["windows-a", "windows-b"], "Android" => ["windows-a", "android"], "iOS" => ["windows-a", "ios"], _ => ["windows-a"] });
        }
        File.Delete(Output);
    }
    internal JsonObject FirstRow(string peer) => Record["results"]!.AsArray().First(row => row!["peer"]!.GetValue<string>() == peer)!.AsObject();
    internal (int Exit, string Text) Import(bool bindPackage = true, string? package = null)
    {
        File.Delete(Output);
        string evidence = Path.Combine(DirectoryPath, "evidence.json");
        File.WriteAllText(evidence, Record.ToJsonString());
        var args = new List<string> { "-Output", Output, "-Evidence", evidence };
        if (bindPackage) args.AddRange(["-Package", package ?? Package]);
        return VerificationScriptTests.Run("run-acceptance.ps1", [.. args]);
    }
    public void Dispose() => System.IO.Directory.Delete(DirectoryPath, recursive: true);
}
