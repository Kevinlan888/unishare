using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;
using UniShare.Application.Models;

namespace UniShare.App.Tests;

[TestClass]
public sealed class TitleBarThemePolicyTests
{
    [TestMethod]
    [DataRow(ThemePolicy.System, TitleBarThemePreference.FollowSystem)]
    [DataRow(ThemePolicy.Light, TitleBarThemePreference.Light)]
    [DataRow(ThemePolicy.Dark, TitleBarThemePreference.Dark)]
    public void RequestedThemeControlsTheSystemCaptionButtons(
        ThemePolicy requestedTheme,
        TitleBarThemePreference expected)
    {
        Assert.AreEqual(expected, TitleBarThemePolicy.Resolve(requestedTheme));
    }
}
