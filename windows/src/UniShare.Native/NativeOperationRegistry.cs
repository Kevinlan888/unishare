using UniShare.Application.Models;

namespace UniShare.Native;

internal sealed record NativeCompletion(OperationId Operation, TransferTerminal Terminal, ShareContent? Content = null);

// Access is serialized by NativeShareSdkClient's submission gate. Removing before
// TrySetResult makes duplicate/late terminal callbacks inert and releases task roots.
internal sealed class NativeOperationRegistry
{
    private readonly Dictionary<OperationId, TaskCompletionSource<NativeCompletion>> pending = [];

    internal Task<NativeCompletion> Register(OperationId operation)
    {
        if (operation.Value == 0)
            throw new ArgumentOutOfRangeException(nameof(operation));
        var completion = new TaskCompletionSource<NativeCompletion>(TaskCreationOptions.RunContinuationsAsynchronously);
        pending.Add(operation, completion);
        return completion.Task;
    }

    internal bool Complete(NativeCompletion value)
    {
        if (!pending.Remove(value.Operation, out var completion))
            return false;
        return completion.TrySetResult(value);
    }

    internal void CancelAll()
    {
        foreach (var pair in pending)
            pair.Value.TrySetResult(new(pair.Key, new(null, new(ShareErrorKind.Cancelled, "UniShare operation cancelled."))));
        pending.Clear();
    }
}
