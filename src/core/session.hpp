#pragma once
#include "identity.hpp"
#include "pairing.hpp"
#include "../protocol/frame_codec.hpp"
#include <deque>
#include <map>
#include <openssl/ssl.h>
namespace unishare::core {
// All Session calls and scheduler tasks run on one core-owned serial queue.
// Post never invokes inline; deadlines use monotonic time. Cancel may race a
// queued task, which must therefore still check its Session state.
class ISessionScheduler : public IExecutor {
public:
 using Time=std::chrono::steady_clock::time_point;
 virtual Time Now() const=0;
 virtual OperationId ScheduleAt(Time,std::function<void()>)=0;
 virtual void Cancel(OperationId)=0;
};
// One instance shared by all sessions belonging to an SDK, never per socket.
class SessionAdmission {
 std::map<std::string,std::deque<ISessionScheduler::Time>> attempts_;
 std::size_t pending_=0,connections_=0;
public:
 bool Open(); void Closed();
 bool Pair(const std::string&,ISessionScheduler::Time); void ReleasePair();
};
struct SessionEvent {
 enum class Kind { PairingRequested,Connected,IdentityChanged,Disconnected };
 Kind kind; OperationId request=0; DeviceId device; std::string sas;
 std::optional<ShareError> error;
};
class Session : public std::enable_shared_from_this<Session> {
public:
 using SocketHandle=std::shared_ptr<ISocket>;
 using Handler=std::function<void(SessionEvent)>;
 Session(LocalIdentity,TrustStore&,ISocketFactory&,ISessionScheduler&,SessionAdmission&,std::shared_ptr<IExecutor>,Handler);
 ~Session();
 Session(const Session&)=delete; Session& operator=(const Session&)=delete;
 void Connect(DeviceInfo);
 void Accept(SocketHandle);
 void ConfirmPairing(OperationId,bool);
 void Close(ShareError);
 bool Connected() const {return connected_;}
private:
 LocalIdentity identity_; TrustStore& trust_; ISocketFactory& factory_;
 ISessionScheduler& scheduler_; SessionAdmission& admission_;
 std::shared_ptr<IExecutor> executor_; Handler handler_; SocketHandle socket_;
 SSL_CTX* ctx_=nullptr; SSL* ssl_=nullptr;
 bool started_=false,closed_=false,initiator_=false,handshake_=false,reading_=false,writing_=false;
 bool admitted_=false,pair_slot_=false,hello_received_=false,local_confirmed_=false,peer_confirmed_=false;
 bool rejecting_=false,decision_flushed_=false;
 bool decision_received_=false,decision_sent_=false,connected_=false,known_=false;
 OperationId deadline_=0,request_=0;
 Fingerprint tls_peer_fingerprint_{}; std::optional<DeviceId> expected_; protocol::Hello local_,peer_;
 protocol::FrameCodec codec_; std::deque<protocol::Bytes> plaintext_,ciphertext_;
 void Begin(SocketHandle); void Pump(); void Read(); void Flush();
 void Message(const protocol::Message&); void Queue(protocol::Message);
 void MaybeConnected(); void Emit(SessionEvent::Kind,std::optional<ShareError> error={},std::string sas={});
 bool InitTls(); bool VerifyPeer(); void Fail(ErrorCode,const char*);
};
}
