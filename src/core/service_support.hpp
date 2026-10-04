#pragma once
#include "share_sdk.hpp"
#include "exception.hpp"
#include <map>
namespace unishare::core {
inline ShareError ServiceError(ErrorCode c, const char *s) { return {c, s, {}}; }
inline bool ServiceUtf8(std::string_view value) {
    unsigned remaining = 0;
    std::uint32_t code = 0, minimum = 0;
    for (unsigned char c : value) {
        if (remaining) {
            if ((c & 0xc0) != 0x80)
                return false;
            code = (code << 6) | (c & 63);
            if (!--remaining &&
                (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)))
                return false;
        } else if (c < 128) {
        } else if (c >= 0xc2 && c <= 0xdf) {
            remaining = 1;
            code = c & 31;
            minimum = 128;
        } else if (c >= 0xe0 && c <= 0xef) {
            remaining = 2;
            code = c & 15;
            minimum = 2048;
        } else if (c >= 0xf0 && c <= 0xf4) {
            remaining = 3;
            code = c & 7;
            minimum = 65536;
        } else
            return false;
    }
    return remaining == 0;
}
struct ServiceState {
    std::weak_ptr<SdkRuntime> runtime;
    std::shared_ptr<SerialScheduler> queue;
    std::shared_ptr<OperationRegistry> operations;
    std::shared_ptr<ByteBudget> buffers;
    bool enabled = true;
    explicit ServiceState(const std::shared_ptr<SdkRuntime> &r)
        : runtime(r), queue(r->scheduler), operations(r->operations), buffers(r->buffers) {}
};
template <class T, class Owner, class Work>
OperationId Submit(std::shared_ptr<Owner> owner, ResultCallback<T> cb, Work work,
                   bool cancellable = true) {
    auto ticket = owner->operations->template Begin<T>(std::move(cb), cancellable);
    owner->queue->Post([owner, ticket, work = std::move(work)]() mutable {
        if (!ticket.Active())
            return;
        auto runtime = owner->runtime.lock();
        if (!runtime || !runtime->alive) {
            ticket.Complete(
                Result<T>::Failure(ServiceError(ErrorCode::Cancelled, "SDK destroyed")));
            return;
        }
        try { work(*owner, *runtime, ticket); }
        catch(...) { ticket.Complete(Result<T>::Failure(CurrentException())); }
    });
    return ticket.id;
}
template <class Event>
void PublicEvent(SdkRuntime &r, const std::function<void(Event)> &handler, Event event) {
    if (handler)
        r.callbacks->Post([handler, event = std::move(event)] { handler(event); });
}
std::shared_ptr<IClipboardService> MakeClipboardService(const std::shared_ptr<SdkRuntime> &,
                                                        IPlatformClipboard &,
                                                        std::function<void(ShareError)> &stop,
                                                        std::function<void()> &start);
} // namespace unishare::core
