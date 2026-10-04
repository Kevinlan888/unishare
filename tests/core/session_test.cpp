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
 std::size_t max_read=0; bool closed=false,eof=false; std::string source="192.0.2.1"; std::vector<std::uint8_t> observed;
 explicit Socket(Scheduler& s):q(s){} std::string RemoteEndpoint() const override{return source;}
 void Deliver(){if(!reader||(!closed&&!eof&&input.empty()))return;auto cb=std::move(reader);std::vector<std::uint8_t> b;while(!input.empty()&&b.size()<std::min<std::size_t>(max_read,37)){b.push_back(input.front());input.pop_front();}q.Post([cb,b]{cb(Result<std::vector<std::uint8_t>>::Success(b));});}
 OperationId Read(std::size_t n,ResultCallback<std::vector<std::uint8_t>> cb) override{CHECK(!reader);max_read=n;reader=std::move(cb);Deliver();return q.next++;}
 OperationId Write(std::vector<std::uint8_t> b,ResultCallback<void> cb) override{auto p=peer.lock();if(closed||!p||p->closed){q.Post([cb]{cb(Result<void>::Failure({ErrorCode::NetworkLost,"closed",{}}));});}else{observed.insert(observed.end(),b.begin(),b.end());p->input.insert(p->input.end(),b.begin(),b.end());p->Deliver();q.Post([cb]{cb(Result<void>::Success());});}return q.next++;}
 void Close() override{closed=true;Deliver();if(auto p=peer.lock()){p->eof=true;p->Deliver();}}
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
 Pair():a_id(IdentityManager::LoadOrCreate(a_store).value()),b_id(IdentityManager::LoadOrCreate(b_store).value()){}
 void Start(){ae.clear();be.clear();ax=std::make_shared<Socket>(q);bx=std::make_shared<Socket>(q);ax->peer=bx;bx->peer=ax;bx->source=source;a_factory.socket=ax;
 auto executor=std::shared_ptr<IExecutor>(&q,[](IExecutor*){});
 a=std::make_shared<Session>(a_id,a_trust,a_factory,q,a_limit,executor,[this](auto e){ae.push_back(e);});b=std::make_shared<Session>(b_id,b_trust,b_factory,q,external_b_limit?*external_b_limit:b_limit,executor,[this](auto e){be.push_back(e);});
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
int main(){
 RejectUnauthenticatedTls(TLS1_3_VERSION);RejectUnauthenticatedTls(TLS1_2_VERSION);
 Pair p;p.Start();
 CHECK(p.ae.size()==1&&p.be.size()==1);
 CHECK(p.ae[0].kind==SessionEvent::Kind::PairingRequested&&p.be[0].kind==SessionEvent::Kind::PairingRequested);
 CHECK(p.ae[0].sas==p.be[0].sas&&p.ae[0].sas.size()==6);
 CHECK(!p.a->Connected()&&!p.b->Connected());p.ConfirmA();CHECK(p.a_store.trust.empty()&&p.b_store.trust.empty());p.ConfirmB();
 CHECK(p.a->Connected()&&p.b->Connected());CHECK(p.a_store.trust.size()==1&&p.b_store.trust.size()==1);
 CHECK(p.ax->observed.size()>100); // Real TLS records, not plaintext Hello.
 CHECK(p.ax->observed[0]==22&&p.ax->observed[1]==3);
 p.Close();CHECK(p.ae.back().kind==SessionEvent::Kind::Disconnected);
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
