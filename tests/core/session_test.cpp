#include "core/session.hpp"
#include <algorithm>
#include <cstdlib>
#include <iostream>
using namespace unishare; using namespace unishare::core;
#define CHECK(...) do {if(!(__VA_ARGS__)){std::cerr<<"check failed "<<__LINE__<<"\n";std::exit(1);}} while(0)
struct Store : IIdentityStore {
 std::optional<IdentityMaterial> identity; std::vector<TrustedDevice> trust; bool fail=false;
 Result<std::optional<IdentityMaterial>> LoadIdentity() override{return Result<std::optional<IdentityMaterial>>::Success(identity);}
 Result<void> SaveIdentity(const IdentityMaterial& i) override{identity=i;return Result<void>::Success();}
 Result<std::vector<TrustedDevice>> LoadTrust() override{return Result<std::vector<TrustedDevice>>::Success(trust);}
 Result<void> SaveTrust(const TrustedDevice& t) override{if(fail)return Result<void>::Failure({ErrorCode::StorageFailed,"test",{}});for(auto& x:trust)if(x.id==t.id)return x.public_key_fingerprint==t.public_key_fingerprint?Result<void>::Success():Result<void>::Failure({ErrorCode::IdentityChanged,"test",{}});trust.push_back(t);return Result<void>::Success();}
 Result<void> RemoveTrust(DeviceId) override{return Result<void>::Success();}
};
struct Scheduler : ISessionScheduler {
 Time now{}; OperationId next=1; std::deque<std::function<void()>> tasks;
 struct Timer {Time at; std::function<void()> fn;}; std::map<OperationId,Timer> timers;
 Time Now() const override{return now;} void Post(std::function<void()> f) override{tasks.push_back(std::move(f));}
 OperationId ScheduleAt(Time t,std::function<void()> f) override{auto id=next++;timers.emplace(id,Timer{t,std::move(f)});return id;}
 void Cancel(OperationId id) override{timers.erase(id);}
 void Run(){std::size_t count=0;while(!tasks.empty()){CHECK(++count<100000);auto fn=std::move(tasks.front());tasks.pop_front();fn();}}
 void Advance(std::chrono::seconds t){now+=t;for(auto i=timers.begin();i!=timers.end();)if(i->second.at<=now){Post(std::move(i->second.fn));i=timers.erase(i);}else ++i;Run();}
};
struct Socket : ISocket, std::enable_shared_from_this<Socket> {
 Scheduler& q; std::weak_ptr<Socket> peer; std::deque<std::uint8_t> input; ResultCallback<std::vector<std::uint8_t>> reader;
 bool hold_writes=false; ResultCallback<void> held_write;
 void CompleteHeld(Result<void> result){CHECK(held_write);auto cb=std::move(held_write);q.Post([cb,result]{cb(result);});}
 std::size_t reads=0; std::size_t max_read=0; bool closed=false,eof=false; std::string source="192.0.2.1"; std::vector<std::uint8_t> observed;
 explicit Socket(Scheduler& s):q(s){} std::string RemoteEndpoint() const override{return source;}
 void Deliver(){if(!reader||(!closed&&!eof&&input.empty()))return;auto cb=std::move(reader);std::vector<std::uint8_t> b;while(!input.empty()&&b.size()<std::min<std::size_t>(max_read,37)){b.push_back(input.front());input.pop_front();}q.Post([cb,b]{cb(Result<std::vector<std::uint8_t>>::Success(b));});}
 OperationId Read(std::size_t n,ResultCallback<std::vector<std::uint8_t>> cb) override{++reads;CHECK(!reader);max_read=n;reader=std::move(cb);Deliver();return q.next++;}
 OperationId Write(std::vector<std::uint8_t> b,ResultCallback<void> cb) override{auto p=peer.lock();if(closed||!p||p->closed){q.Post([cb]{cb(Result<void>::Failure({ErrorCode::NetworkLost,"closed",{}}));});}else{observed.insert(observed.end(),b.begin(),b.end());p->input.insert(p->input.end(),b.begin(),b.end());p->Deliver();if(hold_writes){CHECK(!held_write);held_write=std::move(cb);}else q.Post([cb]{cb(Result<void>::Success());});}return q.next++;}
 void Close() override{if(held_write)CompleteHeld(Result<void>::Failure({ErrorCode::Cancelled,"cancelled write",{}}));closed=true;Deliver();if(auto p=peer.lock()){p->eof=true;p->Deliver();}}
 void AbortAndCleanup() override{Close();}void Cancel(OperationId) override{Close();}
};
struct Factory : ISocketFactory {
 std::shared_ptr<Socket> socket;
 OperationId Connect(std::string,std::uint16_t,ResultCallback<std::shared_ptr<ISocket>> cb) override{cb(Result<std::shared_ptr<ISocket>>::Success(socket));return 1;}
 OperationId Listen(std::uint16_t,std::function<void(Result<std::shared_ptr<ISocket>>)>) override{return 2;} void Stop() override{}
};
struct Pair {
 Scheduler q; Store a_store,b_store; LocalIdentity a_id,b_id; TrustStore a_trust{a_store},b_trust{b_store};
 Factory a_factory,b_factory; SessionAdmission a_limit,b_limit; std::shared_ptr<Socket> ax,bx;
 SessionAdmission* external_b_limit=nullptr;std::string source="192.0.2.1";
 std::shared_ptr<Session> a,b; std::vector<SessionEvent> ae,be;
 std::function<void(const SessionEvent&)> b_event;
 Pair():a_id(IdentityManager::LoadOrCreate(a_store).value()),b_id(IdentityManager::LoadOrCreate(b_store).value()){}
 void Start(bool default_executor=false){ae.clear();be.clear();ax=std::make_shared<Socket>(q);bx=std::make_shared<Socket>(q);ax->peer=bx;bx->peer=ax;bx->source=source;a_factory.socket=ax;
 auto executor=default_executor?std::shared_ptr<IExecutor>{}:std::shared_ptr<IExecutor>(&q,[](IExecutor*){});
 a=std::make_shared<Session>(a_id,a_trust,a_factory,q,a_limit,executor,[this](auto e){ae.push_back(e);});b=std::make_shared<Session>(b_id,b_trust,b_factory,q,external_b_limit?*external_b_limit:b_limit,executor,[this](auto e){be.push_back(e);if(b_event)b_event(e);});
 b->Accept(bx);DeviceInfo d;d.id=b_id.material.id;d.addresses={"192.0.2.1"};d.port=1234;a->Connect(d);q.Run();}
 void ConfirmA(bool yes=true){CHECK(!ae.empty());a->ConfirmPairing(ae.front().request,yes);q.Run();}
 void ConfirmB(bool yes=true){CHECK(!be.empty());b->ConfirmPairing(be.front().request,yes);q.Run();}
 void Close(){a->Close({ErrorCode::Cancelled,"test",{}});q.Run();}
};
void RejectUnauthenticatedTls(int version){
 Pair p; p.ax=std::make_shared<Socket>(p.q);p.bx=std::make_shared<Socket>(p.q);p.ax->peer=p.bx;p.bx->peer=p.ax;
 auto executor=std::shared_ptr<IExecutor>(&p.q,[](IExecutor*){});
 p.b=std::make_shared<Session>(p.b_id,p.b_trust,p.b_factory,p.q,p.b_limit,executor,[&p](auto e){p.be.push_back(e);});p.b->Accept(p.bx);
 auto* ctx=SSL_CTX_new(TLS_method());CHECK(ctx);CHECK(SSL_CTX_set_min_proto_version(ctx,version)==1);CHECK(SSL_CTX_set_max_proto_version(ctx,version)==1);
 // This independent peer intentionally supplies no client certificate.
 auto* ssl=SSL_new(ctx);CHECK(ssl);auto* in=BIO_new(BIO_s_mem());auto* out=BIO_new(BIO_s_mem());CHECK(in&&out);BIO_set_mem_eof_return(in,-1);SSL_set_bio(ssl,in,out);SSL_set_connect_state(ssl);
 for(int n=0;n<100&&p.be.empty();++n){SSL_do_handshake(ssl);while(BIO_ctrl_pending(out)){std::vector<std::uint8_t> bytes(BIO_ctrl_pending(out));int size=BIO_read(out,bytes.data(),static_cast<int>(bytes.size()));CHECK(size>0);bytes.resize(size);p.ax->Write(std::move(bytes),[](auto){});}p.q.Run();if(!p.ax->input.empty()){std::vector<std::uint8_t> bytes(p.ax->input.begin(),p.ax->input.end());p.ax->input.clear();CHECK(BIO_write(in,bytes.data(),static_cast<int>(bytes.size()))==bytes.size());}}
 CHECK(!p.be.empty()&&p.be.back().error->code==ErrorCode::AuthenticationFailed);CHECK(p.b_store.trust.empty());SSL_free(ssl);SSL_CTX_free(ctx);
}
void HeldDecisionWrite(){
 for(auto outcome:{0,1,2}){
  Pair p;p.Start();p.ConfirmA();p.bx->hold_writes=true;p.ConfirmB();
  // The peer received the decision, but B's platform write has not completed.
  CHECK(p.bx->held_write);CHECK(p.a->Connected());CHECK(!p.b->Connected());
  CHECK(p.b_store.trust.empty());CHECK(p.be.size()==1);
  if(outcome==0){p.bx->CompleteHeld(Result<void>::Success());p.q.Run();CHECK(p.b->Connected()&&p.b_store.trust.size()==1);p.Close();}
  else {
   if(outcome==1)p.bx->CompleteHeld(Result<void>::Failure({ErrorCode::NetworkLost,"write failed",{}}));
   else p.b->Close({ErrorCode::Cancelled,"cancel before write completion",{}});
   p.q.Run();CHECK(!p.b->Connected()&&p.b_store.trust.empty());
   CHECK(p.be.size()==2&&p.be.back().kind==SessionEvent::Kind::Disconnected);
   CHECK(p.be.back().error->code==(outcome==1?ErrorCode::NetworkLost:ErrorCode::Cancelled));
  }
 }
}

