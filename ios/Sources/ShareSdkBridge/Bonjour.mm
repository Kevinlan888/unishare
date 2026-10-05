#include "Common.hpp"
#include <dns_sd.h>
#include <arpa/inet.h>
#include <set>
namespace unishare::ios {
namespace {
ShareError DnsError(DNSServiceErrorType code) {return Failure(code==kDNSServiceErr_PolicyDenied?ErrorCode::PermissionDenied:ErrorCode::DiscoveryFailed,"system Bonjour operation failed",code);}
struct Ref {
 DNSServiceRef value=nullptr;
 ~Ref(){Reset();}
 void Reset(){if(value){DNSServiceRefDeallocate(value);value=nullptr;}}
};
struct BonjourState;
struct Node {
 BonjourState* owner;ServiceRecord record;std::string type,domain,fullname;uint32_t index;
 Ref resolve,txt,srv,address;std::set<std::string> addresses;bool visible=false;
};
struct Publication {Ref ref;ResultCallback<void> callback;};
struct BonjourState : std::enable_shared_from_this<BonjourState> {
 std::shared_ptr<Queue> queue=std::make_shared<Queue>("com.unishare.bonjour");
 Ref browse;std::vector<std::unique_ptr<Publication>> publications;
 std::map<ServiceInstanceId,std::unique_ptr<Node>> nodes;
 std::function<void(Result<ServiceRecord>)> found;std::function<void(ServiceInstanceId)> removed;
 void Error(DNSServiceErrorType code) {Deliver(found,Result<ServiceRecord>::Failure(DnsError(code)));}
 bool Schedule(Ref& ref,DNSServiceErrorType code) {
  if(!code)code=DNSServiceSetDispatchQueue(ref.value,queue->Native());
  if(code){ref.Reset();Error(code);return false;}return true;
 }
 void Emit(Node& node) {
  node.record.addresses.assign(node.addresses.begin(),node.addresses.end());
  if(node.addresses.empty()||!node.record.port||node.record.txt_bytes.empty()) {
   if(node.visible){node.visible=false;Deliver(removed,ServiceInstanceId{node.record.instance,node.record.interface_id});}return;
  }
  node.visible=true;Deliver(found,Result<ServiceRecord>::Success(node.record));
 }
 static void Address(DNSServiceRef,DNSServiceFlags flags,uint32_t index,DNSServiceErrorType error,
                     const char*,const sockaddr* address,uint32_t,void* context) {
  auto* node=static_cast<Node*>(context);auto owner=node->owner->shared_from_this();
  if(error){owner->Error(error);return;}
  if(index!=node->index)return;
  char text[INET6_ADDRSTRLEN]{};std::string result;
  if(address->sa_family==AF_INET) {inet_ntop(AF_INET,&reinterpret_cast<const sockaddr_in*>(address)->sin_addr,text,sizeof(text));result=text;}
  else if(address->sa_family==AF_INET6) {
   auto* v6=reinterpret_cast<const sockaddr_in6*>(address);inet_ntop(AF_INET6,&v6->sin6_addr,text,sizeof(text));result=text;
   auto scope=v6->sin6_scope_id;if(!scope&&IN6_IS_ADDR_LINKLOCAL(&v6->sin6_addr))scope=index;
   if(scope)result+="%"+std::to_string(scope);
  } else return;
  if(flags&kDNSServiceFlagsAdd)node->addresses.insert(result);else node->addresses.erase(result);
  owner->Emit(*node);
 }
 static void Txt(DNSServiceRef,DNSServiceFlags flags,uint32_t,DNSServiceErrorType error,const char*,uint16_t,uint16_t,
                 uint16_t length,const void* bytes,uint32_t,void* context) {
  auto* node=static_cast<Node*>(context);auto owner=node->owner->shared_from_this();
  if(error){owner->Error(error);return;}
  const auto* data=static_cast<const uint8_t*>(bytes);
  if(flags&kDNSServiceFlagsAdd)node->record.txt_bytes.assign(data,data+length);
  else if(node->record.txt_bytes==std::vector<uint8_t>(data,data+length))node->record.txt_bytes.clear();
  owner->Emit(*node);
 }
 static void Srv(DNSServiceRef,DNSServiceFlags flags,uint32_t,DNSServiceErrorType error,const char*,uint16_t,uint16_t,
                 uint16_t,const void*,uint32_t,void* context) {
  auto* node=static_cast<Node*>(context);auto owner=node->owner->shared_from_this();
  if(error){owner->Error(error);return;}
  if(flags&kDNSServiceFlagsAdd)owner->Resolve(*node);
  else {node->record.port=0;owner->Emit(*node);}
 }
 static void Resolved(DNSServiceRef,DNSServiceFlags,uint32_t,DNSServiceErrorType error,const char* fullname,
                      const char* host,uint16_t port,uint16_t length,const unsigned char* txt,void* context) {
  auto* node=static_cast<Node*>(context);auto owner=node->owner->shared_from_this();
  if(error){owner->Error(error);return;}
  node->fullname=fullname;node->record.port=ntohs(port);
  node->record.txt_bytes.assign(txt,txt+length);
  if(!node->txt.value) {
   auto code=DNSServiceQueryRecord(&node->txt.value,kDNSServiceFlagsLongLivedQuery,node->index,fullname,kDNSServiceType_TXT,kDNSServiceClass_IN,Txt,node);
   if(!owner->Schedule(node->txt,code))return;
  }
  if(!node->srv.value) {
   auto code=DNSServiceQueryRecord(&node->srv.value,kDNSServiceFlagsLongLivedQuery,node->index,fullname,kDNSServiceType_SRV,kDNSServiceClass_IN,Srv,node);
   if(!owner->Schedule(node->srv,code))return;
  }
  if(node->record.srv_target!=host||!node->address.value) {
   node->record.srv_target=host;node->address.Reset();node->addresses.clear();
   auto code=DNSServiceGetAddrInfo(&node->address.value,0,node->index,kDNSServiceProtocol_IPv4|kDNSServiceProtocol_IPv6,host,Address,node);
   if(!owner->Schedule(node->address,code))return;
  }
  owner->Emit(*node);
 }
 void Resolve(Node& node) {
  node.resolve.Reset();auto code=DNSServiceResolve(&node.resolve.value,0,node.index,node.record.instance.c_str(),node.type.c_str(),node.domain.c_str(),Resolved,&node);
  Schedule(node.resolve,code);
 }
 static void Browsed(DNSServiceRef,DNSServiceFlags flags,uint32_t index,DNSServiceErrorType error,
                     const char* name,const char* type,const char* domain,void* context) {
  auto owner=static_cast<BonjourState*>(context)->shared_from_this();
  if(error){owner->Error(error);return;}
  ServiceInstanceId key{name,std::to_string(index)};
  if(!(flags&kDNSServiceFlagsAdd)) {owner->nodes.erase(key);Deliver(owner->removed,key);return;}
  if(owner->nodes.count(key))return;
  auto node=std::make_unique<Node>();node->owner=owner.get();node->index=index;node->type=type;node->domain=domain;
  node->record.instance=name;node->record.interface_id=key.interface_id;node->record.service_type=std::string(type)+domain;
  auto* raw=node.get();owner->nodes.emplace(key,std::move(node));owner->Resolve(*raw);
 }
 void Stop() {
  browse.Reset();nodes.clear();found={};removed={};
  auto pending=std::move(publications);publications.clear();
  for(auto& p:pending){p->ref.Reset();auto cb=std::exchange(p->callback,{});Deliver(cb,Result<void>::Failure(Cancelled()));}
 }
};
class Bonjour final : public IDnsSd {
 std::shared_ptr<BonjourState> state_=std::make_shared<BonjourState>();
public:
 ~Bonjour(){Stop();}
 OperationId Publish(ServiceRecord record,ResultCallback<void> callback) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,record=std::move(record),callback] {
   if(record.service_type!="_share._tcp.local."||record.instance.empty()||!record.port){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"Bonjour publication")));return;}
   auto bytes=record.txt_bytes;
   if(bytes.empty())for(auto& entry:record.txt){auto field=entry.first+"="+entry.second;if(field.size()>255){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"TXT field")));return;}bytes.push_back(field.size());bytes.insert(bytes.end(),field.begin(),field.end());}
   if(bytes.size()>1300){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"TXT size")));return;}
   auto publication=std::make_unique<Publication>();publication->callback=callback;auto* p=publication.get();
   auto code=DNSServiceRegister(&p->ref.value,0,0,record.instance.c_str(),"_share._tcp","local.",nullptr,htons(record.port),static_cast<uint16_t>(bytes.size()),bytes.data(),
    [](DNSServiceRef,DNSServiceFlags,DNSServiceErrorType error,const char*,const char*,const char*,void* context){
     auto* p=static_cast<Publication*>(context);auto cb=std::exchange(p->callback,{});
     Deliver(cb,error?Result<void>::Failure(DnsError(error)):Result<void>::Success());
    },p);
   if(!code)code=DNSServiceSetDispatchQueue(p->ref.value,s->queue->Native());
   if(code){Deliver(callback,Result<void>::Failure(DnsError(code)));return;}
   s->publications.push_back(std::move(publication));
  });return id;
 }
 OperationId Browse(std::string type,std::function<void(Result<ServiceRecord>)> found) override {return BrowseEvents(std::move(type),std::move(found),[](auto){});}
 OperationId BrowseEvents(std::string type,std::function<void(Result<ServiceRecord>)> found,std::function<void(ServiceInstanceId)> removed) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,type=std::move(type),found,removed] {
   s->browse.Reset();s->nodes.clear();s->found=found;s->removed=removed;
   if(type!="_share._tcp.local."){Deliver(found,Result<ServiceRecord>::Failure(Failure(ErrorCode::InvalidInput,"Bonjour service type")));return;}
   auto code=DNSServiceBrowse(&s->browse.value,0,0,"_share._tcp","local.",BonjourState::Browsed,s.get());s->Schedule(s->browse,code);
  });return id;
 }
 void Stop() override {auto s=state_;s->queue->Sync([s]{s->Stop();});}
};
}
std::shared_ptr<IDnsSd> MakeBonjour() {return std::make_shared<Bonjour>();}
}
