namespace UniShare.App.Presentation;

internal sealed class ShareWidthStateTracker
{
    private string? current;

    public string? ChangeFor(double width)
    {
        var next = width >= 1240 ? "Wide" : width >= 1060 ? "Medium" : "Compact";
        if (next == current)
            return null;
        current = next;
        return next;
    }
}
