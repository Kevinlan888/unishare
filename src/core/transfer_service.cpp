#include "transfer_service.hpp"
#include <algorithm>
#include <atomic>
namespace unishare::core {
namespace {
// Close even when submission is rejected/retired before the engine takes it.
class TextSource final : public IStream {
    std::shared_ptr<IStream> source_;
    std::atomic<bool> closed_{false};
public:
    explicit TextSource(std::shared_ptr<IStream> source) : source_(std::move(source)) {}
    ~TextSource() { try { Close(); } catch (...) {} }
    OperationId Read(std::size_t n, ResultCallback<std::vector<std::uint8_t>> cb) override {
        return source_->Read(n, std::move(cb));
    }
    OperationId Write(std::vector<std::uint8_t> b, ResultCallback<void> cb) override {
        return source_->Write(std::move(b), std::move(cb));
    }
    void Close() override { if (!closed_.exchange(true)) source_->Close(); }
    void AbortAndCleanup() override { Close(); }
};
class TextStream final : public IStream {
    std::shared_ptr<ByteBudget::Reservation> charge_;
    std::string text_;
    std::size_t offset_ = 0;

  public:
    TextStream(std::string text, std::shared_ptr<ByteBudget::Reservation> charge)
        : charge_(std::move(charge)), text_(std::move(text)) {}
    OperationId Read(std::size_t n, ResultCallback<std::vector<std::uint8_t>> cb) override {
        n = (std::min)(n, text_.size() - offset_);
        std::vector<std::uint8_t> b(text_.begin() + offset_, text_.begin() + offset_ + n);
        offset_ += n;
        cb(Result<std::vector<std::uint8_t>>::Success(std::move(b)));
        return 0;
    }
    OperationId Write(std::vector<std::uint8_t>, ResultCallback<void> cb) override {
        cb(Result<void>::Failure(ServiceError(ErrorCode::InvalidInput, "read-only source")));
        return 0;
    }
    void Close() override {
        std::string{}.swap(text_);
        charge_.reset();
        offset_ = 0;
    }
    void AbortAndCleanup() override { Close(); }
};
class TransferService final : public ITransferService,
                              public ServiceState,
                              public std::enable_shared_from_this<TransferService> {
    struct Binding {
        std::shared_ptr<Session> session;
        std::shared_ptr<TransferEngine> engine;
    };
    struct Transfer {
        std::uint64_t connection;
        TransferId local, public_id;
        RequestId request = 0;
        bool pending = false;
        std::optional<OperationRegistry::Ticket<TransferId>> send;
    };
    std::map<std::uint64_t, Binding> engines_;
    std::map<std::string, Transfer> transfers_;
    std::function<std::optional<std::uint64_t>(DeviceId)> connection_;
    std::function<void(IncomingOffer)> offer_;
    std::function<void(TransferProgress)> progress_;
    std::function<void(TransferResult)> result_;
    std::uint64_t next_transfer_ = 1;
    std::optional<std::string> offering_;
    TransferId NewId() {
        TransferId id;
        auto value = next_transfer_++;
        for (unsigned n = 0; n < 8; ++n)
            id.bytes[15 - n] = static_cast<std::uint8_t>(value >> (n * 8));
        id.bytes[0] = 0x75;
        return id;
    }
    auto Find(std::uint64_t c, TransferId id) {
        return std::find_if(transfers_.begin(), transfers_.end(), [&](auto &pair) {
            return pair.second.connection == c && pair.second.local == id;
        });
    }
    void Finish(SdkRuntime &r, const std::string &key, Result<void> result) {
        auto i = transfers_.find(key);
        if (i == transfers_.end())
            return;
        auto transfer = i->second;
        transfers_.erase(i);
        if (transfer.send)
            transfer.send->Complete(result.ok() ? Result<TransferId>::Success(transfer.public_id)
                                                : Result<TransferId>::Failure(result.error()));
        PublicEvent(r, result_, TransferResult{transfer.public_id, result});
    }
    void OnOffer(std::uint64_t c, IncomingOffer event) {
        auto r = runtime.lock();
        if (!r || !engines_.count(c) || !enabled)
            return;
        if (r->next_request == 0 || next_transfer_ == 0) {
            engines_.at(c).engine->Reject(event.transfer_id);
            return;
        }
        Transfer t{c, event.transfer_id, NewId(), r->next_request++, true, {}};
        transfers_.emplace(t.public_id.ToString(), t);
        event.transfer_id = t.public_id;
        event.request_id = t.request;
        if (offer_) {
            // The public queue may be paused past cancellation of the engine's
            // offer. Keep the copied metadata charged until delivery/discard.
            auto charge = r->buffers->Reserve(16384);
            if (!charge) {
                engines_.at(c).engine->Reject(t.local);
                return;
            }
            struct PostedOffer {
                std::shared_ptr<ByteBudget::Reservation> charge;
                IncomingOffer event;
            };
            auto posted = std::make_shared<PostedOffer>(PostedOffer{charge, std::move(event)});
            r->callbacks->Post([handler = offer_, posted] { handler(posted->event); });
        }
    }
    void OnProgress(std::uint64_t c, TransferProgress p) {
        auto r = runtime.lock();
        if (!r)
            return;
        auto i = Find(c, p.id);
        if (i == transfers_.end())
            return;
        p.id = i->second.public_id;
        PublicEvent(*r, progress_, p);
    }
    void OnResult(std::uint64_t c, TransferResult event) {
        auto r = runtime.lock();
        if (!r)
            return;
        auto i = Find(c, event.id);
        if (i == transfers_.end() && offering_) {
            i = transfers_.find(*offering_);
            if (i != transfers_.end())
                i->second.local = event.id;
        }
        if (i != transfers_.end()) {
            auto key = i->first;
            Finish(*r, key, event.result);
        }
    }
    OperationId Send(DeviceId device, ContentSource source, ResultCallback<TransferId> cb,
                     std::optional<ShareError> validation = {}) {
        return Submit<TransferId>(
            shared_from_this(), std::move(cb),
            [device, source = std::move(source), validation](auto &s, auto &r,
                                                             auto ticket) mutable {
                auto c = s.connection_(device);
                if (!s.enabled || !c || !s.engines_.count(*c)) {
                    if (source.content.stream) source.content.stream->Close();
                    ticket.Complete(Result<TransferId>::Failure(ServiceError(
                        ErrorCode::AuthenticationFailed, "no authenticated connection")));
                    return;
                }
                if (!s.next_transfer_) {
                    if (source.content.stream) source.content.stream->Close();
                    ticket.Complete(Result<TransferId>::Failure(
                        ServiceError(ErrorCode::ResourceLimit, "transfer handles exhausted")));
                    return;
                }
                auto id = s.NewId();
                auto key = id.ToString();
                s.transfers_.emplace(key, Transfer{*c, {}, id, 0, false, ticket});
                s.offering_ = key;
                if (validation) {
                    if (source.content.stream) source.content.stream->Close();
                    s.offering_.reset();
                    s.Finish(r, key, Result<void>::Failure(*validation));
                    return;
                }
                PublicEvent(r, s.progress_, TransferProgress{id, 0, source.content.byte_count});
                auto local = s.engines_.at(*c).engine->Offer(std::move(source));
                s.offering_.reset();
                auto i = s.transfers_.find(key);
                if (i != s.transfers_.end())
                    i->second.local = local;
            });
    }
    OperationId Decision(RequestId request, ContentSink destination, bool reject,
                         ResultCallback<void> cb) {
        return Submit<void>(
            shared_from_this(), std::move(cb),
            [request, destination = std::move(destination), reject](auto &s, auto &, auto ticket) {
                auto i = std::find_if(s.transfers_.begin(), s.transfers_.end(), [&](auto &p) {
                    return p.second.pending && p.second.request == request;
                });
                if (!s.enabled || i == s.transfers_.end() || (!reject && !destination)) {
                    ticket.Complete(Result<void>::Failure(
                        ServiceError(ErrorCode::InvalidInput, "invalid incoming decision")));
                    return;
                }
                auto &t = i->second;
                t.pending = false;
                auto engine = s.engines_.at(t.connection).engine;
                if (reject)
                    engine->Reject(t.local);
                else
                    engine->Accept(t.local, destination);
                ticket.Complete(Result<void>::Success());
            });
    }

  public:
    TransferService(const std::shared_ptr<SdkRuntime> &r,
                    std::function<std::optional<std::uint64_t>(DeviceId)> c)
        : ServiceState(r), connection_(std::move(c)) {}
    void Attach(std::uint64_t c, std::shared_ptr<Session> session) {
        auto r = runtime.lock();
        if (!r)
            return;
        std::weak_ptr<TransferService> weak = shared_from_this();
        auto engine =
            TransferEngine::Create(session, *queue, r->admission, r->config, {}, {}, {}, queue);
        engine->SetCoreObservers(
            [weak, c](auto e) {
                if (auto s = weak.lock())
                    s->OnOffer(c, e);
            },
            [weak, c](auto e) {
                if (auto s = weak.lock())
                    s->OnProgress(c, e);
            },
            [weak, c](auto e) {
                if (auto s = weak.lock())
                    s->OnResult(c, e);
            });
        engines_.emplace(c, Binding{std::move(session), std::move(engine)});
    }
    void Detach(std::uint64_t c, ShareError error) {
        auto r = runtime.lock();
        if (!r)
            return;
        auto binding = engines_.find(c);
        if (binding != engines_.end()) {
            binding->second.session->Close(error);
            engines_.erase(binding);
        }
        std::vector<std::string> keys;
        for (auto &p : transfers_)
            if (p.second.connection == c)
                keys.push_back(p.first);
        for (auto &key : keys)
            Finish(*r, key, Result<void>::Failure(error));
    }
    void Shutdown(ShareError e) {
        enabled = false;
        while (!engines_.empty())
            Detach(engines_.begin()->first, e);
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
                s.Shutdown(ServiceError(ErrorCode::Cancelled, "transfers stopped"));
                t.Complete(Result<void>::Success());
            },
            false);
    }
    OperationId SendText(DeviceId d, std::string text, ResultCallback<TransferId> cb) override {
        auto charge =
            text.capacity() < buffers->Limit() ? buffers->Reserve(text.capacity() + 1) : nullptr;
        if (!charge)
            return Submit<TransferId>(
                shared_from_this(), std::move(cb), [](auto &, auto &, auto t) {
                    t.Complete(Result<TransferId>::Failure(ServiceError(
                        ErrorCode::ResourceLimit, "text exceeds available buffer budget")));
                });
        auto size = text.size();
        std::optional<ShareError> error;
        if (!ServiceUtf8(text))
            error = ServiceError(ErrorCode::InvalidInput, "text is not UTF-8");
        return Send(d,
                    {ContentType::Text,
                     {"text", "text/plain;charset=utf-8", size,
                      std::make_shared<TextStream>(std::move(text), std::move(charge))}},
                    std::move(cb), error);
    }
    OperationId SendTextStream(DeviceId d, TransferSource source, ResultCallback<TransferId> cb) override {
        if (source.stream) source.stream = std::make_shared<TextSource>(std::move(source.stream));
        source.display_name = "text";
        source.mime_type = "text/plain;charset=utf-8";
        auto stream = source.stream;
        return Send(d, {ContentType::Text, std::move(source)},
                    [stream = std::move(stream), cb = std::move(cb)](auto result) mutable {
                        // Engine preflight can emit before its local source is destroyed.
                        // Retire staging before exposing any terminal callback to the host.
                        if (stream) { try { stream->Close(); } catch (...) {} }
                        if (cb) cb(std::move(result));
                    });
    }
    OperationId SendImage(DeviceId d, TransferSource source,
                          ResultCallback<TransferId> cb) override {
        return Send(d, {ContentType::Image, std::move(source)}, std::move(cb));
    }
    OperationId SendFile(DeviceId d, TransferSource source,
                         ResultCallback<TransferId> cb) override {
        return Send(d, {ContentType::File, std::move(source)}, std::move(cb));
    }
    OperationId Accept(RequestId r, ContentSink sink, ResultCallback<void> cb) override {
        return Decision(r, std::move(sink), false, std::move(cb));
    }
    OperationId Reject(RequestId r, ResultCallback<void> cb) override {
        return Decision(r, {}, true, std::move(cb));
    }
    OperationId Cancel(TransferId id, ResultCallback<void> cb) override {
        return Submit<void>(shared_from_this(), std::move(cb), [id](auto &s, auto &, auto t) {
            auto i = s.transfers_.find(id.ToString());
            if (i == s.transfers_.end()) {
                t.Complete(Result<void>::Failure(
                    ServiceError(ErrorCode::InvalidInput, "unknown transfer")));
                return;
            }
            s.engines_.at(i->second.connection).engine->Cancel(i->second.local);
            t.Complete(Result<void>::Success());
        });
    }
    void SetIncomingOfferHandler(std::function<void(IncomingOffer)> h) override {
        queue->Invoke([&] { offer_ = std::move(h); });
    }
    void SetProgressHandler(std::function<void(TransferProgress)> h) override {
        queue->Invoke([&] { progress_ = std::move(h); });
    }
    void SetResultHandler(std::function<void(TransferResult)> h) override {
        queue->Invoke([&] { result_ = std::move(h); });
    }
};
} // namespace
TransferWiring MakeTransferService(const std::shared_ptr<SdkRuntime> &r,
                                   std::function<std::optional<std::uint64_t>(DeviceId)> c) {
    auto s = std::make_shared<TransferService>(r, std::move(c));
    std::weak_ptr<TransferService> w = s;
    return {s,
            [w](auto id, auto session) {
                if (auto s = w.lock())
                    s->Attach(id, std::move(session));
            },
            [w](auto id, auto e) {
                if (auto s = w.lock())
                    s->Detach(id, e);
            },
            [w](auto e) {
                if (auto s = w.lock())
                    s->Shutdown(e);
            },
            [w] {
                if (auto s = w.lock())
                    s->enabled = true;
            }};
}
} // namespace unishare::core
