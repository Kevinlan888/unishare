#include "platform/windows/dns_sd.hpp"
#include "platform/windows/network_change.hpp"
#include "platform/windows/sockets.hpp"
#include <condition_variable>
#include <thread>
#include <map>
#include <ws2tcpip.h>
#include <future>
#include <iostream>
#include <atomic>
#include <cstdlib>
#include <new>
// Test-only allocation instrumentation: catch repeated large allocations while
// the real nonblocking recv has no data, without changing the socket boundary.
std::atomic<bool> countReadAllocations{false};
std::atomic<int> readAllocations{0};
void* operator new(std::size_t size){
 if(countReadAllocations.load()&&size>=4*1024*1024&&size<=4*1024*1024+64)++readAllocations;
 if(auto memory=std::malloc(size?size:1))return memory;
 throw std::bad_alloc();
}
void operator delete(void* memory) noexcept {std::free(memory);}
void operator delete(void* memory,std::size_t) noexcept {std::free(memory);}
using namespace unishare;
using namespace unishare::windows;
#define CHECK(x) do { if(!(x)){std::cerr<<"CHECK failed: " #x<<" at "<<__LINE__<<std::endl;std::exit(1);} } while(0)
template<class T> struct Completion {
 std::promise<Result<T>> promise; std::future<Result<T>> future=promise.get_future(); std::atomic<int> calls{0};
 ResultCallback<T> Callback(){return [this](Result<T> r){CHECK(++calls==1);promise.set_value(std::move(r));};}
 Result<T> Get(){CHECK(future.wait_for(std::chrono::seconds(10))==std::future_status::ready);return future.get();}
};
void SocketTests(){
 SocketFactory factory; Completion<std::uint16_t> ready;Completion<std::shared_ptr<ISocket>> accepted,connected;
 factory.ListenBound(0,ready.Callback(),[&](auto r){if(r.ok()){CHECK(ready.calls==1);accepted.Callback()(r);}});
 auto bound=ready.Get();CHECK(bound.ok());CHECK(bound.value()!=0);
 factory.Connect("127.0.0.1",bound.value(),connected.Callback());
 auto c=connected.Get();auto a=accepted.Get();CHECK(c.ok());CHECK(a.ok());
 CHECK(c.value()->RemoteEndpoint()=="127.0.0.1");CHECK(a.value()->RemoteEndpoint()=="127.0.0.1");
 // A large write and small reads expose implementations that complete on a partial send.
 std::vector<std::uint8_t> payload(8*1024*1024);for(size_t i=0;i<payload.size();++i)payload[i]=static_cast<std::uint8_t>(i*17);
 Completion<void> written;c.value()->Write(payload,written.Callback());size_t offset=0;
 while(offset<payload.size()){Completion<std::vector<std::uint8_t>> read;a.value()->Read(8191,read.Callback());auto r=read.Get();CHECK(r.ok());CHECK(!r.value().empty());for(auto b:r.value())CHECK(b==payload[offset++]);}
 CHECK(written.Get().ok());
 Completion<std::vector<std::uint8_t>> cancel;auto op=a.value()->Read(1,cancel.Callback());a.value()->Cancel(op);auto cancelled=cancel.Get();CHECK(!cancelled.ok());CHECK(cancelled.error().code==ErrorCode::Cancelled);
 c.value()->Close();Completion<std::vector<std::uint8_t>> eof;a.value()->Read(1,eof.Callback());CHECK(eof.Get().value().empty());
 Completion<std::vector<std::uint8_t>> stopped;a.value()->Read(1,stopped.Callback());factory.Stop();auto stop=stopped.Get();CHECK(stop.ok()||stop.error().code==ErrorCode::Cancelled);
 Completion<std::vector<std::uint8_t>> late;a.value()->Read(1,late.Callback());CHECK(!late.Get().ok());
 Completion<std::shared_ptr<ISocket>> hostname;factory.Connect("localhost",1234,hostname.Callback());CHECK(!hostname.Get().ok());
 Completion<std::uint16_t> again;factory.ListenBound(0,again.Callback(),[](auto){});CHECK(again.Get().ok());factory.Stop();
}
void PausedRegistrationTest();
void PendingReadAllocationTest();
void MalformedDnsNameTest();
void ConcurrentWatchTest();
void MoreSocketTests();
void LiveDnsTests();
void NetworkTests();
void DnsTests();
int main(){PausedRegistrationTest();MalformedDnsNameTest();PendingReadAllocationTest();SocketTests();MoreSocketTests();DnsTests();NetworkTests();ConcurrentWatchTest();if(std::getenv("UNISHARE_LIVE_MDNS"))LiveDnsTests();std::cout<<"Windows network tests passed\n";}

