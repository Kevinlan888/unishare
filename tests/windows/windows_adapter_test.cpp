#include "unishare/windows.hpp"
#include "unishare/windows.h"
#include "unishare/c_api_bridge.hpp"
#include "platform/windows/adapter.hpp"
#include <future>
#include <atomic>
#include <iostream>
#include <filesystem>
#include <new>

#include <cstdlib>
// Fail allocations only on the teardown thread; native worker queues stay real.
thread_local bool failTeardownAllocations=false;
thread_local bool failNextTeardownAllocation=false;
void* operator new(std::size_t size){
 if(failNextTeardownAllocation){failNextTeardownAllocation=false;throw std::bad_alloc();}
 if(failTeardownAllocations)throw std::bad_alloc();
 if(auto p=std::malloc(size?size:1))return p;throw std::bad_alloc();
}
void operator delete(void* p) noexcept{std::free(p);}
void operator delete(void* p,std::size_t) noexcept{std::free(p);}
using namespace unishare;
#define CHECK(x) do {if(!(x)){std::cerr<<"CHECK failed: " #x<<" at "<<__LINE__<<std::endl;std::exit(1);}} while(0)
template<class T> struct Completion {
 std::promise<Result<T>> p;std::future<Result<T>> f=p.get_future();
 auto Callback(){return [this](Result<T> r){p.set_value(std::move(r));};}
 auto Get(){CHECK(f.wait_for(std::chrono::seconds(10))==std::future_status::ready);return f.get();}
};
void ActivePortsTeardown();
void AllocationFailureTeardown();
void AbiStartStop();
void ConcurrentPlatformWatch();
void AssembledTransfer();
void StartGates();
void PublicAbi();
void WatchFailure();
int main(){std::set_terminate([]{std::_Exit(86);});AllocationFailureTeardown();ActivePortsTeardown();AbiStartStop();ConcurrentPlatformWatch();AssembledTransfer();StartGates();PublicAbi();WatchFailure();
 auto root=std::filesystem::current_path()/("adapter-test-"+std::to_string(GetCurrentProcessId()));
 WindowsOptions options;options.identity_directory=root;
 auto platform=std::make_shared<windows::WindowsPlatform>(options);
 SdkConfig config;config.device_name="Windows assembly";
 auto sdk=windows::CreateRetainedSdk(config,platform);
 CHECK(sdk!=nullptr);CHECK(sdk->GetState()==SdkState::Uninitialized);
 Completion<void> init;sdk->Initialize({},init.Callback());CHECK(init.Get().ok());
 auto identity=platform->IdentityStore().LoadIdentity();CHECK(identity.ok());CHECK(identity.value().has_value());
 sdk.reset();platform.reset();
 auto restarted=std::make_shared<windows::WindowsPlatform>(options);
 auto identity2=restarted->IdentityStore().LoadIdentity();CHECK(identity2.ok());CHECK(identity2.value()->id==identity.value()->id);
 std::cout<<"Windows adapter tests passed\n";
}
struct TestDns : windows::DnsApi {
 std::atomic<unsigned> port{0};DWORD error=0;
 Result<std::wstring> HostName() override{return Result<std::wstring>::Success(L"adapter.local.");}
 Result<std::vector<ULONG>> Interfaces() override{return Result<std::vector<ULONG>>::Success({1});}
 DWORD Register(DNS_SERVICE_REGISTER_REQUEST* r,DNS_SERVICE_CANCEL*) override {
  port=r->pServiceInstance->wPort;
  if(error)return error;
  r->pRegisterCompletionCallback(0,r->pQueryContext,nullptr);return DNS_REQUEST_PENDING;
 }
 DWORD Deregister(DNS_SERVICE_REGISTER_REQUEST*) override{return 0;}
 // No native browse is retained by this fixture.
 DWORD Browse(DNS_SERVICE_BROWSE_REQUEST*,DNS_SERVICE_CANCEL*) override{return ERROR_NO_MORE_ITEMS;}
};
struct DeniedNetwork : windows::NetworkChangeApi {
 DWORD Register(PIPINTERFACE_CHANGE_CALLBACK,void*,HANDLE*) override{return ERROR_ACCESS_DENIED;}
};
void WatchFailure(){
 auto dns=std::make_shared<TestDns>();windows::NativeBoundaries native;native.dns=dns;native.network=std::make_shared<DeniedNetwork>();
 WindowsOptions options;options.identity_directory=std::filesystem::current_path()/("watch-test-"+std::to_string(GetCurrentProcessId()));
 auto platform=std::make_shared<windows::WindowsPlatform>(options,native);
 SdkConfig config;config.device_name="watch failure";auto sdk=windows::CreateRetainedSdk(config,platform);
 Completion<void> init;sdk->Initialize({},init.Callback());CHECK(init.Get().ok());
 Completion<void> start;sdk->Start(start.Callback());auto r=start.Get();CHECK(!r.ok());CHECK(r.error().platform_error==ERROR_ACCESS_DENIED);CHECK(dns->port==0);
}

