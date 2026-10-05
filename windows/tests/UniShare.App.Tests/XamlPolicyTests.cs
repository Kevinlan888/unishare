using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace UniShare.App.Tests;

[TestClass]
public sealed partial class XamlPolicyTests
{
    private static string[] XamlFiles()
    {
        var files = Directory.GetFiles(ResourceCoverageTests.AppPath, "*.xaml", SearchOption.AllDirectories).Where(x => !x.Contains(Path.DirectorySeparatorChar + "obj" + Path.DirectorySeparatorChar)).ToArray();
        Assert.IsTrue(files.Length >= 15, "Shell, feature pages, cards and theme dictionaries are missing.");
        return files;
    }

    [TestMethod]
    public void EveryFeaturePageHasAnAccessibleLocalizedTitle()
    {
        XamlFiles();
        var resources = ResourceCoverageTests.ReadResources("en-US");
        foreach (var name in new[] { "Shell/ShellPage", "Share/SharePage", "Devices/DevicesPage", "History/HistoryPage", "Settings/SettingsPage" })
        {
            var path = Path.Combine(ResourceCoverageTests.AppPath, "Features", name + ".xaml");
            Assert.IsTrue(File.Exists(path), $"Missing page {name}");
            var root = XDocument.Load(path).Root!;
            var uid = root.Attribute(XName.Get("Uid", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value;
            Assert.IsNotNull(uid, $"Missing localized title on {name}");
            Assert.IsTrue(resources.ContainsKey(uid + ".AutomationProperties.Name"), $"Missing accessible title for {name}");
        }
    }

    [TestMethod]
    public void IconActionsHaveAccessibleNamesAndPagesUseThemeColors()
    {
        var resources = ResourceCoverageTests.ReadResources("en-US");
        foreach (var path in XamlFiles())
        {
            foreach (var element in XDocument.Load(path).Descendants())
            {
                if (!path.Contains(Path.Combine("Resources", "Themes")))
                    foreach (var value in element.Attributes().Where(a => new[] { "Background", "Foreground", "BorderBrush", "Fill", "Color" }.Contains(a.Name.LocalName)).Select(a => a.Value))
                        Assert.IsFalse(LiteralColor().IsMatch(value), $"Literal color in {path}: {value}");
                if (element.Name.LocalName is not ("Button" or "AppBarButton" or "MenuFlyoutItem") || !element.Descendants().Any(e => e.Name.LocalName is "FontIcon" or "SymbolIcon") && element.Attribute("Icon") is null) continue;
                var uid = element.Attribute(XName.Get("Uid", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value;
                Assert.IsTrue(element.Attribute("AutomationProperties.Name") is not null || uid is not null && resources.ContainsKey(uid + ".AutomationProperties.Name"), $"Unnamed icon action in {path}");
            }
        }
    }

    [TestMethod]
    public void ResponsiveShellAndActivityCardsExistForAllSupportedKinds()
    {
        XamlFiles();
        foreach (var card in new[] { "TextActivityCard", "ImageActivityCard", "FileActivityCard", "IncomingOfferCard" })
            Assert.IsTrue(File.Exists(Path.Combine(ResourceCoverageTests.AppPath, "Controls", card + ".xaml")), $"Missing activity renderer {card}");
        var share = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Features", "Share", "SharePage.xaml"));
        var states = share.Descendants().Where(x => x.Name.LocalName == "VisualState").Select(x => x.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value).ToArray();
        CollectionAssert.IsSubsetOf(new[] { "Wide", "Medium", "Compact" }, states);
    }

    [GeneratedRegex("^(#[0-9a-fA-F]{3,8}|White|Black|Blue|Red|Green|Transparent)$")]
    private static partial Regex LiteralColor();

    [TestMethod]
    public void EveryContentCardExposesStatusAndReachableDecisionActions()
    {
        foreach (var card in new[] { "TextActivityCard", "ImageActivityCard", "FileActivityCard", "IncomingOfferCard" })
        {
            var document = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Controls", card + ".xaml"));
            Assert.IsTrue(document.Descendants().Any(x => x.Name.LocalName == "TextBlock" && x.Attribute("Text")?.Value == "{Binding StatusText}"), $"No status announcement in {card}");
            var names = document.Descendants().Where(x => x.Name.LocalName == "Button").Select(x => x.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value).ToArray();
            CollectionAssert.IsSubsetOf(new[] { "AcceptButton", "RejectButton", "CancelButton", "RetryButton" }, names, $"Unsupported decision state in {card}");
        }
    }

    [TestMethod]
    public void DemoSnapshotIsOwnedByHostRatherThanFeaturePages()
    {
        Assert.IsTrue(File.Exists(Path.Combine(ResourceCoverageTests.AppPath, "DemoSnapshotService.cs")), "Demo visual-tree capture host is missing.");
        foreach (var page in Directory.GetFiles(Path.Combine(ResourceCoverageTests.AppPath, "Features"), "*.xaml.cs", SearchOption.AllDirectories))
            Assert.IsFalse(Regex.IsMatch(File.ReadAllText(page), @"\b(File|Directory|SqliteConnection|NativeMethods)\."), $"Feature page owns infrastructure: {page}");
    }

    [TestMethod]
    public void ComposerAcceptsDropsWithoutPageFileOperations()
    {
        var page = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Features", "Share", "DeviceSessionView.xaml"));
        Assert.IsTrue(page.Descendants().Any(x => x.Attribute("AllowDrop")?.Value == "True" && x.Attribute("Drop") is not null), "Single-file drop route is missing.");
    }

    [TestMethod]
    public void ActivityReplacementDisposesAllCardSubscriptions()
    {
        var session = File.ReadAllText(Path.Combine(ResourceCoverageTests.AppPath, "Features", "Share", "DeviceSessionView.xaml.cs"));
        Assert.IsTrue(session.Contains("card.Dispose()", StringComparison.Ordinal), "Activity replacement must dispose removed cards before clearing them.");
        Assert.IsTrue(session.IndexOf("card.Dispose()", StringComparison.Ordinal) < session.IndexOf("ActivityHost.Children.Clear()", StringComparison.Ordinal));
        foreach (var card in new[] { "TextActivityCard", "ImageActivityCard", "FileActivityCard", "IncomingOfferCard" })
        {
            var source = File.ReadAllText(Path.Combine(ResourceCoverageTests.AppPath, "Controls", card + ".xaml.cs"));
            Assert.IsTrue(source.Contains("UserControl, IDisposable", StringComparison.Ordinal), $"{card} does not expose its subscription lifetime.");
            Assert.IsTrue(source.Contains("subscription.Dispose()", StringComparison.Ordinal), $"{card} never releases its live activity subscription.");
            Assert.IsFalse(source.Contains("item.PropertyChanged +=", StringComparison.Ordinal), $"{card} bypasses managed subscription ownership.");
        }
    }

    [TestMethod]
    public void DeviceRowsBindTheirVisualAndAccessiblePresence()
    {
        var page = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Features", "Share", "DeviceListPane.xaml"));
        foreach (var template in page.Descendants().Where(x => x.Name.LocalName == "DataTemplate"))
        {
            Assert.IsTrue(template.Descendants().Any(x => x.Name.LocalName == "Ellipse" && x.Attribute("Opacity")?.Value == "{Binding OnlineIndicatorOpacity}"), "A device row always shows the online dot.");
            Assert.IsTrue(template.Descendants().Any(x => x.Attribute("AutomationProperties.Name")?.Value == "{Binding AccessibleName}"), "A device row omits accessible online/offline status.");
        }
    }

    [TestMethod]
    public void ClipboardMarkerUsesSenderLocalPresentationPolicy()
    {
        var card = File.ReadAllText(Path.Combine(ResourceCoverageTests.AppPath, "Controls", "TextActivityCard.xaml.cs"));
        Assert.IsTrue(card.Contains("PresentationState.ClipboardMarkerVisible(item.Direction, item.Origin)", StringComparison.Ordinal), "Incoming text can display sender-local clipboard origin.");
    }
}