struct FakeDns final : DnsApi {
 std::mutex mutex;std::condition_variable changed;
 struct B {DNS_SERVICE_BROWSE_REQUEST request;DNS_SERVICE_CANCEL* cancel;};
 struct R {DNS_SERVICE_RESOLVE_REQUEST request;DNS_SERVICE_CANCEL* cancel;};
 DNS_SERVICE_REGISTER_REQUEST* registration=nullptr;DNS_SERVICE_CANCEL* registrationCancel=nullptr;
 std::vector<B> browses;std::vector<R> resolves;int deregisters=0,cancelBrowses=0,cancelResolves=0,cancelRegisters=0,freed=0;
 DWORD registerResult=DNS_REQUEST_PENDING;std::vector<ULONG> interfaces{7,12};
 Result<std::vector<ULONG>> Interfaces() override{return Result<std::vector<ULONG>>::Success(interfaces);}
 Result<std::wstring> HostName() override{return Result<std::wstring>::Success(L"test-host.local.");}
 DWORD Register(DNS_SERVICE_REGISTER_REQUEST* r,DNS_SERVICE_CANCEL* c) override{std::lock_guard<std::mutex> l(mutex);registration=r;registrationCancel=c;changed.notify_all();return registerResult;}
 DWORD Deregister(DNS_SERVICE_REGISTER_REQUEST* r) override{{std::lock_guard<std::mutex> l(mutex);++deregisters;changed.notify_all();}r->pRegisterCompletionCallback(0,r->pQueryContext,nullptr);return DNS_REQUEST_PENDING;}
 DWORD Browse(DNS_SERVICE_BROWSE_REQUEST* r,DNS_SERVICE_CANCEL* c) override{std::lock_guard<std::mutex> l(mutex);browses.push_back({*r,c});changed.notify_all();return DNS_REQUEST_PENDING;}
 DWORD Resolve(DNS_SERVICE_RESOLVE_REQUEST* r,DNS_SERVICE_CANCEL* c) override{std::lock_guard<std::mutex> l(mutex);resolves.push_back({*r,c});changed.notify_all();return DNS_REQUEST_PENDING;}
 DWORD CancelRegister(DNS_SERVICE_CANCEL*) override{{std::lock_guard<std::mutex> l(mutex);++cancelRegisters;changed.notify_all();}return 0;}
 DWORD CancelBrowse(DNS_SERVICE_CANCEL* c) override{B b{};{std::lock_guard<std::mutex> l(mutex);++cancelBrowses;for(auto& item:browses)if(item.cancel==c)b=item;changed.notify_all();}b.request.pBrowseCallback(ERROR_CANCELLED,b.request.pQueryContext,nullptr);return 0;}
 DWORD CancelResolve(DNS_SERVICE_CANCEL*) override{std::lock_guard<std::mutex> l(mutex);++cancelResolves;changed.notify_all();return 0;}
 void FreeRecords(DNS_RECORD*) override{std::lock_guard<std::mutex> l(mutex);++freed;}
 void FreeInstance(DNS_SERVICE_INSTANCE* p) override{DnsServiceFreeInstance(p);std::lock_guard<std::mutex> l(mutex);++freed;}
 template<class P> void Wait(P predicate){std::unique_lock<std::mutex> l(mutex);CHECK(changed.wait_for(l,std::chrono::seconds(3),predicate));}
 void Ptr(size_t index,DWORD ttl){B b;{std::lock_guard<std::mutex> l(mutex);b=browses.at(index);}DNS_RECORD record{};record.wType=DNS_TYPE_PTR;record.dwTtl=ttl;record.Data.PTR.pNameHost=const_cast<wchar_t*>(L"peer._share._tcp.local.");b.request.pBrowseCallback(0,b.request.pQueryContext,&record);}
 void Resolved(size_t index,DWORD status=0){R r;{std::lock_guard<std::mutex> l(mutex);r=resolves.at(index);}const wchar_t* keys[]={L"id",L"name",L"platform",L"pv"};const wchar_t* values[]={L"123",L"Peer 世界",L"windows",L"1.0"};IP4_ADDRESS v4=htonl(0x7f000001);IP6_ADDRESS v6{};InetPtonW(AF_INET6,L"fe80::42",&v6);auto instance=DnsServiceConstructInstance(L"peer._share._tcp.local.",L"peer-host.local.",&v4,&v6,4321,0,0,4,keys,values);CHECK(instance);instance->dwInterfaceIndex=r.request.InterfaceIndex;r.request.pResolveCompletionCallback(status,r.request.pQueryContext,instance);}
};
void DnsTests(){
 auto native=std::make_shared<FakeDns>();DnsSd dns(native);ServiceRecord publish;publish.instance="self";publish.service_type="_share._tcp.local.";publish.port=4567;publish.txt={{"id","abc"},{"name","Desktop 世界"},{"platform","windows"},{"pv","1.0"}};
 Completion<void> published;dns.Publish(publish,published.Callback());
 // Failure here proves the adapter never reached native registration.
 native->Wait([&]{return native->registration!=nullptr;});CHECK(published.calls==0);
 auto request=native->registration;CHECK(request->unicastEnabled==FALSE);CHECK(request->pServiceInstance->wPort==4567);CHECK(std::wstring(request->pServiceInstance->pszInstanceName)==L"self._share._tcp.local.");CHECK(request->pServiceInstance->dwPropertyCount==4);CHECK(std::wstring(request->pServiceInstance->values[1])==L"Desktop 世界");
 request->pRegisterCompletionCallback(0,request->pQueryContext,nullptr);CHECK(published.Get().ok());
 std::mutex eventsMutex;std::condition_variable eventChanged;std::vector<ServiceRecord> found;std::vector<ServiceInstanceId> removed;std::vector<ShareError> errors;
 dns.BrowseEvents("_share._tcp.local.",[&](auto r){std::lock_guard<std::mutex> l(eventsMutex);if(r.ok())found.push_back(r.value());else errors.push_back(r.error());eventChanged.notify_all();},[&](auto id){std::lock_guard<std::mutex> l(eventsMutex);removed.push_back(id);eventChanged.notify_all();});
 auto waitEvents=[&](auto predicate){std::unique_lock<std::mutex> l(eventsMutex);CHECK(eventChanged.wait_for(l,std::chrono::seconds(3),predicate));};
 native->Wait([&]{return native->browses.size()==2;});CHECK(native->browses[0].request.InterfaceIndex==7);CHECK(native->browses[1].request.InterfaceIndex==12);
 native->Ptr(0,120);native->Ptr(1,120);native->Wait([&]{return native->resolves.size()==2;});native->Resolved(0);native->Resolved(1);waitEvents([&]{return found.size()==2;});
 {std::lock_guard<std::mutex> l(eventsMutex);CHECK(found[0].interface_id=="7");CHECK(found[1].interface_id=="12");CHECK(found[0].srv_target=="peer-host.local.");CHECK(found[0].port==4321);CHECK(found[0].addresses==std::vector<std::string>({"127.0.0.1","fe80::42%7"}));CHECK(found[0].txt[1].second=="Peer 世界");CHECK(!found[0].txt_bytes.empty());}
 native->Ptr(0,120);native->Wait([&]{return native->resolves.size()==3;});native->Ptr(0,0);waitEvents([&]{return removed.size()==1;});native->Resolved(2);
 native->Ptr(1,120);native->Wait([&]{return native->resolves.size()==4;});native->Resolved(3,ERROR_TIMEOUT);waitEvents([&]{return errors.size()==1;});
 {std::lock_guard<std::mutex> l(eventsMutex);CHECK(found.size()==2);CHECK(removed[0].interface_id=="7");CHECK(errors[0].platform_error==ERROR_TIMEOUT);}
 native->Ptr(1,120);native->Wait([&]{return native->resolves.size()==5;});dns.Stop();native->Wait([&]{return native->cancelResolves==1&&native->cancelBrowses==2&&native->deregisters==1;});native->Resolved(4);
 // Replacement generation uses the new actual interface list, not cached/fake IDs.
 native->interfaces={19};dns.BrowseEvents("_share._tcp.local.",[](auto){},[](auto){});native->Wait([&]{return native->browses.size()==3;});CHECK(native->browses[2].request.InterfaceIndex==19);dns.Stop();native->Wait([&]{return native->cancelBrowses==3;});
 auto failed=std::make_shared<FakeDns>();failed->registerResult=ERROR_ACCESS_DENIED;DnsSd denied(failed);Completion<void> denial;denied.Publish(publish,denial.Callback());auto result=denial.Get();CHECK(!result.ok());CHECK(result.error().platform_error==ERROR_ACCESS_DENIED);
 auto pending=std::make_shared<FakeDns>();auto owner=std::make_unique<DnsSd>(pending);Completion<void> cancelled;owner->Publish(publish,cancelled.Callback());pending->Wait([&]{return pending->registration!=nullptr;});owner.reset();CHECK(cancelled.Get().error().code==ErrorCode::Cancelled);pending->Wait([&]{return pending->cancelRegisters==1;});auto late=pending->registration;late->pRegisterCompletionCallback(0,late->pQueryContext,nullptr);pending->Wait([&]{return pending->deregisters==1;});CHECK(cancelled.calls==1);
}