void PublicAbi(){
 auto path=(std::filesystem::current_path()/("abi-test-"+std::to_string(GetCurrentProcessId()))).u8string();
 usdk_platform_handle platform=0;usdk_span directory{reinterpret_cast<const uint8_t*>(path.data()),path.size()};
 CHECK(usdk_windows_platform_create(directory,&platform).code==0);CHECK(platform!=0);
 usdk_config config;CHECK(usdk_config_init(USDK_ABI_VERSION,&config).code==0);
 const std::string name="C ABI Windows";config.device_name={reinterpret_cast<const uint8_t*>(name.data()),name.size()};
 std::promise<usdk_error> initialized;auto ready=initialized.get_future();usdk_handle sdk=0;usdk_operation_id operation=0;
 auto callback=[](void* context,const usdk_result* result){static_cast<std::promise<usdk_error>*>(context)->set_value(result->error);};
 CHECK(usdk_create(platform,&config,callback,&initialized,&sdk,&operation).code==0);
 CHECK(usdk_platform_release(platform).code==0);
 CHECK(ready.wait_for(std::chrono::seconds(10))==std::future_status::ready);CHECK(ready.get().code==0);
 uint32_t state;CHECK(usdk_get_state(sdk,&state).code==0);CHECK(state==1);
 std::promise<usdk_error> stopped;auto stop=stopped.get_future();CHECK(usdk_stop(sdk,callback,&stopped,&operation).code==0);
 CHECK(stop.wait_for(std::chrono::seconds(10))==std::future_status::ready);CHECK(stop.get().code==0);
 CHECK(usdk_destroy(sdk).code==0);CHECK(usdk_abi_version()==1);
 auto file=std::filesystem::current_path()/("handle-test-"+std::to_string(GetCurrentProcessId()));
 HANDLE handle=CreateFileW(file.c_str(),GENERIC_READ|GENERIC_WRITE|DELETE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,CREATE_ALWAYS,0,nullptr);CHECK(handle!=INVALID_HANDLE_VALUE);
  usdk_stream_handle destinationToken=0;CHECK(usdk_windows_open_destination(handle,&destinationToken).code==0);CHECK(destinationToken!=0);
 // Release of an unused destination stream aborts and deletes staging, so use
 // a distinct file for the C++ path below.
 CHECK(usdk_stream_release(destinationToken).code==0);CloseHandle(handle);
 file+=L"-cpp";handle=CreateFileW(file.c_str(),GENERIC_READ|GENERIC_WRITE|DELETE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,CREATE_ALWAYS,0,nullptr);CHECK(handle!=INVALID_HANDLE_VALUE);
 auto destination=OpenWindowsDestination(handle);CHECK(destination.ok());CloseHandle(handle);
 Completion<void> write;destination.value()->Write({1,2,3},write.Callback());CHECK(write.Get().ok());destination.value()->Close();
 handle=CreateFileW(file.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);CHECK(handle!=INVALID_HANDLE_VALUE);
  usdk_stream_handle sourceToken=0;CHECK(usdk_windows_open_source(handle,&sourceToken).code==0);CHECK(sourceToken!=0);CHECK(usdk_stream_release(sourceToken).code==0);
 auto source=OpenWindowsSource(handle);CHECK(source.ok());CloseHandle(handle);
 Completion<std::vector<uint8_t>> read;source.value()->Read(3,read.Callback());CHECK(read.Get().value()==std::vector<uint8_t>({1,2,3}));source.value()->Close();
}


