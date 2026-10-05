using UniShare.Application.Models;

namespace UniShare.Application.Abstractions;

public interface ITransferDrain
{
    bool HasActiveTransfers { get; }
    void BlockNewTransfers();
    void AllowNewTransfers();
    Task WaitForIdleAsync(CancellationToken cancellationToken);
}

public interface ITransferFiles
{
    Task<PreparedReceive> CreateReceiveAsync(IncomingOffer offer, string receiveRoot, CancellationToken cancellationToken);
    Task<string> PublishAsync(PreparedReceive receive, CancellationToken cancellationToken);
    Task AbortAsync(PreparedReceive receive);
    Task<TransientDestination> CreateTextDestinationAsync(CancellationToken cancellationToken);
    Task<TransientSource> StageClipboardImageAsync(ReadOnlyMemory<byte> bytes, string mimeType, CancellationToken cancellationToken);
}