struct FakeNetwork final : NetworkChangeApi {
 std::mutex mutex;std::condition_variable cv;PIPINTERFACE_CHANGE_CALLBACK callback=nullptr;void* context=nullptr;int cancelled=0;DWORD result=0;
 DWORD Register(PIPINTERFACE_CHANGE_CALLBACK cb,void* c,HANDLE* handle) override{std::lock_guard<std::mutex> l(mutex);callback=cb;context=c;*handle=reinterpret_cast<HANDLE>(1);return result;}
 DWORD Cancel(HANDLE handle) override{CHECK(handle==reinterpret_cast<HANDLE>(1));callback(context,nullptr,MibParameterNotification);std::lock_guard<std::mutex> l(mutex);++cancelled;cv.notify_all();return 0;}
 void WaitCancelled(){std::unique_lock<std::mutex> l(mutex);CHECK(cv.wait_for(l,std::chrono::seconds(3),[&]{return cancelled==1;}));}
 void Notify(){callback(context,nullptr,MibParameterNotification);}
};
void NetworkTests(){
 auto native=std::make_shared<FakeNetwork>();auto owner=std::make_unique<NetworkChange>(native);Completion<void> ready;std::promise<void> event;auto future=event.get_future();std::atomic<int> calls{0};
 owner->Start([&]{if(++calls==1)event.set_value();},ready.Callback());CHECK(ready.Get().ok());native->Notify();CHECK(future.wait_for(std::chrono::seconds(3))==std::future_status::ready);
 owner.reset();native->WaitCancelled();CHECK(calls==1);
 auto failed=std::make_shared<FakeNetwork>();failed->result=ERROR_ACCESS_DENIED;NetworkChange denied(failed);Completion<void> error;denied.Start([]{},error.Callback());auto r=error.Get();CHECK(!r.ok());CHECK(r.error().platform_error==ERROR_ACCESS_DENIED);
 // Exercise actual OS registration/unregistration without changing host network configuration.
 NetworkChange real;Completion<void> actual;real.Start([]{},actual.Callback());CHECK(actual.Get().ok());real.Stop();
}
void MoreSocketTests(){
 SocketFactory factory;Completion<std::uint16_t> ready;Completion<std::shared_ptr<ISocket>> accept,connect;
 factory.ListenBound(0,ready.Callback(),[&](auto r){if(r.ok())accept.Callback()(r);});auto port=ready.Get();CHECK(port.ok());
 factory.Connect("::1",port.value(),connect.Callback());auto client=connect.Get();CHECK(client.ok());auto server=accept.Get();CHECK(server.ok());CHECK(client.value()->RemoteEndpoint()=="::1");CHECK(server.value()->RemoteEndpoint()=="::1");
 Completion<std::vector<std::uint8_t>> pending;server.value()->Read(32,pending.Callback());factory.Stop();auto stopped=pending.Get();CHECK(!stopped.ok());CHECK(stopped.error().code==ErrorCode::Cancelled);
 // Stop cancels a blocked large send while keeping its moved buffer alive.
 SocketFactory other;Completion<std::uint16_t> listening;Completion<std::shared_ptr<ISocket>> accepted,connected;
 other.ListenBound(0,listening.Callback(),[&](auto r){if(r.ok())accepted.Callback()(r);});auto bound=listening.Get();CHECK(bound.ok());other.Connect("127.0.0.1",bound.value(),connected.Callback());auto c=connected.Get();auto a=accepted.Get();CHECK(c.ok());CHECK(a.ok());
 Completion<void> writing;c.value()->Write(std::vector<std::uint8_t>(32*1024*1024,42),writing.Callback());other.Stop();auto cancelled=writing.Get();CHECK(!cancelled.ok());CHECK(cancelled.error().code==ErrorCode::Cancelled);c.value()->Cancel(99999999);
 // Destruction with a pending receive still produces one terminal callback.
 auto dying=std::make_unique<SocketFactory>();Completion<std::uint16_t> dyingReady;Completion<std::shared_ptr<ISocket>> dyingAccept,dyingConnect;
 dying->ListenBound(0,dyingReady.Callback(),[&](auto r){if(r.ok())dyingAccept.Callback()(r);});auto dp=dyingReady.Get();CHECK(dp.ok());dying->Connect("127.0.0.1",dp.value(),dyingConnect.Callback());auto dc=dyingConnect.Get();auto da=dyingAccept.Get();CHECK(dc.ok());CHECK(da.ok());Completion<std::vector<std::uint8_t>> drained;da.value()->Read(10,drained.Callback());dying.reset();CHECK(drained.Get().error().code==ErrorCode::Cancelled);
}
void LiveDnsTests(){
 SocketFactory sockets;Completion<std::uint16_t> ready;sockets.ListenBound(0,ready.Callback(),[](auto){});auto port=ready.Get();CHECK(port.ok());
 DnsSd publisher,browser;ServiceRecord record;record.instance="unishare-test-"+std::to_string(GetCurrentProcessId());record.service_type="_share._tcp.local.";record.port=port.value();record.txt={{"id","12345678-1234-1234-1234-123456789abc"},{"name","Windows integration test"},{"platform","windows"},{"pv","1.0"}};
 std::mutex mutex;std::condition_variable cv;bool discovered=false;
 browser.BrowseEvents(record.service_type,[&](auto r){if(!r.ok()){std::cerr<<"live browse error: "<<r.error().message<<" code "<<r.error().platform_error.value_or(0)<<"\n";return;}if(r.value().instance.find(record.instance)==0){std::lock_guard<std::mutex> l(mutex);CHECK(r.value().port==port.value());CHECK(!r.value().interface_id.empty());CHECK(!r.value().addresses.empty());discovered=true;cv.notify_all();}},[](auto){});
 Completion<void> published;publisher.Publish(record,published.Callback());auto p=published.Get();if(!p.ok())std::cerr<<"live publish error "<<p.error().platform_error.value_or(0)<<"\n";CHECK(p.ok());
 {std::unique_lock<std::mutex> l(mutex);CHECK(cv.wait_for(l,std::chrono::seconds(20),[&]{return discovered;}));}publisher.Stop();browser.Stop();sockets.Stop();
}




