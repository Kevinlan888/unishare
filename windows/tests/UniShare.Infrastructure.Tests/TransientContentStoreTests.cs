using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;

namespace UniShare.Infrastructure.Tests;

[TestClass]
public sealed class TransientContentStoreTests
{
    private static ITransferFiles Store(string root)
    {
        var type = typeof(Storage.JsonSettingsStore).Assembly.GetType("UniShare.Infrastructure.Files.TransientContentStore");
        Assert.IsNotNull(type, "Transient content store is missing.");
        return (ITransferFiles)Activator.CreateInstance(type, root)!;
    }
    [TestMethod]
    public async Task TextDestinationIsEmptyUniqueAndDeletedIdempotently()
    {
        var root = Directory.CreateTempSubdirectory("unishare-transient-tests-").FullName;
        try
        {
            var store = Store(root); var first = await store.CreateTextDestinationAsync(default); var second = await store.CreateTextDestinationAsync(default);
            Assert.AreNotEqual(first.Path, second.Path); Assert.AreEqual(0L, new FileInfo(first.Path).Length);
            await using (var stream = new FileStream(first.Path, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete))
                await stream.WriteAsync(System.Text.Encoding.UTF8.GetBytes("private text"));
            await first.DisposeAsync(); await first.DisposeAsync(); await second.DisposeAsync();
            Assert.AreEqual(0, Directory.GetFiles(root).Length);
        }
        finally { Directory.Delete(root, true); }
    }
    [TestMethod]
    public async Task ClipboardImageCopiesBytesAndOwnsOnlyStagedFile()
    {
        var root = Directory.CreateTempSubdirectory("unishare-transient-tests-").FullName;
        try
        {
            var store = Store(root); await using var source = await store.StageClipboardImageAsync(new byte[] { 1, 2, 3 }, "image/png", default);
            CollectionAssert.AreEqual(new byte[] { 1, 2, 3 }, await File.ReadAllBytesAsync(source.Path));
            Assert.AreEqual(3UL, source.Source.ByteCount); Assert.AreEqual("image/png", source.Source.MimeType);
            await source.DisposeAsync(); await source.DisposeAsync(); Assert.IsFalse(File.Exists(source.Path));
        }
        finally { Directory.Delete(root, true); }
    }
    [TestMethod]
    public async Task CancellationAndEmptyImageLeaveNoTransientFiles()
    {
        var root = Directory.CreateTempSubdirectory("unishare-transient-tests-").FullName;
        try
        {
            var store = Store(root);
            await Assert.ThrowsAsync<OperationCanceledException>(() => store.CreateTextDestinationAsync(new CancellationToken(true)));
            await Assert.ThrowsAsync<OperationCanceledException>(() => store.StageClipboardImageAsync(new byte[] { 1 }, "image/png", new CancellationToken(true)));
            await Assert.ThrowsAsync<ArgumentException>(() => store.StageClipboardImageAsync(ReadOnlyMemory<byte>.Empty, "image/png", default));
            Assert.AreEqual(0, Directory.GetFiles(root).Length);
        }
        finally { Directory.Delete(root, true); }
    }
}
