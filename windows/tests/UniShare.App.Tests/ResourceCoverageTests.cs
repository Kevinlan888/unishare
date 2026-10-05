using System.Xml.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;

namespace UniShare.App.Tests;

[TestClass]
public sealed class ResourceCoverageTests
{
    internal static string AppPath
    {
        get
        {
            var directory = new DirectoryInfo(AppContext.BaseDirectory);
            while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "windows", "UniShare.slnx"))) directory = directory.Parent;
            return Path.Combine(directory!.FullName, "windows", "src", "UniShare.App");
        }
    }

    internal static Dictionary<string, string> ReadResources(string language)
    {
        var path = Path.Combine(AppPath, "Strings", language, "Resources.resw");
        Assert.IsTrue(File.Exists(path), $"Missing localization: {language}");
        return XDocument.Load(path).Root!.Elements("data").ToDictionary(x => x.Attribute("name")!.Value, x => x.Element("value")!.Value);
    }

    [TestMethod]
    public void BothLanguagesCoverSemanticStatesAndAllXamlResourceReferences()
    {
        var english = ReadResources("en-US");
        var chinese = ReadResources("zh-CN");
        CollectionAssert.AreEquivalent(english.Keys.ToArray(), chinese.Keys.ToArray());
        string[] required = ["AppTitle", "ShareTitle", "DevicesTitle", "HistoryTitle", "SettingsTitle", "EmptyDevices", "NoSelection", "Offline", "Loading", "Error", "SendText", "SendPhoto", "SendFile", "SendClipboard", "Accept", "Reject", "Cancel", "Retry"];
        foreach (var key in required.Concat(Enum.GetNames<ActivityStatus>().Select(x => "Activity." + x)).Concat(Enum.GetNames<ContentKind>().Select(x => "Content." + x)))
            Assert.IsTrue(english.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value), $"Missing required resource: {key}");
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        foreach (var path in Directory.GetFiles(AppPath, "*.xaml", SearchOption.AllDirectories).Where(x => !x.Contains(Path.DirectorySeparatorChar + "obj" + Path.DirectorySeparatorChar)))
            foreach (var element in XDocument.Load(path).Descendants().Where(e => e.Attribute(x + "Uid") is not null))
            {
                var uid = element.Attribute(x + "Uid")!.Value;
                Assert.IsTrue(english.Keys.Any(key => key.StartsWith(uid + ".", StringComparison.Ordinal)), $"Unresolved x:Uid {uid} in {path}");
            }
    }

    [TestMethod]
    public void TaskEightViewModelErrorsHaveUserFacingTranslations()
    {
        string[] errors = ["Discovery.RetryFailed", "Discovery.PermissionDenied", "Discovery.Failed", "Transfer.SubmissionFailed", "Transfer.AcceptFailed", "Transfer.RejectFailed", "Transfer.CancelFailed", "Transfer.RetryFailed", "Device.ForgetFailed", "Device.IdentityChanged", "Pairing.ConnectFailed", "Pairing.ConfirmFailed", "Pairing.RejectFailed", "History.LoadFailed", "Activity.OpenFailed", "Activity.OpenLocationFailed", "Settings.SaveFailed", "Settings.ReceiveDirectoryNotWritable"];
        foreach (var language in new[] { "en-US", "zh-CN" })
        {
            var resources = ReadResources(language);
            foreach (var key in errors) Assert.IsTrue(resources.TryGetValue(key, out var text) && !string.IsNullOrWhiteSpace(text), $"Missing ViewModel error translation: {key} ({language})");
        }
    }
}
