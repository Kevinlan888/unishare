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
    public void ManifestActivatesNotificationsAndDefaultOffStartupUsingOnlyTheMainExecutable()
    {
        var manifest = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Package.appxmanifest"));
        var applications = manifest.Descendants().Where(e => e.Name.LocalName == "Application").ToArray();
        Assert.AreEqual(1, applications.Length);
        var executable = applications[0].Attribute("Executable")!.Value;
        var server = manifest.Descendants().SingleOrDefault(e => e.Name.LocalName == "ExeServer");
        Assert.IsNotNull(server, "Notification COM activation missing");
        Assert.AreEqual(executable, server.Attribute("Executable")?.Value);
        var toast = manifest.Descendants().Single(e => e.Name.LocalName == "ToastNotificationActivation");
        Assert.AreEqual(server.Elements().Single(e => e.Name.LocalName == "Class").Attribute("Id")?.Value, toast.Attribute("ToastActivatorCLSID")?.Value);
        var startup = manifest.Descendants().Single(e => e.Name.LocalName == "StartupTask");
        Assert.AreEqual("http://schemas.microsoft.com/appx/manifest/desktop/windows10", startup.Name.NamespaceName);
        Assert.AreEqual("false", startup.Attribute("Enabled")?.Value);
        Assert.AreEqual("UniShareStartup", startup.Attribute("TaskId")?.Value);
        Assert.IsFalse(manifest.Descendants().Any(e => e.Name.LocalName == "Service"));
    }

    [TestMethod]
    public void ExecutableManifestUsesPerMonitorV2InsteadOfBitmapScaling()
    {
        var manifest = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "app.manifest"));
        var dpiAware = manifest.Descendants().SingleOrDefault(element => element.Name.LocalName == "dpiAware");
        var dpiAwareness = manifest.Descendants().SingleOrDefault(element => element.Name.LocalName == "dpiAwareness");
        Assert.AreEqual("true/pm", dpiAware?.Value, "Pre-Windows 10 DPI fallback is missing.");
        Assert.AreEqual("PerMonitorV2", dpiAwareness?.Value, "Windows is allowed to bitmap-scale the whole UI.");
    }

    [TestMethod]
    public void SettingsPageOwnsAVisibleAutosaveFailureSurface()
    {
        var page = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Features", "Settings", "SettingsPage.xaml"));
        var error = page.Descendants().SingleOrDefault(element =>
            element.Name.LocalName == "InfoBar" && element.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value == "SettingsSaveError");
        Assert.IsNotNull(error, "Settings save failures are only rendered on the hidden device-session page.");
        Assert.AreEqual("Error", error.Attribute("Severity")?.Value);
    }

    [TestMethod]
    public void PairingDialogExposesCodeAndExplicitMatchOrMismatchCommandsWithoutBypass()
    {
        var path = Path.Combine(ResourceCoverageTests.AppPath, "Features", "Pairing", "PairingDialog.xaml");
        Assert.IsTrue(File.Exists(path), "Pairing dialog missing");
        var root = XDocument.Load(path).Root!;
        Assert.AreEqual("{Binding ConfirmMatchesCommand}", root.Attribute("PrimaryButtonCommand")?.Value);
        Assert.AreEqual("{Binding RejectCommand}", root.Attribute("SecondaryButtonCommand")?.Value);
        Assert.IsTrue(root.Descendants().Any(e => e.Attribute("Text")?.Value == "{Binding PendingRequest.VerificationCode}"));
        Assert.IsNull(root.Attribute("CloseButtonText"));
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
                if (element.Name.LocalName is not ("Button" or "AppBarButton" or "MenuFlyoutItem") || !element.Descendants().Any(e => e.Name.LocalName is "FontIcon" or "SymbolIcon") && element.Attribute("Icon") is null)
                    continue;
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
    public void FileProgressLabelBindsToTheLiveProjection()
    {
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        var card = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Controls", "FileActivityCard.xaml"));
        var label = card.Descendants().SingleOrDefault(element =>
            element.Name.LocalName == "TextBlock" && element.Attribute("Text")?.Value == "{Binding ProgressText}");

        Assert.IsNotNull(label, "The file progress label must bind to the live ProgressText value.");
        Assert.IsNull(label.Attribute(x + "Uid"), "A localized x:Uid could replace the live progress text with demo content.");
    }

    [TestMethod]
    public void FileAndImageTransfersDisplayLiveSpeedAndEstimatedRemainingTime()
    {
        foreach (var name in new[] { "FileActivityCard", "ImageActivityCard" })
        {
            var card = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Controls", name + ".xaml"));
            Assert.IsTrue(card.Descendants().Any(element =>
                element.Name.LocalName == "TextBlock" && element.Attribute("Text")?.Value == "{Binding SpeedText}"),
                $"{name} does not display live transfer speed.");
            Assert.IsTrue(card.Descendants().Any(element =>
                element.Name.LocalName == "TextBlock" && element.Attribute("Text")?.Value == "{Binding EstimatedRemainingText}"),
                $"{name} does not display estimated remaining time.");
        }
    }

    [TestMethod]
    public void CompletedFileAndImageCardsExposeLocationAndDoubleTapOpenActions()
    {
        foreach (var name in new[] { "FileActivityCard", "ImageActivityCard" })
        {
            var card = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Controls", name + ".xaml"));
            Assert.IsTrue(card.Descendants().Any(element => element.Attribute("DoubleTapped")?.Value == "OpenDoubleTapped"),
                $"{name} does not open a received item on double-tap.");
            Assert.IsTrue(card.Descendants().Any(element => element.Attribute("PointerEntered")?.Value == "RefreshFileAvailability"),
                $"{name} does not recheck whether a received item still exists before interaction.");
            Assert.IsTrue(card.Descendants().Any(element =>
                element.Name.LocalName == "Button" &&
                element.Attribute(XName.Get("Name", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value == "OpenLocationButton"),
                $"{name} does not expose an open-location action.");
        }
    }

    [TestMethod]
    public void SharedFilesDisplayTheirFormattedSize()
    {
        var pane = XDocument.Load(Path.Combine(ResourceCoverageTests.AppPath, "Features", "Share", "DeviceDetailsPane.xaml"));
        var sharedFiles = pane.Descendants().Single(element =>
            element.Name.LocalName == "ItemsControl" && element.Attribute("ItemsSource")?.Value == "{Binding SharedFiles}");

        Assert.IsTrue(sharedFiles.Descendants().Any(element =>
            element.Name.LocalName == "TextBlock" && element.Attribute("Text")?.Value == "{Binding Size}"),
            "Shared file rows do not display the formatted size.");
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
    public void CompactDeviceRowsUseSmallCenteredIconsAndBothListsSwitchTemplates()
    {
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        var path = Path.Combine(ResourceCoverageTests.AppPath, "Features", "Share", "DeviceListPane.xaml");
        var page = XDocument.Load(path);
        var compactTemplate = page.Descendants().SingleOrDefault(element =>
            element.Name.LocalName == "DataTemplate" && element.Attribute(x + "Key")?.Value == "CompactDeviceTemplate");

        Assert.IsNotNull(compactTemplate, "The narrow device rail does not have a dedicated compact row template.");
        var hitTarget = compactTemplate.Descendants().Single(element => element.Name.LocalName == "Grid");
        Assert.AreEqual("44", hitTarget.Attribute("Width")?.Value);
        Assert.AreEqual("44", hitTarget.Attribute("Height")?.Value);
        Assert.AreEqual("Center", hitTarget.Attribute("HorizontalAlignment")?.Value);
        Assert.AreEqual("Center", hitTarget.Attribute("VerticalAlignment")?.Value);
        Assert.AreEqual("-16,0,16,0", hitTarget.Attribute("Margin")?.Value,
            "The template must compensate for WinUI's single-selection indicator gutter.");
        Assert.IsTrue(compactTemplate.Descendants().Any(element =>
            element.Name.LocalName == "FontIcon" && element.Attribute("FontSize")?.Value == "20"),
            "Compact device icons should match the navigation icon scale.");
        Assert.IsTrue(compactTemplate.Descendants().Any(element =>
            element.Attribute("ToolTipService.ToolTip")?.Value == "{Binding Name}"),
            "Icon-only device rows must still reveal the device name.");
        var compactContainerStyle = page.Descendants().SingleOrDefault(element =>
            element.Name.LocalName == "Style" && element.Attribute(x + "Key")?.Value == "CompactDeviceItemContainerStyle");
        Assert.IsNotNull(compactContainerStyle, "Compact rows need a container style so ListView selection chrome cannot offset their icons.");
        Assert.IsTrue(compactContainerStyle.Descendants().Any(element =>
            element.Attribute("Property")?.Value == "HorizontalAlignment" && element.Attribute("Value")?.Value == "Stretch"));
        Assert.IsTrue(compactContainerStyle.Descendants().Any(element =>
            element.Attribute("Property")?.Value == "HorizontalContentAlignment" && element.Attribute("Value")?.Value == "Center"));

        var source = File.ReadAllText(Path.ChangeExtension(path, ".xaml.cs"));
        StringAssert.Contains(source, "RecentList.ItemTemplate = compact ? CompactDeviceTemplate : RecentDeviceTemplate");
        StringAssert.Contains(source, "NearbyList.ItemTemplate = compact ? CompactDeviceTemplate : NearbyDeviceTemplate");
        StringAssert.Contains(source, "RecentList.ItemContainerStyle = compact ? CompactDeviceItemContainerStyle : null");
        StringAssert.Contains(source, "NearbyList.ItemContainerStyle = compact ? CompactDeviceItemContainerStyle : null");
        StringAssert.Contains(source, "button.HorizontalContentAlignment = compact ? HorizontalAlignment.Center : HorizontalAlignment.Left");
    }

    [TestMethod]
    public void ClipboardMarkerUsesSenderLocalPresentationPolicy()
    {
        var card = File.ReadAllText(Path.Combine(ResourceCoverageTests.AppPath, "Controls", "TextActivityCard.xaml.cs"));
        Assert.IsTrue(card.Contains("PresentationState.ClipboardMarkerVisible(item.Direction, item.Origin)", StringComparison.Ordinal), "Incoming text can display sender-local clipboard origin.");
    }
}
