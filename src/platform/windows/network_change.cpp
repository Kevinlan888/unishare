#include "network_change.hpp"
#include "core/serial_scheduler.hpp"
#include <atomic>
namespace unishare::windows {
DWORD NetworkChangeApi::Register(PIPINTERFACE_CHANGE_CALLBACK callback,void* context,HANDLE* handle){return NotifyIpInterfaceChange(AF_UNSPEC,callback,context,FALSE,handle);}
DWORD NetworkChangeApi::Cancel(HANDLE handle){return CancelMibChangeNotify2(handle);}
struct NetworkChange::State : std::enable_shared_from_this<State> {
 std::shared_ptr<NetworkChangeApi> api;
 std::shared_ptr<core::SerialScheduler> worker=std::make_shared<core::SerialScheduler>();
 std::shared_ptr<State> nativeOwner;
 std::atomic<bool> stopped{false};HANDLE handle=nullptr;std::function<void()> handler;
 State(std::shared_ptr<NetworkChangeApi> a,std::function<void()> h):api(std::move(a)),handler(std::move(h)){}
 static void WINAPI Changed(void* context,PMIB_IPINTERFACE_ROW,MIB_NOTIFICATION_TYPE){
  // The OS owns Row. Copy no pointer to it. NativeOwner lasts until Cancel drains.
  auto s=static_cast<State*>(context)->shared_from_this();
  if(s->stopped.load())return;
  s->worker->Post([s]{if(!s->stopped.load()&&s->handler)s->handler();});
 }
 void Start(ResultCallback<void> ready){
  if(stopped.load()){ready(Result<void>::Failure({ErrorCode::Cancelled,"network watch cancelled",ERROR_CANCELLED}));return;}
  nativeOwner=shared_from_this();auto status=api->Register(Changed,this,&handle);
  if(status){handle=nullptr;nativeOwner.reset();ready(Result<void>::Failure({ErrorCode::NetworkLost,"register interface changes",status}));return;}
  ready(Result<void>::Success());
 }
 void Cancel(){
  handler={};
  if(!handle){nativeOwner.reset();return;}
  // Runs away from the native callback, holding no mutex needed by it.
  // CancelMibChangeNotify2 waits for native callbacks before releasing context.
  if(api->Cancel(handle)==NO_ERROR){handle=nullptr;nativeOwner.reset();}
  // On unexpected cancellation failure retain native context: releasing it while
  // Windows still owns a callback would be unsafe. No user callbacks remain.
 }
};
NetworkChange::NetworkChange(std::shared_ptr<NetworkChangeApi> api):api_(std::move(api)){}
NetworkChange::~NetworkChange(){Stop();}
void NetworkChange::Start(std::function<void()> handler,ResultCallback<void> ready){
 auto s=std::make_shared<State>(api_,std::move(handler));
 std::lock_guard<std::mutex> guard(mutex_);
 auto previous=std::move(state_);
 if(previous){previous->stopped.store(true);previous->worker->Post([previous]{previous->Cancel();});}
 state_=s;
 s->worker->Post([s,ready=std::move(ready)]() mutable {s->Start(std::move(ready));});
}
void NetworkChange::Stop(){
 std::shared_ptr<State> s;{std::lock_guard<std::mutex> guard(mutex_);s=std::move(state_);}
 if(s){s->stopped.store(true);s->worker->Post([s]{s->Cancel();});}
}
}
