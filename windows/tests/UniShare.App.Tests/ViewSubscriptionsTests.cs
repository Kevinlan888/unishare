using System.Collections.ObjectModel;
using System.ComponentModel;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Presentation;

namespace UniShare.App.Tests;

[TestClass]
public sealed class ViewSubscriptionsTests
{
    [TestMethod]
    public void ReplacingShellRetiresOldViewSubscriptionsAndCardsExactlyOnce()
    {
        var model = new Model();
        var activities = new ObservableCollection<int>();
        var oldRenders = 0;
        var oldCardsDisposed = 0;
        var newRenders = 0;
        var oldView = new ViewSubscriptions(model, _ => oldRenders++, activities, () => oldRenders++, () => oldCardsDisposed++);
        model.Change();
        activities.Add(1);
        Assert.AreEqual(2, oldRenders);
        oldView.Dispose();
        oldView.Dispose();
        using var newView = new ViewSubscriptions(model, _ => newRenders++, activities, () => newRenders++);
        model.Change();
        activities.Add(2);
        Assert.AreEqual(2, oldRenders);
        Assert.AreEqual(2, newRenders);
        Assert.AreEqual(1, oldCardsDisposed);
    }
    [TestMethod]
    public void DetailsSubscriptionAlsoDetachesWithoutACollection()
    {
        var model = new Model();
        var renders = 0;
        var details = new ViewSubscriptions(model, _ => renders++);
        model.Change();
        details.Dispose();
        model.Change();
        Assert.AreEqual(1, renders);
    }
    private sealed class Model : INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler? PropertyChanged; public void Change() => PropertyChanged?.Invoke(this, new("SelectedDevice"));
    }
}
