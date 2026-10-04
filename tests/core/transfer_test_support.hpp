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
 Pair():a_id(IdentityManager::LoadOrCreate(a_store).value()),b_id(IdentityManager::LoadOrCreate(b_store).value()){}
 void Start(bool default_executor=false){ae.clear();be.clear();ax=std::make_shared<Socket>(q);bx=std::make_shared<Socket>(q);ax->peer=bx;bx->peer=ax;bx->source=source;a_factory.socket=ax;
 auto executor=default_executor?std::shared_ptr<IExecutor>{}:std::shared_ptr<IExecutor>(&q,[](IExecutor*){});
 a=std::make_shared<Session>(a_id,a_trust,a_factory,q,a_limit,executor,[this](auto e){ae.push_back(e);});b=std::make_shared<Session>(b_id,b_trust,b_factory,q,external_b_limit?*external_b_limit:b_limit,executor,[this](auto e){be.push_back(e);});
 b->Accept(bx);DeviceInfo d;d.id=b_id.material.id;d.addresses={"192.0.2.1"};d.port=1234;a->Connect(d);q.Run();}
 void ConfirmA(bool yes=true){CHECK(!ae.empty());a->ConfirmPairing(ae.front().request,yes);q.Run();}
 void ConfirmB(bool yes=true){CHECK(!be.empty());b->ConfirmPairing(be.front().request,yes);q.Run();}
 void Close(){a->Close({ErrorCode::Cancelled,"test",{}});q.Run();}
};
