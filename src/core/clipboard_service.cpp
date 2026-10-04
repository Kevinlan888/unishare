#include "service_support.hpp"
namespace unishare::core {
namespace {
std::optional<ShareError> Validate(const ShareContent &c, const SdkConfig &config) {
    if (c.type != ContentType::Text && c.type != ContentType::Image)
        return ServiceError(ErrorCode::InvalidInput, "clipboard supports text and images");
    if (!ServiceUtf8(c.text) || !ServiceUtf8(c.mime_type))
        return ServiceError(ErrorCode::InvalidInput, "invalid clipboard UTF-8");
    if (c.type == ContentType::Text && !c.image.empty())
        return ServiceError(ErrorCode::InvalidInput, "mixed clipboard content");
    if (c.type == ContentType::Image && (c.mime_type.rfind("image/", 0) != 0 || !c.text.empty()))
        return ServiceError(ErrorCode::InvalidInput, "invalid clipboard image");
    auto count = c.type == ContentType::Text ? c.text.size() : c.image.size();
    auto limit = c.type == ContentType::Text
                     ? std::min<std::uint64_t>(config.max_text_bytes, 8ULL * 1024 * 1024)
                     : std::min<std::uint64_t>(config.max_image_bytes, 100ULL * 1024 * 1024);
    if (count > limit)
        return ServiceError(ErrorCode::ResourceLimit, "clipboard content limit");
    return {};
}
struct ChargedContent {
    std::shared_ptr<ByteBudget::Reservation> charge;
    ShareContent content;
};
std::shared_ptr<ByteBudget::Reservation> Charge(const ShareContent &c, ByteBudget &budget) {
    const auto limit = budget.Limit();
    if (c.text.capacity() >= limit || c.mime_type.capacity() >= limit || c.image.capacity() > limit)
        return {};
    return budget.Reserve(c.text.capacity() + 1 + c.mime_type.capacity() + 1 + c.image.capacity());
}
// The operation owns this handoff until its public callback consumes the result.
struct ReadDelivery {
    std::mutex mutex;
    bool delivered = false;
    std::shared_ptr<ByteBudget::Reservation> charge;
    void Keep(std::shared_ptr<ByteBudget::Reservation> value) {
        std::lock_guard<std::mutex> lock(mutex);
        if (!delivered)
            charge = std::move(value);
    }
    std::shared_ptr<ByteBudget::Reservation> Take() {
        std::lock_guard<std::mutex> lock(mutex);
        delivered = true;
        return std::move(charge);
    }
};
class ClipboardService final : public IClipboardService,
                               public ServiceState,
                               public std::enable_shared_from_this<ClipboardService> {
    IPlatformClipboard &port_;
    std::map<OperationId, std::function<void(ShareError)>> pending_;

  public:
    ClipboardService(const std::shared_ptr<SdkRuntime> &r, IPlatformClipboard &p)
        : ServiceState(r), port_(p) {}
    void Shutdown(ShareError error) {
        enabled = false;
        auto pending = std::move(pending_);
        pending_.clear();
        for (auto &p : pending)
            p.second(error);
    }
    OperationId Start(ResultCallback<void> cb) override {
        return Submit<void>(shared_from_this(), std::move(cb), [](auto &s, auto &, auto t) {
            s.enabled = true;
            t.Complete(Result<void>::Success());
        });
    }
    OperationId Stop(ResultCallback<void> cb) override {
        return Submit<void>(
            shared_from_this(), std::move(cb),
            [](auto &s, auto &, auto t) {
                s.Shutdown(ServiceError(ErrorCode::Cancelled, "clipboard stopped"));
                t.Complete(Result<void>::Success());
            },
            false);
    }
    OperationId ReadShareableContent(ResultCallback<ShareContent> cb) override {
        auto delivery = std::make_shared<ReadDelivery>();
        auto completion = [cb = std::move(cb), delivery](Result<ShareContent> result) mutable {
            auto charge = delivery->Take();
            if (cb)
                cb(std::move(result));
        };
        return Submit<ShareContent>(
            shared_from_this(), std::move(completion), [delivery](auto &s, auto &r, auto t) {
                if (!s.enabled) {
                    t.Complete(Result<ShareContent>::Failure(
                        ServiceError(ErrorCode::Cancelled, "clipboard stopped")));
                    return;
                }
                s.pending_[t.id] = [t](ShareError e) {
                    t.Complete(Result<ShareContent>::Failure(e));
                };
                std::weak_ptr<ClipboardService> weak = s.shared_from_this();
                s.port_.Read([weak, q = s.queue, budget = s.buffers, t, config = r.config,
                              delivery](Result<ShareContent> result) mutable {
                    std::shared_ptr<ByteBudget::Reservation> charge;
                    if (result.ok()) {
                        charge = Charge(result.value(), *budget);
                        if (!charge)
                            result = Result<ShareContent>::Failure(
                                ServiceError(ErrorCode::ResourceLimit,
                                             "clipboard exceeds available buffer budget"));
                    }
                    struct HeldResult {
                        std::shared_ptr<ByteBudget::Reservation> charge;
                        Result<ShareContent> result;
                    };
                    auto held = std::make_shared<HeldResult>(
                        HeldResult{std::move(charge), std::move(result)});
                    q->Post([weak, t, config, delivery, held]() mutable {
                        auto s = weak.lock();
                        if (!s || !s->pending_.erase(t.id) || !t.Active())
                            return;
                        if (held->result.ok()) {
                            if (auto error = Validate(held->result.value(), config)) {
                                t.Complete(Result<ShareContent>::Failure(*error));
                                return;
                            }
                        }
                        delivery->Keep(held->charge);
                        t.Complete(std::move(held->result));
                    });
                });
            });
    }
    OperationId WriteContent(ShareContent content, ResultCallback<void> cb) override {
        auto charge = Charge(content, *buffers);
        if (!charge)
            return Submit<void>(shared_from_this(), std::move(cb), [](auto &, auto &, auto t) {
                t.Complete(Result<void>::Failure(ServiceError(
                    ErrorCode::ResourceLimit, "clipboard exceeds available buffer budget")));
            });
        auto held =
            std::make_shared<ChargedContent>(ChargedContent{std::move(charge), std::move(content)});
        return Submit<void>(shared_from_this(), std::move(cb), [held](auto &s, auto &r, auto t) {
            if (!s.enabled) {
                t.Complete(
                    Result<void>::Failure(ServiceError(ErrorCode::Cancelled, "clipboard stopped")));
                return;
            }
            if (auto error = Validate(held->content, r.config)) {
                t.Complete(Result<void>::Failure(*error));
                return;
            }
            s.pending_[t.id] = [t](ShareError e) { t.Complete(Result<void>::Failure(e)); };
            std::weak_ptr<ClipboardService> weak = s.shared_from_this();
            s.port_.Write(std::move(held->content), [weak, q = s.queue, t, charge = held->charge](
                                                        Result<void> result) mutable {
                // Adapter ownership ends on completion; cancellation alone cannot
                // release a payload still retained by the platform operation.
                charge.reset();
                q->Post([weak, t, result] {
                    auto s = weak.lock();
                    if (s && s->pending_.erase(t.id) && t.Active())
                        t.Complete(result);
                });
            });
        });
    }
};
} // namespace
std::shared_ptr<IClipboardService> MakeClipboardService(const std::shared_ptr<SdkRuntime> &r,
                                                        IPlatformClipboard &p,
                                                        std::function<void(ShareError)> &stop,
                                                        std::function<void()> &start) {
    auto service = std::make_shared<ClipboardService>(r, p);
    std::weak_ptr<ClipboardService> weak = service;
    stop = [weak](auto e) {
        if (auto s = weak.lock())
            s->Shutdown(e);
    };
    start = [weak] {
        if (auto s = weak.lock())
            s->enabled = true;
    };
    return service;
}
} // namespace unishare::core
