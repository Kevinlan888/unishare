using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.ViewModels;

namespace UniShare.Application.Tests.ViewModels;

[TestClass]
public sealed class ActivityItemViewModelTests
{
    [TestMethod]
    public void EqualTimeDuplicateAndRegressingSamplesKeepFiniteStableSpeedAndEta()
    {
        using var f = new ViewModelFixture();
        var record = f.Record(new(Guid.NewGuid()));
        var vm = new ActivityItemViewModel(record, f.Ui, f.Dispatcher);
        vm.Update(record with
        {
            TransferredBytes = 100
        });
        Assert.IsNull(vm.EstimatedRemaining);
        Assert.AreEqual(0d, vm.BytesPerSecond);
        vm.Update(record with
        {
            TransferredBytes = 300,
            UpdatedAt = record.UpdatedAt.AddSeconds(2)
        });
        Assert.AreEqual(100d, vm.BytesPerSecond);
        Assert.AreEqual(TimeSpan.FromSeconds(7), vm.EstimatedRemaining);
        vm.Update(record with
        {
            TransferredBytes = 250,
            UpdatedAt = record.UpdatedAt.AddSeconds(1)
        });
        Assert.AreEqual(100d, vm.BytesPerSecond);
        Assert.AreEqual(TimeSpan.FromSeconds(7), vm.EstimatedRemaining);
        vm.Update(record with
        {
            TransferredBytes = 300,
            UpdatedAt = record.UpdatedAt.AddSeconds(2)
        });
        Assert.AreEqual(100d, vm.BytesPerSecond);
        var empty = new ActivityItemViewModel(record with
        {
            ByteCount = 0
        }, f.Ui, f.Dispatcher);
        Assert.AreEqual(0d, empty.ProgressFraction);
        Assert.IsNull(empty.EstimatedRemaining);
    }

    [TestMethod]
    public async Task MissingReceivedFileDisablesAndRechecksBothOpenActions()
    {
        foreach (var kind in new[] { ContentKind.File, ContentKind.Image })
        {
            using var f = new ViewModelFixture();
            var record = f.Record(new(Guid.NewGuid()), ActivityStatus.Completed, direction: ShareDirection.Incoming) with
            {
                Kind = kind,
                ReceivedFilePath = "received"
            };
            var vm = new ActivityItemViewModel(record, f.Ui, f.Dispatcher);
            Assert.IsTrue(vm.OpenCommand.CanExecute(null), $"{kind} should open through the system file boundary.");
            Assert.IsTrue(vm.OpenLocationCommand.CanExecute(null), $"{kind} should expose its file location.");
            f.Ui.Exists = false;
            vm.RefreshFileAvailability();
            Assert.IsFalse(vm.OpenCommand.CanExecute(null));
            Assert.IsFalse(vm.OpenLocationCommand.CanExecute(null));
            await vm.OpenCommand.ExecuteAsync(null);
            await vm.OpenLocationCommand.ExecuteAsync(null);
            Assert.AreEqual(0, f.Ui.Opened.Count);
            Assert.AreEqual("Activity.Completed", vm.StatusResourceKey);
        }
    }

    [TestMethod]
    public async Task HistoryCombinesQueryStatusAndDirection()
    {
        using var f = new ViewModelFixture();
        var device = new DeviceId(Guid.NewGuid());
        f.Repository.Records.AddRange([f.Record(device, ActivityStatus.Completed, direction: ShareDirection.Incoming),
            f.Record(device, ActivityStatus.Failed, direction: ShareDirection.Incoming), f.Record(device, ActivityStatus.Completed),
            f.Record(device, ActivityStatus.Completed, direction: ShareDirection.Incoming, name: "photo.png")]);
        var vm = new HistoryViewModel(f.Repository, f.Ui, f.Dispatcher) { Query = "REPORT", StatusFilter = ActivityStatus.Completed, DirectionFilter = ShareDirection.Incoming };
        await vm.RefreshCommand.ExecuteAsync(null);
        Assert.AreEqual(1, vm.Activities.Count);
        Assert.AreEqual("report.txt", vm.Activities.Single().Record.DisplayName);
        Assert.AreEqual(new ActivityQuery("REPORT", null, ActivityStatus.Completed, ShareDirection.Incoming), f.Repository.LastQuery);
    }
}
