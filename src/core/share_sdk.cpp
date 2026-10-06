#include "share_sdk.hpp"
#include "device_catalog.hpp"
#include "sdk_services.hpp"
#include "exception.hpp"
#include <algorithm>
namespace unishare::core {
namespace {
ShareError Error(ErrorCode code,const char* text){return {code,text,{}};}
using Ticket=OperationRegistry::Ticket<void>;
// TXT validation is delegated to the same parser peers use, with a synthetic
// endpoint. This prevents publishing names that our own catalog would reject.
ServiceRecord Record(const SdkConfig& c,DeviceId id,Platform platform,std::uint16_t port) {
 ServiceRecord r;r.instance=id.ToString();r.service_type="_share._tcp.local.";r.port=port;
 r.txt={{"id",id.ToString()},{"name",c.device_name},{"platform",platform==Platform::Windows?"windows":platform==Platform::Android?"android":"ios"},{"pv","1.0"}};
 for(auto& p:r.txt){auto field=p.first+"="+p.second;if(field.size()>255)return {};r.txt_bytes.push_back(static_cast<std::uint8_t>(field.size()));r.txt_bytes.insert(r.txt_bytes.end(),field.begin(),field.end());}
 if(r.txt_bytes.size()>1300)return {};return r;
}
bool Valid(const SdkConfig& c,Platform platform) {
 if(c.device_name.empty()||!c.max_connections||c.max_connections>8||!c.max_pending_requests||c.max_pending_requests>4||!c.max_buffer_bytes||c.max_buffer_bytes>4194304||c.offline_debounce.count()<0)return false;
 auto id=*Uuid::Parse("01234567-89ab-4def-8123-456789abcdef");auto r=Record(c,id,platform,1);r.interface_id="validation";r.srv_target="validation.local.";r.addresses={"127.0.0.1"};return !DeviceCatalog(DeviceId{}).Apply(std::move(r)).empty();
}
}
struct ShareSdk::State final : IDeviceDiscovery,std::enable_shared_from_this<State> {
 IPlatform& platform;std::shared_ptr<SdkRuntime> runtime=std::make_shared<SdkRuntime>();
 SdkState state=SdkState::Uninitialized;bool starting=false,alive=true;
 std::uint64_t generation=0,expiry_generation=0;OperationId expiry=0;
 std::unique_ptr<DeviceCatalog> catalog;std::vector<Ticket> starters;
 std::function<void(DiscoveryEvent)> event_handler;std::function<void(ShareError)> error_handler;
 std::function<void(DiscoveryState)> state_handler;
 std::function<void(DiagnosticEvent)> diagnostic_handler;
 std::optional<ErrorCode> last_browse_error;
 std::optional<DiagnosticStage> pending_start_stage;
 std::map<std::string,bool> paired_metadata;
 explicit State(IPlatform& p):platform(p){}
 template<class F> auto Guard(std::uint64_t g,F f) {
  return [weak=weak_from_this(),queue=runtime->scheduler,g,f=std::move(f)](auto result) mutable {
   queue->Post([weak,g,f,result=std::move(result)]() mutable {
    auto s=weak.lock();
    if(s&&s->alive&&s->generation==g&&(s->starting||s->state==SdkState::Running))
     try { f(*s,std::move(result)); } catch(...) { s->FailStart(CurrentException()); }
   });
  };
 }
 void EmitState(DiscoveryState v){if(state_handler)runtime->callbacks->Post([f=state_handler,v]{f(v);});}
 void Report(DiagnosticEvent event){if(diagnostic_handler)runtime->callbacks->Post([f=diagnostic_handler,event=std::move(event)]{try{f(event);}catch(...){}});}
 void Report(DiagnosticStage stage,DiagnosticOutcome outcome,std::optional<ShareError> error={}){
  Report({stage,outcome,DiagnosticDirection::None,DiagnosticContentKind::None,0,std::move(error)});
 }
 void BeginPhase(DiagnosticStage stage){pending_start_stage=stage;Report(stage,DiagnosticOutcome::Began);}
 void EndPhase(DiagnosticStage stage,DiagnosticOutcome outcome,std::optional<ShareError> error={}){
  if(pending_start_stage==stage)pending_start_stage.reset();
  Report(stage,outcome,std::move(error));
 }
 void EmitError(ShareError e){if(error_handler)runtime->callbacks->Post([f=error_handler,e=std::move(e)]{f(e);});}
 bool Paired(DeviceId id) const {return std::any_of(runtime->trusted_devices.begin(),runtime->trusted_devices.end(),[&](auto& d){return d.id==id;});}
 void Emit(std::vector<DeviceEvent> events){for(auto& e:events){e.device.paired=Paired(e.device.id);paired_metadata[e.device.id.ToString()]=e.device.paired;if(event_handler)runtime->callbacks->Post([f=event_handler,e=std::move(e)]{f(e);});}}
 void TrustChanged(){if(catalog)for(auto d:catalog->Snapshot())if(paired_metadata[d.id.ToString()]!=Paired(d.id))Emit({{DiscoveryEventKind::Updated,d}});}
 void FinishStart(Result<void> result) {auto pending=std::move(starters);starters.clear();for(auto& t:pending)t.Complete(result);}
 std::optional<ShareError> Shutdown(ShareError reason,bool cancel,bool preserve_catalog=false) {
  std::optional<ShareError> failure;
  if(pending_start_stage){Report(*pending_start_stage,DiagnosticOutcome::Cancelled);pending_start_stage.reset();}
  auto attempt=[&](auto f){try{f();}catch(...){if(!failure)failure=CurrentException();}};
  ++generation;
  starting=false;
  attempt([&]{platform.SetNetworkChangeHandler({});});
  attempt([&]{platform.DnsSd().Stop();});
  attempt([&]{platform.Sockets().Stop();});
  attempt([&]{if(runtime->stop_services)runtime->stop_services(reason);});
  if(cancel)attempt([&]{runtime->operations->CancelAll();});
  starters.clear();
  if(!preserve_catalog) {
   ++expiry_generation;
   runtime->scheduler->Cancel(expiry);
   expiry=0;
   catalog.reset();
   paired_metadata.clear();
  }
  if(state!=SdkState::Uninitialized)state=SdkState::Stopped;
  return failure;
 }
 void FailStart(ShareError error) {
  if(pending_start_stage){Report(*pending_start_stage,DiagnosticOutcome::Failed,error);pending_start_stage.reset();}
  EmitError(error);
  FinishStart(Result<void>::Failure(error));
  // A failed replacement must still retire previously visible devices through
  // their existing debounce deadlines. Only explicit Stop clears the catalog.
  Shutdown(error,false,true);
  EmitState(DiscoveryState::Stopped);
 }
 void EnsureExpiry() {
  if(expiry||!catalog)return;
  const auto token=expiry_generation;
  expiry=runtime->scheduler->ScheduleAt(runtime->scheduler->Now()+std::chrono::milliseconds(50),
   [weak=weak_from_this(),token] {
    auto s=weak.lock();
    if(!s||!s->alive||s->expiry_generation!=token||!s->catalog)return;
    s->expiry=0;
    s->Emit(s->catalog->Expire(std::chrono::system_clock::now()));
    if(s->starting||s->state==SdkState::Running||!s->catalog->Snapshot().empty())s->EnsureExpiry();
   });
 }
 void Publish(std::uint16_t port,std::uint64_t g) {
  if(!port) {
   auto error=Error(ErrorCode::NetworkLost,"listener returned zero port");
   FailStart(error);
   return;
  }
  auto r=Record(runtime->config,runtime->identity->material.id,platform.PlatformKind(),port);
  BeginPhase(DiagnosticStage::Publish);
  platform.DnsSd().Publish(std::move(r),Guard(g,[g](State& s,Result<void> result){
   if(!result.ok()){s.EndPhase(DiagnosticStage::Publish,DiagnosticOutcome::Failed,result.error());s.FailStart(result.error());return;}
   s.EndPhase(DiagnosticStage::Publish,DiagnosticOutcome::Succeeded);
   s.last_browse_error.reset();
   s.BeginPhase(DiagnosticStage::Browse);
   s.platform.DnsSd().BrowseEvents("_share._tcp.local.",
    s.Guard(g,[](State& owner,Result<ServiceRecord> record){
     if(!record.ok()){
      if(owner.last_browse_error!=record.error().code){owner.Report(DiagnosticStage::Browse,DiagnosticOutcome::Failed,record.error());owner.last_browse_error=record.error().code;}
      owner.EmitError(record.error());return;
     }
     owner.last_browse_error.reset();
     owner.Emit(owner.catalog->Apply(record.value()));
    }),
    s.Guard(g,[](State& owner,ServiceInstanceId id){
     owner.Emit(owner.catalog->Remove(std::move(id),std::chrono::system_clock::now()));
    }));
    s.starting=false;
    s.state=SdkState::Running;
   s.EndPhase(DiagnosticStage::Browse,DiagnosticOutcome::Succeeded);
   s.EmitState(DiscoveryState::Browsing);
   s.FinishStart(Result<void>::Success());
  }));
 }
 void Listen(std::uint64_t g) {
  BeginPhase(DiagnosticStage::Listen);
  platform.Sockets().ListenBound(0,
   Guard(g,[g](State& s,Result<std::uint16_t> result){
    if(!result.ok()){s.EndPhase(DiagnosticStage::Listen,DiagnosticOutcome::Failed,result.error());s.FailStart(result.error());}
    else if(!result.value()){auto error=Error(ErrorCode::NetworkLost,"listener returned zero port");s.EndPhase(DiagnosticStage::Listen,DiagnosticOutcome::Failed,error);s.FailStart(error);}
    else {s.EndPhase(DiagnosticStage::Listen,DiagnosticOutcome::Succeeded);s.Publish(result.value(),g);}
   }),
   [weak=weak_from_this(),queue=runtime->scheduler,g](Result<std::shared_ptr<ISocket>> result){
    queue->Post([weak,g,result]{
     auto s=weak.lock();
     if(!s||!s->alive||s->generation!=g||(!s->starting&&s->state!=SdkState::Running)) {
      if(result.ok())result.value()->Close();
      return;
     }
     if(!result.ok()){s->EmitError(result.error());return;}
     if(s->runtime->accept_socket)s->runtime->accept_socket(result.value());
     else result.value()->Close();
    });
   });
 }
 void BeginNetwork() {
  starting=true;
  auto g=++generation;
  if(!catalog)catalog=std::make_unique<DeviceCatalog>(runtime->identity->material.id,
   []{return std::chrono::system_clock::now();},runtime->config.offline_debounce);
  EnsureExpiry();
  EmitState(DiscoveryState::Starting);
  platform.SetNetworkChangeHandler([weak=weak_from_this(),queue=runtime->scheduler,g]{
   queue->Post([weak,g]{
    auto s=weak.lock();
    if(!s||!s->alive||s->generation!=g||(!s->starting&&s->state!=SdkState::Running))return;
    s->Report(DiagnosticStage::NetworkChange,DiagnosticOutcome::Began);
    s->EmitState(DiscoveryState::NetworkChanged);
    s->catalog->InvalidateNetwork(std::chrono::system_clock::now());
    s->platform.DnsSd().Stop();
    s->platform.Sockets().Stop();
    if(s->runtime->stop_services)s->runtime->stop_services(Error(ErrorCode::NetworkLost,"network changed"));
    s->state=SdkState::Stopped;
    s->BeginNetwork();
    s->Report(DiagnosticStage::NetworkChange,DiagnosticOutcome::Succeeded);
   });
  });
  if(runtime->config.permissions) {
   BeginPhase(DiagnosticStage::Permission);
   runtime->config.permissions->RequestLocalNetwork(Guard(g,[g](State& s,Result<void> r){
    if(!r.ok()){s.EndPhase(DiagnosticStage::Permission,DiagnosticOutcome::Failed,r.error());s.FailStart(r.error());}
    else {s.EndPhase(DiagnosticStage::Permission,DiagnosticOutcome::Succeeded);s.Listen(g);}
   }));
  } else Listen(g);
 }
 OperationId Start(ResultCallback<void> cb) override {
  auto ticket=runtime->operations->Begin<void>(std::move(cb));
  runtime->scheduler->Post([s=shared_from_this(),ticket]{
   if(!ticket.Active())return;
   try {
   if(!s->alive||!s->runtime->identity){ticket.Complete(Result<void>::Failure(Error(ErrorCode::InvalidInput,"SDK is not initialized")));return;}
   if(s->starting){s->starters.push_back(ticket);return;}
   if(s->state==SdkState::Running){ticket.Complete(Result<void>::Success());return;}
   s->starters.push_back(ticket);
   if(s->runtime->start_services)s->runtime->start_services();
   s->BeginNetwork();
   } catch(...) {auto error=CurrentException();s->FailStart(error);ticket.Complete(Result<void>::Failure(error));}
  });
  return ticket.id;
 }
 OperationId Stop(ResultCallback<void> cb) override {
  auto ticket=runtime->operations->Begin<void>(std::move(cb),false);
 runtime->scheduler->Post([s=shared_from_this(),ticket]{
   s->Report(DiagnosticStage::Stop,DiagnosticOutcome::Began);
   try {
   if(s->starting||s->state==SdkState::Running||s->catalog) {
    auto failure=s->Shutdown(Error(ErrorCode::Cancelled,"SDK stopped"),true);
    if(failure){s->Report(DiagnosticStage::Stop,DiagnosticOutcome::Failed,*failure);ticket.Complete(Result<void>::Failure(*failure));return;}
    s->EmitState(DiscoveryState::Stopped);
   } else {
    if(s->runtime->stop_services)s->runtime->stop_services(Error(ErrorCode::Cancelled,"SDK stopped"));
    s->runtime->operations->CancelAll();
    if(s->state==SdkState::Initialized)s->state=SdkState::Stopped;
   }
   s->Report(DiagnosticStage::Stop,DiagnosticOutcome::Succeeded);
   ticket.Complete(Result<void>::Success());
   } catch(...) {auto error=CurrentException();s->Report(DiagnosticStage::Stop,DiagnosticOutcome::Failed,error);s->Shutdown(error,true);ticket.Complete(Result<void>::Failure(error));}
  });
  return ticket.id;
 }
 std::vector<DeviceInfo> GetDevices() const override {std::vector<DeviceInfo> out;runtime->scheduler->Invoke([&]{if(catalog)out=catalog->Snapshot();for(auto& d:out)d.paired=Paired(d.id);});return out;}
 void SetEventHandler(std::function<void(DiscoveryEvent)> f) override {runtime->scheduler->Invoke([&]{event_handler=std::move(f);});}
 void SetErrorHandler(std::function<void(ShareError)> f) override {runtime->scheduler->Invoke([&]{error_handler=std::move(f);});}
 void SetStateHandler(std::function<void(DiscoveryState)> f) override {runtime->scheduler->Invoke([&]{state_handler=std::move(f);});}
};
ShareSdk::ShareSdk(IPlatform& p):state_(std::make_shared<State>(p)){}
ShareSdk::~ShareSdk(){auto s=state_;try{s->runtime->scheduler->Invoke([s]{s->alive=false;s->runtime->alive=false;s->Shutdown(Error(ErrorCode::Cancelled,"SDK destroyed"),true);});}catch(...) {}}
OperationId ShareSdk::Initialize(SdkConfig config,ResultCallback<void> cb) {
 auto s=state_;
 // Capture this invocation's executor before queueing. Neither registration nor
 // return waits for the core queue or a different call's identity storage I/O.
 auto executor=config.callback_executor?config.callback_executor:s->runtime->default_callbacks;
 auto t=s->runtime->operations->BeginOn<void>(executor,std::move(cb));
 s->runtime->scheduler->Post([s,t,executor=std::move(executor),config=std::move(config)]() mutable {
  if(!t.Active())return;
  try {
  if(s->state!=SdkState::Uninitialized) {
   t.Complete(Result<void>::Failure(Error(ErrorCode::InvalidInput,"SDK already initialized")));
   return;
  }
  if(!Valid(config,s->platform.PlatformKind())) {
   t.Complete(Result<void>::Failure(Error(ErrorCode::InvalidInput,"invalid SDK configuration")));
   return;
  }
  auto identity=IdentityManager::LoadOrCreate(s->platform.IdentityStore());
  if(!identity.ok()) {
   t.Complete(Result<void>::Failure(identity.error()));
   return;
  }
  auto trust=s->platform.IdentityStore().LoadTrust();
  if(!trust.ok()){t.Complete(Result<void>::Failure(trust.error()));return;}
  // Commit config, executor and identity together in the accepted core command.
  // Concurrent rejected calls keep their own completion executor without
  // changing any accepted configuration or future service callback policy.
  s->runtime->identity=identity.value();
  s->runtime->callbacks=executor;
  s->runtime->operations->SetExecutor(executor);
  s->runtime->config=std::move(config);
  s->runtime->config.callback_executor=executor;
  s->runtime->buffers=std::make_shared<ByteBudget>(static_cast<std::size_t>(s->runtime->config.max_buffer_bytes));
  s->runtime->admission=SessionAdmission(s->runtime->buffers);
  s->runtime->trusted_devices=trust.value();
  std::weak_ptr<State> weak=s;
  InstallServices(s->runtime,s->platform,[weak]{if(auto s=weak.lock())return s->GetDevices();return std::vector<DeviceInfo>{};},[weak]{if(auto s=weak.lock())s->TrustChanged();});
  s->state=SdkState::Initialized;
  t.Complete(Result<void>::Success());
  } catch(...) {t.Complete(Result<void>::Failure(CurrentException()));}
 });
 return t.id;
}
OperationId ShareSdk::Initialize(SdkConfig config,IPlatform& p,ResultCallback<void> cb) {if(&p==&state_->platform)return Initialize(std::move(config),std::move(cb));auto t=state_->runtime->operations->Begin<void>(std::move(cb));t.Complete(Result<void>::Failure(Error(ErrorCode::InvalidInput,"platform differs from SDK owner")));return t.id;}
OperationId ShareSdk::Start(ResultCallback<void> cb){return state_->Start(std::move(cb));}
OperationId ShareSdk::Stop(ResultCallback<void> cb){return state_->Stop(std::move(cb));}
SdkState ShareSdk::GetState() const {SdkState out;state_->runtime->scheduler->Invoke([&]{out=state_->state;});return out;}
void ShareSdk::SetDiagnosticHandler(std::function<void(DiagnosticEvent)> handler){state_->runtime->scheduler->Invoke([s=state_,handler=std::move(handler)]() mutable {s->diagnostic_handler=std::move(handler);});}
std::shared_ptr<IDeviceDiscovery> ShareSdk::Discovery() const{return state_;}
std::shared_ptr<IConnectionManager> ShareSdk::Connections() const {std::shared_ptr<IConnectionManager> out;state_->runtime->scheduler->Invoke([&]{out=state_->runtime->connections;});return out;}
std::shared_ptr<ITransferService> ShareSdk::Transfers() const {std::shared_ptr<ITransferService> out;state_->runtime->scheduler->Invoke([&]{out=state_->runtime->transfers;});return out;}
std::shared_ptr<IClipboardService> ShareSdk::Clipboard() const {std::shared_ptr<IClipboardService> out;state_->runtime->scheduler->Invoke([&]{out=state_->runtime->clipboard;});return out;}
std::shared_ptr<SdkRuntime> ShareSdk::Runtime() const{return state_->runtime;}
}