struct DelayedNetwork : windows::NetworkChangeApi {
 std::promise<void> entered;std::promise<void> release;std::shared_future<void> released=release.get_future().share();
 DWORD Register(PIPINTERFACE_CHANGE_CALLBACK,void*,HANDLE* handle) override{entered.set_value();released.wait();*handle=reinterpret_cast<HANDLE>(1);return 0;}
 DWORD Cancel(HANDLE) override{return 0;}
};
struct DeniedPermission : IPermissionAgent {
 void RequestLocalNetwork(ResultCallback<void> cb) override{cb(Result<void>::Failure({ErrorCode::PermissionDenied,"host policy",1234}));}
};
void StartGates(){
 auto dns=std::make_shared<TestDns>();auto network=std::make_shared<DelayedNetwork>();windows::NativeBoundaries native;native.dns=dns;native.network=network;
 WindowsOptions options;options.identity_directory=std::filesystem::current_path()/("gates-test-"+std::to_string(GetCurrentProcessId()));
 auto p=std::make_shared<windows::WindowsPlatform>(options,native);std::weak_ptr<windows::WindowsPlatform> weak=p;
 SdkConfig config;config.device_name="gates";auto sdk=windows::CreateRetainedSdk(config,p);p.reset();CHECK(!weak.expired());
 Completion<void> init;sdk->Initialize({},init.Callback());CHECK(init.Get().ok());
 auto entered=network->entered.get_future();Completion<void> start;sdk->Start(start.Callback());
 CHECK(entered.wait_for(std::chrono::seconds(3))==std::future_status::ready);CHECK(dns->port==0);
 CHECK(sdk->GetState()==SdkState::Initialized);CHECK(start.f.wait_for(std::chrono::milliseconds(20))==std::future_status::timeout);
 network->release.set_value();CHECK(start.Get().ok());CHECK(dns->port!=0);CHECK(sdk->GetState()==SdkState::Running);
 Completion<void> stop;sdk->Stop(stop.Callback());CHECK(stop.Get().ok());sdk.reset();CHECK(weak.expired());
 // Host denial and native publication/firewall denial preserve their raw codes.
 for(bool host:{true,false}){
  native.network=std::make_shared<windows::NetworkChangeApi>();dns->port=0;dns->error=host?0:ERROR_ACCESS_DENIED;
  config.permissions=host?std::make_shared<DeniedPermission>():nullptr;
  auto owner=std::make_shared<windows::WindowsPlatform>(options,native);auto denied=windows::CreateRetainedSdk(config,owner);
  Completion<void> initialized;denied->Initialize({},initialized.Callback());CHECK(initialized.Get().ok());
  Completion<void> began;denied->Start(began.Callback());auto r=began.Get();CHECK(!r.ok());CHECK(r.error().code==ErrorCode::PermissionDenied);CHECK(r.error().platform_error==(host?1234:ERROR_ACCESS_DENIED));
  CHECK(denied->GetState()==SdkState::Stopped);if(host)CHECK(dns->port==0);
 }
}

