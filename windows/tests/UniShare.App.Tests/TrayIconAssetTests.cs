using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Services;

namespace UniShare.App.Tests;

[TestClass]
public sealed class TrayIconAssetTests
{
    private static string IconPath => Path.Combine(ResourceCoverageTests.AppPath, "Assets", "TrayIcon.ico");

    [TestMethod]
    public void TrayIconContainsDpiAppropriateImages()
    {
        Assert.IsTrue(File.Exists(IconPath), "The tray still has no UniShare icon resource.");
        using var stream = File.OpenRead(IconPath);
        using var reader = new BinaryReader(stream);
        Assert.AreEqual((ushort)0, reader.ReadUInt16());
        Assert.AreEqual((ushort)1, reader.ReadUInt16());
        var count = reader.ReadUInt16();
        var sizes = new List<int>();
        for (var index = 0; index < count; index++)
        {
            var width = reader.ReadByte();
            var height = reader.ReadByte();
            sizes.Add(width == 0 ? 256 : width);
            Assert.AreEqual(width, height);
            stream.Position += 14;
        }

        CollectionAssert.AreEqual(new[] { 16, 20, 24, 32, 48, 64 }, sizes);
    }

    [TestMethod]
    public void TrayIconHandleLoadsAndReleasesTheWindowsIcon()
    {
        using var handle = new TrayIconHandle(IconPath);

        Assert.AreNotEqual(nint.Zero, handle.Value);
        handle.Dispose();
        Assert.AreEqual(nint.Zero, handle.Value);
    }
}