void ConcurrentWatchTest(){
 struct Native final: NetworkChangeApi {
  std::mutex mutex;std::condition_variable cv;std::map<HANDLE,void*> active;std::uintptr_t next=0;
  DWORD Register(PIPINTERFACE_CHANGE_CALLBACK,void* context,HANDLE* handle) override{std::lock_guard<std::mutex> l(mutex);*handle=reinterpret_cast<HANDLE>(++next);active[*handle]=context;return 0;}
  DWORD Cancel(HANDLE handle) override{std::lock_guard<std::mutex> l(mutex);CHECK(active.erase(handle)==1);cv.notify_all();return 0;}
 };
 auto native=std::make_shared<Native>();NetworkChange owner(native);std::promise<void> go;auto barrier=go.get_future().share();std::vector<std::thread> threads;std::atomic<int> done{0};
 for(int i=0;i<32;++i)threads.emplace_back([&]{barrier.wait();owner.Start([]{},[&](auto){std::lock_guard<std::mutex> l(native->mutex);++done;native->cv.notify_all();});});go.set_value();for(auto& thread:threads)thread.join();owner.Stop();
 std::unique_lock<std::mutex> l(native->mutex);CHECK(native->cv.wait_for(l,std::chrono::seconds(3),[&]{return native->active.empty()&&done==32;}));
}

