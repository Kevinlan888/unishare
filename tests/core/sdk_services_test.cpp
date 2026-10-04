#include "core/share_sdk.hpp"
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <thread>
using namespace unishare;
using namespace unishare::core;
#define CHECK(...)                                                                                 \
    do {                                                                                           \
        if (!(__VA_ARGS__)) {                                                                      \
            std::cerr << "check failed " << __LINE__ << ": " << #__VA_ARGS__ << '\n';              \
            std::exit(1);                                                                          \
        }                                                                                          \
    } while (false)
struct Queue : IExecutor {
    std::mutex mutex;
    std::vector<std::function<void()>> tasks;
    void Post(std::function<void()> f) override {
        std::lock_guard<std::mutex> l(mutex);
        tasks.push_back(std::move(f));
    }
    void Drain() {
        for (;;) {
            std::vector<std::function<void()>> next;
            {
                std::lock_guard<std::mutex> l(mutex);
                next.swap(tasks);
            }
            if (next.empty())
                return;
            for (auto &f : next)
                f();
        }
    }
};
struct Store : IIdentityStore {
    std::optional<IdentityMaterial> identity;
    std::vector<TrustedDevice> trusted;
    Result<std::optional<IdentityMaterial>> LoadIdentity() override {
        return Result<std::optional<IdentityMaterial>>::Success(identity);
    }
    Result<void> SaveIdentity(const IdentityMaterial &i) override {
        identity = i;
        return Result<void>::Success();
    }
    Result<std::vector<TrustedDevice>> LoadTrust() override {
        return Result<std::vector<TrustedDevice>>::Success(trusted);
    }
    Result<void> SaveTrust(const TrustedDevice &t) override {
        for (auto &x : trusted)
            if (x.id == t.id)
                return x.public_key_fingerprint == t.public_key_fingerprint
                           ? Result<void>::Success()
                           : Result<void>::Failure({ErrorCode::IdentityChanged, "changed", {}});
        trusted.push_back(t);
        return Result<void>::Success();
    }
    Result<void> RemoveTrust(DeviceId id) override {
        trusted.erase(
            std::remove_if(trusted.begin(), trusted.end(), [&](auto &t) { return t.id == id; }),
            trusted.end());
        return Result<void>::Success();
    }
};
struct Dns : IDnsSd {
    ServiceRecord publication;
    std::function<void(Result<ServiceRecord>)> found;
    OperationId Publish(ServiceRecord r, ResultCallback<void> cb) override {
        publication = std::move(r);
        cb(Result<void>::Success());
        return 1;
    }
    OperationId Browse(std::string, std::function<void(Result<ServiceRecord>)> cb) override {
        found = std::move(cb);
        return 1;
    }
    OperationId BrowseEvents(std::string s, std::function<void(Result<ServiceRecord>)> cb,
                             std::function<void(ServiceInstanceId)>) override {
        return Browse(s, std::move(cb));
    }
    void Stop() override {}
};
struct Sockets : ISocketFactory {
    std::function<void(std::string, ResultCallback<std::shared_ptr<ISocket>>)> connect;
    std::function<void(Result<std::shared_ptr<ISocket>>)> incoming;
    ResultCallback<std::shared_ptr<ISocket>> pending;
    OperationId Connect(std::string address, std::uint16_t,
                        ResultCallback<std::shared_ptr<ISocket>> cb) override {
        if (connect)
            connect(address, std::move(cb));
        else
            pending = std::move(cb);
        return 1;
    }
    OperationId Listen(std::uint16_t,
                       std::function<void(Result<std::shared_ptr<ISocket>>)>) override {
        return 0;
    }
    OperationId ListenBound(std::uint16_t, ResultCallback<std::uint16_t> cb,
                            std::function<void(Result<std::shared_ptr<ISocket>>)> f) override {
        incoming = std::move(f);
        cb(Result<std::uint16_t>::Success(43210));
        return 1;
    }
    void Stop() override {}
};
struct PlatformFake : IPlatform, IStreamFactory, IPlatformClipboard {
    Store store;
    Dns dns;
    ::Sockets sockets;
    std::function<void()> network;
    int reads = 0, writes = 0;
    ResultCallback<ShareContent> read;
    ResultCallback<void> write;
    ShareContent written;
    IDnsSd &DnsSd() override { return dns; }
    ISocketFactory &Sockets() override { return sockets; }
    IIdentityStore &IdentityStore() override { return store; }
    IStreamFactory &Streams() override { return *this; }
    IPlatformClipboard &Clipboard() override { return *this; }
    Platform PlatformKind() const override { return Platform::Windows; }
    std::shared_ptr<IExecutor> DefaultExecutor() override { return {}; }
    void SetNetworkChangeHandler(std::function<void()> f) override { network = std::move(f); }
    Result<std::shared_ptr<unishare::IStream>> OpenSource(std::string) override {
        return Result<std::shared_ptr<unishare::IStream>>::Failure(
            {ErrorCode::StorageFailed, "unused", {}});
    }
    Result<std::shared_ptr<unishare::IStream>> OpenDestination(std::string s) override {
        return OpenSource(s);
    }
    OperationId Read(ResultCallback<ShareContent> cb) override {
        ++reads;
        read = std::move(cb);
        return 1;
    }
    OperationId Write(ShareContent c, ResultCallback<void> cb) override {
        ++writes;
        written = std::move(c);
        write = std::move(cb);
        return 1;
    }
};
void Barrier(ShareSdk &sdk) {
    sdk.Runtime()->scheduler->Invoke([] {});
}
void Pump(ShareSdk &sdk, const std::shared_ptr<Queue> &q) {
    for (int n = 0; n < 10; ++n) {
        Barrier(sdk);
        q->Drain();
    }
}
void Initialize(ShareSdk &sdk, const std::shared_ptr<Queue> &q, std::uint32_t limit = 8) {
    SdkConfig c;
    c.device_name = "test";
    c.callback_executor = q;
    c.max_connections = limit;
    bool done = false;
    sdk.Initialize(c, [&](auto r) {
        CHECK(r.ok());
        done = true;
    });
    Pump(sdk, q);
    CHECK(done);
}
// Removing service installation or bypassing explicit clipboard validation must fail.
void ClipboardContract() {
    PlatformFake p;
    ShareSdk sdk(p);
    auto q = std::make_shared<Queue>();
    Initialize(sdk, q);
    CHECK(sdk.Connections() && sdk.Transfers() && sdk.Clipboard());
    CHECK(p.reads == 0 && p.writes == 0);
    auto c = sdk.Clipboard();
    int done = 0;
    auto operation = c->ReadShareableContent([&](auto r) {
        CHECK(r.ok() && r.value().text == "manual");
        ++done;
    });
    CHECK(operation);
    Barrier(sdk);
    CHECK(done == 0 && p.reads == 1);
    ShareContent text;
    text.text = "manual";
    p.read(Result<ShareContent>::Success(text));
    Barrier(sdk);
    CHECK(done == 0);
    q->Drain();
    CHECK(done == 1);
    ShareContent image;
    image.type = ContentType::Image;
    image.mime_type = "image/png";
    image.image = {1, 2, 3};
    c->WriteContent(image, [&](auto r) {
        CHECK(r.ok());
        ++done;
    });
    Barrier(sdk);
    CHECK(p.writes == 1 && p.written.image == image.image);
    p.write(Result<void>::Success());
    Pump(sdk, q);
    CHECK(done == 2);
    ShareContent file;
    file.type = ContentType::File;
    c->WriteContent(file, [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
        ++done;
    });
    Pump(sdk, q);
    CHECK(done == 3 && p.writes == 1);
    c->ReadShareableContent([&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
        ++done;
    });
    Barrier(sdk);
    p.read(Result<ShareContent>::Success(file));
    Pump(sdk, q);
    CHECK(done == 4);
    c->ReadShareableContent([&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::PermissionDenied &&
              r.error().platform_error == 17);
        ++done;
    });
    Barrier(sdk);
    p.read(Result<ShareContent>::Failure({ErrorCode::PermissionDenied, "denied", 17}));
    Pump(sdk, q);
    CHECK(done == 5);
    c->ReadShareableContent([&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::Cancelled);
        ++done;
    });
    Barrier(sdk);
    auto late = p.read;
    c->Stop([&](auto r) {
        CHECK(r.ok());
        ++done;
    });
    Pump(sdk, q);
    CHECK(done == 7);
    late(Result<ShareContent>::Success(text));
    Pump(sdk, q);
    CHECK(done == 7);
    c->Start([&](auto r) {
        CHECK(r.ok());
        ++done;
    });
    Pump(sdk, q);
    CHECK(done == 8);
}
struct Socket : ISocket {
    std::mutex mutex;
    std::weak_ptr<Socket> peer;
    std::vector<std::uint8_t> bytes;
    ResultCallback<std::vector<std::uint8_t>> reader;
    std::size_t requested = 0;
    bool closed = false, eof = false;
    std::string RemoteEndpoint() const override { return "192.0.2.9"; }
    void Deliver() {
        ResultCallback<std::vector<std::uint8_t>> cb;
        std::vector<std::uint8_t> out;
        {
            std::lock_guard<std::mutex> l(mutex);
            if (!reader || (bytes.empty() && !closed && !eof))
                return;
            cb = std::move(reader);
            auto n = (std::min)(requested, bytes.size());
            out.assign(bytes.begin(), bytes.begin() + n);
            bytes.erase(bytes.begin(), bytes.begin() + n);
        }
        cb(Result<std::vector<std::uint8_t>>::Success(std::move(out)));
    }
    OperationId Read(std::size_t n, ResultCallback<std::vector<std::uint8_t>> cb) override {
        {
            std::lock_guard<std::mutex> l(mutex);
            CHECK(!reader);
            requested = n;
            reader = std::move(cb);
        }
        Deliver();
        return 1;
    }
    OperationId Write(std::vector<std::uint8_t> b, ResultCallback<void> cb) override {
        auto p = peer.lock();
        if (!p) {
            cb(Result<void>::Failure({ErrorCode::NetworkLost, "closed", {}}));
            return 1;
        }
        {
            std::lock_guard<std::mutex> l(p->mutex);
            p->bytes.insert(p->bytes.end(), b.begin(), b.end());
        }
        p->Deliver();
        cb(Result<void>::Success());
        return 1;
    }
    void Close() override {
        {
            std::lock_guard<std::mutex> l(mutex);
            closed = true;
        }
        Deliver();
        if (auto p = peer.lock()) {
            {
                std::lock_guard<std::mutex> l(p->mutex);
                p->eof = true;
            }
            p->Deliver();
        }
    }
    void AbortAndCleanup() override { Close(); }
    void Cancel(OperationId) override { Close(); }
};
struct Memory : unishare::IStream {
    std::vector<std::uint8_t> bytes;
    std::size_t offset = 0;
    bool closed = false, aborted = false, hold = false;
    ResultCallback<void> pending;
    OperationId Read(std::size_t n, ResultCallback<std::vector<std::uint8_t>> cb) override {
        n = (std::min)(n, bytes.size() - offset);
        std::vector<std::uint8_t> out(bytes.begin() + offset, bytes.begin() + offset + n);
        offset += n;
        cb(Result<std::vector<std::uint8_t>>::Success(out));
        return 1;
    }
    OperationId Write(std::vector<std::uint8_t> b, ResultCallback<void> cb) override {
        bytes.insert(bytes.end(), b.begin(), b.end());
        if (hold)
            pending = std::move(cb);
        else
            cb(Result<void>::Success());
        return 1;
    }
    void Close() override { closed = true; }
    void AbortAndCleanup() override {
        closed = true;
        aborted = true;
        bytes.clear();
    }
};
struct Peer {
    PlatformFake p;
    std::shared_ptr<Queue> q = std::make_shared<Queue>();
    ShareSdk sdk{p};
    std::vector<PairingRequest> pairing;
    std::vector<PairingResult> paired;
    std::vector<IncomingOffer> offers;
    std::vector<TransferResult> results;
    std::vector<DiscoveryEvent> discovery;
    explicit Peer(std::uint32_t limit = 8) {
        Initialize(sdk, q, limit);
        CHECK(sdk.Connections() && sdk.Transfers());
        sdk.Connections()->SetPairingRequestHandler([this](auto e) { pairing.push_back(e); });
        sdk.Connections()->SetPairingResultHandler([this](auto e) { paired.push_back(e); });
        sdk.Transfers()->SetIncomingOfferHandler([this](auto e) { offers.push_back(e); });
        sdk.Transfers()->SetResultHandler([this](auto e) { results.push_back(e); });
        sdk.Discovery()->SetEventHandler([this](auto e) { discovery.push_back(e); });
        sdk.Start([](auto r) { CHECK(r.ok()); });
        Pump(sdk, q);
    }
    void Discover(Peer &peer) {
        auto r = peer.p.dns.publication;
        r.interface_id = "wifi";
        r.srv_target = "peer.local.";
        r.addresses = {"bad", "good"};
        p.dns.found(Result<ServiceRecord>::Success(r));
        Pump(sdk, q);
    }
    void Wire(Peer &peer) {
        p.sockets.connect = [&peer](std::string address, auto cb) {
            if (address == "bad") {
                cb(Result<std::shared_ptr<ISocket>>::Failure(
                    {ErrorCode::NetworkLost, "unreachable", {}}));
                return;
            }
            auto a = std::make_shared<Socket>(), b = std::make_shared<Socket>();
            a->peer = b;
            b->peer = a;
            peer.p.sockets.incoming(Result<std::shared_ptr<ISocket>>::Success(b));
            cb(Result<std::shared_ptr<ISocket>>::Success(a));
        };
    }
    DeviceId Id() { return p.store.identity->id; }
};
void Settle(Peer &a, Peer &b) {
    for (int n = 0; n < 100; ++n) {
        Pump(a.sdk, a.q);
        Pump(b.sdk, b.q);
    }
}
void Connect(Peer &a, Peer &b) {
    auto ai = a.pairing.size(), bi = b.pairing.size(), ar = a.paired.size(), br = b.paired.size();
    a.Discover(b);
    a.Wire(b);
    int done = 0;
    a.sdk.Connections()->Connect(b.Id(), [&](auto r) {
        CHECK(r.ok());
        ++done;
    });
    Settle(a, b);
    CHECK(a.pairing.size() == ai + 1 && b.pairing.size() == bi + 1 && done == 0);
    CHECK(a.pairing.back().verification_code == b.pairing.back().verification_code);
    a.sdk.Connections()->ConfirmPairing(a.pairing.back().id, true, [](auto r) { CHECK(r.ok()); });
    b.sdk.Connections()->ConfirmPairing(b.pairing.back().id, true, [](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    CHECK(done == 1 && a.paired.size() == ar + 1 && b.paired.size() == br + 1);
    CHECK(a.sdk.Connections()->GetTrustedDevices().size() >= 1);
    CHECK(a.sdk.Discovery()->GetDevices()[0].paired);
}
// Missing consent gates, receipt confirmation, or global request routing fail here.
void ConnectionAndTransfers() {
    Peer a, b;
    Connect(a, b);
    int sends = 0, decisions = 0;
    a.sdk.Transfers()->SendText(b.Id(), "hello", [&](auto r) {
        CHECK(r.ok());
        ++sends;
    });
    Settle(a, b);
    CHECK(b.offers.size() == 1 && sends == 0);
    auto destination = std::make_shared<Memory>();
    b.sdk.Transfers()->Accept(b.offers.back().request_id, destination, [&](auto r) {
        CHECK(r.ok());
        ++decisions;
    });
    Settle(a, b);
    CHECK(sends == 1 && decisions == 1 && destination->closed &&
          destination->bytes == std::vector<std::uint8_t>({'h', 'e', 'l', 'l', 'o'}));
    CHECK(a.results.size() == 1 && b.results.size() == 1);
    for (auto type : {ContentType::Image, ContentType::File}) {
        auto source = std::make_shared<Memory>();
        source->bytes = {4, 5, 6};
        TransferSource s{"data",
                         type == ContentType::Image ? "image/png" : "application/octet-stream", 3,
                         source};
        auto cb = [&](auto r) {
            CHECK(r.ok());
            ++sends;
        };
        if (type == ContentType::Image)
            a.sdk.Transfers()->SendImage(b.Id(), s, cb);
        else
            a.sdk.Transfers()->SendFile(b.Id(), s, cb);
        Settle(a, b);
        CHECK(b.offers.back().content_type == type);
        auto sink = std::make_shared<Memory>();
        b.sdk.Transfers()->Accept(b.offers.back().request_id, sink, [](auto r) { CHECK(r.ok()); });
        Settle(a, b);
        CHECK(sink->bytes == std::vector<std::uint8_t>({4, 5, 6}) && sink->closed);
    }
    CHECK(sends == 3);
    a.sdk.Transfers()->SendText(b.Id(), "reject", [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::TransferRejected);
        ++sends;
    });
    Settle(a, b);
    b.sdk.Transfers()->Reject(b.offers.back().request_id, [](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    CHECK(sends == 4);
    a.sdk.Connections()->ForgetDevice(b.Id(), [](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    CHECK(a.sdk.Connections()->GetTrustedDevices().empty());
    CHECK(!a.sdk.Discovery()->GetDevices()[0].paired);
    CHECK(a.discovery.back().kind == DiscoveryEventKind::Updated &&
          !a.discovery.back().device.paired);
}
// A paused application executor cannot change an already confirmed receipt.
void ReceiptBeforeStop() {
    Peer a, b;
    Connect(a, b);
    int done = 0;
    a.sdk.Transfers()->SendText(b.Id(), "receipt", [&](auto r) {
        CHECK(r.ok());
        ++done;
    });
    Settle(a, b);
    auto sink = std::make_shared<Memory>();
    b.sdk.Transfers()->Accept(b.offers.back().request_id, sink, [](auto r) { CHECK(r.ok()); });
    for (int n = 0; n < 100; ++n) {
        Barrier(a.sdk);
        Barrier(b.sdk);
    }
    CHECK(sink->closed);
    CHECK(done == 0);
    a.sdk.Stop([](auto r) { CHECK(r.ok()); });
    Barrier(a.sdk);
    a.q->Drain();
    CHECK(done == 1);
    CHECK(a.results.size() == 1 && a.results.back().result.ok());
}
void GlobalHandlesAndCleanup() {
    Peer a, b, c;
    Connect(a, b);
    Connect(c, b);
    int finished = 0;
    a.sdk.Transfers()->SendText(b.Id(), "one", [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::TransferRejected);
        ++finished;
    });
    c.sdk.Transfers()->SendText(b.Id(), "two", [&](auto r) {
        CHECK(r.ok());
        ++finished;
    });
    Settle(a, b);
    Settle(c, b);
    CHECK(b.offers.size() == 2 && b.offers[0].request_id != b.offers[1].request_id &&
          b.offers[0].transfer_id != b.offers[1].transfer_id);
    for (auto &offer : b.offers) {
        if (offer.device_id == a.Id())
            b.sdk.Transfers()->Reject(offer.request_id, [](auto r) { CHECK(r.ok()); });
        else {
            auto sink = std::make_shared<Memory>();
            b.sdk.Transfers()->Accept(offer.request_id, sink, [](auto r) { CHECK(r.ok()); });
        }
    }
    Settle(a, b);
    Settle(c, b);
    CHECK(finished == 2 && b.results.size() == 2);
    a.sdk.Transfers()->SendText(b.Id(), "cancel", [&](auto r) {
        CHECK(!r.ok());
        ++finished;
    });
    Settle(a, b);
    auto sink = std::make_shared<Memory>();
    sink->hold = true;
    auto offer = b.offers.back();
    b.sdk.Transfers()->Accept(offer.request_id, sink, [](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    CHECK(sink->pending && !sink->aborted);
    b.sdk.Transfers()->Cancel(offer.transfer_id, [](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    CHECK(sink->aborted && finished == 3 && b.results.size() == 3);
    auto late = std::move(sink->pending);
    late(Result<void>::Success());
    Settle(a, b);
    CHECK(b.results.size() == 3);
    c.sdk.Transfers()->SendText(b.Id(), "loss", [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::NetworkLost);
        ++finished;
    });
    Settle(c, b);
    auto network = c.p.network;
    network();
    Settle(c, b);
    CHECK(finished == 4);
    CHECK(c.sdk.GetState() == SdkState::Running);
}
void DestroyWithPendingCallbacks() {
    PlatformFake p;
    auto q = std::make_shared<Queue>();
    int completed = 0;
    ResultCallback<ShareContent> late;
    std::shared_ptr<IClipboardService> clipboard;
    std::shared_ptr<SdkRuntime> retained_runtime;
    {
        ShareSdk sdk(p);
        Initialize(sdk, q);
        clipboard = sdk.Clipboard();
        retained_runtime = sdk.Runtime();
        clipboard->ReadShareableContent([&](auto r) {
            CHECK(!r.ok() && r.error().code == ErrorCode::Cancelled);
            ++completed;
        });
        Barrier(sdk);
        late = p.read;
    }
    q->Drain();
    CHECK(completed == 1);
    int restarted = 0;
    clipboard->Start([&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::Cancelled);
        ++restarted;
    });
    retained_runtime->scheduler->Invoke([] {});
    q->Drain();
    CHECK(restarted == 1);
    retained_runtime.reset();
    ShareContent text;
    text.text = "late";
    late(Result<ShareContent>::Success(text));
    clipboard->ReadShareableContent([&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::Cancelled);
        ++completed;
    });
    for (int i = 0; i < 100 && completed != 2; ++i) {
        q->Drain();
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    CHECK(completed == 2);
    Peer a;
    auto b = std::make_unique<Peer>();
    Connect(a, *b);
    a.sdk.Transfers()->SendText(b->Id(), "pending", [](auto r) { CHECK(!r.ok()); });
    Settle(a, *b);
    auto sink = std::make_shared<Memory>();
    sink->hold = true;
    b->sdk.Transfers()->Accept(b->offers.back().request_id, sink, [](auto r) { CHECK(r.ok()); });
    Settle(a, *b);
    CHECK(sink->pending);
    std::weak_ptr<SerialScheduler> queue_lifetime = b->sdk.Runtime()->scheduler;
    b.reset();
    CHECK(sink->aborted && !queue_lifetime.expired());
    auto write = std::move(sink->pending);
    write(Result<void>::Success());
    write = {};
    Pump(a.sdk, a.q);
    for (int i = 0; i < 100 && !queue_lifetime.expired(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    CHECK(queue_lifetime.expired());
}
void Validation() {
    PlatformFake p;
    ShareSdk sdk(p);
    auto q = std::make_shared<Queue>();
    Initialize(sdk, q);
    int done = 0;
    ShareContent invalid;
    invalid.text = std::string(1, char(0xff));
    sdk.Clipboard()->WriteContent(invalid, [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
        ++done;
    });
    Pump(sdk, q);
    CHECK(done == 1 && p.writes == 0);
    Peer a, b;
    Connect(a, b);
    int sent = 0;
    a.sdk.Transfers()->SendText(b.Id(), invalid.text, [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
        ++sent;
    });
    Settle(a, b);
    CHECK(sent == 1 && b.offers.empty());
    auto source = std::make_shared<Memory>();
    TransferSource oversized{"huge", "application/octet-stream", 20ULL * 1024 * 1024 * 1024 + 1,
                             source};
    a.sdk.Transfers()->SendFile(b.Id(), oversized, [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::ResourceLimit);
        ++sent;
    });
    Settle(a, b);
    CHECK(sent == 2 && b.offers.empty() && source->offset == 0);
    a.sdk.Transfers()->SendFile(b.Id(), {"missing", "application/octet-stream", 0, {}},
                                [&](auto r) {
                                    CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
                                    ++sent;
                                });
    Settle(a, b);
    CHECK(sent == 3 && b.offers.empty());
    auto empty = std::make_shared<Memory>();
    b.sdk.Transfers()->SendFile(a.Id(), {"empty", "application/octet-stream", 0, empty},
                                [&](auto r) {
                                    CHECK(r.ok());
                                    ++sent;
                                });
    Settle(a, b);
    CHECK(a.offers.size() == 1 && sent == 3);
    a.sdk.Transfers()->Accept(a.offers.back().request_id, {}, [](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
    });
    Settle(a, b);
    CHECK(sent == 3);
    auto sink = std::make_shared<Memory>();
    a.sdk.Transfers()->Accept(a.offers.back().request_id, sink, [](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    CHECK(sent == 4 && sink->closed && sink->bytes.empty());
}
void LimitsAndIdentity() {
    Peer a(1), b, c;
    Connect(a, b);
    int done = 0;
    a.Discover(c);
    a.sdk.Connections()->Connect(c.Id(), [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::ResourceLimit);
        ++done;
    });
    a.sdk.Connections()->Connect(b.Id(), [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::ResourceLimit);
        ++done;
    });
    Settle(a, b);
    CHECK(done == 2);
    a.sdk.Connections()->ConfirmPairing(a.pairing.back().id, true, [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::InvalidInput);
        ++done;
    });
    Settle(a, b);
    CHECK(done == 3);
    a.sdk.Stop([](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    a.sdk.Runtime()->scheduler->Invoke(
        [&] { a.p.store.trusted[0].public_key_fingerprint[0] ^= 1; });
    a.sdk.Start([](auto r) { CHECK(r.ok()); });
    Settle(a, b);
    a.Discover(b);
    int changed = 0;
    a.sdk.Connections()->SetIdentityChangedHandler([&](auto d) {
        CHECK(d == b.Id());
        ++changed;
    });
    a.sdk.Connections()->Connect(b.Id(), [&](auto r) {
        CHECK(!r.ok() && r.error().code == ErrorCode::IdentityChanged);
        ++done;
    });
    Settle(a, b);
    CHECK(done == 4 && changed == 1);
}
void SchedulerFinalOwnerOnWorker() {
    auto queue = std::make_shared<SerialScheduler>();
    std::weak_ptr<SerialScheduler> lifetime = queue;
    std::promise<void> entered, release;
    auto released = release.get_future().share();
    queue->Post([owner = queue, &entered, released] {
        entered.set_value();
        released.wait();
    });
    entered.get_future().wait();
    queue.reset();
    release.set_value();
    for (int n = 0; n < 100 && !lifetime.expired(); ++n)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    CHECK(lifetime.expired());
    // Also give a broken destructor time to surface an exception/terminate.
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
}
void QueuedOffersKeepTheirBudget() {
    Peer a, b;
    Connect(a, b);
    a.sdk.Transfers()->SendText(b.Id(), "queued", [](auto r) { CHECK(!r.ok()); });
    for (int n = 0; n < 100; ++n) { Barrier(a.sdk); Barrier(b.sdk); }
    a.sdk.Stop([](auto r) { CHECK(r.ok()); });
    for (int n = 0; n < 100; ++n) { Barrier(a.sdk); Barrier(b.sdk); }
    CHECK(b.sdk.Runtime()->buffers->Used() >= 16384);
    Settle(a, b);
    CHECK(b.sdk.Runtime()->buffers->Used() == 0);
}
int main(int argc, char **argv) {
    if (argc > 1 && std::string(argv[1]) == "offer-budget") {
        QueuedOffersKeepTheirBudget();
        return 0;
    }
    if (argc > 1 && std::string(argv[1]) == "scheduler-owner") {
        SchedulerFinalOwnerOnWorker();
        return 0;
    }
    if (argc > 1 && std::string(argv[1]) == "receipt-stop") {
        ReceiptBeforeStop();
        return 0;
    }
    if (argc > 1 && std::string(argv[1]) == "validation") {
        Validation();
        return 0;
    }
    ClipboardContract();
    ConnectionAndTransfers();
    ReceiptBeforeStop();
    GlobalHandlesAndCleanup();
    DestroyWithPendingCallbacks();
    Validation();
    LimitsAndIdentity();
    SchedulerFinalOwnerOnWorker();
    QueuedOffersKeepTheirBudget();
    std::cout << "sdk services passed\n";
}
