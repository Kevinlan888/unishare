using UniShare.Application.Models;

namespace UniShare.Native;

// Both submissions and copied callback envelopes use the same client gate.
internal sealed class OutgoingTransferRegistry
{
    private readonly LinkedList<OperationId> pending = [];
    private readonly HashSet<TransferId> known = [];

    internal void Register(OperationId operation)
    {
        if (operation.Value == 0) throw new ArgumentOutOfRangeException(nameof(operation));
        pending.AddLast(operation);
    }

    internal void ObserveIncoming(TransferId transfer) => known.Add(transfer);

    internal ShareSdkEvent.OutgoingTransferRegistered? ObserveProgress(TransferId transfer)
    {
        if (!known.Add(transfer) || pending.First is not { } first) return null;
        pending.RemoveFirst();
        return new(first.Value, transfer);
    }

    internal void Complete(OperationId operation, TransferId? transfer)
    {
        pending.Remove(operation);
        // Retain terminal IDs until Stop so delayed progress cannot steal the next send.
        if (transfer is { } id) known.Add(id);
    }

    internal void Clear() { pending.Clear(); known.Clear(); }
}