// Removing authentication, serialized send admission, or consumption gating
// must break these tests; payload expectations are independent literals.
void ContentTransport() {
 {
  Pair p; p.Start(); int completed=0, delivered=0;
  p.b->SetContentHandler([&](auto,auto ack){++delivered;ack(Result<void>::Success());},[](auto){});
  p.a->SendContent(protocol::Data{},[&](auto r){++completed;CHECK(!r.ok()&&r.error().code==ErrorCode::AuthenticationFailed);});
  CHECK(completed==0);p.q.Run();CHECK(completed==1&&delivered==0);
  p.ConfirmA();p.ConfirmB();CHECK(p.a->Connected()&&p.b->Connected());p.Close();
 }
 {
  Pair p;p.Start();p.ConfirmA();p.ConfirmB();
  int received=0,sent=0,closed=0;std::function<void(Result<void>)> held_ack;
  p.b->SetContentHandler([&](std::shared_ptr<const protocol::Message> m,auto ack){
   ++received;auto& d=std::get<protocol::Data>(*m);CHECK(d.payload.size()==262144&&d.payload.front()==0xa5&&d.payload.back()==0xa5&&d.sequence==42);
   held_ack=std::move(ack);
  },[&](auto){++closed;});
  protocol::Data d;d.sequence=42;d.payload.assign(262144,0xa5);
  p.a->SendContent(d,[&](auto r){CHECK(r.ok());++sent;});p.q.Run();
  CHECK(sent==1&&received==1);auto reads=p.bx->reads;
  p.a->SendContent(d,[&](auto r){CHECK(r.ok());++sent;});p.q.Run();
  CHECK(sent==2&&received==1&&p.bx->reads==reads);
  auto first_ack=held_ack;held_ack(Result<void>::Success());p.q.Run();CHECK(received==2);
  first_ack(Result<void>::Success());p.q.Run();CHECK(received==2);
  p.b->Close({ErrorCode::Cancelled,"cancel stalled consumer",{}});p.Close();CHECK(closed==1);held_ack(Result<void>::Success());p.q.Run();CHECK(received==2&&closed==1);
  CHECK(p.a_limit.Buffers().Used()==0&&p.b_limit.Buffers().Used()==0);
 }
 for(bool fail:{false,true}){
  Pair p;p.Start();p.ConfirmA();p.ConfirmB();
  p.b->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[](auto){});
  p.ax->hold_writes=true;int sent=0,rejected=0;
  protocol::Data d;d.payload.assign(262144,0x38);
  p.a->SendContent(d,[&](auto r){++sent;CHECK(r.ok()!=fail);if(fail)CHECK(r.error().code==ErrorCode::NetworkLost);});p.q.Run();
  CHECK(sent==0&&p.ax->held_write);
  auto used=p.a_limit.Buffers().Used();
  for(int i=0;i<50;++i)p.a->SendContent(d,[&](auto r){++rejected;CHECK(!r.ok()&&r.error().code==ErrorCode::ResourceLimit);});
  p.q.Run();CHECK(sent==0&&rejected==50&&p.a_limit.Buffers().Used()==used);
  if(fail)p.ax->CompleteHeld(Result<void>::Failure({ErrorCode::NetworkLost,"fault",{}}));
  else {p.ax->hold_writes=false;p.ax->CompleteHeld(Result<void>::Success());}
  p.q.Run();CHECK(sent==1);p.Close();CHECK(sent==1&&p.a_limit.Buffers().Used()==0);
 }
 {
  Pair p;p.Start();p.ConfirmA();p.ConfirmB();int completed=0;
  p.a->SendContent(protocol::Hello{},[&](auto r){++completed;CHECK(!r.ok()&&r.error().code==ErrorCode::InvalidInput);});
  p.q.Run();CHECK(completed==1&&p.a->Connected());p.Close();
 }
}
void SharedByteBudget(){
 auto budget=std::make_shared<ByteBudget>(100);
 auto a=budget->Reserve(60);CHECK(a&&budget->Used()==60);
 CHECK(!budget->Reserve(41));auto b=budget->Reserve(40);CHECK(b&&budget->Used()==100);
 a.reset();CHECK(budget->Used()==40);b.reset();CHECK(budget->Used()==0);
 CHECK(!budget->Reserve(101));
 SessionAdmission first(budget),second(budget);
 auto c=first.Buffers().Reserve(80);CHECK(c&&!second.Buffers().Reserve(21));
}


