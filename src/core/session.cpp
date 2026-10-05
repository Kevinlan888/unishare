#include "session.hpp"
#include <openssl/err.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <atomic>
#include <algorithm>
#include <stdexcept>
namespace unishare::core {
namespace {
std::atomic<OperationId> next_request{1};
constexpr std::size_t MaxQueued=256*1024;
// Negotiation has bounded input/output BIOs plus two transport callbacks.
// Once connected, frame-specific reservations cover parser/encoding copies,
// ciphertext and in-flight writes; this base covers TLS record/read staging.
constexpr std::size_t NegotiationCharge=2*MaxQueued+64*1024;
// Covers two retained <=32 KiB BIOs, two TLS records, and posted read copies.
constexpr std::size_t ConnectedCharge=160*1024;
// TLS proves possession. After handshake VerifyPeer enforces a valid self-signed
// leaf; trust is established only by the persisted SPKI binding or bilateral SAS.
int OnCertificate(int,X509_STORE_CTX*){return 1;}
// BIO_reset does not free retained capacity. Replace an empty large memory
// buffer before releasing the frame charge that paid for its allocation.
bool TrimBio(BIO* bio){
 if(BIO_ctrl_pending(bio))return true;
 BUF_MEM* buffer=nullptr;BIO_get_mem_ptr(bio,&buffer);
 if(!buffer||buffer->max<=32*1024)return true;
 auto* replacement=BUF_MEM_new();if(!replacement)return false;
 BIO_set_mem_buf(bio,replacement,BIO_CLOSE);return true;
}
}
bool SessionAdmission::Open(){if(connections_==8)return false;++connections_;return true;}
void SessionAdmission::Closed(){if(connections_)--connections_;}
bool SessionAdmission::Pair(const std::string& source,ISessionScheduler::Time now){
 for(auto i=attempts_.begin();i!=attempts_.end();){auto& q=i->second;while(!q.empty()&&now-q.front()>=std::chrono::seconds(60))q.pop_front();if(q.empty())i=attempts_.erase(i);else ++i;}
 // Bound source history as well as simultaneous requests; saturated history fails closed.
 if(source.empty()||pending_>=4||(attempts_.count(source)==0&&attempts_.size()>=1024))return false;
 auto& q=attempts_[source];if(q.size()>=3)return false;q.push_back(now);++pending_;return true;
}
void SessionAdmission::ReleasePair(){if(pending_)--pending_;}
Session::Session(LocalIdentity i,TrustStore& t,ISocketFactory& f,ISessionScheduler& s,SessionAdmission& a,std::shared_ptr<IExecutor> e,Handler h,std::shared_ptr<ISessionScheduler> owner)
 :scheduler_owner_(std::move(owner)),identity_(std::move(i)),trust_(t),factory_(f),scheduler_(s),admission_(a),executor_(e?std::move(e):std::shared_ptr<IExecutor>(&s,[](IExecutor*){})),handler_(std::move(h)){}
Session::~Session(){if(!closed_)Close({ErrorCode::Cancelled,"session destroyed",{}});if(deadline_)scheduler_.Cancel(deadline_);if(socket_)socket_->Close();if(pair_slot_)admission_.ReleasePair();if(admitted_)admission_.Closed();SSL_free(ssl_);SSL_CTX_free(ctx_);}
void Session::Emit(SessionEvent::Kind kind,std::optional<ShareError> error,std::string sas){
 SessionEvent event{kind,request_,hello_received_?peer_.device_id:expected_.value_or(DeviceId{}),std::move(sas),std::move(error)};
 auto handler=handler_;if(handler)executor_->Post([handler,event]{handler(event);});
}
void Session::Fail(ErrorCode code,const char* why){Close({code,why,{}});}
void Session::Close(ShareError error){
 // The synchronous internal observer may release the caller's last owner.
 // Retain this object through all cleanup and public event posting. During
 // destruction weak_from_this is expired, but the destructor owns that scope.
 auto keep_alive=weak_from_this().lock();
 if(closed_)return;closed_=true;connected_=false;
 if(deadline_){scheduler_.Cancel(deadline_);deadline_=0;}
 if(socket_)socket_->Close();
 if(pair_slot_){admission_.ReleasePair();pair_slot_=false;}if(admitted_){admission_.Closed();admitted_=false;}
 plaintext_.clear();ciphertext_.clear();
 protocol::Bytes{}.swap(read_frame_); codec_=protocol::FrameCodec{};
 delivery_.reset(); ++delivery_generation_; read_charge_.reset();
 // Clear BIO staging before releasing its reservation. Socket::Close completes
 // outstanding writes; their callbacks retain their own send charge meanwhile.
 SSL_free(ssl_);ssl_=nullptr;SSL_CTX_free(ctx_);ctx_=nullptr;
 io_charge_.reset();
 CompleteSend(Result<void>::Failure(error));
 auto closed=std::move(content_closed_);content_handler_={};
 if(closed)closed(error);
 if(error.code==ErrorCode::IdentityChanged)Emit(SessionEvent::Kind::IdentityChanged,error);
 Emit(SessionEvent::Kind::Disconnected,error);
}
void Session::Connect(DeviceInfo device){
 if(started_||closed_)return;started_=true;initiator_=true;expected_=device.id;
 if(device.addresses.empty()||!device.port){Fail(ErrorCode::InvalidInput,"missing connect endpoint");return;}
 auto weak=weak_from_this();deadline_=scheduler_.ScheduleAt(scheduler_.Now()+std::chrono::seconds(60),[weak]{if(auto self=weak.lock())if(!self->connected_)self->Fail(ErrorCode::Timeout,"connection approval deadline");});
 auto* scheduler=&scheduler_;
 factory_.Connect(device.addresses.front(),device.port,[weak,scheduler,owner=scheduler_owner_](Result<SocketHandle> result){scheduler->Post([weak,result]{if(auto self=weak.lock()){if(self->closed_){if(result.ok())result.value()->Close();return;}if(!result.ok())self->Close(result.error());else self->Begin(result.value());}else if(result.ok())result.value()->Close();});});
}
void Session::Accept(SocketHandle socket){if(started_||closed_){if(socket)socket->Close();return;}started_=true;Begin(std::move(socket));}
bool Session::InitTls(){
 ctx_=SSL_CTX_new(TLS_method());if(!ctx_)return false;
 if(SSL_CTX_set_min_proto_version(ctx_,TLS1_3_VERSION)!=1||SSL_CTX_set_max_proto_version(ctx_,TLS1_3_VERSION)!=1||SSL_CTX_set_max_early_data(ctx_,0)!=1)return false;
 SSL_CTX_set_session_cache_mode(ctx_,SSL_SESS_CACHE_OFF);SSL_CTX_set_options(ctx_,SSL_OP_NO_TICKET);if(SSL_CTX_set_num_tickets(ctx_,0)!=1)return false;
 SSL_CTX_set_verify(ctx_,SSL_VERIFY_PEER|SSL_VERIFY_FAIL_IF_NO_PEER_CERT,OnCertificate);
 SSL_CTX_set_max_cert_list(ctx_,64*1024);
 const auto* cert_data=identity_.material.certificate.data();const auto* key_data=identity_.material.private_key.data();
 auto* cert=d2i_X509(nullptr,&cert_data,static_cast<long>(identity_.material.certificate.size()));
 auto* info=d2i_PKCS8_PRIV_KEY_INFO(nullptr,&key_data,static_cast<long>(identity_.material.private_key.size()));auto* key=info?EVP_PKCS82PKEY(info):nullptr;
 const bool loaded=cert&&key&&SSL_CTX_use_certificate(ctx_,cert)==1&&SSL_CTX_use_PrivateKey(ctx_,key)==1&&SSL_CTX_check_private_key(ctx_)==1;
 EVP_PKEY_free(key);PKCS8_PRIV_KEY_INFO_free(info);X509_free(cert);if(!loaded)return false;
 ssl_=SSL_new(ctx_);if(!ssl_)return false;auto* in=BIO_new(BIO_s_mem());auto* out=BIO_new(BIO_s_mem());if(!in||!out){BIO_free(in);BIO_free(out);return false;}
 BIO_set_mem_eof_return(in,-1);BIO_set_mem_eof_return(out,-1);SSL_set_bio(ssl_,in,out);
 if(initiator_)SSL_set_connect_state(ssl_);else SSL_set_accept_state(ssl_);
 local_.device_id=identity_.material.id;local_.public_key_fingerprint=identity_.public_key_fingerprint;
 return RAND_bytes(local_.nonce.data(),static_cast<int>(local_.nonce.size()))==1;
}
void Session::Begin(SocketHandle socket){
 socket_=std::move(socket);
 if(!socket_){Fail(ErrorCode::InvalidInput,"null socket");return;}
 if(!admission_.Open()){Fail(ErrorCode::ResourceLimit,"connection limit");return;}admitted_=true;
 auto weak=weak_from_this();if(!deadline_)deadline_=scheduler_.ScheduleAt(scheduler_.Now()+std::chrono::seconds(60),[weak]{if(auto self=weak.lock())if(!self->connected_)self->Fail(ErrorCode::Timeout,"connection approval deadline");});
 io_charge_=admission_.Buffers().Reserve(NegotiationCharge);
 if(!io_charge_){Fail(ErrorCode::ResourceLimit,"session buffer budget");return;}
 if(!InitTls()){Fail(ErrorCode::AuthenticationFailed,"TLS initialization failed");return;}Pump();
}
bool Session::VerifyPeer(){
 if(SSL_version(ssl_)!=TLS1_3_VERSION||SSL_session_reused(ssl_))return false;
 auto* cert=SSL_get1_peer_certificate(ssl_);if(!cert)return false;auto* key=X509_get_pubkey(cert);
 unsigned char* encoded=nullptr;const int size=key?i2d_PUBKEY(key,&encoded):0;Fingerprint fp{};unsigned int digest_size=0;
 const bool valid=key&&EVP_PKEY_is_a(key,"EC")&&EVP_PKEY_get_bits(key)==256&&X509_verify(cert,key)==1&&X509_cmp_current_time(X509_get0_notBefore(cert))<0&&X509_cmp_current_time(X509_get0_notAfter(cert))>0&&size>0&&EVP_Digest(encoded,size,fp.data(),&digest_size,EVP_sha256(),nullptr)==1&&digest_size==32;
 OPENSSL_free(encoded);EVP_PKEY_free(key);X509_free(cert);if(valid)tls_peer_fingerprint_=fp;return valid;
}
void Session::PostResult(ResultCallback<void> callback,Result<void> result){
 if(callback)scheduler_.Post([callback=std::move(callback),result]{callback(result);});
}
void Session::CompleteSend(Result<void> result){
 auto done=std::exchange(send_done_,{});send_charge_.reset();PostResult(std::move(done),result);
}
void Session::SetContentHandler(ContentHandler handler,std::function<void(ShareError)> closed){
 if(content_handler_||content_closed_)throw std::logic_error("content handler already attached");
 if(closed_){if(closed)scheduler_.Post([closed]{closed({ErrorCode::NetworkLost,"session already closed",{}});});return;}
 content_handler_=std::move(handler);content_closed_=std::move(closed);
}
void Session::SendContent(const protocol::Message& message,ResultCallback<void> done){
 auto reject=[&](ErrorCode code,const char* why){PostResult(std::move(done),Result<void>::Failure({code,why,{}}));};
 if(!connected_||closed_){reject(ErrorCode::AuthenticationFailed,"content requires authenticated connection");return;}
 const auto type=protocol::TypeOf(message);
 if(type<protocol::MessageType::Offer||type>protocol::MessageType::Cancel){reject(ErrorCode::InvalidInput,"not a content message");return;}
 if(send_charge_){reject(ErrorCode::ResourceLimit,"one pending content send");return;}
 // Controls are capped at 64 KiB by the codec; data has a fixed 29-byte
 // envelope. Four frame sizes cover encoding temporaries, retained plaintext,
 // encrypted output, and the in-flight socket buffer without double spending.
 std::size_t frame=protocol::MaxControlFrame+4;
 if(auto* data=std::get_if<protocol::Data>(&message)){
  if(data->payload.size()>protocol::MaxDataPayload){reject(ErrorCode::InvalidInput,"data limit");return;}
  frame=data->payload.size()+29;
 }
 auto charge=admission_.Buffers().Reserve(4*frame+64*1024);
 if(!charge){reject(ErrorCode::ResourceLimit,"content send buffer budget");return;}
 protocol::Bytes encoded;
 try{encoded=codec_.Encode(message);}catch(const std::invalid_argument& e){reject(ErrorCode::InvalidInput,e.what());return;}
 send_charge_=std::move(charge);send_done_=std::move(done);plaintext_.push_back(std::move(encoded));Pump();
}
void Session::Queue(protocol::Message message){plaintext_.push_back(codec_.Encode(message));}
void Session::Deliver(const protocol::Message& message,std::shared_ptr<ByteBudget::Reservation> charge){
 // The peer may receive our affirmative decision before the platform reports
 // its write complete. Hold one charged frame until that existing pin gate
 // succeeds, but never admit content while either consent is missing.
 const bool awaiting_flush=hello_received_&&local_confirmed_&&peer_confirmed_&&decision_sent_&&!rejecting_;
 if(!connected_&&!awaiting_flush){Fail(ErrorCode::AuthenticationFailed,"content before authenticated handler");return;}
 delivery_=std::make_shared<Delivery>(Delivery{std::move(charge),message});
 ++delivery_generation_;
 if(connected_)DispatchDelivery();
}
void Session::DispatchDelivery(){
 const auto generation=delivery_generation_;
 std::shared_ptr<const protocol::Message> view(delivery_,&delivery_->message);
 auto weak=weak_from_this();auto* queue=&scheduler_;
 ContentAck ack=[weak,queue,generation,owner=scheduler_owner_](Result<void> result){queue->Post([weak,generation,result]{
  if(auto self=weak.lock()){
   if(self->closed_||!self->delivery_||self->delivery_generation_!=generation)return;
   self->delivery_.reset();
   if(!result.ok()){self->Close(result.error());return;}
   self->Pump();
  }
 });};
 // Always defer delivery; callbacks cannot reenter the OpenSSL pump.
 // Connected is posted before this task. Its service observer installs the
 // transfer handler, so resolve that handler only when delivery runs.
 scheduler_.Post([weak,generation,view=std::move(view),ack=std::move(ack)]{
  if(auto self=weak.lock())if(!self->closed_&&self->delivery_generation_==generation){
   auto handler=self->content_handler_;
   if(!handler){self->Fail(ErrorCode::AuthenticationFailed,"content before authenticated handler");return;}
   handler(view,ack);
  }
 });
}
void Session::ReadFrames(){
 while(!closed_&&!delivery_){
  auto* destination=header_size_<4?frame_header_.data()+header_size_:read_frame_.data()+frame_size_;
  const auto remaining=header_size_<4?4-header_size_:read_frame_.size()-frame_size_;
  std::size_t count=0;ERR_clear_error();
  const int rc=SSL_read_ex(ssl_,destination,std::min<std::size_t>(remaining,16384),&count);
  const int error=rc==1?SSL_ERROR_NONE:SSL_get_error(ssl_,rc);
  if(rc!=1){if(error==SSL_ERROR_WANT_READ||error==SSL_ERROR_WANT_WRITE)return;Fail(ErrorCode::NetworkLost,"TLS channel closed");return;}
  if(header_size_<4){
   header_size_+=count;if(header_size_<4)continue;
   std::uint32_t length=0;for(auto byte:frame_header_)length=(length<<8)|byte;
   if(!length||length>protocol::MaxDataPayload+25){Fail(ErrorCode::InvalidInput,"invalid frame length");return;}
   // Includes incoming encoded frame, codec vector growth, decoded copies,
   // immutable delivery, and bounded record staging. Charge BEFORE allocation.
   read_charge_=admission_.Buffers().Reserve(6*(std::size_t(length)+4)+64*1024);
   if(!read_charge_){Fail(ErrorCode::ResourceLimit,"content receive buffer budget");return;}
   read_frame_.resize(std::size_t(length)+4);
   std::copy(frame_header_.begin(),frame_header_.end(),read_frame_.begin());frame_size_=4;
   continue;
  }
  frame_size_+=count;if(frame_size_!=read_frame_.size())continue;
  auto parsed=codec_.Feed(read_frame_);
  // Release retained parser/encoded capacities before the next frame.
  codec_=protocol::FrameCodec{};protocol::Bytes{}.swap(read_frame_);header_size_=frame_size_=0;
  if(!parsed.ok()){Close(parsed.error());return;}
  if(parsed.value().size()!=1){Fail(ErrorCode::InvalidInput,"invalid frame count");return;}
  auto charge=std::move(read_charge_);
  const auto& message=parsed.value().front();
  const auto type=protocol::TypeOf(message);
  if(type>=protocol::MessageType::Offer&&type<=protocol::MessageType::Cancel)Deliver(message,std::move(charge));
  else Message(message);
 }
}
void Session::Pump(){
 if(closed_||pumping_)return;
 pumping_=true;struct PumpGuard {bool& flag;~PumpGuard(){flag=false;}} guard{pumping_};
 bool write_blocked=false;
 if(!handshake_){ERR_clear_error();const int rc=SSL_do_handshake(ssl_);const int error=rc==1?SSL_ERROR_NONE:SSL_get_error(ssl_,rc);
  if(rc==1){if(!VerifyPeer()){Fail(ErrorCode::AuthenticationFailed,"peer certificate rejected");return;}handshake_=true;Queue(local_);}
  else if(error!=SSL_ERROR_WANT_READ&&error!=SSL_ERROR_WANT_WRITE){Fail(ErrorCode::AuthenticationFailed,"TLS handshake failed");return;}
 }
 if(handshake_){
  // Memory BIO writes do not block on the transport. Retain a whole frame if
  // OpenSSL requests read progress so the retry uses exactly the same bytes.
  while(!plaintext_.empty()){auto& frame=plaintext_.front();std::size_t written=0;ERR_clear_error();const int rc=SSL_write_ex(ssl_,frame.data(),frame.size(),&written);const int error=rc==1?SSL_ERROR_NONE:SSL_get_error(ssl_,rc);
   if(rc==1){if(written!=frame.size()){Fail(ErrorCode::NetworkLost,"partial TLS frame write");return;}plaintext_.pop_front();}
   else if(error==SSL_ERROR_WANT_READ||error==SSL_ERROR_WANT_WRITE){write_blocked=true;break;}else{Fail(ErrorCode::NetworkLost,"TLS write failed");return;}
  }
  ReadFrames();if(closed_)return;
 }
 // Bound queued ciphertext even if the platform stops completing writes.
 std::size_t queued=BIO_ctrl_pending(SSL_get_wbio(ssl_));for(const auto& b:ciphertext_)queued+=b.size();
 if(queued>(send_charge_?protocol::MaxDataPayload+64*1024:MaxQueued)){Fail(ErrorCode::ResourceLimit,"TLS output budget");return;}
 while(BIO_ctrl_pending(SSL_get_wbio(ssl_))){protocol::Bytes b(std::min<std::size_t>(16384,BIO_ctrl_pending(SSL_get_wbio(ssl_))));const int n=BIO_read(SSL_get_wbio(ssl_),b.data(),static_cast<int>(b.size()));if(n<=0){Fail(ErrorCode::NetworkLost,"TLS output failed");return;}b.resize(n);ciphertext_.push_back(std::move(b));}
 if(!TrimBio(SSL_get_wbio(ssl_))||!TrimBio(SSL_get_rbio(ssl_))){Fail(ErrorCode::ResourceLimit,"TLS buffer trim failed");return;}
 Flush();Read();
 // Messages read above may enqueue a decision after the write phase.
 if(!write_blocked&&!plaintext_.empty()){auto weak=weak_from_this();scheduler_.Post([weak]{if(auto self=weak.lock())self->Pump();});}
}
void Session::Read(){
 if(closed_||reading_||delivery_)return;reading_=true;auto weak=weak_from_this();auto* scheduler=&scheduler_;
 socket_->Read(16384,[weak,scheduler,owner=scheduler_owner_,charge=io_charge_](Result<protocol::Bytes> result){scheduler->Post([weak,result,charge]{if(auto self=weak.lock()){if(self->closed_)return;self->reading_=false;if(!result.ok()){self->Close(result.error());return;}if(result.value().empty()){self->Fail(ErrorCode::NetworkLost,"socket EOF");return;}if(result.value().size()>16384||BIO_ctrl_pending(SSL_get_rbio(self->ssl_))+result.value().size()>MaxQueued){self->Fail(ErrorCode::ResourceLimit,"TLS input budget");return;}if(BIO_write(SSL_get_rbio(self->ssl_),result.value().data(),static_cast<int>(result.value().size()))!=static_cast<int>(result.value().size())){self->Fail(ErrorCode::NetworkLost,"TLS input failed");return;}self->Pump();}});});
}
void Session::Flush(){
 if(closed_||writing_)return;
 if(ciphertext_.empty()){
  if(plaintext_.empty()&&BIO_ctrl_pending(SSL_get_wbio(ssl_))==0&&decision_sent_){
   decision_flushed_=true;
   if(rejecting_){Fail(ErrorCode::PairingRejected,"user rejected pairing");return;}
   MaybeConnected();if(closed_)return;
  }
  if(send_charge_&&plaintext_.empty()&&BIO_ctrl_pending(SSL_get_wbio(ssl_))==0)CompleteSend(Result<void>::Success());
  return;
 }
 writing_=true;auto weak=weak_from_this();auto* scheduler=&scheduler_;
 auto bytes=std::move(ciphertext_.front());ciphertext_.pop_front();socket_->Write(std::move(bytes),[weak,scheduler,owner=scheduler_owner_,charge=send_charge_?send_charge_:io_charge_](Result<void> result){scheduler->Post([weak,result,charge]{if(auto self=weak.lock()){if(self->closed_)return;self->writing_=false;if(!result.ok()){self->Close(result.error());return;}self->Flush();}});});
}
void Session::Message(const protocol::Message& message){
 if(auto* hello=std::get_if<protocol::Hello>(&message)){
  if(hello_received_){Fail(ErrorCode::AuthenticationFailed,"duplicate Hello");return;}
  peer_=*hello;hello_received_=true;
  if(peer_.public_key_fingerprint!=tls_peer_fingerprint_||(expected_&&peer_.device_id!=*expected_)){Fail(ErrorCode::IdentityChanged,"Hello identity differs from authenticated peer");return;}
  if(peer_.device_id==local_.device_id){Fail(ErrorCode::AuthenticationFailed,"self connection");return;}
  auto match=trust_.Match(peer_.device_id,peer_.public_key_fingerprint);if(!match.ok()){Close(match.error());return;}known_=match.value();
  if(known_&&initiator_){local_confirmed_=true;decision_sent_=true;Queue(protocol::PairDecision{true});MaybeConnected();return;}
  if(!admission_.Pair(socket_->RemoteEndpoint(),scheduler_.Now())){Fail(ErrorCode::ResourceLimit,"pairing admission limit");return;}pair_slot_=true;request_=next_request.fetch_add(1);
  std::array<unsigned char,32> exporter{};constexpr char label[]="EXPORTER-unishare-pairing-v1";
  if(SSL_export_keying_material(ssl_,exporter.data(),exporter.size(),label,sizeof(label)-1,nullptr,0,0)!=1){Fail(ErrorCode::AuthenticationFailed,"TLS exporter failed");return;}
  auto sas=Pairing::ComputeSas(exporter,initiator_?local_:peer_,initiator_?peer_:local_);if(!sas.ok()){Close(sas.error());return;}
  Emit(SessionEvent::Kind::PairingRequested,{},sas.value());return;
 }
 if(auto* decision=std::get_if<protocol::PairDecision>(&message)){
  if(!hello_received_||decision_received_||connected_){Fail(ErrorCode::AuthenticationFailed,"out of order pairing decision");return;}decision_received_=true;
  if(!decision->confirmed){Fail(ErrorCode::PairingRejected,"peer rejected pairing");return;}peer_confirmed_=true;MaybeConnected();return;
 }
 // Content frames use Deliver; only negotiation messages belong here.
 Fail(ErrorCode::AuthenticationFailed,"unexpected content in session negotiation");
}
void Session::ConfirmPairing(OperationId request,bool confirmed){
 if(closed_||connected_||!pair_slot_||request!=request_||decision_sent_)return;
 if(!confirmed){rejecting_=true;decision_sent_=true;Queue(protocol::PairDecision{false});Pump();return;}
 local_confirmed_=true;decision_sent_=true;Queue(protocol::PairDecision{true});Pump();MaybeConnected();
}
void Session::MaybeConnected(){
 if(closed_||connected_||rejecting_||!decision_flushed_||!hello_received_||!local_confirmed_||!peer_confirmed_||!decision_sent_)return;
 if(!known_){auto pin=trust_.Pin(peer_.device_id,peer_.public_key_fingerprint);if(!pin.ok()){Close(pin.error());return;}}
 connected_=true;if(io_charge_)io_charge_->Shrink(ConnectedCharge);if(deadline_){scheduler_.Cancel(deadline_);deadline_=0;}if(pair_slot_){admission_.ReleasePair();pair_slot_=false;}
 Emit(SessionEvent::Kind::Connected);
 if(delivery_)DispatchDelivery();
}
}
