#import <UIKit/UIKit.h>
#import <Security/Security.h>
#include <dns_sd.h>
#include <arpa/inet.h>
#include "Ports.hpp"
#include <future>
#include <thread>
#include <cstdio>
#include <atomic>

using namespace unishare;
using namespace unishare::ios;
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %s:%d %s\n",__FILE__,__LINE__,#x); throw std::runtime_error(#x); } } while(0)
template<class T, class F> Result<T> Await(F f) {
 auto p=std::make_shared<std::promise<Result<T>>>(); auto result=p->get_future();
 f([p](Result<T> r){p->set_value(std::move(r));});
 CHECK(result.wait_for(std::chrono::seconds(10))==std::future_status::ready);
 return result.get();
}
static void Identity() {
 NSString* service=[@"com.unishare.ports." stringByAppendingString:NSUUID.UUID.UUIDString];
 auto a=MakeIdentityStore(service); CHECK(a);
 auto initial=a->LoadIdentity();
 if(!initial.ok())fprintf(stderr,"Keychain initial error code=%u platform=%lld\n",static_cast<unsigned>(initial.error().code),static_cast<long long>(initial.error().platform_error.value_or(0)));
 CHECK(initial.ok()); CHECK(!initial.value());
 IdentityMaterial m; m.id.bytes[0]=42; m.private_key={1,2,3}; m.certificate={4,5,6};
 CHECK(a->SaveIdentity(m).ok()); CHECK(!a->SaveIdentity(m).ok());
 auto b=MakeIdentityStore(service); auto loaded=b->LoadIdentity(); CHECK(loaded.ok());
 CHECK(loaded.value()->id==m.id); CHECK(loaded.value()->private_key==m.private_key);
 TrustedDevice trust{m.id,{}}; CHECK(a->SaveTrust(trust).ok()); CHECK(b->SaveTrust(trust).ok());
 trust.public_key_fingerprint[0]=9; auto changed=b->SaveTrust(trust);
 CHECK(!changed.ok()); CHECK(changed.error().code==ErrorCode::IdentityChanged);
 CHECK(a->LoadTrust().value().size()==1); CHECK(b->RemoveTrust(m.id).ok()); CHECK(b->RemoveTrust(m.id).ok());
 NSDictionary* query=@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,
                       (__bridge id)kSecAttrService:service,(__bridge id)kSecAttrAccount:@"identity"};
 CHECK(SecItemUpdate((__bridge CFDictionaryRef)query,(__bridge CFDictionaryRef)@{(__bridge id)kSecValueData:[NSData dataWithBytes:"bad" length:3]})==errSecSuccess);
 CHECK(!a->LoadIdentity().ok());
 SecItemDelete((__bridge CFDictionaryRef)@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,(__bridge id)kSecAttrService:service});
}
static void Files() {
 auto streams=MakeStreams(); CHECK(streams);
 NSURL* url=[NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString]];
 CHECK([[NSData data] writeToURL:url atomically:YES]);
 auto token=streams->Register(url,true); CHECK(token.ok());
 auto opened=streams->OpenDestination(token.value()); CHECK(opened.ok()); auto out=opened.value();
 CHECK(!streams->OpenDestination(token.value()).ok());
 CHECK(Await<void>([&](auto cb){out->Write({1,2,3,4},cb);}).ok()); out->Close();
 auto source=streams->Register(url,false); CHECK(source.ok()); auto in=streams->OpenSource(source.value()); CHECK(in.ok());
 auto read=Await<std::vector<uint8_t>>([&](auto cb){in.value()->Read(100,cb);}); CHECK(read.ok()); CHECK(read.value()==std::vector<uint8_t>({1,2,3,4}));
 CHECK(!streams->Register(url,true).ok()); in.value()->Close();
 [[NSFileManager defaultManager] removeItemAtURL:url error:nil]; CHECK([[NSData data] writeToURL:url atomically:YES]);
 auto aborted=streams->Register(url,true); CHECK(aborted.ok()); auto dest=streams->OpenDestination(aborted.value()); CHECK(dest.ok());
 dest.value()->AbortAndCleanup(); CHECK(![[NSFileManager defaultManager] fileExistsAtPath:url.path]);
 CHECK(!streams->OpenSource(url.path.UTF8String).ok());
}
static void Sockets() {
 auto factory=MakeSockets(); CHECK(factory);
 auto accepted=std::make_shared<std::promise<std::shared_ptr<ISocket>>>(); auto peer=accepted->get_future();
 auto ready=Await<uint16_t>([&](auto cb){factory->ListenBound(0,cb,[accepted](auto r){if(r.ok())accepted->set_value(r.value());});}); CHECK(ready.ok()); CHECK(ready.value());
 auto client=Await<std::shared_ptr<ISocket>>([&](auto cb){factory->Connect("127.0.0.1",ready.value(),cb);}); CHECK(client.ok());
 CHECK(peer.wait_for(std::chrono::seconds(10))==std::future_status::ready); auto server=peer.get(); CHECK(server->RemoteEndpoint()=="127.0.0.1");
 CHECK(Await<void>([&](auto cb){client.value()->Write({7,8},cb);}).ok());
 auto bytes=Await<std::vector<uint8_t>>([&](auto cb){server->Read(10,cb);}); CHECK(bytes.ok()); CHECK(bytes.value()==std::vector<uint8_t>({7,8}));
 auto stopped=Await<std::vector<uint8_t>>([&](auto cb){server->Read(10,cb); factory->Stop();}); CHECK(!stopped.ok()); CHECK(stopped.error().code==ErrorCode::Cancelled);
 CHECK(!Await<std::shared_ptr<ISocket>>([&](auto cb){factory->Connect("localhost",1,cb);}).ok());
 auto accepted6=std::make_shared<std::promise<std::shared_ptr<ISocket>>>(); auto peer6=accepted6->get_future();
 auto ready6=Await<uint16_t>([&](auto cb){factory->ListenBound(0,cb,[accepted6](auto r){if(r.ok())accepted6->set_value(r.value());});}); CHECK(ready6.ok());
 auto client6=Await<std::shared_ptr<ISocket>>([&](auto cb){factory->Connect("::1",ready6.value(),cb);}); CHECK(client6.ok());
 CHECK(peer6.wait_for(std::chrono::seconds(10))==std::future_status::ready);auto server6=peer6.get(); CHECK(server6->RemoteEndpoint()=="::1");
 auto count=std::make_shared<std::atomic<int>>(0);
 auto cancelled=Await<std::vector<uint8_t>>([&](auto cb){auto operation=server6->Read(10,[count,cb](auto r){++*count;cb(r);});server6->Cancel(operation);});
 CHECK(!cancelled.ok());CHECK(cancelled.error().code==ErrorCode::Cancelled);factory->Stop();
 std::this_thread::sleep_for(std::chrono::milliseconds(150));CHECK(*count==1);
}
static void ClipboardAndLifecycle() {
 __block UIPasteboard* board;
 dispatch_sync(dispatch_get_main_queue(),^{board=[UIPasteboard pasteboardWithUniqueName];});
 auto clipboard=MakeClipboard(board); CHECK(clipboard);
 ShareContent text; text.text="hello \xe4\xb8\xad\xe6\x96\x87";
 CHECK(Await<void>([&](auto cb){clipboard->Write(text,cb);}).ok());
 auto read=Await<ShareContent>([&](auto cb){clipboard->Read(cb);}); CHECK(read.ok()); CHECK(read.value().text==text.text);
 __block NSData* png;
 dispatch_sync(dispatch_get_main_queue(),^{UIGraphicsBeginImageContextWithOptions(CGSizeMake(2,2),NO,1); [UIColor.redColor setFill]; UIRectFill(CGRectMake(0,0,2,2)); png=UIImagePNGRepresentation(UIGraphicsGetImageFromCurrentImageContext()); UIGraphicsEndImageContext();});
 ShareContent image; image.type=ContentType::Image; image.mime_type="image/png";
 image.image.assign((const uint8_t*)png.bytes,(const uint8_t*)png.bytes+png.length);
 CHECK(Await<void>([&](auto cb){clipboard->Write(image,cb);}).ok());
 auto picture=Await<ShareContent>([&](auto cb){clipboard->Read(cb);}); CHECK(picture.ok()); CHECK(picture.value().type==ContentType::Image); CHECK(!picture.value().image.empty());
 ShareContent mismatch=image; mismatch.mime_type="image/jpeg";
 CHECK(!Await<void>([&](auto cb){clipboard->Write(mismatch,cb);}).ok());
 ShareContent bad; bad.type=ContentType::File; CHECK(!Await<void>([&](auto cb){clipboard->Write(bad,cb);}).ok());
 auto platform=MakePlatform(@"com.unishare.ports.lifecycle",@{},board); CHECK(platform);
 platform->Foreground(true);
 auto denied=Await<void>([&](auto cb){platform->Permissions()->RequestLocalNetwork(cb);}); CHECK(!denied.ok()); CHECK(denied.error().code==ErrorCode::PermissionDenied);
 platform=MakePlatform(@"com.unishare.ports.lifecycle",@{@"NSLocalNetworkUsageDescription":@"Test",@"NSBonjourServices":@[@"_share._tcp"]},board);
 platform->Foreground(true);auto changes=std::make_shared<std::atomic<int>>(0);
 platform->SetNetworkChangeHandler([changes]{++*changes;});
 platform->Foreground(false);
 CHECK(*changes==1);platform->SetNetworkChangeHandler({});platform->Foreground(true);platform->Foreground(false);CHECK(*changes==1);
 auto background=Await<void>([&](auto cb){platform->Permissions()->RequestLocalNetwork(cb);}); CHECK(!background.ok()); CHECK(background.error().code==ErrorCode::NetworkLost);
 usdk_platform_handle handle=0; CHECK(Register(platform,&handle).code==0); CHECK(handle); CHECK(usdk_platform_release(handle).code==0);
 dispatch_sync(dispatch_get_main_queue(),^{[UIPasteboard removePasteboardWithName:board.name];});
}
static void Bonjour() {
 auto publisher=MakeBonjour(); auto browser=MakeBonjour(); CHECK(publisher); CHECK(browser);
 ServiceRecord record; record.instance="ports-"+std::string(NSUUID.UUID.UUIDString.UTF8String);
 record.service_type="_share._tcp.local."; record.port=42424; record.txt={{"name","Port test"}};
 auto found=std::make_shared<std::promise<ServiceRecord>>(); auto result=found->get_future();
 auto done=std::make_shared<std::atomic<bool>>(false);
 browser->BrowseEvents(record.service_type,[found,done,name=record.instance](auto r){
  if(r.ok()&&r.value().instance==name&&!done->exchange(true)) found->set_value(r.value());
 },[](auto){});
 auto published=Await<void>([&](auto cb){publisher->Publish(record,cb);}); CHECK(published.ok());
 CHECK(result.wait_for(std::chrono::seconds(15))==std::future_status::ready);
 auto resolved=result.get(); CHECK(resolved.port==42424); CHECK(!resolved.interface_id.empty()); CHECK(!resolved.addresses.empty()); CHECK(!resolved.txt_bytes.empty());
 publisher->Stop(); browser->Stop();
}
static void BonjourUpdates() {
 auto browser=MakeBonjour();CHECK(browser);
 std::string name="updates-"+std::string(NSUUID.UUID.UUIDString.UTF8String);
 struct Events {std::promise<ServiceRecord> initial,updated;std::promise<ServiceInstanceId> removed;std::string interface;bool first=false,changed=false,gone=false;std::atomic<bool> expectRemoval{false};};
 auto events=std::make_shared<Events>();auto initial=events->initial.get_future();auto updated=events->updated.get_future();auto removed=events->removed.get_future();
 browser->BrowseEvents("_share._tcp.local.",[events,name](auto result) {
  if(!result.ok()||result.value().instance!=name)return;auto record=result.value();
  if(!events->first){events->first=true;events->interface=record.interface_id;events->initial.set_value(record);}
  if(!events->changed&&record.txt_bytes==std::vector<uint8_t>({5,'v','=','n','e','w'})){events->changed=true;events->updated.set_value(record);}
 },[events,name](auto id){if(events->expectRemoval&&id.instance==name&&id.interface_id==events->interface&&!events->gone){events->gone=true;events->removed.set_value(id);}});
 struct Published {DNSServiceRef ref=nullptr;~Published(){if(ref)DNSServiceRefDeallocate(ref);}} publication;
 const uint8_t oldTxt[]={5,'v','=','o','l','d'};
 CHECK(DNSServiceRegister(&publication.ref,0,0,name.c_str(),"_share._tcp","local.",nullptr,htons(42425),sizeof(oldTxt),oldTxt,[](DNSServiceRef,DNSServiceFlags,DNSServiceErrorType,const char*,const char*,const char*,void*){},nullptr)==0);
 dispatch_queue_t queue=dispatch_queue_create("com.unishare.dns.fixture",DISPATCH_QUEUE_SERIAL);
 CHECK(DNSServiceSetDispatchQueue(publication.ref,queue)==0);
 CHECK(initial.wait_for(std::chrono::seconds(15))==std::future_status::ready);auto first=initial.get();CHECK(!first.interface_id.empty());
 const uint8_t newTxt[]={5,'v','=','n','e','w'};CHECK(DNSServiceUpdateRecord(publication.ref,nullptr,0,sizeof(newTxt),newTxt,0)==0);
 CHECK(updated.wait_for(std::chrono::seconds(15))==std::future_status::ready);CHECK(updated.get().port==42425);
 events->expectRemoval=true;DNSServiceRefDeallocate(publication.ref);publication.ref=nullptr;
 CHECK(removed.wait_for(std::chrono::seconds(15))==std::future_status::ready);CHECK(removed.get().interface_id==first.interface_id);browser->Stop();
}
static void BridgeOwnership() {
 NSString* service=[@"com.unishare.ports.bridge." stringByAppendingString:NSUUID.UUID.UUIDString];
 auto platform=MakePlatform(service,@{@"NSLocalNetworkUsageDescription":@"Test",@"NSBonjourServices":@[@"_share._tcp"]});
 platform->Foreground(true);std::weak_ptr<unishare::ios::Platform> weak=platform;
 usdk_platform_handle token=0;CHECK(Register(platform,&token).code==0);
 usdk_config config{};CHECK(usdk_config_init(1,&config).code==0);
 const char name[]="iOS port test";config.device_name={reinterpret_cast<const uint8_t*>(name),sizeof(name)-1};
 usdk_handle sdk=0;usdk_operation_id operation=0;std::promise<usdk_error> initialized;auto init=initialized.get_future();
 auto completion=[](void* context,const usdk_result* result){static_cast<std::promise<usdk_error>*>(context)->set_value(result->error);};
 CHECK(usdk_create(token,&config,completion,&initialized,&sdk,&operation).code==0);
 CHECK(usdk_platform_release(token).code==0);platform.reset();CHECK(!weak.expired());
 CHECK(init.wait_for(std::chrono::seconds(15))==std::future_status::ready);CHECK(init.get().code==0);
 std::promise<usdk_error> started;auto start=started.get_future();CHECK(usdk_start(sdk,completion,&started,&operation).code==0);
 CHECK(start.wait_for(std::chrono::seconds(15))==std::future_status::ready);CHECK(start.get().code==0);
 weak.lock()->Foreground(false);
 uint32_t state=0;
 for(int i=0;i<200;++i){CHECK(usdk_get_state(sdk,&state).code==0);if(state==3)break;std::this_thread::sleep_for(std::chrono::milliseconds(10));}
 CHECK(state==3);CHECK(usdk_destroy(sdk).code==0);
 SecItemDelete((__bridge CFDictionaryRef)@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,(__bridge id)kSecAttrService:service});
}
@interface TestApp : UIResponder <UIApplicationDelegate>
@end
@implementation TestApp
- (BOOL)application:(UIApplication*)app didFinishLaunchingWithOptions:(NSDictionary*)options {
 dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{
  int failures=0;
  for(auto test:{Identity,Files,Sockets,ClipboardAndLifecycle,Bonjour,BonjourUpdates,BridgeOwnership}) { @autoreleasepool {try {test();fprintf(stderr,"PASS port case\n");}catch(const std::exception& e){fprintf(stderr,"FAILED %s\n",e.what());++failures;}} }
  fprintf(stderr,"PORTS_RESULT failures=%d\n",failures); fflush(stderr); exit(failures?1:0);
 }); return YES;
}
@end
int main(int argc,char** argv) { @autoreleasepool {return UIApplicationMain(argc,argv,nil,NSStringFromClass(TestApp.class));} }