void RejectContentBeforePairing(){
 Pair p;p.ax=std::make_shared<Socket>(p.q);p.bx=std::make_shared<Socket>(p.q);p.ax->peer=p.bx;p.bx->peer=p.ax;
 int delivered=0;
 p.b=std::make_shared<Session>(p.b_id,p.b_trust,p.b_factory,p.q,p.b_limit,nullptr,[&](auto e){p.be.push_back(e);});
 p.b->SetContentHandler([&](auto,auto){++delivered;},[](auto){});p.b->Accept(p.bx);
 auto* ctx=SSL_CTX_new(TLS_method());CHECK(ctx);CHECK(SSL_CTX_set_min_proto_version(ctx,TLS1_3_VERSION)==1);
 const auto* cert_bytes=p.a_id.material.certificate.data();auto* cert=d2i_X509(nullptr,&cert_bytes,static_cast<long>(p.a_id.material.certificate.size()));
 const auto* key_bytes=p.a_id.material.private_key.data();auto* info=d2i_PKCS8_PRIV_KEY_INFO(nullptr,&key_bytes,static_cast<long>(p.a_id.material.private_key.size()));
 CHECK(cert&&info);auto* key=EVP_PKCS82PKEY(info);CHECK(key&&SSL_CTX_use_certificate(ctx,cert)==1&&SSL_CTX_use_PrivateKey(ctx,key)==1);
 X509_free(cert);EVP_PKEY_free(key);PKCS8_PRIV_KEY_INFO_free(info);
 auto* ssl=SSL_new(ctx);auto* in=BIO_new(BIO_s_mem());auto* out=BIO_new(BIO_s_mem());CHECK(ssl&&in&&out);BIO_set_mem_eof_return(in,-1);SSL_set_bio(ssl,in,out);SSL_set_connect_state(ssl);
 bool sent=false;
 for(int n=0;n<100&&p.be.empty();++n){
  const int ready=SSL_do_handshake(ssl);
  if(ready==1&&!sent){protocol::FrameCodec codec;protocol::Data data;data.payload={1,2,3};auto encoded=codec.Encode(data);std::size_t written=0;CHECK(SSL_write_ex(ssl,encoded.data(),encoded.size(),&written)==1&&written==encoded.size());sent=true;}
  while(BIO_ctrl_pending(out)){std::vector<std::uint8_t> bytes(BIO_ctrl_pending(out));int size=BIO_read(out,bytes.data(),static_cast<int>(bytes.size()));CHECK(size>0);bytes.resize(size);p.ax->Write(std::move(bytes),[](auto){});}
  p.q.Run();
  if(!p.ax->input.empty()){std::vector<std::uint8_t> bytes(p.ax->input.begin(),p.ax->input.end());p.ax->input.clear();CHECK(BIO_write(in,bytes.data(),static_cast<int>(bytes.size()))==bytes.size());}
 }
 CHECK(sent&&delivered==0&&!p.be.empty()&&p.be.back().error->code==ErrorCode::AuthenticationFailed);
 CHECK(p.b_store.trust.empty()&&p.b_limit.Buffers().Used()==0);SSL_free(ssl);SSL_CTX_free(ctx);
}
void SharedSessionBudget(){
 auto budget=std::make_shared<ByteBudget>();SessionAdmission shared(budget);
 std::vector<std::unique_ptr<Pair>> pairs;
 for(int i=0;i<3;++i){auto p=std::make_unique<Pair>();p->external_b_limit=&shared;p->source="192.0.2."+std::to_string(i+1);p->Start();p->ConfirmA();p->ConfirmB();CHECK(p->b->Connected());pairs.push_back(std::move(p));}
 std::vector<Session::ContentAck> held;int rejected=0;
 for(auto& p:pairs)p->b->SetContentHandler([&](auto,auto ack){held.push_back(std::move(ack));},[&](auto e){if(e.code==ErrorCode::ResourceLimit)++rejected;});
 protocol::Data data;data.payload.assign(262144,0x99);
 for(auto& p:pairs){p->a->SendContent(data,[](auto){});p->q.Run();CHECK(budget->Used()<=4194304);}
 CHECK(held.size()==2&&rejected==1);CHECK(pairs[0]->b->Connected()&&pairs[1]->b->Connected()&&!pairs[2]->b->Connected());
 for(auto& p:pairs){p->b->Close({ErrorCode::Cancelled,"shutdown",{}});p->Close();}
 CHECK(budget->Used()==0);for(auto& ack:held)ack(Result<void>::Success());for(auto& p:pairs)p->q.Run();CHECK(budget->Used()==0);
}
void ContentTerminalRaces(){
 // Close must complete a held send exactly once, even if its socket callback
 // was already posted. A retained incoming message must retain its charge.
 Pair p;p.Start();p.ConfirmA();p.ConfirmB();
 std::shared_ptr<const protocol::Message> retained;Session::ContentAck ack;int closed=0,completed=0;
 p.b->SetContentHandler([&](auto m,auto a){retained=std::move(m);ack=std::move(a);},[&](auto e){++closed;CHECK(e.code==ErrorCode::Cancelled);});
 protocol::Data data;data.payload={4,5,6};
 p.a->SendContent(data,[](auto r){CHECK(r.ok());});p.q.Run();CHECK(retained&&ack);
 p.b->Close({ErrorCode::Cancelled,"cancel",{}});p.q.Run();CHECK(closed==1&&p.b_limit.Buffers().Used()>0);
 retained.reset();CHECK(p.b_limit.Buffers().Used()==0);ack(Result<void>::Success());ack(Result<void>::Failure({ErrorCode::StorageFailed,"late",{}}));p.q.Run();CHECK(closed==1);
 p.Close();
 Pair r;r.Start();r.ConfirmA();r.ConfirmB();r.ax->hold_writes=true;
 r.b->SetContentHandler([](auto,auto a){a(Result<void>::Success());},[](auto){});
 r.a->SendContent(data,[&](auto result){++completed;CHECK(!result.ok()&&result.error().code==ErrorCode::Cancelled);});r.q.Run();CHECK(completed==0&&r.ax->held_write);
 r.ax->CompleteHeld(Result<void>::Success());r.a->Close({ErrorCode::Cancelled,"cancel before posted write",{}});r.q.Run();CHECK(completed==1&&r.a_limit.Buffers().Used()==0);r.Close();CHECK(completed==1);
 // TCP FIN behind a paused read is observed once consumption resumes.
 Pair t;t.Start();t.ConfirmA();t.ConfirmB();int peer_closed=0;Session::ContentAck resume;
 t.b->SetContentHandler([&](auto,auto a){resume=std::move(a);},[&](auto e){CHECK(e.code==ErrorCode::NetworkLost);++peer_closed;});
 t.a->SendContent(data,[](auto r){CHECK(r.ok());});t.q.Run();t.Close();CHECK(peer_closed==0);
 resume(Result<void>::Success());t.q.Run();CHECK(peer_closed==1&&t.b_limit.Buffers().Used()==0);
}


