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
        return Submit<ShareContent>(
            shared_from_this(), std::move(cb), [](auto &s, auto &r, auto t) {
                if (!s.enabled) {
                    t.Complete(Result<ShareContent>::Failure(
                        ServiceError(ErrorCode::Cancelled, "clipboard stopped")));
                    return;
                }
                s.pending_[t.id] = [t](ShareError e) {
                    t.Complete(Result<ShareContent>::Failure(e));
                };
                std::weak_ptr<ClipboardService> weak = s.shared_from_this();
                s.port_.Read(
                    [weak, q = s.queue, t, config = r.config](Result<ShareContent> result) {
                        q->Post([weak, t, config, result] {
                            auto s = weak.lock();
                            if (!s || !s->pending_.erase(t.id) || !t.Active())
                                return;
                            if (result.ok()) {
                                auto error = Validate(result.value(), config);
                                if (error) {
                                    t.Complete(Result<ShareContent>::Failure(*error));
                                    return;
                                }
                            }
                            t.Complete(result);
                        });
                    });
            });
    }
    OperationId WriteContent(ShareContent content, ResultCallback<void> cb) override {
        return Submit<void>(shared_from_this(), std::move(cb),
                            [content = std::move(content)](auto &s, auto &r, auto t) {
                                if (!s.enabled) {
                                    t.Complete(Result<void>::Failure(
                                        ServiceError(ErrorCode::Cancelled, "clipboard stopped")));
                                    return;
                                }
                                if (auto e = Validate(content, r.config)) {
                                    t.Complete(Result<void>::Failure(*e));
                                    return;
                                }
                                s.pending_[t.id] = [t](ShareError e) {
                                    t.Complete(Result<void>::Failure(e));
                                };
                                std::weak_ptr<ClipboardService> weak = s.shared_from_this();
                                s.port_.Write(content, [weak, q = s.queue, t](Result<void> result) {
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