#include "dns_bus.hpp"
#include <fstream>
#include <thread>
struct MemoryClipboard : windows::ClipboardBoundary {
 std::vector<windows::ClipboardData> content;
 Result<std::vector<windows::ClipboardData>> Read() override{return Result<std::vector<windows::ClipboardData>>::Success(content);}
 Result<void> Write(std::vector<windows::ClipboardData> v) override{content=std::move(v);return Result<void>::Success();}
};
template<class F> void Eventually(F f){
 auto end=std::chrono::steady_clock::now()+std::chrono::seconds(10);
 while(!f()){CHECK(std::chrono::steady_clock::now()<end);std::this_thread::sleep_for(std::chrono::milliseconds(10));}
}
void AssembledTransfer(){
 auto bus=std::make_shared<test::DnsBus>();windows::NativeBoundaries native;native.dns=bus;native.clipboard=std::make_shared<MemoryClipboard>();
 auto root=std::filesystem::current_path()/("integration-"+std::to_string(GetCurrentProcessId()));std::filesystem::create_directory(root);
 auto pa=std::make_shared<windows::WindowsPlatform>(WindowsOptions{root/"a"},native);
 auto pb=std::make_shared<windows::WindowsPlatform>(WindowsOptions{root/"b"},native);
 SdkConfig ca;ca.device_name="Windows A";ca.offline_debounce=std::chrono::seconds(0);SdkConfig cb=ca;cb.device_name="Windows B";
 auto a=windows::CreateRetainedSdk(ca,pa);auto b=windows::CreateRetainedSdk(cb,pb);
 Completion<void> ai,bi;a->Initialize({},ai.Callback());b->Initialize({},bi.Callback());CHECK(ai.Get().ok());CHECK(bi.Get().ok());
 Completion<void> as,bs;a->Start(as.Callback());b->Start(bs.Callback());CHECK(as.Get().ok());CHECK(bs.Get().ok());
 Eventually([&]{return a->Discovery()->GetDevices().size()==1&&b->Discovery()->GetDevices().size()==1;});
 auto peer=a->Discovery()->GetDevices().front();CHECK(peer.name=="Windows B");CHECK(peer.port!=0);CHECK(peer.addresses==std::vector<std::string>{"127.0.0.1"});
 Completion<PairingRequest> ar,br;
 a->Connections()->SetPairingRequestHandler([&](auto r){ar.Callback()(Result<PairingRequest>::Success(r));});
 b->Connections()->SetPairingRequestHandler([&](auto r){br.Callback()(Result<PairingRequest>::Success(r));});
 Completion<void> connected;a->Connections()->Connect(peer.id,connected.Callback());auto apr=ar.Get().value(),bpr=br.Get().value();CHECK(apr.verification_code==bpr.verification_code);CHECK(apr.verification_code.size()==6);
 Completion<void> ac,bc;a->Connections()->ConfirmPairing(apr.id,true,ac.Callback());b->Connections()->ConfirmPairing(bpr.id,true,bc.Callback());CHECK(ac.Get().ok());CHECK(bc.Get().ok());CHECK(connected.Get().ok());
 CHECK(a->Connections()->GetTrustedDevices().size()==1);CHECK(b->Connections()->GetTrustedDevices().size()==1);
 std::atomic<int> offers{0},received{0};
 b->Transfers()->SetIncomingOfferHandler([&](auto offer){
  auto path=root/(offer.content_type==ContentType::Text?"text":"file");
  HANDLE h=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE|DELETE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,CREATE_ALWAYS,0,nullptr);CHECK(h!=INVALID_HANDLE_VALUE);
  auto sink=OpenWindowsDestination(h);CloseHandle(h);CHECK(sink.ok());++offers;
  b->Transfers()->Accept(offer.request_id,sink.value(),[](auto r){CHECK(r.ok());});
 });
 b->Transfers()->SetResultHandler([&](auto r){CHECK(r.result.ok());++received;});
 ShareContent content;content.text="explicit clipboard text";Completion<void> wrote;a->Clipboard()->WriteContent(content,wrote.Callback());CHECK(wrote.Get().ok());
 Completion<ShareContent> clip;a->Clipboard()->ReadShareableContent(clip.Callback());auto text=clip.Get();CHECK(text.ok());CHECK(text.value().text==content.text);
 Completion<TransferId> sent;a->Transfers()->SendText(peer.id,text.value().text,sent.Callback());CHECK(sent.Get().ok());
 auto sourcePath=root/"source";{std::ofstream file(sourcePath,std::ios::binary);file<<std::string(300000,'W');}
 HANDLE h=CreateFileW(sourcePath.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);CHECK(h!=INVALID_HANDLE_VALUE);auto source=OpenWindowsSource(h);CloseHandle(h);CHECK(source.ok());
 Completion<TransferId> file;a->Transfers()->SendFile(peer.id,{"test.bin","application/octet-stream",300000,source.value()},file.Callback());CHECK(file.Get().ok());
 Eventually([&]{return received==2;});CHECK(offers==2);
 auto read=[](const auto& path){std::ifstream input(path,std::ios::binary);return std::string(std::istreambuf_iterator<char>(input),{});};
 CHECK(read(root/"text")==content.text);CHECK(read(root/"file")==std::string(300000,'W'));
 Completion<void> stopped;b->Stop(stopped.Callback());CHECK(stopped.Get().ok());Eventually([&]{return a->Discovery()->GetDevices().empty();});
 b.reset();cb.device_name="Windows B renamed";b=windows::CreateRetainedSdk(cb,pb);Completion<void> renamed;b->Initialize({},renamed.Callback());CHECK(renamed.Get().ok());Completion<void> again;b->Start(again.Callback());CHECK(again.Get().ok());
 Eventually([&]{auto devices=a->Discovery()->GetDevices();return devices.size()==1&&devices[0].name=="Windows B renamed";});CHECK(a->Discovery()->GetDevices()[0].id==peer.id);
 Completion<void> astop,bstop;a->Stop(astop.Callback());b->Stop(bstop.Callback());CHECK(astop.Get().ok());CHECK(bstop.Get().ok());
 a.reset();b.reset();
}

