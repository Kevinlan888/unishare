#include "sockets.hpp"
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <sys/socket.h>
#include <atomic>
#include <mutex>
#include <map>
#include <thread>
#include <fcntl.h>
#include <poll.h>
#include <algorithm>
#include <cstring>
namespace unishare::android {
namespace {
std::atomic<OperationId> next{1};
ShareError error(int e,const char* text){return {e==EACCES||e==EPERM?ErrorCode::PermissionDenied:ErrorCode::NetworkLost,text,e};}
ShareError cancelled(){return {ErrorCode::Cancelled,"socket cancelled",{}};}
struct Fd {
 int fd;std::atomic<bool> closed{false};explicit Fd(int f):fd(f){}
 ~Fd(){::close(fd);}void Stop() noexcept {if(!closed.exchange(true))::shutdown(fd,SHUT_RDWR);}
};
std::string endpoint(int fd){sockaddr_storage peer{};socklen_t size=sizeof(peer);if(getpeername(fd,reinterpret_cast<sockaddr*>(&peer),&size))throw error(errno,"getpeername");
 char text[INET6_ADDRSTRLEN];if(peer.ss_family==AF_INET){auto* p=reinterpret_cast<sockaddr_in*>(&peer);inet_ntop(AF_INET,&p->sin_addr,text,sizeof(text));return text;}
 auto* p=reinterpret_cast<sockaddr_in6*>(&peer);if(IN6_IS_ADDR_V4MAPPED(&p->sin6_addr)){inet_ntop(AF_INET,p->sin6_addr.s6_addr+12,text,sizeof(text));return text;}
 inet_ntop(AF_INET6,&p->sin6_addr,text,sizeof(text));return std::string(text)+(p->sin6_scope_id?"%"+std::to_string(p->sin6_scope_id):"");
}
class Socket final : public ISocket {
 struct Pending {std::function<void(ShareError)> cancel;};
 struct State {std::shared_ptr<Fd> fd;std::mutex mutex;std::map<OperationId,Pending> pending;bool reading=false,writing=false;};
 std::shared_ptr<State> s_;std::string remote_;
 template<class T> OperationId Submit(bool writing,ResultCallback<T> cb,std::function<Result<T>(int)> action){
  auto id=next++;auto s=s_;auto finish=std::make_shared<ResultCallback<T>>(std::move(cb));
  std::optional<ShareError> rejection;
  {std::lock_guard<std::mutex> lock(s->mutex);if(s->fd->closed)rejection=cancelled();
   else{bool& busy=writing?s->writing:s->reading;if(busy)rejection=ShareError{ErrorCode::ResourceLimit,"one I/O per direction",{}};
   else{busy=true;s->pending.emplace(id,Pending{[finish](auto e){(*finish)(Result<T>::Failure(e));}});}}}
  if(rejection){(*finish)(Result<T>::Failure(*rejection));return id;}
  try{std::thread([s,id,writing,finish,action=std::move(action)](){auto result=[&]{try{return action(s->fd->fd);}catch(...){return Result<T>::Failure({ErrorCode::ResourceLimit,"socket worker failed",{}});}}();bool deliver=false;
   {std::lock_guard<std::mutex> lock(s->mutex);auto p=s->pending.find(id);if(p!=s->pending.end()){s->pending.erase(p);deliver=true;}(writing?s->writing:s->reading)=false;}
   if(deliver)try{(*finish)(s->fd->closed?Result<T>::Failure(cancelled()):std::move(result));}catch(...){} }).detach();}
  catch(...){{std::lock_guard<std::mutex> lock(s->mutex);s->pending.erase(id);(writing?s->writing:s->reading)=false;}(*finish)(Result<T>::Failure({ErrorCode::ResourceLimit,"worker unavailable",{}}));}
  return id;
 }
public:
 explicit Socket(std::shared_ptr<Fd> fd):s_(std::make_shared<State>()),remote_(endpoint(fd->fd)){s_->fd=std::move(fd);}
 ~Socket(){Close();}
 std::string RemoteEndpoint()const override{return remote_;}
 OperationId Read(std::size_t n,ResultCallback<std::vector<uint8_t>> cb)override{
  if(n==0||n>4*1024*1024){auto id=next++;cb(Result<std::vector<uint8_t>>::Failure({ErrorCode::InvalidInput,"read size",{}}));return id;}
  return Submit<std::vector<uint8_t>>(false,std::move(cb),[n](int fd){std::vector<uint8_t> bytes(n);ssize_t count;do{count=recv(fd,bytes.data(),n,0);}while(count<0&&errno==EINTR);
   if(count<0)return Result<std::vector<uint8_t>>::Failure(error(errno,"recv"));bytes.resize(static_cast<std::size_t>(count));return Result<std::vector<uint8_t>>::Success(std::move(bytes));});
 }
 OperationId Write(std::vector<uint8_t> bytes,ResultCallback<void> cb)override{return Submit<void>(true,std::move(cb),[bytes=std::move(bytes)](int fd){std::size_t offset=0;
  while(offset<bytes.size()){ssize_t count=send(fd,bytes.data()+offset,bytes.size()-offset,MSG_NOSIGNAL);if(count<0&&errno==EINTR)continue;if(count<=0)return Result<void>::Failure(error(count<0?errno:EPIPE,"send"));offset+=static_cast<std::size_t>(count);}return Result<void>::Success();});}
 void Close()override{std::map<OperationId,Pending> pending;s_->fd->Stop();{std::lock_guard<std::mutex> lock(s_->mutex);pending.swap(s_->pending);}for(auto& item:pending)try{item.second.cancel(cancelled());}catch(...) {}}
 void AbortAndCleanup()override{Close();}
 void Cancel(OperationId id)override{bool found;{std::lock_guard<std::mutex> lock(s_->mutex);found=s_->pending.count(id)!=0;}if(found)Close();}
};
}
struct SocketFactory::State {std::mutex mutex;std::uint64_t generation=0;std::vector<std::weak_ptr<Fd>> sockets;
 void Track(const std::shared_ptr<Fd>& fd){sockets.erase(std::remove_if(sockets.begin(),sockets.end(),[](const auto& w){return w.expired();}),sockets.end());sockets.push_back(fd);}
};
SocketFactory::SocketFactory():state_(std::make_shared<State>()){}
SocketFactory::~SocketFactory(){Stop();}
OperationId SocketFactory::Connect(std::string address,std::uint16_t port,ResultCallback<std::shared_ptr<ISocket>> cb){auto id=next++;addrinfo hints{},*raw=nullptr;
 hints.ai_family=AF_UNSPEC;hints.ai_socktype=SOCK_STREAM;hints.ai_flags=AI_NUMERICHOST|AI_NUMERICSERV;
 int lookup=getaddrinfo(address.c_str(),std::to_string(port).c_str(),&hints,&raw);
 if(lookup||!port){if(raw)freeaddrinfo(raw);cb(Result<std::shared_ptr<ISocket>>::Failure({ErrorCode::InvalidInput,"numeric IP and nonzero port required",lookup?std::optional<int64_t>(lookup):std::nullopt}));return id;}
 std::unique_ptr<addrinfo,decltype(&freeaddrinfo)> info(raw,freeaddrinfo);int native=socket(raw->ai_family,SOCK_STREAM|SOCK_CLOEXEC,0);
 if(native<0){cb(Result<std::shared_ptr<ISocket>>::Failure(error(errno,"socket")));return id;}auto fd=std::make_shared<Fd>(native);auto s=state_;
 {std::lock_guard<std::mutex> lock(s->mutex);s->Track(fd);}sockaddr_storage destination{};memcpy(&destination,raw->ai_addr,raw->ai_addrlen);auto length=raw->ai_addrlen;
 auto completion=std::make_shared<ResultCallback<std::shared_ptr<ISocket>>>(std::move(cb));
 try{std::thread([fd,destination,length,completion](){int flags=fcntl(fd->fd,F_GETFL,0);fcntl(fd->fd,F_SETFL,flags|O_NONBLOCK);int result=connect(fd->fd,reinterpret_cast<const sockaddr*>(&destination),length);int code=errno;
  if(result<0&&code==EINPROGRESS){pollfd item{fd->fd,POLLOUT,0};while(!fd->closed){int polled=poll(&item,1,100);if(polled<0&&errno==EINTR)continue;if(polled<0){code=errno;break;}if(polled){socklen_t size=sizeof(code);if(getsockopt(fd->fd,SOL_SOCKET,SO_ERROR,&code,&size))code=errno;result=code? -1:0;break;}}}
  fcntl(fd->fd,F_SETFL,flags);
  auto& cb=*completion;
  if(fd->closed){cb(Result<std::shared_ptr<ISocket>>::Failure(cancelled()));return;}
  if(result){cb(Result<std::shared_ptr<ISocket>>::Failure(error(code,"connect")));return;}
  try{cb(Result<std::shared_ptr<ISocket>>::Success(std::make_shared<Socket>(fd)));}catch(const ShareError& e){cb(Result<std::shared_ptr<ISocket>>::Failure(e));}}).detach();}
 catch(...){(*completion)(Result<std::shared_ptr<ISocket>>::Failure({ErrorCode::ResourceLimit,"connect worker unavailable",{}}));}return id;
}
OperationId SocketFactory::Listen(std::uint16_t p,std::function<void(Result<std::shared_ptr<ISocket>>)> cb){return ListenBound(p,[](auto){},std::move(cb));}
OperationId SocketFactory::ListenBound(std::uint16_t port,ResultCallback<std::uint16_t> ready,std::function<void(Result<std::shared_ptr<ISocket>>)> cb){auto id=next++;auto s=state_;
 int native=socket(AF_INET6,SOCK_STREAM|SOCK_CLOEXEC,0);if(native<0){ready(Result<std::uint16_t>::Failure(error(errno,"listener socket")));return id;}auto fd=std::make_shared<Fd>(native);
 int dual=0;if(setsockopt(native,IPPROTO_IPV6,IPV6_V6ONLY,&dual,sizeof(dual))){ready(Result<std::uint16_t>::Failure(error(errno,"dual-stack listener")));return id;}sockaddr_in6 addr{};addr.sin6_family=AF_INET6;addr.sin6_port=htons(port);addr.sin6_addr=in6addr_any;
 if(bind(native,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))||listen(native,16)){ready(Result<std::uint16_t>::Failure(error(errno,"bind/listen")));return id;}
 socklen_t length=sizeof(addr);if(getsockname(native,reinterpret_cast<sockaddr*>(&addr),&length)){ready(Result<std::uint16_t>::Failure(error(errno,"listener port")));return id;}
 std::uint64_t generation;{std::lock_guard<std::mutex> lock(s->mutex);generation=s->generation;s->Track(fd);}
 ready(Result<std::uint16_t>::Success(ntohs(addr.sin6_port)));
 std::thread([s,fd,generation,cb=std::move(cb)](){while(!fd->closed){int native=accept4(fd->fd,nullptr,nullptr,SOCK_CLOEXEC);int code=errno;if(native<0){if(code==EINTR)continue;if(!fd->closed)cb(Result<std::shared_ptr<ISocket>>::Failure(error(code,"accept")));break;}
  auto peer=std::make_shared<Fd>(native);{std::lock_guard<std::mutex> lock(s->mutex);if(generation!=s->generation){peer->Stop();break;}s->Track(peer);}
  try{cb(Result<std::shared_ptr<ISocket>>::Success(std::make_shared<Socket>(peer)));}catch(const ShareError& e){cb(Result<std::shared_ptr<ISocket>>::Failure(e));}
 }}).detach();return id;
}
void SocketFactory::Stop(){std::lock_guard<std::mutex> lock(state_->mutex);++state_->generation;for(auto& weak:state_->sockets)if(auto fd=weak.lock())fd->Stop();state_->sockets.clear();}
}
