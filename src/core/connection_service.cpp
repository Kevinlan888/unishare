#include "sdk_services.hpp"
#include "transfer_service.hpp"
#include <algorithm>
namespace unishare::core {
namespace {
class Connections final : public IConnectionManager,
                          public ServiceState,
                          public std::enable_shared_from_this<Connections> {
    struct Connection {
        std::shared_ptr<Session> session;
        DeviceInfo device;
        std::size_t address = 0;
        std::optional<OperationRegistry::Ticket<void>> connect;
        RequestId public_request = 0;
        OperationId local_request = 0;
        bool decision = false, paired = false;
        std::uint64_t diagnostic_sequence = 0;
        DiagnosticDirection diagnostic_direction = DiagnosticDirection::None;
    };
    IPlatform &platform_;
    TrustStore trust_;
    std::function<std::vector<DeviceInfo>()> devices_;
    std::function<void()> trust_changed_;
    std::map<std::uint64_t, Connection> connections_;
    std::uint64_t next_ = 1;
    std::function<void(ConnectionEvent)> connection_;
    std::function<void(PairingRequest)> pairing_;
    std::function<void(PairingResult)> paired_;
    std::function<void(DeviceId)> identity_;
    void Report(const Connection &c,DiagnosticStage stage,DiagnosticOutcome outcome,
                std::optional<ShareError> error={}) {
        if(auto r=runtime.lock())r->ReportDiagnostic({stage,outcome,c.diagnostic_direction,
            DiagnosticContentKind::None,c.diagnostic_sequence,std::move(error)});
    }
    Result<void> Refresh(SdkRuntime &r) {
        auto result = platform_.IdentityStore().LoadTrust();
        if (!result.ok())
            return Result<void>::Failure(result.error());
        r.trusted_devices = result.value();
        trust_changed_();
        return Result<void>::Success();
    }
    void Begin(std::uint64_t id, std::shared_ptr<ISocket> socket = {}) {
        auto r = runtime.lock();
        if (!r)
            return;
        auto &c = connections_.at(id);
        std::weak_ptr<Connections> weak = shared_from_this();
        std::weak_ptr<SdkRuntime> weak_runtime=r;
        c.session = std::make_shared<Session>(
            *r->identity, trust_, platform_.Sockets(), *queue, r->admission, queue,
            [weak, id](auto e) {
                if (auto s = weak.lock())
                    s->Event(id, e);
            },
            queue,[weak_runtime](DiagnosticEvent event){if(auto owner=weak_runtime.lock())owner->ReportDiagnostic(std::move(event));},
            c.diagnostic_sequence,c.diagnostic_direction);
        if (socket)
            c.session->Accept(std::move(socket));
        else {
            auto device = c.device;
            device.addresses = {c.device.addresses[c.address]};
            c.session->Connect(std::move(device));
        }
    }
    bool Duplicate(std::uint64_t id, DeviceId device) {
        for (auto &p : connections_)
            if (p.first != id && p.second.device.id == device)
                return true;
        return false;
    }
    void Event(std::uint64_t id, SessionEvent event) {
        auto r = runtime.lock();
        auto found = connections_.find(id);
        if (!r || found == connections_.end())
            return;
        auto &c = found->second;
        if (event.kind == SessionEvent::Kind::PairingRequested ||
            event.kind == SessionEvent::Kind::Connected) {
            if (Duplicate(id, event.device)) {
                c.session->Close(
                    ServiceError(ErrorCode::ResourceLimit, "duplicate peer connection"));
                return;
            }
            c.device.id = event.device;
        }
        if (event.kind == SessionEvent::Kind::PairingRequested) {
            if (!r->next_request) {
                c.session->Close(
                    ServiceError(ErrorCode::ResourceLimit, "request handles exhausted"));
                return;
            }
            c.public_request = r->next_request++;
            c.local_request = event.request;
            for (auto &device : devices_())
                if (device.id == event.device) {
                    c.device = device;
                    break;
                }
            PublicEvent(*r, pairing_, PairingRequest{c.public_request, c.device, event.sas});
            return;
        }
        if (event.kind == SessionEvent::Kind::IdentityChanged) {
            PublicEvent(*r, identity_, event.device);
            return;
        }
        if (event.kind == SessionEvent::Kind::Connected) {
            auto refreshed = Refresh(*r);
            if (!refreshed.ok()) {
                c.session->Close(refreshed.error());
                return;
            }
            c.paired = true;
            Report(c,DiagnosticStage::Connect,DiagnosticOutcome::Succeeded);
            transfers.attach(id, c.session);
            if (c.public_request) {
                PublicEvent(*r, paired_,
                            PairingResult{c.public_request, event.device, Result<void>::Success()});
                c.public_request = 0;
            }
            if (c.connect) {
                c.connect->Complete(Result<void>::Success());
                c.connect.reset();
            }
            PublicEvent(*r, connection_,
                        ConnectionEvent{event.device, ConnectionState::Connected, {}});
            return;
        }
        auto error = event.error.value_or(ServiceError(ErrorCode::NetworkLost, "disconnected"));
        if (c.connect && !c.public_request && !c.paired && error.code == ErrorCode::NetworkLost &&
            c.address + 1 < c.device.addresses.size()) {
            ++c.address;
            Begin(id);
            return;
        }
        Remove(id, error);
    }
    void Remove(std::uint64_t id, ShareError error) {
        auto r = runtime.lock();
        auto found = connections_.find(id);
        if (!r || found == connections_.end())
            return;
        auto c = std::move(found->second);
        connections_.erase(found);
        const auto outcome=error.code==ErrorCode::Cancelled?DiagnosticOutcome::Cancelled:DiagnosticOutcome::Failed;
        Report(c,c.paired?DiagnosticStage::Disconnect:DiagnosticStage::Connect,outcome,error);
        c.session->Close(error);
        transfers.detach(id, error);
        if (c.connect)
            c.connect->Complete(Result<void>::Failure(error));
        if (c.public_request)
            PublicEvent(*r, paired_,
                        PairingResult{c.public_request, c.device.id, Result<void>::Failure(error)});
        PublicEvent(*r, connection_,
                    ConnectionEvent{c.device.id, ConnectionState::Disconnected, error});
    }
    OperationId Decide(RequestId request, bool matches, ResultCallback<void> cb) {
        return Submit<void>(
            shared_from_this(), std::move(cb), [request, matches](auto &s, auto &, auto t) {
                auto i = std::find_if(s.connections_.begin(), s.connections_.end(), [&](auto &p) {
                    return p.second.public_request == request && !p.second.decision;
                });
                if (!request || i == s.connections_.end()) {
                    t.Complete(Result<void>::Failure(
                        ServiceError(ErrorCode::InvalidInput, "unknown pairing request")));
                    return;
                }
                i->second.decision = true;
                i->second.session->ConfirmPairing(i->second.local_request, matches);
                t.Complete(Result<void>::Success());
            });
    }

  public:
    TransferWiring transfers;
    Connections(const std::shared_ptr<SdkRuntime> &r, IPlatform &p,
                std::function<std::vector<DeviceInfo>()> d, std::function<void()> changed)
        : ServiceState(r), platform_(p), trust_(p.IdentityStore()), devices_(std::move(d)),
          trust_changed_(std::move(changed)) {}
    std::optional<std::uint64_t> Find(DeviceId device) {
        for (auto &p : connections_)
            if (p.second.device.id == device && p.second.paired && p.second.session->Connected())
                return p.first;
        return {};
    }
    void Accept(std::shared_ptr<ISocket> socket) {
        auto r = runtime.lock();
        if (!r || !enabled || connections_.size() >= r->config.max_connections || !next_) {
            socket->Close();
            return;
        }
        auto id = next_++;
        Connection c;c.diagnostic_sequence=r->NextDiagnosticSequence();c.diagnostic_direction=DiagnosticDirection::Receive;
        connections_.emplace(id,std::move(c));
        Report(connections_.at(id),DiagnosticStage::Connect,DiagnosticOutcome::Began);
        Begin(id, std::move(socket));
    }
    void Shutdown(ShareError error, bool disable) {
        if (disable)
            enabled = false;
        while (!connections_.empty())
            Remove(connections_.begin()->first, error);
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
                s.Shutdown(ServiceError(ErrorCode::Cancelled, "connections stopped"), true);
                t.Complete(Result<void>::Success());
            },
            false);
    }
    OperationId Connect(DeviceId device, ResultCallback<void> cb) override {
        return Submit<void>(
            shared_from_this(), std::move(cb), [device](auto &s, auto &r, auto ticket) {
                if (!s.enabled) {
                    ticket.Complete(Result<void>::Failure(
                        ServiceError(ErrorCode::Cancelled, "connections stopped")));
                    return;
                }
                if (s.Duplicate(0, device) || s.connections_.size() >= r.config.max_connections ||
                    !s.next_) {
                    ticket.Complete(Result<void>::Failure(ServiceError(
                        ErrorCode::ResourceLimit, "connection already pending or limit reached")));
                    return;
                }
                auto devices = s.devices_();
                auto i = std::find_if(devices.begin(), devices.end(),
                                      [&](auto &d) { return d.id == device; });
                if (i == devices.end() || i->addresses.empty() || !i->port) {
                    ticket.Complete(Result<void>::Failure(
                        ServiceError(ErrorCode::InvalidInput, "device not discovered")));
                    return;
                }
                auto id = s.next_++;
                Connection c;
                c.device = *i;
                c.connect = ticket;
                c.diagnostic_sequence=r.NextDiagnosticSequence();
                c.diagnostic_direction=DiagnosticDirection::Send;
                s.connections_.emplace(id, std::move(c));
                s.Report(s.connections_.at(id),DiagnosticStage::Connect,DiagnosticOutcome::Began);
                s.Begin(id);
            });
    }
    OperationId ConfirmPairing(RequestId request, bool matches, ResultCallback<void> cb) override {
        return Decide(request, matches, std::move(cb));
    }
    OperationId RejectPairing(RequestId request, ResultCallback<void> cb) override {
        return Decide(request, false, std::move(cb));
    }
    std::vector<TrustedDevice> GetTrustedDevices() const override {
        std::vector<TrustedDevice> out;
        queue->Invoke([&] {
            auto r = runtime.lock();
            if (!r || !r->alive)
                return;
            auto result = platform_.IdentityStore().LoadTrust();
            if (result.ok())
                out = result.value();
        });
        return out;
    }
    OperationId ForgetDevice(DeviceId device, ResultCallback<void> cb) override {
        return Submit<void>(shared_from_this(), std::move(cb), [device](auto &s, auto &r, auto t) {
            auto result = s.trust_.Forget(device);
            if (result.ok()) {
                std::vector<std::uint64_t> ids;
                for (auto &p : s.connections_)
                    if (p.second.device.id == device)
                        ids.push_back(p.first);
                for (auto id : ids)
                    s.Remove(id, ServiceError(ErrorCode::Cancelled, "device forgotten"));
                result = s.Refresh(r);
            }
            t.Complete(result);
        });
    }
    void SetConnectionHandler(std::function<void(ConnectionEvent)> f) override {
        queue->Invoke([&] { connection_ = std::move(f); });
    }
    void SetPairingRequestHandler(std::function<void(PairingRequest)> f) override {
        queue->Invoke([&] { pairing_ = std::move(f); });
    }
    void SetPairingResultHandler(std::function<void(PairingResult)> f) override {
        queue->Invoke([&] { paired_ = std::move(f); });
    }
    void SetIdentityChangedHandler(std::function<void(DeviceId)> f) override {
        queue->Invoke([&] { identity_ = std::move(f); });
    }
};
} // namespace
void InstallServices(const std::shared_ptr<SdkRuntime> &r, IPlatform &p,
                     std::function<std::vector<DeviceInfo>()> devices,
                     std::function<void()> changed) {
    auto connections = std::make_shared<Connections>(r, p, std::move(devices), std::move(changed));
    std::weak_ptr<Connections> weak = connections;
    auto transfer = MakeTransferService(r, [weak](auto d) -> std::optional<std::uint64_t> {
        if (auto s = weak.lock())
            return s->Find(d);
        return {};
    });
    connections->transfers = transfer;
    r->connections = connections;
    r->transfers = transfer.service;
    std::function<void(ShareError)> clipboard_stop;
    std::function<void()> clipboard_start;
    r->clipboard = MakeClipboardService(r, p.Clipboard(), clipboard_stop, clipboard_start);
    r->accept_socket = [weak](auto socket) {
        if (auto s = weak.lock())
            s->Accept(std::move(socket));
        else
            socket->Close();
    };
    r->stop_services = [weak, transfer, clipboard_stop](auto error) {
        if (auto s = weak.lock())
            s->Shutdown(error, error.code != ErrorCode::NetworkLost);
        if (error.code != ErrorCode::NetworkLost) {
            transfer.stop(error);
            clipboard_stop(error);
        }
    };
    r->start_services = [weak, transfer, clipboard_start] {
        if (auto s = weak.lock())
            s->enabled = true;
        transfer.start();
        clipboard_start();
    };
}
} // namespace unishare::core
