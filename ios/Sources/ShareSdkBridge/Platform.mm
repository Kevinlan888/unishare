#include "Common.hpp"
#import <Network/Network.h>
namespace unishare::ios {
namespace {
struct Lifecycle {
 std::atomic<bool> foreground{false};NSDictionary* info;
 std::mutex mutex;std::function<void()> changed;
 void Notify() {std::function<void()> callback;{std::lock_guard<std::mutex> lock(mutex);callback=changed;}Deliver(callback);}
};
class Permission final : public IPermissionAgent {
 std::shared_ptr<Lifecycle> lifecycle_;std::shared_ptr<Queue> queue_;
public:
 Permission(std::shared_ptr<Lifecycle> l,std::shared_ptr<Queue> q):lifecycle_(std::move(l)),queue_(std::move(q)) {}
 void RequestLocalNetwork(ResultCallback<void> callback) override {
  auto state=lifecycle_;queue_->Post([state,callback] {
   if(!state->foreground){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::NetworkLost,"host is not foreground")));return;}
   id reason=state->info[@"NSLocalNetworkUsageDescription"];id services=state->info[@"NSBonjourServices"];
   if(![reason isKindOfClass:NSString.class]||![reason length]||![services isKindOfClass:NSArray.class]||![services containsObject:@"_share._tcp"])
    Deliver(callback,Result<void>::Failure(Failure(ErrorCode::PermissionDenied,"host must declare local network usage and _share._tcp")));
   else Deliver(callback,Result<void>::Success()); // Actual authorization occurs in Bonjour.
  });
 }
};
class NativePlatform final : public Platform,public std::enable_shared_from_this<NativePlatform> {
 std::shared_ptr<Lifecycle> lifecycle_=std::make_shared<Lifecycle>();
 std::shared_ptr<Queue> callbacks_=std::make_shared<Queue>("com.unishare.callbacks");
 std::shared_ptr<IDnsSd> dns_=MakeBonjour();std::shared_ptr<ISocketFactory> sockets_=MakeSockets();
 std::shared_ptr<IIdentityStore> identity_;std::shared_ptr<UrlStreams> streams_=MakeStreams();
 std::shared_ptr<IPlatformClipboard> clipboard_;std::shared_ptr<IPermissionAgent> permissions_;
 nw_path_monitor_t monitor_;id background_,foreground_;nw_path_t previous_;
public:
 NativePlatform(NSString* service,NSDictionary* info,UIPasteboard* board):identity_(MakeIdentityStore(service)),clipboard_(MakeClipboard(board)) {
  lifecycle_->info=[info copy];permissions_=std::make_shared<Permission>(lifecycle_,callbacks_);
 }
 void Observe() {
  std::weak_ptr<NativePlatform> weak=shared_from_this();
  auto initial=[&] {lifecycle_->foreground=UIApplication.sharedApplication.applicationState==UIApplicationStateActive;};
  if(NSThread.isMainThread)initial();else dispatch_sync(dispatch_get_main_queue(),^{initial();});
  background_=[NSNotificationCenter.defaultCenter addObserverForName:UIApplicationDidEnterBackgroundNotification object:nil queue:nil usingBlock:^(NSNotification*) {if(auto p=weak.lock())p->Foreground(false);}];
  foreground_=[NSNotificationCenter.defaultCenter addObserverForName:UIApplicationDidBecomeActiveNotification object:nil queue:nil usingBlock:^(NSNotification*) {if(auto p=weak.lock())p->Foreground(true);}];
  monitor_=nw_path_monitor_create();nw_path_monitor_set_queue(monitor_,callbacks_->Native());
  nw_path_monitor_set_update_handler(monitor_,^(nw_path_t path) {
   if(auto p=weak.lock()) {bool changed=p->previous_&&!nw_path_is_equal(p->previous_,path);p->previous_=path;if(changed&&p->lifecycle_->foreground)p->lifecycle_->Notify();}
  });nw_path_monitor_start(monitor_);
 }
 ~NativePlatform() {
  SetNetworkChangeHandler({});[NSNotificationCenter.defaultCenter removeObserver:background_];[NSNotificationCenter.defaultCenter removeObserver:foreground_];
  nw_path_monitor_cancel(monitor_);dns_->Stop();sockets_->Stop();
 }
 IDnsSd& DnsSd() override {return *dns_;}
 ISocketFactory& Sockets() override {return *sockets_;}
 IIdentityStore& IdentityStore() override {return *identity_;}
 IStreamFactory& Streams() override {return *streams_;}
 IPlatformClipboard& Clipboard() override {return *clipboard_;}
 std::shared_ptr<IExecutor> DefaultExecutor() override {return callbacks_;}
 unishare::Platform PlatformKind() const override {return unishare::Platform::Ios;}
 std::shared_ptr<IPermissionAgent> Permissions() override {return permissions_;}
 UrlStreams& FileUrls() override {return *streams_;}
 void Foreground(bool active) override {
  bool previous=lifecycle_->foreground.exchange(active);
  if(previous&&!active){dns_->Stop();sockets_->Stop();lifecycle_->Notify();}
 }
 void SetNetworkChangeHandler(std::function<void()> callback) override {std::lock_guard<std::mutex> lock(lifecycle_->mutex);lifecycle_->changed=std::move(callback);}
};
}
std::shared_ptr<Platform> MakePlatform(NSString* service,NSDictionary* info,UIPasteboard* board) {
 if(!board) {
  __block UIPasteboard* selected=nil;
  if(NSThread.isMainThread)selected=UIPasteboard.generalPasteboard;
  else dispatch_sync(dispatch_get_main_queue(),^{selected=UIPasteboard.generalPasteboard;});
  board=selected;
 }
 auto platform=std::make_shared<NativePlatform>(service,info,board);platform->Observe();return platform;
}
usdk_error Register(const std::shared_ptr<Platform>& platform,usdk_platform_handle* output) noexcept {
 if(!platform){if(output)*output=0;return {USDK_INVALID_INPUT,0,0};}
 return abi::RegisterPlatform(platform,platform->DefaultExecutor(),platform->Permissions(),output);
}
}
