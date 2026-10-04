#include <winsock2.h>
#include <ws2tcpip.h>
#include "sockets.hpp"
#include "core/serial_scheduler.hpp"
#include <atomic>
#include <deque>
#include <map>
#include <algorithm>
namespace unishare::windows {
namespace {
std::atomic<OperationId> nextId{1};
ShareError NetError(const char* text,int code){return {code==WSAEACCES?ErrorCode::PermissionDenied:ErrorCode::NetworkLost,text,code};}
ShareError Cancelled(){return {ErrorCode::Cancelled,"socket operation cancelled",WSA_OPERATION_ABORTED};}
struct Winsock {
 int error; Winsock(){WSADATA data{};error=WSAStartup(MAKEWORD(2,2),&data);} ~Winsock(){if(!error)WSACleanup();}
};
std::string Address(const sockaddr_storage& address){
 char text[INET6_ADDRSTRLEN]{};
 if(address.ss_family==AF_INET){auto& a=reinterpret_cast<const sockaddr_in&>(address);InetNtopA(AF_INET,const_cast<IN_ADDR*>(&a.sin_addr),text,sizeof text);return text;}
 auto& a=reinterpret_cast<const sockaddr_in6&>(address);
 if(IN6_IS_ADDR_V4MAPPED(&a.sin6_addr)){IN_ADDR v4{};memcpy(&v4,&a.sin6_addr.u.Byte[12],4);InetNtopA(AF_INET,&v4,text,sizeof text);return text;}
 InetNtopA(AF_INET6,const_cast<IN6_ADDR*>(&a.sin6_addr),text,sizeof text);
 return std::string(text)+(a.sin6_scope_id?"%"+std::to_string(a.sin6_scope_id):"");
}
bool NumericAddress(const std::string& input,std::uint16_t port,sockaddr_storage& address,int& size){
 addrinfo hints{};hints.ai_family=AF_UNSPEC;hints.ai_socktype=SOCK_STREAM;hints.ai_flags=AI_NUMERICHOST|AI_NUMERICSERV;
 addrinfo* result=nullptr;auto service=std::to_string(port);
 if(getaddrinfo(input.c_str(),service.c_str(),&hints,&result)!=0)return false;
 bool valid=result&&(result->ai_family==AF_INET||result->ai_family==AF_INET6)&&result->ai_addrlen<=sizeof address;
 if(valid){size=static_cast<int>(result->ai_addrlen);memcpy(&address,result->ai_addr,size);}freeaddrinfo(result);return valid;
}
}
struct SocketFactory::State : std::enable_shared_from_this<State> {
 struct ReadOp {OperationId id;std::size_t count;ResultCallback<std::vector<std::uint8_t>> callback;};
 struct WriteOp {OperationId id;std::vector<std::uint8_t> bytes;std::size_t offset=0;ResultCallback<void> callback;};
 struct Endpoint {
  SOCKET socket=INVALID_SOCKET;bool closed=false;std::string remote;
  std::deque<ReadOp> reads;std::deque<WriteOp> writes;
  ResultCallback<std::shared_ptr<ISocket>> connecting;
  std::function<void(Result<std::shared_ptr<ISocket>>)> accepting;
 };
 class Socket final : public ISocket {
  std::shared_ptr<State> state_;std::shared_ptr<Endpoint> endpoint_;
 public:
  Socket(std::shared_ptr<State> s,std::shared_ptr<Endpoint> e):state_(std::move(s)),endpoint_(std::move(e)){}
  ~Socket(){Close();}
  std::string RemoteEndpoint() const override{return endpoint_->remote;}
  OperationId Read(std::size_t size,ResultCallback<std::vector<std::uint8_t>> cb) override {
   auto id=nextId++;auto s=state_;auto e=endpoint_;
   s->worker->Post([s,e,id,size,cb=std::move(cb)]() mutable {
    if(e->closed){cb(Result<std::vector<std::uint8_t>>::Failure(Cancelled()));return;}
    if(!size){cb(Result<std::vector<std::uint8_t>>::Failure({ErrorCode::InvalidInput,"zero read size",{}}));return;}
    e->reads.push_back({id,std::min<std::size_t>(size,4*1024*1024),std::move(cb)});s->Poll(e);
   });return id;
  }
  OperationId Write(std::vector<std::uint8_t> bytes,ResultCallback<void> cb) override {
   auto id=nextId++;auto s=state_;auto e=endpoint_;
   s->worker->Post([s,e,id,bytes=std::move(bytes),cb=std::move(cb)]() mutable {
    if(e->closed){cb(Result<void>::Failure(Cancelled()));return;}
    e->writes.push_back({id,std::move(bytes),0,std::move(cb)});s->Poll(e);
   });return id;
  }
  void Cancel(OperationId id) override {
   auto s=state_;auto e=endpoint_;s->worker->Post([s,e,id]{
    for(auto it=e->reads.begin();it!=e->reads.end();++it)if(it->id==id){auto cb=std::move(it->callback);e->reads.erase(it);cb(Result<std::vector<std::uint8_t>>::Failure(Cancelled()));return;}
    for(auto it=e->writes.begin();it!=e->writes.end();++it)if(it->id==id){
     // A partially transmitted frame cannot safely be resumed by later writes.
     if(it->offset){s->Close(e);return;}
     auto cb=std::move(it->callback);e->writes.erase(it);cb(Result<void>::Failure(Cancelled()));return;
    }
   });
  }
  void Close() override {auto s=state_;auto e=endpoint_;s->worker->Post([s,e]{s->Close(e);});}
  void AbortAndCleanup() override {Close();}
 };
 Winsock winsock;
 std::shared_ptr<core::SerialScheduler> worker=std::make_shared<core::SerialScheduler>();
 std::vector<std::shared_ptr<Endpoint>> endpoints;bool stopped=false;
 void Close(const std::shared_ptr<Endpoint>& e){
  if(e->closed)return;e->closed=true;
  if(e->socket!=INVALID_SOCKET){shutdown(e->socket,SD_BOTH);closesocket(e->socket);e->socket=INVALID_SOCKET;}
  auto reads=std::move(e->reads);auto writes=std::move(e->writes);auto connect=std::move(e->connecting);auto accept=std::move(e->accepting);
  for(auto& op:reads)op.callback(Result<std::vector<std::uint8_t>>::Failure(Cancelled()));
  for(auto& op:writes)op.callback(Result<void>::Failure(Cancelled()));
  if(connect)connect(Result<std::shared_ptr<ISocket>>::Failure(Cancelled()));
  if(accept)accept(Result<std::shared_ptr<ISocket>>::Failure(Cancelled()));
 }
 std::shared_ptr<Endpoint> Make(SOCKET socket){auto e=std::make_shared<Endpoint>();e->socket=socket;endpoints.push_back(e);return e;}
 bool Nonblocking(SOCKET socket){u_long value=1;return ioctlsocket(socket,FIONBIO,&value)==0;}
 void Connected(const std::shared_ptr<Endpoint>& e,ResultCallback<std::shared_ptr<ISocket>> cb){
  sockaddr_storage remote{};int size=sizeof remote;
  if(getpeername(e->socket,reinterpret_cast<sockaddr*>(&remote),&size)){
   auto error=WSAGetLastError();Close(e);cb(Result<std::shared_ptr<ISocket>>::Failure(NetError("remote endpoint",error)));return;
  }
  e->remote=Address(remote);
  cb(Result<std::shared_ptr<ISocket>>::Success(std::make_shared<Socket>(shared_from_this(),e)));
 }
 void Poll(const std::shared_ptr<Endpoint>& e){
  if(e->closed)return;
  if(e->connecting){
   fd_set write,errors;FD_ZERO(&write);FD_ZERO(&errors);FD_SET(e->socket,&write);FD_SET(e->socket,&errors);timeval timeout{};
   int selected=select(0,nullptr,&write,&errors,&timeout);if(!selected)return;
   int code=0;int size=sizeof code;if(selected<0)code=WSAGetLastError();else if(getsockopt(e->socket,SOL_SOCKET,SO_ERROR,reinterpret_cast<char*>(&code),&size))code=WSAGetLastError();
   auto cb=std::move(e->connecting);
   if(code){cb(Result<std::shared_ptr<ISocket>>::Failure(NetError("connect",code)));Close(e);return;}
   Connected(e,std::move(cb));return;
  }
  if(e->accepting){
   sockaddr_storage peer{};int size=sizeof peer;SOCKET accepted=accept(e->socket,reinterpret_cast<sockaddr*>(&peer),&size);
   if(accepted==INVALID_SOCKET){auto code=WSAGetLastError();if(code!=WSAEWOULDBLOCK){auto cb=std::move(e->accepting);cb(Result<std::shared_ptr<ISocket>>::Failure(NetError("accept",code)));Close(e);}return;}
   if(!Nonblocking(accepted)){auto code=WSAGetLastError();closesocket(accepted);e->accepting(Result<std::shared_ptr<ISocket>>::Failure(NetError("accepted socket",code)));return;}
   auto child=Make(accepted);child->remote=Address(peer);e->accepting(Result<std::shared_ptr<ISocket>>::Success(std::make_shared<Socket>(shared_from_this(),child)));return;
  }
  if(!e->reads.empty()){
   auto& op=e->reads.front();std::vector<std::uint8_t> bytes(op.count);int count=recv(e->socket,reinterpret_cast<char*>(bytes.data()),static_cast<int>(bytes.size()),0);
   int error=count<0?WSAGetLastError():0;
   if(error!=WSAEWOULDBLOCK){auto cb=std::move(op.callback);e->reads.pop_front();if(error)cb(Result<std::vector<std::uint8_t>>::Failure(NetError("receive",error)));else{bytes.resize(count);cb(Result<std::vector<std::uint8_t>>::Success(std::move(bytes)));}}
  }
  if(!e->writes.empty()){
   auto& op=e->writes.front();int error=0;
   // Bound each native send and each tick for fairness and retained-buffer ownership.
   if(op.offset<op.bytes.size()){
    int count=send(e->socket,reinterpret_cast<const char*>(op.bytes.data()+op.offset),static_cast<int>(std::min<std::size_t>(64*1024,op.bytes.size()-op.offset)),0);
    if(count>0)op.offset+=count;else error=count<0?WSAGetLastError():WSAECONNRESET;
   }
   if((error&&error!=WSAEWOULDBLOCK)||op.offset==op.bytes.size()){
    auto cb=std::move(op.callback);e->writes.pop_front();if(error)cb(Result<void>::Failure(NetError("send",error)));else cb(Result<void>::Success());
   }
  }
 }
 void Tick(){
  if(stopped)return;
  auto snapshot=endpoints;for(auto& e:snapshot)Poll(e);
  endpoints.erase(std::remove_if(endpoints.begin(),endpoints.end(),[](auto& e){return e->closed;}),endpoints.end());
  auto s=shared_from_this();worker->ScheduleAt(worker->Now()+std::chrono::milliseconds(2),[s]{s->Tick();});
 }
};
SocketFactory::SocketFactory()=default;
SocketFactory::~SocketFactory(){Stop();}
std::shared_ptr<SocketFactory::State> SocketFactory::Current(){
 std::lock_guard<std::mutex> guard(mutex_);if(!state_){state_=std::make_shared<State>();auto s=state_;s->worker->Post([s]{s->Tick();});}return state_;
}
OperationId SocketFactory::Connect(std::string address,std::uint16_t port,ResultCallback<std::shared_ptr<ISocket>> cb){
 auto s=Current();auto id=nextId++;s->worker->Post([s,address=std::move(address),port,cb=std::move(cb)]() mutable {
  using R=Result<std::shared_ptr<ISocket>>;
  if(s->stopped){cb(R::Failure(Cancelled()));return;}
  if(s->winsock.error){cb(R::Failure(NetError("WSAStartup",s->winsock.error)));return;}
  sockaddr_storage remote{};int size=0;if(!NumericAddress(address,port,remote,size)){cb(R::Failure({ErrorCode::InvalidInput,"numeric discovered IP address required",WSAEINVAL}));return;}
  SOCKET socket=::socket(remote.ss_family,SOCK_STREAM,IPPROTO_TCP);if(socket==INVALID_SOCKET){cb(R::Failure(NetError("socket",WSAGetLastError())));return;}
  auto e=s->Make(socket);e->remote=Address(remote);
  if(!s->Nonblocking(socket)){auto error=WSAGetLastError();s->Close(e);cb(R::Failure(NetError("nonblocking socket",error)));return;}
  auto result=connect(socket,reinterpret_cast<sockaddr*>(&remote),size);int error=result?WSAGetLastError():0;
  if(error&&error!=WSAEWOULDBLOCK){s->Close(e);cb(R::Failure(NetError("connect",error)));return;}
  if(!error)s->Connected(e,std::move(cb));else e->connecting=std::move(cb);
 });return id;
}
OperationId SocketFactory::Listen(std::uint16_t port,std::function<void(Result<std::shared_ptr<ISocket>>)> cb){
 return ListenBound(port,[cb](auto r){if(!r.ok())cb(Result<std::shared_ptr<ISocket>>::Failure(r.error()));},cb);
}
OperationId SocketFactory::ListenBound(std::uint16_t port,ResultCallback<std::uint16_t> ready,std::function<void(Result<std::shared_ptr<ISocket>>)> cb){
 auto s=Current();auto id=nextId++;s->worker->Post([s,port,ready=std::move(ready),cb=std::move(cb)]() mutable {
  using R=Result<std::uint16_t>;if(s->stopped){ready(R::Failure(Cancelled()));return;}
  if(s->winsock.error){ready(R::Failure(NetError("WSAStartup",s->winsock.error)));return;}
  SOCKET socket=::socket(AF_INET6,SOCK_STREAM,IPPROTO_TCP);if(socket==INVALID_SOCKET){ready(R::Failure(NetError("listener socket",WSAGetLastError())));return;}
  auto e=s->Make(socket);DWORD dual=0;BOOL exclusive=TRUE;sockaddr_in6 local{};local.sin6_family=AF_INET6;local.sin6_port=htons(port);
  if(setsockopt(socket,IPPROTO_IPV6,IPV6_V6ONLY,reinterpret_cast<char*>(&dual),sizeof dual)||setsockopt(socket,SOL_SOCKET,SO_EXCLUSIVEADDRUSE,reinterpret_cast<char*>(&exclusive),sizeof exclusive)||!s->Nonblocking(socket)||bind(socket,reinterpret_cast<sockaddr*>(&local),sizeof local)||listen(socket,SOMAXCONN)){
   auto error=WSAGetLastError();s->Close(e);ready(R::Failure(NetError("bind listener",error)));return;
  }
  int size=sizeof local;if(getsockname(socket,reinterpret_cast<sockaddr*>(&local),&size)){auto error=WSAGetLastError();s->Close(e);ready(R::Failure(NetError("listener port",error)));return;}
  ready(R::Success(ntohs(local.sin6_port)));e->accepting=std::move(cb);
 });return id;
}
void SocketFactory::Stop(){std::shared_ptr<State> s;{std::lock_guard<std::mutex> guard(mutex_);s=std::move(state_);}if(s)s->worker->Post([s]{s->stopped=true;for(auto& e:s->endpoints)s->Close(e);s->endpoints.clear();});}
}
