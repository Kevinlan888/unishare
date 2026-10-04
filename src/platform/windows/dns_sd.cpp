#include "dns_sd.hpp"
#include "core/serial_scheduler.hpp"
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <set>
#include <map>
#include <atomic>
#include <algorithm>
namespace unishare::windows {
namespace {
std::atomic<OperationId> ids{1};
ShareError DnsError(const char* text,DWORD code){return {code==ERROR_ACCESS_DENIED?ErrorCode::PermissionDenied:ErrorCode::DiscoveryFailed,text,code};}
std::wstring Wide(const std::string& text){
 if(text.empty())return {};int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),nullptr,0);
 if(!n)throw DnsError("UTF-8 conversion",GetLastError());std::wstring result(n,0);MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),result.data(),n);return result;
}
std::string Utf8(const wchar_t* text){
 if(!text)return {};int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,text,-1,nullptr,0,nullptr,nullptr);
 if(!n)throw DnsError("UTF-16 conversion",GetLastError());std::string result(n,0);WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,text,-1,result.data(),n,nullptr,nullptr);result.pop_back();return result;
}
std::string DnsName(const DNS_RECORD& record,const wchar_t* text){
 if(!text)return {};
 if(record.Flags.S.CharSet==DnsCharSetUtf8)return reinterpret_cast<const char*>(text);
 return Utf8(text);
}
}
Result<std::vector<ULONG>> DnsApi::Interfaces(){
 ULONG size=16*1024;std::vector<unsigned char> buffer(size);ULONG status=0;
 for(int i=0;i<3;++i){status=GetAdaptersAddresses(AF_UNSPEC,GAA_FLAG_SKIP_ANYCAST|GAA_FLAG_SKIP_MULTICAST|GAA_FLAG_SKIP_DNS_SERVER,nullptr,reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()),&size);if(status!=ERROR_BUFFER_OVERFLOW)break;buffer.resize(size);}
 if(status)return Result<std::vector<ULONG>>::Failure(DnsError("enumerate interfaces",status));
 std::set<ULONG> indices;for(auto a=reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());a;a=a->Next){
  if(a->OperStatus!=IfOperStatusUp||a->IfType==IF_TYPE_SOFTWARE_LOOPBACK||(a->Flags&IP_ADAPTER_NO_MULTICAST))continue;
  if(a->IfIndex)indices.insert(a->IfIndex);if(a->Ipv6IfIndex)indices.insert(a->Ipv6IfIndex);
 }
 return Result<std::vector<ULONG>>::Success({indices.begin(),indices.end()});
}
Result<std::wstring> DnsApi::HostName(){
 wchar_t name[256]{};DWORD size=256;if(!GetComputerNameExW(ComputerNamePhysicalDnsHostname,name,&size))return Result<std::wstring>::Failure(DnsError("DNS host name",GetLastError()));
 return Result<std::wstring>::Success(std::wstring(name)+L".local.");
}
DWORD DnsApi::Register(DNS_SERVICE_REGISTER_REQUEST* r,DNS_SERVICE_CANCEL* c){return DnsServiceRegister(r,c);}
DWORD DnsApi::Deregister(DNS_SERVICE_REGISTER_REQUEST* r){return DnsServiceDeRegister(r,nullptr);}
DWORD DnsApi::Browse(DNS_SERVICE_BROWSE_REQUEST* r,DNS_SERVICE_CANCEL* c){return DnsServiceBrowse(r,c);}
DWORD DnsApi::Resolve(DNS_SERVICE_RESOLVE_REQUEST* r,DNS_SERVICE_CANCEL* c){return DnsServiceResolve(r,c);}
DWORD DnsApi::CancelRegister(DNS_SERVICE_CANCEL* c){return DnsServiceRegisterCancel(c);}
DWORD DnsApi::CancelBrowse(DNS_SERVICE_CANCEL* c){return DnsServiceBrowseCancel(c);}
DWORD DnsApi::CancelResolve(DNS_SERVICE_CANCEL* c){return DnsServiceResolveCancel(c);}
void DnsApi::FreeInstance(DNS_SERVICE_INSTANCE* p){DnsServiceFreeInstance(p);}
void DnsApi::FreeRecords(DNS_RECORD* p){DnsRecordListFree(p,DnsFreeRecordList);}
struct DnsSd::State : std::enable_shared_from_this<State> {
 enum class Kind {Publish,Browse,Resolve};
 struct Context : std::enable_shared_from_this<Context> {
  std::shared_ptr<State> owner;Kind kind;ULONG interfaceIndex=0;
  DNS_SERVICE_CANCEL cancel{};DNS_SERVICE_REGISTER_REQUEST registration{};DNS_SERVICE_BROWSE_REQUEST browse{};DNS_SERVICE_RESOLVE_REQUEST resolve{};
  DNS_SERVICE_INSTANCE instance{};std::wstring name,host;std::vector<std::wstring> keys,values;std::vector<PWSTR> keyPointers,valuePointers;
  bool registered=false,deregistering=false,finished=false;std::uint64_t revision=0;
  ResultCallback<void> published;std::function<void(Result<ServiceRecord>)> found;std::function<void(ServiceInstanceId)> removed;
  std::map<std::string,std::uint64_t> revisions;
  std::shared_ptr<Context> browser;
 };
 std::shared_ptr<DnsApi> api;std::shared_ptr<core::SerialScheduler> worker=std::make_shared<core::SerialScheduler>();
 std::vector<std::shared_ptr<Context>> contexts;bool stopped=false;
 explicit State(std::shared_ptr<DnsApi> a):api(std::move(a)){}
 std::shared_ptr<Context> Make(Kind kind){auto c=std::make_shared<Context>();c->owner=shared_from_this();c->kind=kind;contexts.push_back(c);return c;}
 void Finish(const std::shared_ptr<Context>& c){c->finished=true;contexts.erase(std::remove(contexts.begin(),contexts.end(),c),contexts.end());}
 void Unregister(const std::shared_ptr<Context>& c){
  if(c->deregistering||c->finished)return;c->deregistering=true;
  auto status=api->Deregister(&c->registration);if(status!=DNS_REQUEST_PENDING)Finish(c);
 }
 static void WINAPI Registered(DWORD status,void* raw,DNS_SERVICE_INSTANCE* instance){
  auto c=static_cast<Context*>(raw)->shared_from_this();auto s=c->owner;
  if(instance)s->api->FreeInstance(instance);
  s->worker->Post([s,c,status]{
   if(c->deregistering){s->Finish(c);return;}
   c->registered=status==ERROR_SUCCESS;
   if(c->published){auto cb=std::move(c->published);if(status)cb(Result<void>::Failure(DnsError("register service",status)));else cb(Result<void>::Success());}
   if(!c->registered)s->Finish(c);else if(s->stopped)s->Unregister(c);
  });
 }
 static void WINAPI Browsed(DWORD status,void* raw,DNS_RECORD* records){
  auto c=static_cast<Context*>(raw)->shared_from_this();auto s=c->owner;std::vector<std::pair<std::string,bool>> entries;
  try{for(auto record=records;record;record=record->pNext)if(record->wType==DNS_TYPE_PTR&&record->Data.PTR.pNameHost)entries.emplace_back(DnsName(*record,record->Data.PTR.pNameHost),record->dwTtl==0);}catch(const ShareError& e){status=static_cast<DWORD>(e.platform_error.value_or(ERROR_INVALID_DATA));}
  if(records)s->api->FreeRecords(records);
  s->worker->Post([s,c,status,entries=std::move(entries)]{
   if(status==ERROR_CANCELLED){s->Finish(c);return;}
   if(s->stopped)return;
   if(status){c->found(Result<ServiceRecord>::Failure(DnsError("browse service",status)));return;}
   for(auto& entry:entries){
    auto revision=++c->revisions[entry.first];
    if(entry.second){c->removed({entry.first,std::to_string(c->interfaceIndex)});continue;}
    s->Resolve(c,entry.first,revision);
   }
  });
 }
 static ServiceRecord Decode(const Context& c,const DNS_SERVICE_INSTANCE& instance){
  ServiceRecord record;record.instance=Utf8(instance.pszInstanceName);record.service_type="_share._tcp.local.";record.interface_id=std::to_string(c.interfaceIndex);record.srv_target=Utf8(instance.pszHostName);record.port=instance.wPort;
  char address[INET6_ADDRSTRLEN]{};
  if(instance.ip4Address&&InetNtopA(AF_INET,instance.ip4Address,address,sizeof address))record.addresses.emplace_back(address);
  if(instance.ip6Address&&InetNtopA(AF_INET6,instance.ip6Address,address,sizeof address)){
   auto v6=reinterpret_cast<const IN6_ADDR*>(instance.ip6Address);std::string scoped=address;if(IN6_IS_ADDR_LINKLOCAL(v6))scoped+="%"+std::to_string(c.interfaceIndex);record.addresses.push_back(std::move(scoped));
  }
  bool byteEncodable=true;
  for(DWORD i=0;i<instance.dwPropertyCount;++i){
   auto key=Utf8(instance.keys?instance.keys[i]:nullptr);auto value=Utf8(instance.values?instance.values[i]:nullptr);record.txt.emplace_back(key,value);auto text=key+"="+value;
   if(text.size()>255)byteEncodable=false;
   if(byteEncodable){record.txt_bytes.push_back(static_cast<std::uint8_t>(text.size()));record.txt_bytes.insert(record.txt_bytes.end(),text.begin(),text.end());}
  }
  // Keep pairs when a value cannot be represented as one TXT string; core validates sizes.
  if(!byteEncodable)record.txt_bytes.clear();return record;
 }
 static void WINAPI Resolved(DWORD status,void* raw,DNS_SERVICE_INSTANCE* instance){
  auto c=static_cast<Context*>(raw)->shared_from_this();auto s=c->owner;std::optional<ServiceRecord> record;
  if(!status){try{if(instance)record=Decode(*c,*instance);else status=ERROR_INVALID_DATA;}catch(const ShareError& e){status=static_cast<DWORD>(e.platform_error.value_or(ERROR_INVALID_DATA));}}
  if(instance)s->api->FreeInstance(instance);
  s->worker->Post([s,c,status,record=std::move(record)]() mutable {
   auto browser=c->browser;auto name=Utf8(c->name.c_str());
   if(!s->stopped&&browser->revisions[name]==c->revision){if(status)browser->found(Result<ServiceRecord>::Failure(DnsError("resolve service",status)));else browser->found(Result<ServiceRecord>::Success(std::move(*record)));}
   s->Finish(c);
  });
 }
 void Resolve(const std::shared_ptr<Context>& browser,const std::string& name,std::uint64_t revision){
  std::wstring query;
  try{query=Wide(name);}catch(const ShareError& error){browser->found(Result<ServiceRecord>::Failure(error));return;}
  auto c=Make(Kind::Resolve);c->browser=browser;c->interfaceIndex=browser->interfaceIndex;c->name=std::move(query);c->revision=revision;
  c->resolve={DNS_QUERY_REQUEST_VERSION1,c->interfaceIndex,c->name.data(),Resolved,c.get()};
  auto status=api->Resolve(&c->resolve,&c->cancel);
  if(status!=DNS_REQUEST_PENDING){browser->found(Result<ServiceRecord>::Failure(DnsError("begin resolve",status)));Finish(c);}
 }
 void Publish(ServiceRecord record,ResultCallback<void> cb){
  if(stopped){cb(Result<void>::Failure({ErrorCode::Cancelled,"DNS stopped",ERROR_CANCELLED}));return;}
  auto c=Make(Kind::Publish);c->published=std::move(cb);
  try{
   auto host=api->HostName();if(!host.ok())throw host.error();c->host=host.value();
   std::string name=record.instance;
   if(name.size()<record.service_type.size()||name.compare(name.size()-record.service_type.size(),record.service_type.size(),record.service_type)!=0)name+="."+record.service_type;
   c->name=Wide(name);
   for(auto& item:record.txt){c->keys.push_back(Wide(item.first));c->values.push_back(Wide(item.second));}
   for(auto& key:c->keys)c->keyPointers.push_back(key.data());for(auto& value:c->values)c->valuePointers.push_back(value.data());
   c->instance.pszInstanceName=c->name.data();c->instance.pszHostName=c->host.data();c->instance.wPort=record.port;c->instance.dwPropertyCount=static_cast<DWORD>(c->keys.size());c->instance.keys=c->keyPointers.data();c->instance.values=c->valuePointers.data();
   c->registration={DNS_QUERY_REQUEST_VERSION1,0,&c->instance,Registered,c.get(),nullptr,FALSE};
   auto status=api->Register(&c->registration,&c->cancel);
   if(status!=DNS_REQUEST_PENDING)throw DnsError("begin registration",status);
  }catch(const ShareError& error){auto callback=std::move(c->published);Finish(c);callback(Result<void>::Failure(error));}
 }
 void Browse(std::string service,std::function<void(Result<ServiceRecord>)> found,std::function<void(ServiceInstanceId)> removed){
  if(stopped)return;
  auto interfaces=api->Interfaces();if(!interfaces.ok()){found(Result<ServiceRecord>::Failure(interfaces.error()));return;}
  if(interfaces.value().empty()){found(Result<ServiceRecord>::Failure(DnsError("no multicast interface",ERROR_NETWORK_UNREACHABLE)));return;}
  for(auto index:interfaces.value()){
   auto c=Make(Kind::Browse);c->interfaceIndex=index;c->found=found;c->removed=removed;
   try{c->name=Wide(service);}catch(const ShareError& e){Finish(c);found(Result<ServiceRecord>::Failure(e));continue;}
   c->browse.Version=DNS_QUERY_REQUEST_VERSION1;c->browse.InterfaceIndex=index;c->browse.QueryName=c->name.c_str();c->browse.pBrowseCallback=Browsed;c->browse.pQueryContext=c.get();
   auto status=api->Browse(&c->browse,&c->cancel);if(status!=DNS_REQUEST_PENDING){Finish(c);found(Result<ServiceRecord>::Failure(DnsError("begin browse",status)));}
  }
 }
 void Stop(){
  stopped=true;auto pending=contexts;
  for(auto& c:pending){
   if(c->kind==Kind::Publish){
    if(c->published){auto cb=std::move(c->published);cb(Result<void>::Failure({ErrorCode::Cancelled,"DNS publication cancelled",ERROR_CANCELLED}));}
    if(c->registered)Unregister(c);else api->CancelRegister(&c->cancel);
   }else if(c->kind==Kind::Browse)api->CancelBrowse(&c->cancel);else api->CancelResolve(&c->cancel);
  }
  // Contexts retain this generation until native terminal callbacks drain. In
  // particular, BrowseCancel's final ERROR_CANCELLED owns the last browse release.
 }
};
DnsSd::DnsSd(std::shared_ptr<DnsApi> api):api_(std::move(api)){}
DnsSd::~DnsSd(){Stop();}
std::shared_ptr<DnsSd::State> DnsSd::Current(){std::lock_guard<std::mutex> guard(mutex_);if(!state_)state_=std::make_shared<State>(api_);return state_;}
OperationId DnsSd::Publish(ServiceRecord record,ResultCallback<void> cb){auto s=Current();auto id=ids++;s->worker->Post([s,record=std::move(record),cb=std::move(cb)]() mutable {s->Publish(std::move(record),std::move(cb));});return id;}
OperationId DnsSd::Browse(std::string service,std::function<void(Result<ServiceRecord>)> found){return BrowseEvents(std::move(service),std::move(found),[](auto){});}
OperationId DnsSd::BrowseEvents(std::string service,std::function<void(Result<ServiceRecord>)> found,std::function<void(ServiceInstanceId)> removed){auto s=Current();auto id=ids++;s->worker->Post([s,service=std::move(service),found=std::move(found),removed=std::move(removed)]() mutable {s->Browse(std::move(service),std::move(found),std::move(removed));});return id;}
void DnsSd::Stop(){std::shared_ptr<State> s;{std::lock_guard<std::mutex> guard(mutex_);s=std::move(state_);}if(s)s->worker->Post([s]{s->Stop();});}
}