struct ImmediateNetwork : windows::NetworkChangeApi {
 DWORD Register(PIPINTERFACE_CHANGE_CALLBACK,void*,HANDLE* h) override{*h=reinterpret_cast<HANDLE>(1);return 0;}
 DWORD Cancel(HANDLE) override{return 0;}
};
void ConcurrentPlatformWatch(){
 windows::NativeBoundaries native;native.network=std::make_shared<ImmediateNetwork>();
 WindowsOptions options;options.identity_directory=std::filesystem::current_path()/"concurrent-watch";
 windows::WindowsPlatform p(options,native);
 for(int iteration=0;iteration<50;++iteration){
  std::vector<std::thread> threads;std::atomic<bool> start{false};
  for(int i=0;i<16;++i)threads.emplace_back([&]{while(!start.load())std::this_thread::yield();p.SetNetworkChangeHandler([]{});});
  start=true;for(auto& thread:threads)thread.join();
  Completion<void> ready;p.Permissions()->RequestLocalNetwork(ready.Callback());CHECK(ready.Get().ok());p.SetNetworkChangeHandler({});
 }
}

void AbiStartStop(){
 auto dns=std::make_shared<TestDns>();windows::NativeBoundaries native;native.dns=dns;
 WindowsOptions options;options.identity_directory=std::filesystem::current_path()/("abi-start-"+std::to_string(GetCurrentProcessId()));
 auto platform=std::make_shared<windows::WindowsPlatform>(options,native);usdk_platform_handle handle;
 CHECK(abi::RegisterPlatform(platform,platform->DefaultExecutor(),platform->Permissions(),&handle).code==0);
 usdk_config config;CHECK(usdk_config_init(1,&config).code==0);const std::string name="ABI start";config.device_name={reinterpret_cast<const uint8_t*>(name.data()),name.size()};
 auto callback=[](void* context,const usdk_result* result){static_cast<std::promise<usdk_error>*>(context)->set_value(result->error);};
 auto wait=[](auto& future){CHECK(future.wait_for(std::chrono::seconds(10))==std::future_status::ready);CHECK(future.get().code==0);};
 std::promise<usdk_error> initialized;auto init=initialized.get_future();usdk_handle sdk;usdk_operation_id operation;
 CHECK(usdk_create(handle,&config,callback,&initialized,&sdk,&operation).code==0);CHECK(usdk_platform_release(handle).code==0);platform.reset();wait(init);
 std::promise<usdk_error> started;auto start=started.get_future();CHECK(usdk_start(sdk,callback,&started,&operation).code==0);wait(start);CHECK(dns->port!=0);
 std::promise<usdk_error> stopped;auto stop=stopped.get_future();CHECK(usdk_stop(sdk,callback,&stopped,&operation).code==0);wait(stop);CHECK(usdk_destroy(sdk).code==0);
}
void AllocationFailureTeardown(){
 WindowsOptions options;options.identity_directory=std::filesystem::current_path()/"teardown-identity";
 auto platform=std::make_unique<windows::WindowsPlatform>(options);
 auto permission=platform->Permissions();
 failTeardownAllocations=true;
 platform.reset(); // No gate allocation is needed to retire an unused platform.
 failTeardownAllocations=false;
 Completion<void> retired;permission->RequestLocalNetwork(retired.Callback());
 auto result=retired.Get();CHECK(!result.ok());CHECK(result.error().code==ErrorCode::Cancelled);
}

