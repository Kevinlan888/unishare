using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Native.Interop;

namespace UniShare.Native.Tests;

[TestClass]
public sealed class NativeMapperTests
{
    private static object? Map(string method, params object[] arguments)
    {
        var type = Assembly.Load("UniShare.Native").GetType("UniShare.Native.Interop.NativeMapper");
        Assert.IsNotNull(type, "Missing copy-before-return mapper");
        var target = type.GetMethod(method, BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.Public);
        Assert.IsNotNull(target);
        try { return target.Invoke(null, arguments); }
        catch (TargetInvocationException exception) when (exception.InnerException is not null)
        { System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(exception.InnerException).Throw(); throw; }
    }

    [TestMethod]
    public void UuidUsesNetworkByteOrder()
    {
        byte[] bytes = [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff];
        var id = (Guid)Map("ReadUuid", bytes)!;
        Assert.AreEqual(Guid.Parse("00112233-4455-6677-8899-aabbccddeeff"), id);
        CollectionAssert.AreEqual(bytes, (byte[])Map("WriteUuid", id)!);
        Assert.AreEqual(id, NativeMapper.ToGuid(NativeMapper.ToNativeUuid(id)));
    }

    [TestMethod]
    public void BorrowedTextAndAddressesAreCopiedBeforeReturn()
    {
        var bytes = Encoding.UTF8.GetBytes("设备\0hello");
        var data = Marshal.AllocHGlobal(bytes.Length);
        var spans = Marshal.AllocHGlobal(16);
        string text;
        string[] addresses;
        try
        {
            Marshal.Copy(bytes, 0, data, bytes.Length);
            Marshal.WriteIntPtr(spans, data);
            Marshal.WriteInt64(spans, 8, bytes.Length);
            text = (string)Map("CopyText", data, (ulong)bytes.Length)!;
            addresses = (string[])Map("CopyAddresses", spans, 1UL)!;
            Marshal.Copy(new byte[bytes.Length], 0, data, bytes.Length);
        }
        finally { Marshal.FreeHGlobal(data); Marshal.FreeHGlobal(spans); }
        Assert.AreEqual("设备\0hello", text);
        CollectionAssert.AreEqual(new[] { "设备\0hello" }, addresses);
    }

    [TestMethod]
    public void InvalidUtf8IsRejected()
    {
        var data = Marshal.AllocHGlobal(2);
        try
        {
            Marshal.Copy(new byte[] { 0xc3, 0x28 }, 0, data, 2);
            Assert.ThrowsExactly<DecoderFallbackException>(() => Map("CopyText", data, 2UL));
        }
        finally { Marshal.FreeHGlobal(data); }
    }

