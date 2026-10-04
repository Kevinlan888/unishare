#include "core/share_sdk.hpp"
#include <condition_variable>
#include <cstdlib>
#include <future>
#include <iostream>
#include <thread>
using namespace unishare;
using namespace unishare::core;
#define CHECK(...) do { if(!(__VA_ARGS__)) { std::cerr << "check failed " << __LINE__ << '\n'; std::exit(1); } } while(false)
struct Queue : IExecutor {
 std::mutex mutex; std::vector<std::function<void()>> tasks;
 void Post(std::function<void()> f) override { std::lock_guard<std::mutex> l(mutex); tasks.push_back(std::move(f)); }
 void Drain() { for(;;) { std::vector<std::function<void()>> next; {std::lock_guard<std::mutex> l(mutex);next.swap(tasks);} if(next.empty())return;for(auto& f:next)f();} }
};
struct Store : IIdentityStore {
 std::optional<IdentityMaterial> identity; int writes=0;
 std::function<void()> before_load;
 Result<std::optional<IdentityMaterial>> LoadIdentity() override {if(before_load)before_load();return Result<std::optional<IdentityMaterial>>::Success(identity);}
 Result<void> SaveIdentity(const IdentityMaterial& i) override {identity=i;++writes;return Result<void>::Success();}
 Result<std::vector<TrustedDevice>> LoadTrust() override {return Result<std::vector<TrustedDevice>>::Success({});}
 Result<void> SaveTrust(const TrustedDevice&) override {return Result<void>::Success();}
 Result<void> RemoveTrust(DeviceId) override {return Result<void>::Success();}
};
struct Dns : IDnsSd {
 ServiceRecord publication; int publishes=0,stops=0; ResultCallback<void> published;
 std::function<void(Result<ServiceRecord>)> found; std::function<void(ServiceInstanceId)> removed;
 OperationId Publish(ServiceRecord r,ResultCallback<void> cb) override {publication=std::move(r);++publishes;published=std::move(cb);return 1;}
 OperationId Browse(std::string,std::function<void(Result<ServiceRecord>)> cb) override {found=std::move(cb);return 2;}
 OperationId BrowseEvents(std::string type,std::function<void(Result<ServiceRecord>)> cb,std::function<void(ServiceInstanceId)> rm) override {CHECK(type=="_share._tcp.local.");removed=std::move(rm);return Browse(type,std::move(cb));}
 void Stop() override {++stops;}
};
struct Sockets : ISocketFactory {
 int listens=0,stops=0; ResultCallback<std::uint16_t> ready;
 std::function<void(Result<std::shared_ptr<ISocket>>)> incoming;
 OperationId Connect(std::string,std::uint16_t,ResultCallback<std::shared_ptr<ISocket>>) override {return 1;}
 OperationId Listen(std::uint16_t,std::function<void(Result<std::shared_ptr<ISocket>>)>) override {std::exit(1);}
 OperationId ListenBound(std::uint16_t port,ResultCallback<std::uint16_t> r,std::function<void(Result<std::shared_ptr<ISocket>>)> accept) override {CHECK(port==0);++listens;ready=std::move(r);incoming=std::move(accept);return 2;}
 void Stop() override {++stops;}
};
struct Socket : ISocket {
 bool closed=false;
 std::string RemoteEndpoint() const override{return "192.0.2.1";}
 void Cancel(OperationId) override{}
 OperationId Read(std::size_t,ResultCallback<std::vector<std::uint8_t>>) override{return 0;}
 OperationId Write(std::vector<std::uint8_t>,ResultCallback<void>) override{return 0;}
 void Close() override{closed=true;}void AbortAndCleanup() override{Close();}
};
struct PlatformFake : IPlatform,IStreamFactory,IPlatformClipboard {
 Store store; Dns dns; ::Sockets sockets; std::function<void()> changed; int defaults=0;
 IDnsSd& DnsSd() override{return dns;} ISocketFactory& Sockets() override{return sockets;}
 IIdentityStore& IdentityStore() override{return store;} IStreamFactory& Streams() override{return *this;} IPlatformClipboard& Clipboard() override{return *this;}
 std::shared_ptr<IExecutor> DefaultExecutor() override{++defaults;return {};}
 Platform PlatformKind() const override{return Platform::Android;}
 void SetNetworkChangeHandler(std::function<void()> cb) override{changed=std::move(cb);}
 Result<std::shared_ptr<unishare::IStream>> OpenSource(std::string) override{return Result<std::shared_ptr<unishare::IStream>>::Failure({ErrorCode::StorageFailed,"unused",{}});}
 Result<std::shared_ptr<unishare::IStream>> OpenDestination(std::string s) override{return OpenSource(s);}
 OperationId Read(ResultCallback<ShareContent>) override{return 0;} OperationId Write(ShareContent,ResultCallback<void>) override{return 0;}
};
struct Permission : IPermissionAgent { ResultCallback<void> pending; void RequestLocalNetwork(ResultCallback<void> cb) override{pending=std::move(cb);} };
void Barrier(ShareSdk& sdk) {sdk.Runtime()->scheduler->Invoke([]{});}
void Ready(ShareSdk& sdk,PlatformFake& p) {Barrier(sdk);p.sockets.ready(Result<std::uint16_t>::Success(43123));Barrier(sdk);p.dns.published(Result<void>::Success());Barrier(sdk);}
ServiceRecord Peer() {ServiceRecord r;r.instance="peer";r.interface_id="wifi";r.srv_target="peer.local.";r.service_type="_share._tcp.local.";r.port=1234;r.addresses={"192.0.2.1"};r.txt={{"id","01234567-89ab-4def-8123-456789abcdef"},{"name","peer"},{"platform","ios"},{"pv","1.0"}};return r;}
void InitializePreservesRequestedExecutors() {
 PlatformFake platform;
 ShareSdk sdk(platform);
 auto executor_a=std::make_shared<Queue>();
 auto executor_b=std::make_shared<Queue>();
 std::promise<void> reading,release;
 auto release_future=release.get_future().share();
 platform.store.before_load=[&]{reading.set_value();release_future.wait();};
 SdkConfig a,b;
 a.device_name="accepted A";a.callback_executor=executor_a;
 b.device_name="rejected B";b.callback_executor=executor_b;
 int draining=0,route_a=0,route_b=0;
 sdk.Initialize(a,[&](auto result){CHECK(result.ok());route_a=draining;});
 reading.get_future().wait();
 std::promise<void> entered;
 std::thread concurrent([&]{
  entered.set_value();
  sdk.Initialize(b,[&](auto result){CHECK(!result.ok());route_b=draining;});
 });
 entered.get_future().wait();
 release.set_value();
 concurrent.join();
 Barrier(sdk);
 draining=1;executor_a->Drain();
 draining=2;executor_b->Drain();
 CHECK(route_a==1 && route_b==2);
 CHECK(sdk.Runtime()->config.device_name=="accepted A");
 CHECK(sdk.Runtime()->callbacks==executor_a);
}

