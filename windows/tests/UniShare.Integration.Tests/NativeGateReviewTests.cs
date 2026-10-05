using System.Text.Json;
using System.Xml.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace UniShare.Integration.Tests;

[TestClass]
public sealed class NativeGateReviewTests
{
    // Removing/renaming a CTest registration must never redefine the release gate.
    [TestMethod]
    public void NativeInventoryAndReportRequireTheIndependentFourteenUniqueNames()
    {
        using var fixture = new NativeGateFixture();
        Assert.AreEqual(0, fixture.Validate().Exit);
        foreach (bool mutateInventory in new[] { true, false })
        {
            foreach (string mutation in new[] { "removed", "renamed", "duplicate" })
            {
                var names = NativeGateFixture.Names.ToList();
                if (mutation == "removed") names.RemoveAt(0);
                else if (mutation == "renamed") names[0] = "different_native_test";
                else names[0] = names[1];
                fixture.Write(mutateInventory ? names : NativeGateFixture.Names, mutateInventory ? NativeGateFixture.Names : names);
                var rejected = fixture.Validate();
                Assert.AreNotEqual(0, rejected.Exit, $"Accepted {mutation}, inventory={mutateInventory}: {rejected.Text}");
                StringAssert.Contains(rejected.Text, "required native test");
            }
        }
    }

    [TestMethod]
    public void CallerCannotReduceRequiredNativeGateBySupplyingASmallerCount()
    {
        using var fixture = new NativeGateFixture();
        fixture.Write(NativeGateFixture.Names.Skip(1), NativeGateFixture.Names.Skip(1));
        var result = VerificationScriptTests.Run("validate-native-tests.ps1", "-Report", fixture.Report, "-ExpectedCount", "13");
        Assert.AreNotEqual(0, result.Exit, "A 13-case gate cannot pass after removing a required native test.");
    }

    [TestMethod]
    public void ExistingDriveRelativePrefixIsRejectedBeforePrerequisiteLookup()
    {
        var result = VerificationScriptTests.Run("build-native.ps1", "-OpenSslRoot", Path.GetPathRoot(Path.GetTempPath())![..2] + ".");
        Assert.AreNotEqual(0, result.Exit);
        StringAssert.Contains(result.Text, "OpenSslRoot must be an existing absolute");
    }
}

internal sealed class NativeGateFixture : IDisposable
{
    internal static readonly string[] Names = ["sdk_services", "sdk_lifecycle", "transfer_engine", "identity", "protocol", "contracts", "device_catalog", "session", "sdk_api", "sdk_api_c11", "windows_storage_stream_clipboard", "windows_network", "windows_adapter", "windows_package"];
    private readonly string directory = Path.Combine(Path.GetTempPath(), "unishare-native-gate-" + Guid.NewGuid());
    internal string Report => Path.Combine(directory, "results.xml");
    private string Inventory => Path.Combine(directory, "inventory.json");
    internal NativeGateFixture() { Directory.CreateDirectory(directory); Write(Names, Names); }
    internal void Write(IEnumerable<string> inventory, IEnumerable<string> report, bool skipped = false)
    {
        File.WriteAllText(Inventory, JsonSerializer.Serialize(new { tests = inventory.Select(name => new { name }) }));
        File.WriteAllText(Report, new XElement("testsuite", report.Select((name, index) => new XElement("testcase", new XAttribute("name", name), new XAttribute("status", skipped && index == 0 ? "notrun" : "run"), skipped && index == 0 ? new XElement("skipped") : null))).ToString());
    }
    internal (int Exit, string Text) Validate() => VerificationScriptTests.Run("validate-native-tests.ps1", "-Inventory", Inventory, "-Report", Report);
    public void Dispose() => Directory.Delete(directory, recursive: true);
}
