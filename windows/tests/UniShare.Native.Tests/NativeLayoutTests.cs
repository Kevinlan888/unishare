using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Native.Interop;

namespace UniShare.Native.Tests;

[TestClass]
public sealed class NativeLayoutTests
{
    // Reflection lets the first RED exercise the absent ABI declarations at runtime.
    [TestMethod]
    [DataRow("NativeError", 16)]
    [DataRow("NativeSpan", 16)]
    [DataRow("NativeConfig", 80)]
    [DataRow("NativeDevice", 72)]
    [DataRow("NativeResult", 96)]
    [DataRow("NativeEvent", 192)]
    [DataRow("NativeSource", 48)]
    public void FrozenX64Layout(string name, int expected)
    {
        Assert.AreEqual(8, IntPtr.Size);
        var assembly = Assembly.Load("UniShare.Native");
        var type = assembly.GetType($"UniShare.Native.Interop.{name}");
        Assert.IsNotNull(type, $"Missing frozen ABI declaration: {name}");
        Assert.AreEqual(expected, Marshal.SizeOf(type));
    }

    [TestMethod]
    public void FrozenNestedFieldOffsetsMatchCHeader()
    {
        Assert.AreEqual(8L, Marshal.OffsetOf<NativeConfig>("DeviceName").ToInt64());
        Assert.AreEqual(32L, Marshal.OffsetOf<NativeConfig>("MaxBufferBytes").ToInt64());
        Assert.AreEqual(48L, Marshal.OffsetOf<NativeDevice>("LastSeenUnixMs").ToInt64());
        Assert.AreEqual(56L, Marshal.OffsetOf<NativeDevice>("Addresses").ToInt64());
        Assert.AreEqual(40L, Marshal.OffsetOf<NativeResult>("Content").ToInt64());
        Assert.AreEqual(48L, Marshal.OffsetOf<NativeEvent>("Device").ToInt64());
        Assert.AreEqual(144L, Marshal.OffsetOf<NativeEvent>("DisplayName").ToInt64());
        Assert.AreEqual(176L, Marshal.OffsetOf<NativeEvent>("ByteCount").ToInt64());
    }
}
