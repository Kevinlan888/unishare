#include "adapter.hpp"
#include "sockets.hpp"
#include "core/exception.hpp"
#include <openssl/crypto.h>
#include <map>
#include <mutex>
#include <atomic>
#include <limits>
#include <cstring>
#include <set>
namespace unishare::android {
namespace {
using Bytes=std::vector<uint8_t>;
struct Packet {
 Bytes bytes;std::size_t offset=0;
 Packet()=default;explicit Packet(Bytes b):bytes(std::move(b)){}Packet(Packet&&)=default;Packet& operator=(Packet&&)=default;Packet(const Packet&)=default;
 ~Packet(){if(!bytes.empty())OPENSSL_cleanse(bytes.data(),bytes.size());}
 void Put(uint64_t value,int n){for(int i=n-1;i>=0;--i)bytes.push_back(static_cast<uint8_t>(value>>(i*8)));}
 uint64_t Get(int n){if(offset+n>bytes.size())throw ShareError{ErrorCode::StorageFailed,"truncated JNI packet",{}};uint64_t v=0;while(n--)v=(v<<8)|bytes[offset++];return v;}
 void Blob(const Bytes& value){Put(value.size(),4);bytes.insert(bytes.end(),value.begin(),value.end());}
 Bytes Blob(){auto n=Get(4);if(n>bytes.size()-offset)throw ShareError{ErrorCode::StorageFailed,"JNI field length",{}};Bytes out(bytes.begin()+offset,bytes.begin()+offset+n);offset+=n;return out;}
 void Text(const std::string& value){Blob(Bytes(value.begin(),value.end()));}
 std::string Text(){auto b=Blob();return std::string(b.begin(),b.end());}
 void Raw(const uint8_t* data,std::size_t n){bytes.insert(bytes.end(),data,data+n);}
 void ReadRaw(uint8_t* data,std::size_t n){if(n>bytes.size()-offset)throw ShareError{ErrorCode::StorageFailed,"JNI record length",{}};memcpy(data,bytes.data()+offset,n);offset+=n;}
 void Status(){auto code=Get(4);auto native=static_cast<int64_t>(Get(8));auto text=Text();if(code)throw ShareError{static_cast<ErrorCode>(code),std::move(text),native==std::numeric_limits<int64_t>::min()?std::nullopt:std::optional<int64_t>(native)};}
};
struct Env {
 JavaVM* vm;JNIEnv* env=nullptr;bool attached=false;
 explicit Env(JavaVM* v):vm(v){if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)throw ShareError{ErrorCode::ResourceLimit,"attach JNI thread",{}};attached=true;}}
 ~Env(){if(attached)vm->DetachCurrentThread();}
};
struct Bridge;
std::mutex bridges_mutex;std::map<uint64_t,std::weak_ptr<Bridge>> bridges;std::atomic<uint64_t> sequence{1};
struct Bridge {
 JavaVM* vm; jobject object; jmethodID call,start,shutdown;uint64_t key=sequence++;
 struct Callback {bool once;std::function<void(Packet)> run;};
 std::mutex mutex;std::map<OperationId,Callback> callbacks;std::function<void()> network,suspend;std::optional<ShareError> watch_error;std::atomic<bool> retired{false};
 Bridge(JNIEnv* env,jobject framework){env->GetJavaVM(&vm);object=env->NewGlobalRef(framework);if(!object)throw std::bad_alloc();auto cls=env->GetObjectClass(framework);
  call=env->GetMethodID(cls,"call","(I[B)[B");start=env->GetMethodID(cls,"start","(IJ[B)V");shutdown=env->GetMethodID(cls,"shutdown","()V");env->DeleteLocalRef(cls);}
 ~Bridge(){try{{std::lock_guard<std::mutex> lock(bridges_mutex);bridges.erase(key);}Env e(vm);e.env->CallVoidMethod(object,shutdown);if(e.env->ExceptionCheck())e.env->ExceptionClear();e.env->DeleteGlobalRef(object);}catch(...) {}}
 Bytes Java(int command,const Bytes& data){Env e(vm);auto input=e.env->NewByteArray(static_cast<jsize>(data.size()));if(!input)throw std::bad_alloc();
  e.env->SetByteArrayRegion(input,0,static_cast<jsize>(data.size()),reinterpret_cast<const jbyte*>(data.data()));auto output=static_cast<jbyteArray>(e.env->CallObjectMethod(object,call,command,input));
  if(command==2){std::vector<jbyte> zero(data.size());e.env->SetByteArrayRegion(input,0,static_cast<jsize>(zero.size()),zero.data());}e.env->DeleteLocalRef(input);
  if(e.env->ExceptionCheck()){e.env->ExceptionClear();throw ShareError{ErrorCode::StorageFailed,"unexpected Java bridge exception",{}};}if(!output)throw std::bad_alloc();
  Bytes result(e.env->GetArrayLength(output));e.env->GetByteArrayRegion(output,0,static_cast<jsize>(result.size()),reinterpret_cast<jbyte*>(result.data()));
  if(command==1){std::vector<jbyte> zero(result.size());e.env->SetByteArrayRegion(output,0,static_cast<jsize>(zero.size()),zero.data());}e.env->DeleteLocalRef(output);return result;
 }
 Packet Call(int command,Bytes data={}){try{Packet packet{Java(command,data)};if(command==2)OPENSSL_cleanse(data.data(),data.size());packet.Status();return packet;}catch(...){if(command==2)OPENSSL_cleanse(data.data(),data.size());throw;}}
 OperationId Start(int command,Bytes data,std::function<void(Packet)> callback,bool once=true){auto id=sequence++;{std::lock_guard<std::mutex> lock(mutex);callbacks.emplace(id,Callback{once,std::move(callback)});}
  try{Env e(vm);auto input=e.env->NewByteArray(static_cast<jsize>(data.size()));if(!input)throw std::bad_alloc();e.env->SetByteArrayRegion(input,0,static_cast<jsize>(data.size()),reinterpret_cast<const jbyte*>(data.data()));
   e.env->CallVoidMethod(object,start,command,static_cast<jlong>(id),input);e.env->DeleteLocalRef(input);if(e.env->ExceptionCheck()){e.env->ExceptionClear();throw ShareError{ErrorCode::ResourceLimit,"Java async submission",{}};}}
  catch(...){Fail(id,core::CurrentException());}return id;
 }
 void Complete(OperationId id,Packet packet){std::function<void(Packet)> cb;{std::lock_guard<std::mutex> lock(mutex);auto it=callbacks.find(id);if(it==callbacks.end())return;cb=it->second.run;if(it->second.once)callbacks.erase(it);}try{cb(std::move(packet));}catch(...) {}}
 void Forget(OperationId id){std::lock_guard<std::mutex> lock(mutex);callbacks.erase(id);}
 void Fail(OperationId id,const ShareError& error){Packet p;p.Put(static_cast<uint32_t>(error.code),4);p.Put(static_cast<uint64_t>(error.platform_error.value_or(std::numeric_limits<int64_t>::min())),8);p.Text(error.message);Complete(id,std::move(p));}
};
template<class T,class F> Result<T> Outcome(F fn){try{return Result<T>::Success(fn());}catch(...){return Result<T>::Failure(core::CurrentException());}}
template<class F> Result<void> Done(F fn){try{fn();return Result<void>::Success();}catch(...){return Result<void>::Failure(core::CurrentException());}}
struct Identity final:IIdentityStore {
 std::shared_ptr<Bridge> b;explicit Identity(std::shared_ptr<Bridge> v):b(std::move(v)){}
 Result<std::optional<IdentityMaterial>> LoadIdentity()override{return Outcome<std::optional<IdentityMaterial>>([&](){auto p=b->Call(1);if(!p.Get(1))return std::optional<IdentityMaterial>{};IdentityMaterial m;p.ReadRaw(m.id.bytes.data(),16);m.private_key=p.Blob();m.certificate=p.Blob();OPENSSL_cleanse(p.bytes.data(),p.bytes.size());return std::optional<IdentityMaterial>(std::move(m));});}
 Result<void> SaveIdentity(const IdentityMaterial& m)override{Packet p;p.Raw(m.id.bytes.data(),16);p.Blob(m.private_key);p.Blob(m.certificate);auto result=Done([&]{b->Call(2,p.bytes);});OPENSSL_cleanse(p.bytes.data(),p.bytes.size());return result;}
 Result<std::vector<TrustedDevice>> LoadTrust()override{return Outcome<std::vector<TrustedDevice>>([&]{auto p=b->Call(3);auto count=p.Get(4);if(count>100000)throw ShareError{ErrorCode::StorageFailed,"trust count",{}};std::vector<TrustedDevice> trust;for(uint64_t i=0;i<count;++i){TrustedDevice d;p.ReadRaw(d.id.bytes.data(),16);p.ReadRaw(d.public_key_fingerprint.data(),32);trust.push_back(d);}return trust;});}
 Result<void> SaveTrust(const TrustedDevice& d)override{return Done([&]{Packet p;p.Raw(d.id.bytes.data(),16);p.Raw(d.public_key_fingerprint.data(),32);b->Call(4,p.bytes);});}
 Result<void> RemoveTrust(DeviceId id)override{return Done([&]{Packet p;p.Raw(id.bytes.data(),16);b->Call(5,p.bytes);});}
};
struct Stream final:IStream {
 struct State {std::mutex mutex;bool closed=false;std::set<OperationId> operations;};std::shared_ptr<Bridge> b;uint64_t handle;std::shared_ptr<State> state=std::make_shared<State>();
 Stream(std::shared_ptr<Bridge> bridge,uint64_t id):b(std::move(bridge)),handle(id){}~Stream(){Close();}
 OperationId Read(std::size_t n,ResultCallback<Bytes> cb)override{if(!n||n>4*1024*1024){auto id=sequence++;cb(Result<Bytes>::Failure({ErrorCode::InvalidInput,"stream read size",{}}));return id;}Packet p;p.Put(handle,8);p.Put(n,4);return Submit(12,p.bytes,[cb=std::move(cb)](Packet p){cb(Outcome<Bytes>([&]{p.Status();return Bytes(p.bytes.begin()+p.offset,p.bytes.end());}));});}
 OperationId Write(Bytes bytes,ResultCallback<void> cb)override{Packet p;p.Put(handle,8);p.Blob(bytes);return Submit(13,p.bytes,[cb=std::move(cb)](Packet p){cb(Done([&]{p.Status();}));});}
 OperationId Submit(int command,Bytes bytes,std::function<void(Packet)> cb){
  struct Ticket {OperationId id=0;bool finished=false;};auto ticket=std::make_shared<Ticket>();auto s=state;
  bool closed;{std::lock_guard<std::mutex> lock(s->mutex);closed=s->closed;}
  if(closed){auto id=sequence++;Packet p;p.Put(8,4);p.Put(std::numeric_limits<int64_t>::min(),8);p.Text("stream closed");cb(std::move(p));return id;}
  auto id=b->Start(command,std::move(bytes),[s,ticket,cb=std::move(cb)](Packet p){bool closed;
   {std::lock_guard<std::mutex> lock(s->mutex);ticket->finished=true;if(ticket->id)s->operations.erase(ticket->id);closed=s->closed;}
   if(closed){p=Packet{};p.Put(8,4);p.Put(std::numeric_limits<int64_t>::min(),8);p.Text("stream closed");}cb(std::move(p));});
  bool cancel;{std::lock_guard<std::mutex> lock(s->mutex);ticket->id=id;cancel=s->closed&&!ticket->finished;if(!ticket->finished&&!cancel)s->operations.insert(id);}
  if(cancel)b->Fail(id,{ErrorCode::Cancelled,"stream closed",{}});return id;
 }
 void Close()override{Stop(false);}void AbortAndCleanup()override{Stop(true);}
 void Stop(bool abort)noexcept{try{std::set<OperationId> pending;{std::lock_guard<std::mutex> lock(state->mutex);if(state->closed)return;state->closed=true;pending.swap(state->operations);}for(auto id:pending)b->Fail(id,{ErrorCode::Cancelled,"stream closed",{}});Packet p;p.Put(handle,8);b->Call(abort?15:14,p.bytes);}catch(...) {}}
};
struct Streams final:IStreamFactory {std::shared_ptr<Bridge> b;explicit Streams(std::shared_ptr<Bridge> v):b(std::move(v)){}
 Result<std::shared_ptr<IStream>> Open(std::string uri,bool write){return Outcome<std::shared_ptr<IStream>>([&]{Packet p;p.Text(uri);auto result=b->Call(write?11:10,p.bytes);return std::make_shared<Stream>(b,result.Get(8));});}
 Result<std::shared_ptr<IStream>> OpenSource(std::string uri)override{return Open(std::move(uri),false);}
 Result<std::shared_ptr<IStream>> OpenDestination(std::string uri)override{return Open(std::move(uri),true);}
};
struct Dns final:IDnsSd {std::shared_ptr<Bridge> b;std::mutex mutex;std::vector<OperationId> operations;explicit Dns(std::shared_ptr<Bridge> v):b(std::move(v)){}~Dns(){Stop();}
 OperationId Publish(ServiceRecord record,ResultCallback<void> cb)override{Packet p;p.Text(record.instance);p.Put(record.port,4);p.Put(record.txt.size(),4);for(auto& item:record.txt){p.Text(item.first);p.Text(item.second);}
  std::lock_guard<std::mutex> lock(mutex);auto id=b->Start(40,p.bytes,[cb=std::move(cb)](Packet p){cb(Done([&]{p.Status();}));});operations.push_back(id);return id;}
 OperationId Browse(std::string type,std::function<void(Result<ServiceRecord>)> found)override{return BrowseEvents(std::move(type),std::move(found),[](auto){});}
 OperationId BrowseEvents(std::string type,std::function<void(Result<ServiceRecord>)> found,std::function<void(ServiceInstanceId)> lost)override{
  if(type!="_share._tcp.local."){found(Result<ServiceRecord>::Failure({ErrorCode::InvalidInput,"service type",{}}));return sequence++;}
  std::lock_guard<std::mutex> lock(mutex);auto id=b->Start(41,{},[found=std::move(found),lost=std::move(lost)](Packet p){try{p.Status();auto kind=p.Get(4);if(kind==1){ServiceInstanceId id;id.instance=p.Text();id.interface_id=p.Text();lost(std::move(id));return;}
   ServiceRecord r;r.service_type="_share._tcp.local.";r.instance=p.Text();r.interface_id=p.Text();r.srv_target=p.Text();r.port=static_cast<uint16_t>(p.Get(4));auto n=p.Get(4);if(n>32)throw ShareError{ErrorCode::DiscoveryFailed,"TXT count",{}};
   for(uint64_t i=0;i<n;++i){auto key=p.Text();auto value=p.Text();r.txt.emplace_back(std::move(key),std::move(value));}n=p.Get(4);if(n>128)throw ShareError{ErrorCode::DiscoveryFailed,"address count",{}};for(uint64_t i=0;i<n;++i)r.addresses.push_back(p.Text());found(Result<ServiceRecord>::Success(std::move(r)));
  }catch(...){found(Result<ServiceRecord>::Failure(core::CurrentException()));}},false);operations.push_back(id);return id;
 }
 void Stop()override{try{std::vector<OperationId> ids;{std::lock_guard<std::mutex> lock(mutex);ids.swap(operations);}try{b->Call(42);}catch(...){}for(auto id:ids){b->Fail(id,{ErrorCode::Cancelled,"DNS stopped",{}});b->Forget(id);}}catch(...) {}}
};
struct Clipboard final:IPlatformClipboard {std::shared_ptr<Bridge> b;explicit Clipboard(std::shared_ptr<Bridge> v):b(std::move(v)){}
 OperationId Read(ResultCallback<ShareContent> cb)override{return b->Start(30,{},[cb=std::move(cb)](Packet p){cb(Outcome<ShareContent>([&]{p.Status();ShareContent c;c.type=static_cast<ContentType>(p.Get(4));c.text=p.Text();c.mime_type=p.Text();c.image=p.Blob();return c;}));});}
 OperationId Write(ShareContent c,ResultCallback<void> cb)override{Packet p;p.Put(static_cast<uint32_t>(c.type),4);p.Text(c.text);p.Text(c.mime_type);p.Blob(c.image);return b->Start(31,p.bytes,[cb=std::move(cb)](Packet p){cb(Done([&]{p.Status();}));});}
};
struct Permission final:IPermissionAgent {std::shared_ptr<Bridge> b;explicit Permission(std::shared_ptr<Bridge> v):b(std::move(v)){}
 void RequestLocalNetwork(ResultCallback<void> cb)override{if(b->retired){cb(Result<void>::Failure({ErrorCode::Cancelled,"platform retired",{}}));return;}std::optional<ShareError> error;{std::lock_guard<std::mutex> lock(b->mutex);error=b->watch_error;}if(error){cb(Result<void>::Failure(*error));return;}cb(Done([&]{b->Call(60);}));}
};
struct PlatformImpl final:IPlatform {
 std::shared_ptr<Bridge> b;Dns dns;SocketFactory sockets;Identity identity;struct Streams streams;struct Clipboard clipboard;std::shared_ptr<Permission> permission;std::mutex watch_mutex;
 explicit PlatformImpl(std::shared_ptr<Bridge> bridge):b(bridge),dns(bridge),identity(bridge),streams(bridge),clipboard(bridge),permission(std::make_shared<Permission>(bridge)){}
 ~PlatformImpl(){b->retired=true;try{b->Call(62);}catch(...){}try{SetNetworkChangeHandler({});}catch(...){}try{sockets.Stop();}catch(...){}try{dns.Stop();}catch(...) {}}
 IDnsSd& DnsSd()override{return dns;}ISocketFactory& Sockets()override{return sockets;}IIdentityStore& IdentityStore()override{return identity;}IStreamFactory& Streams()override{return streams;}IPlatformClipboard& Clipboard()override{return clipboard;}
 std::shared_ptr<IExecutor> DefaultExecutor()override{return {};}
 unishare::Platform PlatformKind()const override{return unishare::Platform::Android;}
 void SetNetworkChangeHandler(std::function<void()> handler)override{std::lock_guard<std::mutex> watch_lock(watch_mutex);bool enabled=static_cast<bool>(handler);{std::lock_guard<std::mutex> lock(b->mutex);b->network=std::move(handler);}Packet p;p.Put(enabled,1);auto result=Done([&]{b->Call(61,p.bytes);});{std::lock_guard<std::mutex> lock(b->mutex);b->watch_error=result.ok()?std::nullopt:std::optional<ShareError>(result.error());}}
};
std::shared_ptr<Bridge> Find(uint64_t key){std::lock_guard<std::mutex> lock(bridges_mutex);auto it=bridges.find(key);return it==bridges.end()?nullptr:it->second.lock();}
}
std::shared_ptr<IPlatform> CreatePlatform(JNIEnv* env,jobject framework){auto bridge=std::make_shared<Bridge>(env,framework);{std::lock_guard<std::mutex> lock(bridges_mutex);bridges.emplace(bridge->key,bridge);}auto cls=env->GetObjectClass(framework);env->SetLongField(framework,env->GetFieldID(cls,"nativeKey","J"),bridge->key);env->DeleteLocalRef(cls);
 auto platform=std::make_shared<PlatformImpl>(bridge);bridge->suspend=[weak=std::weak_ptr<PlatformImpl>(platform)]{if(auto p=weak.lock()){p->sockets.Stop();p->dns.Stop();std::function<void()> handler;{std::lock_guard<std::mutex> lock(p->b->mutex);handler=p->b->network;}if(handler)handler();}};return platform;
}
std::shared_ptr<IPermissionAgent> Permissions(const std::shared_ptr<IPlatform>& platform){return std::dynamic_pointer_cast<PlatformImpl>(platform)->permission;}
}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_NativeBridge_complete(JNIEnv* env,jclass,jlong key,jlong id,jbyteArray bytes){try{auto bridge=unishare::android::Find(key);if(!bridge)return;unishare::android::Packet p;p.bytes.resize(env->GetArrayLength(bytes));env->GetByteArrayRegion(bytes,0,p.bytes.size(),reinterpret_cast<jbyte*>(p.bytes.data()));bridge->Complete(id,std::move(p));}catch(...) {}}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_NativeBridge_networkChanged(JNIEnv*,jclass,jlong key){try{auto b=unishare::android::Find(key);if(!b)return;std::function<void()> handler;{std::lock_guard<std::mutex> lock(b->mutex);handler=b->network;}if(handler)handler();}catch(...) {}}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_NativeBridge_suspended(JNIEnv*,jclass,jlong key){try{auto b=unishare::android::Find(key);if(b&&b->suspend)b->suspend();}catch(...) {}}
