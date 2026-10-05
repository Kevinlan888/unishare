#include "Common.hpp"
#import <Network/Network.h>
#include <arpa/inet.h>
#include <netdb.h>
namespace unishare::ios {
namespace {
ShareError NetworkError(nw_error_t error) {
 int code=error?nw_error_get_error_code(error):0;
 return Failure(code==EACCES||code==EPERM?ErrorCode::PermissionDenied:ErrorCode::NetworkLost,"Network framework operation failed",code);
}
std::string Canonical(nw_endpoint_t endpoint) {
 const sockaddr* address=nw_endpoint_get_address(endpoint);char text[INET6_ADDRSTRLEN]{};
 if(!address)return {};
 if(address->sa_family==AF_INET) {inet_ntop(AF_INET,&reinterpret_cast<const sockaddr_in*>(address)->sin_addr,text,sizeof(text));return text;}
 if(address->sa_family!=AF_INET6)return {};
 auto* v6=reinterpret_cast<const sockaddr_in6*>(address);
 if(IN6_IS_ADDR_V4MAPPED(&v6->sin6_addr)) {inet_ntop(AF_INET,v6->sin6_addr.s6_addr+12,text,sizeof(text));return text;}
 inet_ntop(AF_INET6,&v6->sin6_addr,text,sizeof(text));
 return std::string(text)+(v6->sin6_scope_id?"%"+std::to_string(v6->sin6_scope_id):"");
}
struct SocketState {
 std::shared_ptr<Queue> queue; nw_connection_t connection;
 bool closed=false;OperationId readId=0,writeId=0;
 ResultCallback<std::vector<uint8_t>> reading;ResultCallback<void> writing;
 std::function<void(Result<std::shared_ptr<ISocket>>)> ready;
 std::string remote;
 void FinishRead(Result<std::vector<uint8_t>> result) {
  auto callback=std::exchange(reading,{});
  readId=0;
  Deliver(callback,std::move(result));
 }
 void FinishWrite(Result<void> result) {
  auto callback=std::exchange(writing,{});
  writeId=0;
  Deliver(callback,std::move(result));
 }
 void Stop(ShareError error) {
  if(closed)return;
  closed=true;
  auto callback=std::exchange(ready,{});
  Deliver(callback,Result<std::shared_ptr<ISocket>>::Failure(error));
  FinishRead(Result<std::vector<uint8_t>>::Failure(error));
  FinishWrite(Result<void>::Failure(error));
  nw_connection_cancel(connection);
 }
};
class Socket final : public ISocket {
 std::shared_ptr<SocketState> state_;
public:
 explicit Socket(std::shared_ptr<SocketState> state):state_(std::move(state)) {}
 ~Socket(){Close();}
 std::string RemoteEndpoint() const override {return state_->remote;}
 OperationId Read(size_t maximum,ResultCallback<std::vector<uint8_t>> callback) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,maximum,callback,id] {
   if(s->closed){Deliver(callback,Result<std::vector<uint8_t>>::Failure(Cancelled()));return;}
   if(!maximum||s->reading){Deliver(callback,Result<std::vector<uint8_t>>::Failure(Failure(s->reading?ErrorCode::ResourceLimit:ErrorCode::InvalidInput,"read admission")));return;}
   s->reading=callback;s->readId=id;
   nw_connection_receive(s->connection,1,static_cast<uint32_t>(std::min<size_t>(maximum,256*1024)),^(dispatch_data_t content,nw_content_context_t,bool,nw_error_t error) {
    if(s->readId!=id)return;
    if(error){s->FinishRead(Result<std::vector<uint8_t>>::Failure(NetworkError(error)));return;}
    std::vector<uint8_t> bytes;
    if(content) {
     const void* p=nullptr;size_t n=0;dispatch_data_t mapped=dispatch_data_create_map(content,&p,&n);
     if(n)bytes.assign(static_cast<const uint8_t*>(p),static_cast<const uint8_t*>(p)+n);
     (void)mapped;
    }
    s->FinishRead(Result<std::vector<uint8_t>>::Success(std::move(bytes)));
   });
  });return id;
 }
 OperationId Write(std::vector<uint8_t> bytes,ResultCallback<void> callback) override {
  auto id=Next();auto s=state_;auto buffer=std::make_shared<std::vector<uint8_t>>(std::move(bytes));
  s->queue->Post([s,buffer,callback,id] {
   if(s->closed){Deliver(callback,Result<void>::Failure(Cancelled()));return;}
   if(s->writing){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::ResourceLimit,"one write at a time")));return;}
   s->writing=callback;s->writeId=id;
   dispatch_data_t content=dispatch_data_create(buffer->data(),buffer->size(),s->queue->Native(),^{(void)buffer;});
   nw_connection_send(s->connection,content,NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT,true,^(nw_error_t error) {
    if(s->writeId==id)s->FinishWrite(error?Result<void>::Failure(NetworkError(error)):Result<void>::Success());
   });
  });return id;
 }
 void Close() override {auto s=state_;s->queue->Sync([s]{s->Stop(Cancelled());});}
 void AbortAndCleanup() override {Close();}
 void Cancel(OperationId id) override {auto s=state_;s->queue->Sync([s,id]{if(s->readId==id||s->writeId==id)s->Stop(Cancelled());});}
};
struct Listener {
 nw_listener_t native;ResultCallback<uint16_t> ready;
 std::function<void(Result<std::shared_ptr<ISocket>>)> accept;bool stopped=false;
 void Stop() {
  if(stopped)return;
  stopped=true;
  auto callback=std::exchange(ready,{});
  Deliver(callback,Result<uint16_t>::Failure(Cancelled()));
  nw_listener_cancel(native);
 }
};
struct FactoryState {
 std::shared_ptr<Queue> queue=std::make_shared<Queue>("com.unishare.network");
 std::vector<std::weak_ptr<SocketState>> sockets;std::vector<std::shared_ptr<Listener>> listeners;
 void Adopt(nw_connection_t connection,std::function<void(Result<std::shared_ptr<ISocket>>)> ready) {
  auto s=std::make_shared<SocketState>();s->queue=queue;s->connection=connection;s->ready=std::move(ready);
  sockets.erase(std::remove_if(sockets.begin(),sockets.end(),[](auto& w){return w.expired();}),sockets.end());sockets.push_back(s);
  nw_connection_set_queue(connection,queue->Native());
  nw_connection_set_state_changed_handler(connection,^(nw_connection_state_t state,nw_error_t error) {
   if(state==nw_connection_state_ready&&!s->closed&&s->ready) {
    s->remote=Canonical(nw_connection_copy_endpoint(s->connection));
    auto cb=std::exchange(s->ready,{});
    if(s->remote.empty()) {s->Stop(Failure(ErrorCode::NetworkLost,"missing numeric peer endpoint"));Deliver(cb,Result<std::shared_ptr<ISocket>>::Failure(Failure(ErrorCode::NetworkLost,"missing numeric peer endpoint")));}
    else Deliver(cb,Result<std::shared_ptr<ISocket>>::Success(std::make_shared<Socket>(s)));
   } else if(state==nw_connection_state_failed||state==nw_connection_state_waiting)s->Stop(NetworkError(error));
   else if(state==nw_connection_state_cancelled) {s->Stop(Cancelled());nw_connection_set_state_changed_handler(s->connection,nil);s->connection=nil;}
  });
  nw_connection_start(connection);
 }
};
class Sockets final : public ISocketFactory {
 std::shared_ptr<FactoryState> state_=std::make_shared<FactoryState>();
public:
 ~Sockets(){Stop();}
 OperationId Connect(std::string address,uint16_t port,ResultCallback<std::shared_ptr<ISocket>> callback) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,address=std::move(address),port,callback] {
   addrinfo hints{},*raw=nullptr;hints.ai_flags=AI_NUMERICHOST|AI_NUMERICSERV;hints.ai_family=AF_UNSPEC;hints.ai_socktype=SOCK_STREAM;
   int code=getaddrinfo(address.c_str(),std::to_string(port).c_str(),&hints,&raw);
   if(code||!port) {if(raw)freeaddrinfo(raw);Deliver(callback,Result<std::shared_ptr<ISocket>>::Failure(Failure(ErrorCode::InvalidInput,"numeric address and nonzero port required",code)));return;}
   nw_endpoint_t endpoint=nw_endpoint_create_address(raw->ai_addr);freeaddrinfo(raw);
   nw_parameters_t parameters=nw_parameters_create_secure_tcp(NW_PARAMETERS_DISABLE_PROTOCOL,NW_PARAMETERS_DEFAULT_CONFIGURATION);
   nw_connection_t connection=nw_connection_create(endpoint,parameters);
   if(!connection){Deliver(callback,Result<std::shared_ptr<ISocket>>::Failure(Failure(ErrorCode::NetworkLost,"connection creation failed")));return;}
   s->Adopt(connection,callback);
  });return id;
 }
 OperationId Listen(uint16_t port,std::function<void(Result<std::shared_ptr<ISocket>>)> callback) override {return ListenBound(port,[](auto){},std::move(callback));}
 OperationId ListenBound(uint16_t port,ResultCallback<uint16_t> ready,std::function<void(Result<std::shared_ptr<ISocket>>)> callback) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,port,ready,callback] {
   auto listener=std::make_shared<Listener>();listener->ready=ready;listener->accept=callback;
   nw_parameters_t parameters=nw_parameters_create_secure_tcp(NW_PARAMETERS_DISABLE_PROTOCOL,NW_PARAMETERS_DEFAULT_CONFIGURATION);
   listener->native=nw_listener_create_with_port(std::to_string(port).c_str(),parameters);
   if(!listener->native){Deliver(ready,Result<uint16_t>::Failure(Failure(ErrorCode::NetworkLost,"listener creation failed")));return;}
   s->listeners.push_back(listener);nw_listener_set_queue(listener->native,s->queue->Native());
   nw_listener_set_new_connection_handler(listener->native,^(nw_connection_t connection) {
    if(listener->stopped)nw_connection_cancel(connection);else s->Adopt(connection,listener->accept);
   });
   nw_listener_set_state_changed_handler(listener->native,^(nw_listener_state_t state,nw_error_t error) {
    if(state==nw_listener_state_ready&&!listener->stopped) {auto cb=std::exchange(listener->ready,{});Deliver(cb,Result<uint16_t>::Success(nw_listener_get_port(listener->native)));}
    else if(state==nw_listener_state_failed||state==nw_listener_state_waiting) {
     auto cb=std::exchange(listener->ready,{});if(cb)Deliver(cb,Result<uint16_t>::Failure(NetworkError(error)));
     else Deliver(listener->accept,Result<std::shared_ptr<ISocket>>::Failure(NetworkError(error)));
     listener->Stop();
    } else if(state==nw_listener_state_cancelled) {
     nw_listener_set_new_connection_handler(listener->native,nil);nw_listener_set_state_changed_handler(listener->native,nil);listener->native=nil;
    }
   });nw_listener_start(listener->native);
  });return id;
 }
 void Stop() override {
  auto state=state_;
  state->queue->Sync([state] {
   // Cancellation invokes client callbacks synchronously on this queue. Detach
   // BOTH collections before any callback can recursively Stop this factory.
   // Local snapshots keep iteration valid even if callbacks retire other ports.
   auto listeners=std::exchange(state->listeners,{});
   auto sockets=std::exchange(state->sockets,{});
   for(const auto& listener:listeners)listener->Stop();
   for(const auto& weak:sockets) {
    if(auto socket=weak.lock())socket->Stop(Cancelled());
   }
  });
 }
};
}
std::shared_ptr<ISocketFactory> MakeSockets() {return std::make_shared<Sockets>();}
}