void InitializeReturnsWhileStorageBlocked() {
 PlatformFake platform;
 ShareSdk sdk(platform);
 auto executor=std::make_shared<Queue>();
 std::promise<void> reading,release,returned;
 auto release_future=release.get_future().share();
 platform.store.before_load=[&]{reading.set_value();release_future.wait();};
 SdkConfig config;config.device_name="blocked store";config.callback_executor=executor;
 sdk.Initialize(config,[](auto result){CHECK(result.ok());});
 reading.get_future().wait();
 std::thread caller([&]{sdk.Initialize(config,[](auto result){CHECK(!result.ok());});returned.set_value();});
 const bool immediate=returned.get_future().wait_for(std::chrono::milliseconds(200))==std::future_status::ready;
 release.set_value();
 caller.join();
 Barrier(sdk);
 executor->Drain();
 CHECK(immediate);
}

void InitializeQueuesConcurrentConfigurations() {
 PlatformFake platform;
 ShareSdk sdk(platform);
 auto executor_a=std::make_shared<Queue>();
 auto executor_b=std::make_shared<Queue>();
 std::promise<void> core_entered,release_core,launch,returned_a,returned_b;
 auto core_release=release_core.get_future().share();
 auto begin=launch.get_future().share();
 sdk.Runtime()->scheduler->Post([&]{core_entered.set_value();core_release.wait();});
 core_entered.get_future().wait();
 SdkConfig a,b;
 a.device_name="concurrent A";a.callback_executor=executor_a;
 b.device_name="concurrent B";b.callback_executor=executor_b;
 bool success_a=false,success_b=false;
 int draining=0,route_a=0,route_b=0;
 OperationId id_a=0,id_b=0;
 std::thread caller_a([&]{
  begin.wait();
  id_a=sdk.Initialize(a,[&](auto result){success_a=result.ok();route_a=draining;});
  returned_a.set_value();
 });
 std::thread caller_b([&]{
  begin.wait();
  id_b=sdk.Initialize(b,[&](auto result){success_b=result.ok();route_b=draining;});
  returned_b.set_value();
 });
 launch.set_value();
 const bool queued_a=returned_a.get_future().wait_for(std::chrono::milliseconds(200))==std::future_status::ready;
 const bool queued_b=returned_b.get_future().wait_for(std::chrono::milliseconds(200))==std::future_status::ready;
 release_core.set_value();
 caller_a.join();caller_b.join();
 Barrier(sdk);
 draining=1;executor_a->Drain();
 draining=2;executor_b->Drain();
 CHECK(queued_a && queued_b && id_a && id_b && id_a!=id_b);
 CHECK(route_a==1 && route_b==2 && success_a!=success_b);
 CHECK(sdk.Runtime()->config.device_name==(success_a?"concurrent A":"concurrent B"));
 CHECK(sdk.Runtime()->callbacks==(success_a?executor_a:executor_b));
}

