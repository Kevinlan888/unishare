using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;

namespace UniShare.App.Tests;

[TestClass]
public sealed class ShareWidthStateTrackerTests
{
    [TestMethod]
    public void ContinuousResizeOnlyUpdatesWhenTheResponsiveStateChanges()
    {
        var tracker = new ShareWidthStateTracker();

        Assert.AreEqual("Compact", tracker.ChangeFor(960));
        Assert.IsNull(tracker.ChangeFor(1000), "Resizing within Compact must not rebuild device rows.");
        Assert.AreEqual("Medium", tracker.ChangeFor(1060));
        Assert.IsNull(tracker.ChangeFor(1200), "Resizing within Medium must not reapply the layout.");
        Assert.AreEqual("Wide", tracker.ChangeFor(1240));
        Assert.IsNull(tracker.ChangeFor(1400), "Resizing within Wide must not reapply the layout.");
        Assert.AreEqual("Medium", tracker.ChangeFor(1239));
        Assert.AreEqual("Compact", tracker.ChangeFor(1059));
    }
}