    [TestMethod]
    public void ExcessiveCountsAreRejectedBeforeDereference()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => Map("CopyAddresses", IntPtr.Zero, ulong.MaxValue));
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => Map("CopyText", IntPtr.Zero, ulong.MaxValue));
        Assert.ThrowsExactly<ArgumentException>(() => Map("CopyText", IntPtr.Zero, 1UL));
        Assert.AreEqual(string.Empty, Map("CopyText", IntPtr.Zero, 0UL));
    }

    [TestMethod]
    [DataRow(1u, ShareErrorKind.PermissionDenied)]
    [DataRow(2u, ShareErrorKind.DiscoveryFailed)]
    [DataRow(3u, ShareErrorKind.VersionMismatch)]
    [DataRow(4u, ShareErrorKind.PairingRejected)]
    [DataRow(5u, ShareErrorKind.AuthenticationFailed)]
    [DataRow(6u, ShareErrorKind.IdentityChanged)]
    [DataRow(7u, ShareErrorKind.TransferRejected)]
    [DataRow(8u, ShareErrorKind.Cancelled)]
    [DataRow(9u, ShareErrorKind.Timeout)]
    [DataRow(10u, ShareErrorKind.NetworkLost)]
    [DataRow(11u, ShareErrorKind.StorageFailed)]
    [DataRow(12u, ShareErrorKind.IntegrityFailed)]
    [DataRow(13u, ShareErrorKind.InvalidInput)]
    [DataRow(14u, ShareErrorKind.ResourceLimit)]
    [DataRow(999u, ShareErrorKind.Unknown)]
    public void ErrorCodesMapToApplicationFailures(uint code, ShareErrorKind expected)
    {
        var failure = (ShareFailure)Map("MapError", code, true, -42L)!;
        Assert.AreEqual(expected, failure.Kind);
        Assert.AreEqual(-42L, failure.PlatformError);
        Assert.IsFalse(string.IsNullOrWhiteSpace(failure.Message));
    }

    [TestMethod]
    public void SuccessHasNoFailureAndAbsentPlatformErrorStaysAbsent()
    {
        Assert.IsNull(Map("MapError", 0u, false, 42L));
        Assert.IsNull(((ShareFailure)Map("MapError", 13u, false, 42L)!).PlatformError);
    }

    [TestMethod]
    public void OnlyAbiVersionOneIsAccepted()
    {
        Map("RequireAbiVersion", 1u);
        Assert.ThrowsExactly<NotSupportedException>(() => Map("RequireAbiVersion", 0u));
        Assert.ThrowsExactly<NotSupportedException>(() => Map("RequireAbiVersion", 2u));
    }

    [TestMethod]
    public void DeviceCopiesNestedBorrowedFields()
    {
        var data = Marshal.AllocHGlobal(9);
        var spans = Marshal.AllocHGlobal(16);
        DeviceSnapshot snapshot;
        try
        {
            Marshal.Copy(Encoding.UTF8.GetBytes("127.0.0.1"), 0, data, 9);
            Marshal.StructureToPtr(new NativeSpan { Data = data, Length = 9 }, spans, false);
            snapshot = NativeMapper.Device(new NativeDevice
            {
                Id = NativeMapper.ToNativeUuid(Guid.Parse("00112233-4455-6677-8899-aabbccddeeff")),
                Name = new() { Data = data, Length = 9 }, Platform = 2,
                ProtocolMajor = 1, ProtocolMinor = 2, Port = 54321, Paired = 1,
                LastSeenUnixMs = 1000, Addresses = spans, AddressCount = 1
            });
            Marshal.Copy(new byte[9], 0, data, 9);
        }
        finally { Marshal.FreeHGlobal(data); Marshal.FreeHGlobal(spans); }
        Assert.AreEqual(Guid.Parse("00112233-4455-6677-8899-aabbccddeeff"), snapshot.Id.Value);
        Assert.AreEqual("127.0.0.1", snapshot.Name);
        CollectionAssert.AreEqual(new[] { "127.0.0.1" }, snapshot.Addresses.ToArray());
        Assert.AreEqual(DevicePlatform.Ios, snapshot.Platform);
        Assert.AreEqual((ushort)1, snapshot.ProtocolMajor);
        Assert.AreEqual((ushort)2, snapshot.ProtocolMinor);
        Assert.AreEqual((ushort)54321, snapshot.Port);
        Assert.IsTrue(snapshot.IsTrusted);
        Assert.AreEqual(DateTimeOffset.FromUnixTimeMilliseconds(1000), snapshot.LastSeen);
    }

    [TestMethod]
    public void ClipboardImageCopiesBytesBeforeNativeStorageIsReleased()
    {
        var data = Marshal.AllocHGlobal(4);
        ShareContent content;
        try
        {
            Marshal.Copy(new byte[] { 1, 2, 3, 4 }, 0, data, 4);
            content = NativeMapper.Content(new NativeContent { Type = 2, Image = new() { Data = data, Length = 4 } });
            Marshal.Copy(new byte[4], 0, data, 4);
        }
        finally { Marshal.FreeHGlobal(data); }
        Assert.AreEqual(ContentKind.Image, content.Kind);
        Assert.IsNull(content.Text);
        CollectionAssert.AreEqual(new byte[] { 1, 2, 3, 4 }, content.ImageBytes.ToArray());
    }

    [TestMethod]
    public void ProgressAndTerminalUseFrozenNativeEventNumbers()
    {
        var transfer = Guid.Parse("00112233-4455-6677-8899-aabbccddeeff");
        var native = new NativeEvent { Kind = 11, Transfer = NativeMapper.ToNativeUuid(transfer), Transferred = 5, ByteCount = 20 };
        var progress = (ShareSdkEvent.ProgressReceived)NativeMapper.Event(native);
        Assert.AreEqual(new TransferId(transfer), progress.Progress.TransferId);
        Assert.AreEqual(5UL, progress.Progress.TransferredBytes);
        Assert.AreEqual(20UL, progress.Progress.TotalBytes);
        native.Kind = 12;
        native.Error.Code = 8;
        var completed = (ShareSdkEvent.TransferCompleted)NativeMapper.Event(native);
        Assert.AreEqual(new TransferId(transfer), completed.Terminal.TransferId);
        Assert.AreEqual(ShareErrorKind.Cancelled, completed.Terminal.Failure!.Kind);
        Assert.IsNull(NativeMapper.Terminal(default).TransferId);
        Assert.IsNull(NativeMapper.Terminal(default).Failure);
    }
}