void LifecycleDuringInitializeUsesConfiguredExecutor() {
 PlatformFake platform;
 ShareSdk sdk(platform);
 auto executor=std::make_shared<Queue>();
 std::promise<void> reading,release;
 auto release_future=release.get_future().share();
 platform.store.before_load=[&]{reading.set_value();release_future.wait();};
 SdkConfig config;config.device_name="pending initialization";config.callback_executor=executor;
 // Mark each completion by which queue is actually draining it. The default
 // worker is drained before the configured executor, so no marker races occur.
 int draining=0,init_route=0,start_route=0,stop_route=0;
 sdk.Initialize(config,[&](auto result){CHECK(result.ok());init_route=draining;});
 reading.get_future().wait();
 auto start=sdk.Start([&](auto result){
  CHECK(!result.ok()&&result.error().code==ErrorCode::Cancelled);
  start_route=draining;
 });
 auto stop=sdk.Stop([&](auto result){CHECK(result.ok());stop_route=draining;});
 CHECK(start && stop && start!=stop);
 release.set_value();
 Barrier(sdk);
 sdk.Runtime()->default_callbacks->Invoke([]{});
 draining=1;
 executor->Drain();
 CHECK(init_route==1 && start_route==1 && stop_route==1);
 CHECK(sdk.GetState()==SdkState::Stopped);
}

void RecoveryExpiresPeers(bool fail) {
 PlatformFake platform;
 ShareSdk sdk(platform);
 auto executor=std::make_shared<Queue>();
 SdkConfig config;config.device_name="recovery";config.callback_executor=executor;
 config.offline_debounce=std::chrono::seconds(1);
 sdk.Initialize(config,[](auto result){CHECK(result.ok());});
 Barrier(sdk);executor->Drain();
 std::vector<DiscoveryEventKind> events;
 sdk.Discovery()->SetEventHandler([&](auto event){events.push_back(event.kind);});
 sdk.Start([](auto result){CHECK(result.ok());});
 Ready(sdk,platform);
 platform.dns.found(Result<ServiceRecord>::Success(Peer()));
 Barrier(sdk);executor->Drain();
 platform.changed();
 Barrier(sdk);
 CHECK(sdk.Discovery()->GetDevices().size()==1);
 // Reach publication but deliberately hold its completion beyond debounce.
 platform.sockets.ready(Result<std::uint16_t>::Success(43124));
 Barrier(sdk);
 if(fail) {
  platform.dns.published(Result<void>::Failure({ErrorCode::DiscoveryFailed,"recovery failed",77}));
  Barrier(sdk);
  // Failure cannot silently clear an already-online peer.
  CHECK(sdk.Discovery()->GetDevices().size()==1);
 }
 std::this_thread::sleep_for(std::chrono::milliseconds(1150));
 Barrier(sdk);executor->Drain();
 CHECK(events==std::vector<DiscoveryEventKind>({DiscoveryEventKind::Online,DiscoveryEventKind::Offline}));
 CHECK(sdk.Discovery()->GetDevices().empty());
 sdk.Stop([](auto result){CHECK(result.ok());});
 Barrier(sdk);executor->Drain();
}

