using System.Text.Json;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Infrastructure.Storage;

namespace UniShare.Infrastructure.Tests;

[TestClass]
public sealed class JsonSettingsStoreTests
{
    [TestMethod]
    public async Task FirstLoadResolvesDownloadsAndPreservesTaskOneDefaults()
    {
        using var directory = new TemporaryDirectory();
        var redirected = System.IO.Path.Combine(directory.Path, "redirected-downloads");
        var settings = await new JsonSettingsStore(directory.Path, () => redirected).LoadAsync(default);
        Assert.AreEqual(new AppSettings { ReceiveDirectory = System.IO.Path.Combine(redirected, "UniShare") }, settings);
        Assert.IsFalse(File.Exists(System.IO.Path.Combine(directory.Path, "settings.json")));
    }

    [TestMethod]
    public async Task DefaultResolverReturnsAbsoluteDownloadsChildOnWindows()
    {
        using var directory = new TemporaryDirectory();
        var settings = await new JsonSettingsStore(directory.Path).LoadAsync(default);
        Assert.IsNotNull(settings.ReceiveDirectory);
        Assert.IsTrue(System.IO.Path.IsPathFullyQualified(settings.ReceiveDirectory));
        Assert.AreEqual("UniShare", System.IO.Path.GetFileName(settings.ReceiveDirectory));
    }

    [TestMethod]
    public async Task SaveAndReplaceRoundTripSettingsAndKeepRecoverablePriorJson()
    {
        using var directory = new TemporaryDirectory();
        var store = new JsonSettingsStore(directory.Path);
        var first = new AppSettings { DeviceName = "first", ReceiveDirectory = "D:\\receive", Theme = ThemePolicy.Dark };
        var second = first with
        {
            DeviceName = "second",
            Window = new(2, 3, 900, 600, true),
            StartupEnabled = true
        };
        await store.SaveAsync(first, default);
        await store.SaveAsync(second, default);
        Assert.AreEqual(second, await new JsonSettingsStore(directory.Path).LoadAsync(default));
        Assert.AreEqual(first, JsonSerializer.Deserialize<AppSettings>(await File.ReadAllTextAsync(System.IO.Path.Combine(directory.Path, "settings.json.bak"))));
        Assert.IsFalse(File.Exists(System.IO.Path.Combine(directory.Path, "settings.json.tmp")));
    }

    [TestMethod]
    public async Task PartialTemporaryReplacementLeavesPriorSettingsReadable()
    {
        using var directory = new TemporaryDirectory();
        var store = new JsonSettingsStore(directory.Path);
        var prior = new AppSettings { DeviceName = "prior", ReceiveDirectory = "D:\\receive" };
        await store.SaveAsync(prior, default);
        var temporary = System.IO.Path.Combine(directory.Path, "settings.json.tmp");
        await File.WriteAllTextAsync(temporary, "{\"DeviceName\":\"partial");
        Assert.AreEqual(prior, await new JsonSettingsStore(directory.Path).LoadAsync(default));
        using (var held = new FileStream(temporary, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
            await Assert.ThrowsAsync<IOException>(() => store.SaveAsync(prior with { DeviceName = "replacement" }, default));
        Assert.AreEqual(prior, JsonSerializer.Deserialize<AppSettings>(await File.ReadAllTextAsync(System.IO.Path.Combine(directory.Path, "settings.json"))));
    }

    [TestMethod]
    public async Task CorruptPrimaryCanRecoverPriorBackupWithoutReadingPartialTemporaryFile()
    {
        using var directory = new TemporaryDirectory();
        var store = new JsonSettingsStore(directory.Path);
        var prior = new AppSettings { DeviceName = "prior", ReceiveDirectory = "D:\\receive" };
        await store.SaveAsync(prior, default);
        await store.SaveAsync(prior with
        {
            DeviceName = "current"
        }, default);
        await File.WriteAllTextAsync(System.IO.Path.Combine(directory.Path, "settings.json"), "{");
        await File.WriteAllTextAsync(System.IO.Path.Combine(directory.Path, "settings.json.tmp"), "{partial");
        Assert.AreEqual(prior, await store.LoadAsync(default));
    }

    [TestMethod]
    public async Task ConcurrentStoresPublishCompleteJsonAndCancelledSavePreservesIt()
    {
        using var directory = new TemporaryDirectory();
        await Task.WhenAll(Enumerable.Range(0, 15).Select(i => Task.Run(() => new JsonSettingsStore(directory.Path).SaveAsync(new AppSettings { DeviceName = $"name-{i}", ReceiveDirectory = "D:\\receive" }, default))));
        var store = new JsonSettingsStore(directory.Path);
        var saved = await store.LoadAsync(default);
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAsync<OperationCanceledException>(() => store.SaveAsync(saved with { DeviceName = "cancelled" }, cancellation.Token));
        Assert.AreEqual(saved, await store.LoadAsync(default));
        Assert.IsFalse(File.Exists(System.IO.Path.Combine(directory.Path, "settings.json.tmp")));
    }
}
