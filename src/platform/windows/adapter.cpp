#include "adapter.hpp"
#include "core/share_sdk.hpp"
#include "core/exception.hpp"
#include <shlobj.h>
namespace unishare::windows {
class PermissionGate {
 struct Watch {
  std::mutex mutex;
  std::optional<Result<void>> result;
  std::vector<ResultCallback<void>> waiting;
  void Finish(Result<void> r){
   std::vector<ResultCallback<void>> callbacks;
   {std::lock_guard<std::mutex> lock(mutex);if(result)return;result=r;callbacks.swap(waiting);}
   for(auto& cb:callbacks)cb(r);
  }
  void Wait(ResultCallback<void> cb){
   std::optional<Result<void>> ready;
   {std::lock_guard<std::mutex> lock(mutex);ready=result;if(!ready)waiting.push_back(cb);}
   if(ready)cb(*ready);
  }
 };
 std::mutex mutex_;
 std::shared_ptr<Watch> current_=std::make_shared<Watch>();
public:
 ResultCallback<void> Reset(){
  auto next=std::make_shared<Watch>();std::shared_ptr<Watch> old;
  {std::lock_guard<std::mutex> lock(mutex_);old=std::exchange(current_,next);}
  old->Finish(Result<void>::Failure({ErrorCode::Cancelled,"network watch replaced",ERROR_CANCELLED}));
  return [next](Result<void> r){next->Finish(std::move(r));};
 }
 void Wait(ResultCallback<void> cb){
  std::shared_ptr<Watch> watch;{std::lock_guard<std::mutex> lock(mutex_);watch=current_;}
  watch->Wait(std::move(cb));
 }
};
namespace {
std::filesystem::path StoragePath(WindowsOptions options){
 if(!options.identity_directory.empty()){
  if(!options.identity_directory.is_absolute())throw ShareError{ErrorCode::InvalidInput,"absolute identity directory required",{}};
  return options.identity_directory;
 }
 PWSTR raw=nullptr;
 auto status=SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&raw);
 std::unique_ptr<wchar_t,decltype(&CoTaskMemFree)> owner(raw,CoTaskMemFree);
 if(FAILED(status))throw ShareError{ErrorCode::StorageFailed,"locate LocalAppData",status};
 return std::filesystem::path(raw)/L"UniShare";
}
// Only the exact default configuration selects factory defaults. Every other
// Initialize configuration replaces them wholesale; there is no field merging.
bool IsDefault(const SdkConfig& c){
 SdkConfig d;
 return c.device_name.empty()&&c.max_connections==d.max_connections&&c.max_pending_requests==d.max_pending_requests&&
 c.max_buffer_bytes==d.max_buffer_bytes&&c.max_text_bytes==d.max_text_bytes&&c.max_image_bytes==d.max_image_bytes&&
 c.max_file_bytes==d.max_file_bytes&&c.control_frame_bytes==d.control_frame_bytes&&c.data_chunk_bytes==d.data_chunk_bytes&&
 c.request_timeout==d.request_timeout&&c.offline_debounce==d.offline_debounce&&!c.callback_executor&&!c.permissions;
}
class PermissionPolicy final : public IPermissionAgent {
 std::shared_ptr<PermissionGate> gate_;
 std::shared_ptr<IPermissionAgent> host_;
public:
 PermissionPolicy(std::shared_ptr<PermissionGate> g,std::shared_ptr<IPermissionAgent> h):gate_(std::move(g)),host_(std::move(h)){}
 void RequestLocalNetwork(ResultCallback<void> cb) override {
  gate_->Wait([host=host_,cb=std::move(cb)](Result<void> r){
   if(!r.ok()||!host){cb(std::move(r));return;}
   try{host->RequestLocalNetwork(cb);}catch(...){cb(Result<void>::Failure(core::CurrentException()));}
  });
 }
};
class RetainedSdk final : public IShareSdk {
 std::shared_ptr<WindowsPlatform> platform_;
 SdkConfig defaults_;
 core::ShareSdk sdk_;
public:
 RetainedSdk(SdkConfig c,std::shared_ptr<WindowsPlatform> p):platform_(std::move(p)),defaults_(std::move(c)),sdk_(*platform_){}
 OperationId Initialize(SdkConfig c,ResultCallback<void> cb) override {
  if(IsDefault(c))c=defaults_;
  if(!c.callback_executor)c.callback_executor=platform_->DefaultExecutor();
  c.permissions=platform_->Permissions(c.permissions);
  return sdk_.Initialize(std::move(c),std::move(cb));
 }
 OperationId Start(ResultCallback<void> cb) override{return sdk_.Start(std::move(cb));}
 OperationId Stop(ResultCallback<void> cb) override{return sdk_.Stop(std::move(cb));}
 SdkState GetState() const override{return sdk_.GetState();}
 std::shared_ptr<IDeviceDiscovery> Discovery() const override{return sdk_.Discovery();}
 std::shared_ptr<IConnectionManager> Connections() const override{return sdk_.Connections();}
 std::shared_ptr<ITransferService> Transfers() const override{return sdk_.Transfers();}
 std::shared_ptr<IClipboardService> Clipboard() const override{return sdk_.Clipboard();}
};
}
WindowsPlatform::WindowsPlatform(WindowsOptions options,NativeBoundaries native)
 :permission_(std::make_shared<PermissionGate>()),dns_(std::move(native.dns)),identity_(StoragePath(std::move(options))),clipboard_(std::move(native.clipboard)),
 executor_(std::make_shared<core::SerialScheduler>()),network_(std::move(native.network)){}
WindowsPlatform::~WindowsPlatform(){SetNetworkChangeHandler({});dns_.Stop();sockets_.Stop();}
void WindowsPlatform::SetNetworkChangeHandler(std::function<void()> handler){
 std::lock_guard<std::mutex> lock(watch_mutex_);
 auto ready=permission_->Reset();
 if(handler)network_.Start(std::move(handler),std::move(ready));
 else {network_.Stop();ready(Result<void>::Failure({ErrorCode::Cancelled,"network watch stopped",ERROR_CANCELLED}));}
}
std::shared_ptr<IPermissionAgent> WindowsPlatform::Permissions(std::shared_ptr<IPermissionAgent> host){return std::make_shared<PermissionPolicy>(permission_,std::move(host));}
std::unique_ptr<IShareSdk> CreateRetainedSdk(SdkConfig c,std::shared_ptr<WindowsPlatform> p){return std::make_unique<RetainedSdk>(std::move(c),std::move(p));}
}
namespace unishare {
std::unique_ptr<IShareSdk> CreateWindowsShareSdk(SdkConfig config,WindowsOptions options){
 return windows::CreateRetainedSdk(std::move(config),std::make_shared<windows::WindowsPlatform>(std::move(options)));
}
}
