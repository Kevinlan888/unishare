#pragma once
#include "platform.hpp"

namespace unishare {
struct TransferSource {
    std::string display_name, mime_type;
    std::uint64_t byte_count = 0;
    std::shared_ptr<IStream> stream;
};
// Read returns at most the requested bytes; empty is EOF. Write consumes all
// bytes before success. Callbacks complete once, on any thread. Close stops I/O
// without deleting source data; sink Close seals successful staging. All
// fallible persistence belongs in Write. AbortAndCleanup invalidates/removes
// staging even while an operation is pending; buffers live through completion.
struct ContentSource { ContentType type=ContentType::File; TransferSource content; };
using ContentSink = std::shared_ptr<IStream>;
struct IncomingOffer {
    RequestId request_id;
    TransferId transfer_id;
    DeviceId device_id;
    ContentType content_type;
    std::string display_name, mime_type;
    std::uint64_t byte_count = 0;
};
struct TransferProgress {
    TransferId id;
    std::uint64_t transferred = 0, total = 0;
};
struct TransferResult {
    TransferId id;
    Result<void> result;
};

// Operation callbacks are one-shot; installed event handlers receive events
// across multiple transfers. Both use the SDK configured/default executor.
class ITransferService {
public:
    virtual ~ITransferService() = default;
    virtual OperationId Start(ResultCallback<void>) = 0;
    // Cancels unfinished service operations/transfers with Cancelled exactly
    // once; Stop itself follows IShareSdk::Stop's completion/queue semantics.
    virtual OperationId Stop(ResultCallback<void>) = 0;

    // These callbacks are TERMINAL transfer results, not initiation acknowledgments.
    // Success returns the transfer ID only after the peer confirms Complete.
    // Rejection, cancellation, timeout and transport/integrity errors complete
    // the callback with failure exactly once. The returned OperationId identifies
    // the pending send operation; progress/offer events expose its TransferId.
    // Initial progress (0 bytes) exposes the ID before waiting for consent,
    // so Cancel can be used while the peer is deciding. SendText retains its
    // value in the shared SDK buffer budget; insufficient available capacity
    // returns ResourceLimit even below the configured text size limit.
    virtual OperationId SendText(DeviceId, std::string, ResultCallback<TransferId>) = 0;
    virtual OperationId SendImage(DeviceId, TransferSource, ResultCallback<TransferId>) = 0;
    virtual OperationId SendFile(DeviceId, TransferSource, ResultCallback<TransferId>) = 0;

    // Accept's callback completes the acceptance decision, not receipt of all
    // bytes. The incoming transfer's terminal outcome is delivered by the result
    // event. Destination ownership is retained until completion or cleanup.
    virtual OperationId Accept(RequestId, std::shared_ptr<IStream> destination,
                               ResultCallback<void>) = 0;
    // Reject and Cancel callbacks acknowledge completion of their respective
    // commands; the affected transfer independently has one terminal outcome.
    virtual OperationId Reject(RequestId, ResultCallback<void>) = 0;
    virtual OperationId Cancel(TransferId, ResultCallback<void>) = 0;

    // Each offer is emitted once; progress may recur until terminal completion.
    virtual void SetIncomingOfferHandler(std::function<void(IncomingOffer)>) = 0;
    virtual void SetProgressHandler(std::function<void(TransferProgress)>) = 0;
    // One event per terminal incoming/outgoing transfer. For outgoing sends this
    // observes the same outcome as the one-shot send callback; it is not a second
    // completion of the operation. No progress follows a terminal result.
    virtual void SetResultHandler(std::function<void(TransferResult)>) = 0;
};
}
