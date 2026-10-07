using UniShare.Application.Models;

namespace UniShare.App.Presentation;

public enum TitleBarThemePreference
{
    FollowSystem,
    Light,
    Dark
}

internal static class TitleBarThemePolicy
{
    public static TitleBarThemePreference Resolve(ThemePolicy theme) => theme switch
    {
        ThemePolicy.Light => TitleBarThemePreference.Light,
        ThemePolicy.Dark => TitleBarThemePreference.Dark,
        _ => TitleBarThemePreference.FollowSystem
    };
}