void PausedRegistrationTest(){
 struct PausedApi final : NetworkChangeApi {
  std::mutex mutex;std::condition_variable cv;bool entered=false,released=false;int registrations=0,cancelled=0;
  DWORD Register(PIPINTERFACE_CHANGE_CALLBACK,void*,HANDLE* handle) override {
   std::unique_lock<std::mutex> lock(mutex);auto id=++registrations;*handle=reinterpret_cast<HANDLE>(static_cast<std::uintptr_t>(id));
   if(id==1){entered=true;cv.notify_all();cv.wait(lock,[&]{return released;});}return NO_ERROR;
  }
  DWORD Cancel(HANDLE) override {std::lock_guard<std::mutex> lock(mutex);++cancelled;cv.notify_all();return NO_ERROR;}
  void WaitEntered(){std::unique_lock<std::mutex> lock(mutex);CHECK(cv.wait_for(lock,std::chrono::seconds(3),[&]{return entered;}));}
  void Release(){std::lock_guard<std::mutex> lock(mutex);released=true;cv.notify_all();}
  void WaitCancelled(int count){std::unique_lock<std::mutex> lock(mutex);CHECK(cv.wait_for(lock,std::chrono::seconds(3),[&]{return cancelled==count;}));}
 };
 for(bool replace:{false,true}){
  auto native=std::make_shared<PausedApi>();NetworkChange owner(native);Completion<void> first,second;std::atomic<int> events{0};
  owner.Start([&]{++events;},first.Callback());native->WaitEntered();
  if(replace){owner.Start([&]{++events;},second.Callback());CHECK(second.Get().ok());}else owner.Stop();
  native->Release();auto result=first.Get();CHECK(!result.ok());CHECK(result.error().code==ErrorCode::Cancelled);native->WaitCancelled(1);CHECK(events==0);
  owner.Stop();if(replace)native->WaitCancelled(2);CHECK(first.calls==1);
 }
}
void MalformedDnsNameTest(){
 auto native=std::make_shared<FakeDns>();native->interfaces={7};DnsSd dns(native);Completion<ServiceRecord> error,valid;std::atomic<int> errors{0};
 dns.BrowseEvents("_share._tcp.local.",[&](auto result){if(result.ok())valid.Callback()(result);else{++errors;error.Callback()(result);}},[](auto){});
 native->Wait([&]{return native->browses.size()==1;});auto request=native->browses[0].request;
 char malformed[]={static_cast<char>(0xc3),'(',0};DNS_RECORD record{};record.wType=DNS_TYPE_PTR;record.dwTtl=120;record.Flags.S.CharSet=DnsCharSetUtf8;record.Data.PTR.pNameHost=reinterpret_cast<wchar_t*>(malformed);
 request.pBrowseCallback(NO_ERROR,request.pQueryContext,&record);
 auto result=error.Get();CHECK(!result.ok());CHECK(result.error().code==ErrorCode::DiscoveryFailed);CHECK(result.error().platform_error==ERROR_NO_UNICODE_TRANSLATION);
 {std::lock_guard<std::mutex> lock(native->mutex);CHECK(native->resolves.empty());}
 native->Ptr(0,120);native->Wait([&]{return native->resolves.size()==1;});native->Resolved(0);CHECK(valid.Get().ok());
 dns.Stop();native->Wait([&]{return native->cancelBrowses==1;});{std::lock_guard<std::mutex> lock(native->mutex);CHECK(native->cancelResolves==0);}CHECK(errors==1);
}