void EveryContentTypeAndConsumerFailure(){
 Pair p;p.Start();p.ConfirmA();p.ConfirmB();
 std::vector<protocol::MessageType> received;int closed=0;
 p.b->SetContentHandler([&](auto message,auto ack){received.push_back(protocol::TypeOf(*message));ack(Result<void>::Success());},[&](auto){++closed;});
 std::vector<protocol::Message> messages{protocol::Offer{},protocol::Status{protocol::MessageType::Accept,{},{}},protocol::Status{protocol::MessageType::Reject,{},{}},protocol::Data{},protocol::Finish{},protocol::Status{protocol::MessageType::Complete,{},{}},protocol::Status{protocol::MessageType::Failed,{},{}},protocol::Status{protocol::MessageType::Cancel,{},{}}};
 int sent=0;for(const auto& m:messages){p.a->SendContent(m,[&](auto r){CHECK(r.ok());++sent;});p.q.Run();}
 CHECK(sent==8&&received==std::vector<protocol::MessageType>({protocol::MessageType::Offer,protocol::MessageType::Accept,protocol::MessageType::Reject,protocol::MessageType::Data,protocol::MessageType::Finish,protocol::MessageType::Complete,protocol::MessageType::Failed,protocol::MessageType::Cancel}));p.Close();CHECK(closed==1);
 Pair r;r.Start();r.ConfirmA();r.ConfirmB();int failed=0;
 r.b->SetContentHandler([](auto,auto ack){ack(Result<void>::Failure({ErrorCode::StorageFailed,"consumer failure",42}));},[&](auto e){++failed;CHECK(e.code==ErrorCode::StorageFailed&&e.platform_error==42);});
 r.a->SendContent(protocol::Data{},[](auto){});r.q.Run();CHECK(failed==1&&!r.b->Connected()&&r.b_limit.Buffers().Used()==0);r.Close();CHECK(failed==1);
 // Destruction owns cancellation, independent of the caller retaining a Session.
 Pair t;t.Start();t.ConfirmA();t.ConfirmB();t.ax->hold_writes=true;int cancelled=0;
 t.b->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[](auto){});
 t.a->SendContent(protocol::Data{},[&](auto result){++cancelled;CHECK(!result.ok()&&result.error().code==ErrorCode::Cancelled);});t.q.Run();CHECK(cancelled==0);
 t.a.reset();t.q.Run();CHECK(cancelled==1&&t.a_limit.Buffers().Used()==0);
}