int main(int argc,char** argv) {
 if(argc>1) {
  const std::string test=argv[1];
  if(test=="init-executors")InitializePreservesRequestedExecutors();
  else if(test=="init-return")InitializeReturnsWhileStorageBlocked();
  else if(test=="init-concurrent")InitializeQueuesConcurrentConfigurations();
  else if(test=="pending-init-lifecycle")LifecycleDuringInitializeUsesConfiguredExecutor();
  else if(test=="recovery-delayed")RecoveryExpiresPeers(false);
  else if(test=="recovery-failed")RecoveryExpiresPeers(true);
  else CHECK(false);
  return 0;
 }
 InitializePreservesRequestedExecutors();
 InitializeReturnsWhileStorageBlocked();
 InitializeQueuesConcurrentConfigurations();
 LifecycleDuringInitializeUsesConfiguredExecutor();
 RecoveryExpiresPeers(false);
 RecoveryExpiresPeers(true);
 PlatformFake p;auto q=std::make_shared<Queue>();SdkConfig config;config.device_name="phone";config.callback_executor=q;
 ShareSdk sdk(p);int initialized=0,starts=0,stops=0;std::vector<DiscoveryEventKind> events;std::vector<ErrorCode> errors;std::vector<DiscoveryState> states;
 sdk.Initialize(config,[&](auto r){CHECK(r.ok());++initialized;});Barrier(sdk);CHECK(initialized==0);q->Drain();CHECK(initialized==1);
 CHECK(sdk.GetState()==SdkState::Initialized);auto runtime=sdk.Runtime();CHECK(&runtime->admission.Buffers()==runtime->buffers.get());CHECK(runtime->buffers->Limit()==4194304);
 sdk.Discovery()->SetEventHandler([&](auto e){events.push_back(e.kind);});sdk.Discovery()->SetErrorHandler([&](auto e){errors.push_back(e.code);});
 sdk.Discovery()->SetStateHandler([&](auto s){states.push_back(s);});
 sdk.Start([&](auto r){CHECK(r.ok());++starts;});sdk.Start([&](auto r){CHECK(r.ok());++starts;});Ready(sdk,p);q->Drain();CHECK(starts==2 && p.sockets.listens==1 && sdk.GetState()==SdkState::Running);
 CHECK(p.dns.publication.port==43123 && p.dns.publication.service_type=="_share._tcp.local." && p.dns.publication.txt_bytes.size()<=1300);
 CHECK(p.dns.publication.txt[2].second=="android");auto identity=p.store.identity->id;
 CHECK(states==std::vector<DiscoveryState>({DiscoveryState::Starting,DiscoveryState::Browsing}));
 // Exercise the lifecycle fallback explicitly: Initialize now installs services.
 runtime->scheduler->Invoke([&]{runtime->accept_socket={};});
 auto unwired=std::make_shared<Socket>();p.sockets.incoming(Result<std::shared_ptr<ISocket>>::Success(unwired));Barrier(sdk);CHECK(unwired->closed);
 int accepted=0;auto wired=std::make_shared<Socket>();runtime->scheduler->Invoke([&]{runtime->accept_socket=[&](auto socket){CHECK(socket==wired);++accepted;};});p.sockets.incoming(Result<std::shared_ptr<ISocket>>::Success(wired));Barrier(sdk);CHECK(accepted==1&&!wired->closed);runtime->scheduler->Invoke([&]{runtime->accept_socket={};});
 p.dns.found(Result<ServiceRecord>::Failure({ErrorCode::DiscoveryFailed,"query failed",9}));Barrier(sdk);q->Drain();CHECK(errors==std::vector<ErrorCode>({ErrorCode::DiscoveryFailed}));
 auto peer=Peer();p.dns.found(Result<ServiceRecord>::Success(peer));p.dns.found(Result<ServiceRecord>::Success(peer));peer.txt[1].second="renamed";p.dns.found(Result<ServiceRecord>::Success(peer));Barrier(sdk);q->Drain();CHECK(events==std::vector<DiscoveryEventKind>({DiscoveryEventKind::Online,DiscoveryEventKind::Updated}));CHECK(sdk.Discovery()->GetDevices()[0].name=="renamed");
 p.dns.removed({"peer","wifi"});Barrier(sdk);std::this_thread::sleep_for(std::chrono::milliseconds(5150));Barrier(sdk);q->Drain();CHECK(events.back()==DiscoveryEventKind::Offline);
 auto stale=p.dns.found;p.changed();Barrier(sdk);CHECK(p.sockets.listens==2);Ready(sdk,p);q->Drain();CHECK(p.dns.publishes==2);
 CHECK(states[2]==DiscoveryState::NetworkChanged&&states.back()==DiscoveryState::Browsing);
 int cleanups=0;runtime->scheduler->Invoke([&]{runtime->stop_services=[&](auto reason){CHECK(reason.code==ErrorCode::Cancelled);wired->Close();++cleanups;};});auto old_accept=p.sockets.incoming;
 auto ticket=runtime->operations->Begin<void>([&](auto r){CHECK(!r.ok()&&r.error().code==ErrorCode::Cancelled&&cleanups==1&&wired->closed);++stops;});
 sdk.Stop([&](auto r){CHECK(r.ok());++stops;});Barrier(sdk);CHECK(!ticket.Complete(Result<void>::Success()));stale(Result<ServiceRecord>::Success(Peer()));Barrier(sdk);q->Drain();CHECK(stops==2&&events.size()==3);
 sdk.Stop([&](auto r){CHECK(r.ok());++stops;});Barrier(sdk);q->Drain();CHECK(stops==3);
 // Independently restarted services must also be cleaned by a repeated SDK Stop.
 CHECK(cleanups==2);auto late_socket=std::make_shared<Socket>();old_accept(Result<std::shared_ptr<ISocket>>::Success(late_socket));Barrier(sdk);CHECK(late_socket->closed);runtime->scheduler->Invoke([&]{runtime->stop_services={};});
 sdk.Start([&](auto r){CHECK(r.ok());++starts;});Ready(sdk,p);q->Drain();CHECK(p.store.identity->id==identity&&p.store.writes==1&&starts==3);
 sdk.Stop([](auto){});Barrier(sdk);q->Drain();
 // Stop wins over delayed permission success and suppresses a late publication.
 auto permissions=std::make_shared<Permission>();config.permissions=permissions;PlatformFake p2;ShareSdk delayed(p2);delayed.Initialize(config,[](auto r){CHECK(r.ok());});Barrier(delayed);q->Drain();int cancelled=0;
 delayed.Start([&](auto r){CHECK(!r.ok()&&r.error().code==ErrorCode::Cancelled);++cancelled;});Barrier(delayed);delayed.Stop([](auto r){CHECK(r.ok());});Barrier(delayed);permissions->pending(Result<void>::Success());Barrier(delayed);q->Drain();CHECK(cancelled==1&&p2.sockets.listens==0);
 delayed.Start([&](auto r){CHECK(!r.ok()&&r.error().code==ErrorCode::PermissionDenied);++cancelled;});Barrier(delayed);permissions->pending(Result<void>::Failure({ErrorCode::PermissionDenied,"denied",7}));Barrier(delayed);q->Drain();CHECK(cancelled==2&&p2.sockets.listens==0);
 // The fallback is owned by the SDK, runs asynchronously, and preserves order.
 PlatformFake p3;ShareSdk owned(p3);SdkConfig c;c.device_name="owned";std::promise<std::thread::id> done;owned.Initialize(c,[&](auto r){CHECK(r.ok());done.set_value(std::this_thread::get_id());});CHECK(done.get_future().get()!=std::this_thread::get_id());CHECK(p3.defaults==0);
 auto executor=owned.Runtime()->callbacks;std::vector<int> order;std::promise<void> drained;executor->Post([&]{order.push_back(1);});executor->Post([&]{order.push_back(2);drained.set_value();});drained.get_future().get();CHECK(order==std::vector<int>({1,2}));
 PlatformFake p4;ShareSdk invalid(p4);c.device_name=std::string(251,'x');std::promise<bool> rejected;invalid.Initialize(c,[&](auto r){rejected.set_value(!r.ok()&&r.error().code==ErrorCode::InvalidInput);});CHECK(rejected.get_future().get()&&p4.store.writes==0);
 // Late publication completion after Stop must neither browse nor resurrect Running.
 PlatformFake p5;ShareSdk publishing(p5);config.permissions.reset();publishing.Initialize(config,[](auto r){CHECK(r.ok());});Barrier(publishing);q->Drain();int publish_cancelled=0;
 publishing.Start([&](auto r){CHECK(!r.ok()&&r.error().code==ErrorCode::Cancelled);++publish_cancelled;});Barrier(publishing);p5.sockets.ready(Result<std::uint16_t>::Success(5000));Barrier(publishing);auto late=p5.dns.published;publishing.Stop([](auto r){CHECK(r.ok());});Barrier(publishing);late(Result<void>::Success());Barrier(publishing);q->Drain();CHECK(publish_cancelled==1&&!p5.dns.found&&publishing.GetState()==SdkState::Stopped);
 publishing.Start([](auto r){CHECK(!r.ok()&&r.error().code==ErrorCode::NetworkLost&&r.error().platform_error==55);});Barrier(publishing);p5.sockets.ready(Result<std::uint16_t>::Failure({ErrorCode::NetworkLost,"bind failed",55}));Barrier(publishing);q->Drain();CHECK(publishing.GetState()==SdkState::Stopped);
 // A terminal completion and Stop contend for the same arbitration object.
 auto registry=std::make_shared<OperationRegistry>(q);for(int n=0;n<100;++n){int calls=0;auto op=registry->Begin<void>([&](auto r){CHECK(r.ok()||r.error().code==ErrorCode::Cancelled);++calls;});std::thread a([&]{op.Complete(Result<void>::Success());});std::thread b([&]{registry->CancelAll();});a.join();b.join();q->Drain();CHECK(calls==1&&!op.Active());}
 // Cancelled timers never run; one thrown application callback cannot strand later work.
 auto serial=std::make_shared<SerialScheduler>();int timer_calls=0;auto timer=serial->ScheduleAt(serial->Now()+std::chrono::hours(1),[&]{++timer_calls;});serial->Cancel(timer);serial->Post([]{throw 1;});serial->Invoke([&]{CHECK(timer_calls==0);});
 // Interface replacement retains visible peers through debounce, then expires
 // records from the old network even when the adapter sends no removals.
 PlatformFake p6;ShareSdk switching(p6);config.offline_debounce=std::chrono::seconds(1);switching.Initialize(config,[](auto r){CHECK(r.ok());});Barrier(switching);q->Drain();std::vector<DiscoveryEventKind> network_events;switching.Discovery()->SetEventHandler([&](auto e){network_events.push_back(e.kind);});switching.Start([](auto r){CHECK(r.ok());});Ready(switching,p6);p6.dns.found(Result<ServiceRecord>::Success(Peer()));Barrier(switching);q->Drain();p6.changed();Barrier(switching);CHECK(switching.Discovery()->GetDevices().size()==1);Ready(switching,p6);std::this_thread::sleep_for(std::chrono::milliseconds(1150));Barrier(switching);q->Drain();CHECK(network_events==std::vector<DiscoveryEventKind>({DiscoveryEventKind::Online,DiscoveryEventKind::Offline}));
 // Retrying Initialize from its failed default callback may switch executors.
 // Joining that executing callback queue from the core would deadlock.
 PlatformFake p7;ShareSdk retry(p7);std::promise<void> retried;SdkConfig bad;bad.device_name="";retry.Initialize(bad,[&](auto r){CHECK(!r.ok());SdkConfig good;good.device_name="retry";good.callback_executor=q;retry.Initialize(good,[](auto r){CHECK(r.ok());});retried.set_value();});auto retry_done=retried.get_future();CHECK(retry_done.wait_for(std::chrono::seconds(2))==std::future_status::ready);Barrier(retry);q->Drain();CHECK(retry.GetState()==SdkState::Initialized);
 PlatformFake p8;ShareSdk reset_executor(p8);bad.callback_executor=q;reset_executor.Initialize(bad,[](auto r){CHECK(!r.ok());});Barrier(reset_executor);q->Drain();SdkConfig good;good.device_name="default retry";std::promise<void> default_retry;reset_executor.Initialize(good,[&](auto r){CHECK(r.ok());default_retry.set_value();});CHECK(default_retry.get_future().wait_for(std::chrono::seconds(2))==std::future_status::ready);
}
