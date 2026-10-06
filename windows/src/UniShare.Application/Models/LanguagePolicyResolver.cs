namespace UniShare.Application.Models;

public static class LanguagePolicyResolver
{
    public static string Resolve(LanguagePolicy policy, string systemLanguage) => policy switch
    {
        LanguagePolicy.English => "en-US",
        LanguagePolicy.SimplifiedChinese => "zh-CN",
        _ when systemLanguage.Equals("zh-CN", StringComparison.OrdinalIgnoreCase) ||
               systemLanguage.Equals("zh-SG", StringComparison.OrdinalIgnoreCase) ||
               systemLanguage.StartsWith("zh-Hans", StringComparison.OrdinalIgnoreCase) => "zh-CN",
        _ => "en-US"
    };
}
