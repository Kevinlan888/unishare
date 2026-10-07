using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.Tests.ViewModels;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class RecentFileActivitiesTests
{
    [TestMethod]
    public void EmptyHistoryProducesNoSharedFiles()
    {
        Assert.AreEqual(0, RecentFileActivities.Select([]).Count);
    }

    [TestMethod]
    public void SharedFilesComeOnlyFromRecentFileAndImageActivities()
    {
        using var fixture = new ViewModelFixture();
        var device = new DeviceId(Guid.NewGuid());
        var oldFile = fixture.Record(device, name: "old.txt") with
        {
            CreatedAt = fixture.Clock.UtcNow.AddMinutes(-4)
        };
        var text = fixture.Record(device, name: "text") with
        {
            Kind = ContentKind.Text,
            CreatedAt = fixture.Clock.UtcNow.AddMinutes(-1)
        };
        var image = fixture.Record(device, name: "photo.jpg") with
        {
            Kind = ContentKind.Image,
            CreatedAt = fixture.Clock.UtcNow.AddMinutes(-2)
        };
        var file = fixture.Record(device, name: "new.zip") with
        {
            CreatedAt = fixture.Clock.UtcNow
        };

        var result = RecentFileActivities.Select([oldFile, text, image, file], limit: 2);

        CollectionAssert.AreEqual(new[] { "new.zip", "photo.jpg" }, result.Select(record => record.DisplayName).ToArray());
    }
}
