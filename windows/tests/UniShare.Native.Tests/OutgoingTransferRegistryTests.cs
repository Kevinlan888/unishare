using System.Reflection;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;

namespace UniShare.Native.Tests;

[TestClass]
public sealed class OutgoingTransferRegistryTests
{
    private static object Create()
    {
        var type = Assembly.Load("UniShare.Native").GetType("UniShare.Native.OutgoingTransferRegistry");
        Assert.IsNotNull(type, "Missing outgoing FIFO registry");
        return Activator.CreateInstance(type, nonPublic: true)!;
    }
    private static object? Invoke(object registry, string method, params object?[] arguments)
        => registry.GetType().GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(registry, arguments);
    private static TransferId Id(int value) => new(new Guid(value, 0, 0, new byte[8]));

    [TestMethod]
    public void KnownIncomingProgressDoesNotConsumeFifoAndRepeatedProgressDoesNotRebind()
    {
        var registry = Create();
        Invoke(registry, "Register", new OperationId(20));
        Invoke(registry, "Register", new OperationId(21));
        Invoke(registry, "ObserveIncoming", Id(1));
        Assert.IsNull(Invoke(registry, "ObserveProgress", Id(1)));
        Assert.AreEqual(new ShareSdkEvent.OutgoingTransferRegistered(new(20), Id(2)), Invoke(registry, "ObserveProgress", Id(2)));
        Assert.IsNull(Invoke(registry, "ObserveProgress", Id(2)));
        Assert.AreEqual(new ShareSdkEvent.OutgoingTransferRegistered(new(21), Id(3)), Invoke(registry, "ObserveProgress", Id(3)));
        Assert.IsNull(Invoke(registry, "ObserveProgress", Id(4)));
    }

    [TestMethod]
    public void TerminalWithoutProgressRemovesOnlyItsPendingOperation()
    {
        var registry = Create();
        Invoke(registry, "Register", new OperationId(20));
        Invoke(registry, "Register", new OperationId(21));
        Invoke(registry, "Complete", new OperationId(20), (TransferId?)Id(2));
        Assert.IsNull(Invoke(registry, "ObserveProgress", Id(2)));
        Assert.AreEqual(new ShareSdkEvent.OutgoingTransferRegistered(new(21), Id(3)), Invoke(registry, "ObserveProgress", Id(3)));
    }

    [TestMethod]
    public void ClearRemovesBothQueuedOperationsAndKnownTransferIds()
    {
        var registry = Create();
        Invoke(registry, "Register", new OperationId(20));
        Invoke(registry, "ObserveIncoming", Id(1));
        Invoke(registry, "Clear");
        Assert.IsNull(Invoke(registry, "ObserveProgress", Id(2)));
        Invoke(registry, "Register", new OperationId(21));
        Assert.AreEqual(new ShareSdkEvent.OutgoingTransferRegistered(new(21), Id(1)), Invoke(registry, "ObserveProgress", Id(1)));
    }
}
