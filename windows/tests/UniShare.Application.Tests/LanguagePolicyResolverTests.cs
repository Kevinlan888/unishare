using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class LanguagePolicyResolverTests
{
    [TestMethod]
    [DataRow("zh-CN", "zh-CN")]
    [DataRow("zh-Hans-CN", "zh-CN")]
    [DataRow("en-US", "en-US")]
    [DataRow("fr-FR", "en-US")]
    public void FollowingSystemSelectsSupportedResources(string systemLanguage, string expected)
    {
        Assert.AreEqual(expected, LanguagePolicyResolver.Resolve(LanguagePolicy.System, systemLanguage));
    }

    [TestMethod]
    public void ExplicitLanguageOverridesSystemLanguage()
    {
        Assert.AreEqual("en-US", LanguagePolicyResolver.Resolve(LanguagePolicy.English, "zh-CN"));
        Assert.AreEqual("zh-CN", LanguagePolicyResolver.Resolve(LanguagePolicy.SimplifiedChinese, "en-US"));
    }
}
