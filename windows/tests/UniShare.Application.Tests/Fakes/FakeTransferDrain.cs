using UniShare.Application.Abstractions;

namespace UniShare.Application.Tests.Fakes;

internal sealed class FakeTransferDrain : ITransferDrain
{
    private TaskCompletionSource? idle;
    public bool HasActiveTransfers => idle is not null && !idle.Task.IsCompleted;
    public bool NewTransfersAllowed { get; private set; }
    public List<string> Calls { get; } = [];
    public TaskCompletionSource WaitEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public void BeginTransfer() => idle = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public void CompleteTransfer() => idle?.TrySetResult();
    public void BlockNewTransfers() { Calls.Add("block"); NewTransfersAllowed = false; }
    public void AllowNewTransfers() { Calls.Add("allow"); NewTransfersAllowed = true; }
    public Task WaitForIdleAsync(CancellationToken cancellationToken)
    {
        Calls.Add(NewTransfersAllowed ? "wait-unblocked" : "wait-blocked");
        WaitEntered.TrySetResult();
        return idle?.Task.WaitAsync(cancellationToken) ?? Task.CompletedTask;
    }
}