void SmallCompletionIsConsumedBeforeClose(){
 Pair p;p.Start();p.ConfirmA();p.ConfirmB();int completions=0;
 p.b->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[](auto){});
 // A pointer-sized callable uses libc++'s small callable storage. Moving a
 // std::function need not clear that storage; one-shot ownership must do so.
 p.a->SendContent(protocol::Data{},[&completions](auto){++completions;});p.q.Run();
 CHECK(completions==1);
 p.Close();
 CHECK(completions==1);
}
void ContentRacesLocalPairingFlush(){
 for(int outcome=0;outcome<4;++outcome){
 Pair p;p.Start();int delivered=0;
 // The public SDK attaches its transfer engine in the queued Connected event.
 p.b_event=[&](const SessionEvent& e){if(e.kind==SessionEvent::Kind::Connected)
  p.b->SetContentHandler([&](auto,auto ack){++delivered;ack(Result<void>::Success());},[](auto){});};
 p.ConfirmA();p.bx->hold_writes=true;p.ConfirmB();CHECK(p.a->Connected()&&!p.b->Connected());
 p.a->SendContent(protocol::Data{},[](auto result){CHECK(result.ok());});p.q.Run();
 CHECK(delivered==0&&p.b_store.trust.empty()&&p.be.size()==1&&p.bx->held_write);
 p.bx->hold_writes=false;
 if(outcome==1)p.bx->CompleteHeld(Result<void>::Failure({ErrorCode::NetworkLost,"decision write failed",{}}));
 else if(outcome==2)p.b->Close({ErrorCode::Cancelled,"cancel held decision",{}});
 else {if(outcome==3)p.b_store.fail=true;p.bx->CompleteHeld(Result<void>::Success());}
 p.q.Run();
 if(outcome==0)CHECK(p.b->Connected()&&delivered==1&&p.b_store.trust.size()==1);
 else CHECK(!p.b->Connected()&&delivered==0&&p.b_store.trust.empty()&&p.b_limit.Buffers().Used()==0);
 p.Close();
 }
}