void PendingReadAllocationTest(){
 SocketFactory factory;Completion<std::uint16_t> ready;Completion<std::shared_ptr<ISocket>> accepted,connected;
 factory.ListenBound(0,ready.Callback(),[&](auto result){if(result.ok())accepted.Callback()(result);});auto port=ready.Get();CHECK(port.ok());
 factory.Connect("127.0.0.1",port.value(),connected.Callback());auto client=connected.Get();CHECK(client.ok());auto server=accepted.Get();CHECK(server.ok());
 Completion<std::vector<std::uint8_t>> read;readAllocations=0;countReadAllocations=true;server.value()->Read(4*1024*1024,read.Callback());
 // Empty writes complete on the same socket worker and exercise Poll while the
 // receive remains pending. They send no bytes that could satisfy the read.
 for(int i=0;i<8;++i){Completion<void> marker;server.value()->Write({},marker.Callback());CHECK(marker.Get().ok());}
 countReadAllocations=false;CHECK(readAllocations<=1);CHECK(read.calls==0);
 Completion<void> sent;client.value()->Write({11,22,33},sent.Callback());CHECK(sent.Get().ok());auto bytes=read.Get();CHECK(bytes.ok());CHECK(bytes.value()==std::vector<std::uint8_t>({11,22,33}));factory.Stop();
}