struct TeardownDns : TestDns {
 std::atomic<int> deregistered{0};
 DWORD Deregister(DNS_SERVICE_REGISTER_REQUEST*) override{++deregistered;return 0;}
};
struct TeardownNetwork : DelayedNetwork {
 std::atomic<int> cancelled{0};
 DWORD Cancel(HANDLE) override{++cancelled;return 0;}
};
void ActivePortsTeardown(){
 auto dns=std::make_shared<TeardownDns>();auto network=std::make_shared<TeardownNetwork>();windows::NativeBoundaries native;native.dns=dns;native.network=network;
 WindowsOptions options;options.identity_directory=std::filesystem::current_path()/"throwing-teardown";
 auto platform=std::make_unique<windows::WindowsPlatform>(options,native);
  auto entered=network->entered.get_future();platform->SetNetworkChangeHandler([]{});
 CHECK(entered.wait_for(std::chrono::seconds(3))==std::future_status::ready);
 auto permission=platform->Permissions();std::atomic<bool> called{false};
 permission->RequestLocalNetwork([&](auto){called=true;});

 ServiceRecord record;record.instance="teardown";record.service_type="_share._tcp.local.";record.port=1234;
 Completion<void> published;platform->DnsSd().Publish(record,published.Callback());CHECK(published.Get().ok());
 Completion<uint16_t> bound;Completion<std::shared_ptr<ISocket>> accepted,connected;
 platform->Sockets().ListenBound(0,bound.Callback(),[&](auto r){if(r.ok())accepted.Callback()(r);});auto port=bound.Get();CHECK(port.ok());
 platform->Sockets().Connect("127.0.0.1",port.value(),connected.Callback());auto client=connected.Get();CHECK(client.ok());auto socket=accepted.Get();CHECK(socket.ok());
 Completion<std::vector<uint8_t>> pending;socket.value()->Read(1,pending.Callback());
  // Fail native-watch cancellation submission, then let the blocked registration
 // finish: its stopped branch drains its own handle. Other ports must still stop.
 failNextTeardownAllocation=true;platform.reset();CHECK(!failNextTeardownAllocation);CHECK(!called);
 network->release.set_value();
 auto result=pending.Get();CHECK(!result.ok());CHECK(result.error().code==ErrorCode::Cancelled);
 Eventually([&]{return dns->deregistered==1&&network->cancelled==1;});CHECK(!called);
}