void CloseObserverReleasesLastOwner(){
 Pair p;p.Start();p.ConfirmA();p.ConfirmB();
 std::weak_ptr<Session> lifetime=p.a;int closed=0;
 p.a->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[&](auto error){
  ++closed;CHECK(error.code==ErrorCode::Cancelled);
  p.a.reset();
  // Close still has to post its public Disconnected event after this callback.
  // Its owner must survive until that work has finished, even on a direct call.
  CHECK(!lifetime.expired());
 });
 p.a->Close({ErrorCode::Cancelled,"observer releases last owner",{}});
 CHECK(closed==1&&!p.a&&lifetime.expired());p.q.Run();
 CHECK(p.ae.size()==3&&p.ae.back().kind==SessionEvent::Kind::Disconnected);
 CHECK(p.ae.back().error->code==ErrorCode::Cancelled);
 CHECK(p.a_limit.Buffers().Used()==0&&p.b_limit.Buffers().Used()==0);
}

int main(){SmallCompletionIsConsumedBeforeClose();
 {SessionAdmission a;CHECK(a.AcquireOffer(1));CHECK(!a.AcquireOffer(1));a.ReleaseOffer();CHECK(a.AcquireOffer(4));CHECK(a.AcquireOffer(4));CHECK(a.AcquireOffer(4));CHECK(a.AcquireOffer(4));CHECK(!a.AcquireOffer(99));for(int i=0;i<4;++i)a.ReleaseOffer();}
 CloseObserverReleasesLastOwner();
 ContentRacesLocalPairingFlush();
 EveryContentTypeAndConsumerFailure();
 RejectContentBeforePairing();SharedSessionBudget();ContentTerminalRaces();
 SharedByteBudget();ContentTransport();
 {Pair p;p.Start(true);CHECK(p.ae.size()==1&&p.ae.front().kind==SessionEvent::Kind::PairingRequested);CHECK(p.be.size()==1&&p.be.front().kind==SessionEvent::Kind::PairingRequested);p.ConfirmA();p.ConfirmB();CHECK(p.a->Connected()&&p.b->Connected());p.Close();}
 {Pair p;auto s=std::make_shared<Session>(p.a_id,p.a_trust,p.a_factory,p.q,p.a_limit,nullptr,[&p](auto e){p.ae.push_back(e);});s->Close({ErrorCode::Cancelled,"close before start",{}});CHECK(p.ae.empty());p.q.Run();CHECK(p.ae.size()==1&&p.ae[0].error->code==ErrorCode::Cancelled);s->Close({ErrorCode::Cancelled,"repeat",{}});p.q.Run();CHECK(p.ae.size()==1);}
 HeldDecisionWrite();
 RejectUnauthenticatedTls(TLS1_3_VERSION);RejectUnauthenticatedTls(TLS1_2_VERSION);
 Pair p;p.Start();
 CHECK(p.ae.size()==1&&p.be.size()==1);
 CHECK(p.ae[0].kind==SessionEvent::Kind::PairingRequested&&p.be[0].kind==SessionEvent::Kind::PairingRequested);
 CHECK(p.ae[0].sas==p.be[0].sas&&p.ae[0].sas.size()==6);
 CHECK(!p.a->PeerDevice()&&!p.b->PeerDevice());
 CHECK(!p.a->Connected()&&!p.b->Connected());p.ConfirmA();CHECK(p.a_store.trust.empty()&&p.b_store.trust.empty());p.ConfirmB();
 CHECK(p.a->Connected()&&p.b->Connected());CHECK(p.a_store.trust.size()==1&&p.b_store.trust.size()==1);
 CHECK(p.a->PeerDevice()==p.b_id.material.id&&p.b->PeerDevice()==p.a_id.material.id);
 CHECK(p.ax->observed.size()>100); // Real TLS records, not plaintext Hello.
 CHECK(p.ax->observed[0]==22&&p.ax->observed[1]==3);
 p.Close();CHECK(p.ae.back().kind==SessionEvent::Kind::Disconnected);
 CHECK(!p.a->PeerDevice()&&!p.b->PeerDevice());
 // Reconnect still requires receiver consent. Initiator is preauthorized by Connect.
 p.Start();CHECK(!p.a->Connected()&&!p.b->Connected());CHECK(p.ae.empty()&&p.be.size()==1);p.ConfirmB();CHECK(p.a->Connected()&&p.b->Connected());p.Close();
 // A new valid certificate/key claiming the old pinned DeviceId is rejected.
 Store changed;auto replacement=IdentityManager::LoadOrCreate(changed).value();replacement.material.id=p.b_id.material.id;p.b_id=replacement;p.Start();
 CHECK(!p.a->Connected());CHECK(p.ae.front().kind==SessionEvent::Kind::IdentityChanged);CHECK(p.a_store.trust.size()==1&&p.a_store.trust[0].public_key_fingerprint!=replacement.public_key_fingerprint);
 {Pair r;r.Start();r.ConfirmA(false);CHECK(!r.a->Connected()&&!r.b->Connected());CHECK(r.a_store.trust.empty()&&r.b_store.trust.empty());CHECK(r.ae.back().error->code==ErrorCode::PairingRejected);CHECK(r.be.back().error->code==ErrorCode::PairingRejected);}
 {Pair r;r.Start();r.q.Advance(std::chrono::seconds(59));CHECK(r.ae.size()==1);r.q.Advance(std::chrono::seconds(1));CHECK(r.ae.back().error->code==ErrorCode::Timeout);CHECK(r.a_store.trust.empty()&&r.b_store.trust.empty());}
 {Pair r;for(int i=0;i<3;++i){r.Start();CHECK(r.be.front().kind==SessionEvent::Kind::PairingRequested);r.Close();}r.Start();CHECK(r.be.back().error->code==ErrorCode::ResourceLimit);r.q.Advance(std::chrono::seconds(60));r.Start();CHECK(r.be.front().kind==SessionEvent::Kind::PairingRequested);r.Close();}
  // Shared per-instance cap cannot be bypassed by creating another Session.
 {SessionAdmission shared;std::vector<std::unique_ptr<Pair>> pending;
  for(int j=0;j<5;++j){auto r=std::make_unique<Pair>();r->external_b_limit=&shared;r->source="192.0.2."+std::to_string(j+1);r->Start();if(j<4)CHECK(r->be.front().kind==SessionEvent::Kind::PairingRequested);else CHECK(r->be.back().error->code==ErrorCode::ResourceLimit);pending.push_back(std::move(r));}
  for(auto& r:pending)r->Close();
 }
 {Pair r;r.b_id.public_key_fingerprint[0]^=1;r.Start();CHECK(r.ae.back().error->code==ErrorCode::IdentityChanged);CHECK(r.a_store.trust.empty());}
 {Pair r;r.Start();r.a->ConfirmPairing(r.ae.front().request+999,true);r.q.Run();CHECK(r.a_store.trust.empty());r.Close();auto count=r.ae.size();r.a->Close({ErrorCode::Timeout,"late",{}});r.q.Advance(std::chrono::seconds(60));CHECK(r.ae.size()==count);}
 {Pair r;r.Start();r.b_store.fail=true;r.ConfirmA();r.ConfirmB();CHECK(r.be.back().error->code==ErrorCode::StorageFailed);CHECK(r.b_store.trust.empty());}
  // A deadline already queued when cancellation wins must not close a connection.
 {Pair r;r.Start();auto late=r.q.timers.begin()->second.fn;r.ConfirmA();r.ConfirmB();CHECK(r.a->Connected()&&r.b->Connected());late();r.q.Run();CHECK(r.a->Connected()&&r.b->Connected());r.Close();}
 protocol::Hello i,r;i.device_id.bytes[0]=1;r.device_id.bytes[0]=2;i.nonce.fill(3);r.nonce.fill(4);i.public_key_fingerprint.fill(5);r.public_key_fingerprint.fill(6);std::array<std::uint8_t,32> e{};e.fill(7);
 auto sas=Pairing::ComputeSas(e,i,r);CHECK(sas.ok()&&sas.value()=="317527");auto different=[&](auto ex,auto in,auto re){auto result=Pairing::ComputeSas(ex,in,re);CHECK(result.ok()&&result.value()!=sas.value());};auto changed_exporter=e;changed_exporter[0]^=1;different(changed_exporter,i,r);auto changed_id=i;changed_id.device_id.bytes[0]^=1;different(e,changed_id,r);auto changed_fp=r;changed_fp.public_key_fingerprint[0]^=1;different(e,i,changed_fp);different(e,r,i);
}
