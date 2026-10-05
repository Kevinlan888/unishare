#include "platform/android/adapter.hpp"
#include "core/identity.hpp"
#include "core/device_catalog.hpp"
#include "unishare/c_api_bridge.hpp"
#include <future>
#include <stdexcept>
#include <cstring>
using namespace unishare;
#define CHECK(value) do{if(!(value))throw std::runtime_error(#value);}while(false)
template<class T>T Await(std::future<T> f){if(f.wait_for(std::chrono::seconds(15))!=std::future_status::ready)throw std::runtime_error("native callback timeout");return f.get();}
extern "C" JNIEXPORT jstring JNICALL Java_com_unishare_sdk_internal_NativeProbe_run(JNIEnv* env,jclass,jobject framework,jstring sourceUri){try{
 auto platform=android::CreatePlatform(env,framework);auto identity=core::IdentityManager::LoadOrCreate(platform->IdentityStore());CHECK(identity.ok());
 auto again=core::IdentityManager::LoadOrCreate(platform->IdentityStore());CHECK(again.ok());CHECK(identity.value().material.id==again.value().material.id);
 CHECK(identity.value().material.private_key==again.value().material.private_key);
 TrustedDevice peer;peer.id.bytes[15]=1;peer.public_key_fingerprint.fill(42);CHECK(platform->IdentityStore().SaveTrust(peer).ok());CHECK(platform->IdentityStore().SaveTrust(peer).ok());
 peer.public_key_fingerprint.fill(43);auto mismatch=platform->IdentityStore().SaveTrust(peer);CHECK(!mismatch.ok());CHECK(mismatch.error().code==ErrorCode::IdentityChanged);
 auto trust=platform->IdentityStore().LoadTrust();CHECK(trust.ok());CHECK(trust.value().size()==1);CHECK(trust.value()[0].public_key_fingerprint[0]==42);
 CHECK(platform->IdentityStore().RemoveTrust(peer.id).ok());CHECK(platform->IdentityStore().RemoveTrust(peer.id).ok());CHECK(platform->IdentityStore().LoadTrust().value().empty());
 auto source=platform->Streams().OpenSource("file:///etc/hosts");CHECK(!source.ok());CHECK(source.error().code==ErrorCode::InvalidInput);
 const char* text=env->GetStringUTFChars(sourceUri,nullptr);std::string uri(text);env->ReleaseStringUTFChars(sourceUri,text);
 auto destination=platform->Streams().OpenDestination(uri);CHECK(destination.ok());std::promise<Result<void>> written;destination.value()->Write({11,22,33},[&](auto r){written.set_value(r);});CHECK(Await(written.get_future()).ok());destination.value()->Close();
 source=platform->Streams().OpenSource(uri);CHECK(source.ok());std::promise<Result<std::vector<uint8_t>>> read;source.value()->Read(2,[&](auto r){read.set_value(r);});auto bytes=Await(read.get_future());CHECK(bytes.ok()&&bytes.value()==std::vector<uint8_t>({11,22}));source.value()->Close();
 destination=platform->Streams().OpenDestination(uri);CHECK(destination.ok());destination.value()->AbortAndCleanup();
 std::promise<Result<void>> permission;android::Permissions(platform)->RequestLocalNetwork([&](auto r){permission.set_value(r);});CHECK(Await(permission.get_future()).ok());
 auto retainedPermission=android::Permissions(platform);usdk_platform_handle token=0;CHECK(!abi::RegisterPlatform(platform,{},retainedPermission,&token).code);
 usdk_config config;CHECK(!usdk_config_init(1,&config).code);const char name[]="Android native probe";config.device_name={reinterpret_cast<const uint8_t*>(name),sizeof(name)-1};
 struct Completion {std::promise<usdk_error> promise;static void Call(void* p,const usdk_result* r){static_cast<Completion*>(p)->promise.set_value(r->error);}};
 Completion created;usdk_handle sdk=0;usdk_operation_id operation=0;
 CHECK(!usdk_create(token,&config,&Completion::Call,&created,&sdk,&operation).code);CHECK(!usdk_platform_release(token).code);platform.reset();
 CHECK(!Await(created.promise.get_future()).code);Completion started;CHECK(!usdk_start(sdk,&Completion::Call,&started,&operation).code);auto start=Await(started.promise.get_future());CHECK(!start.code);
 Completion stopped;CHECK(!usdk_stop(sdk,&Completion::Call,&stopped,&operation).code);CHECK(!Await(stopped.promise.get_future()).code);CHECK(!usdk_destroy(sdk).code);
 std::promise<Result<void>> retired;retainedPermission->RequestLocalNetwork([&](auto r){retired.set_value(r);});auto cancelled=Await(retired.get_future());CHECK(!cancelled.ok()&&cancelled.error().code==ErrorCode::Cancelled);
 return env->NewStringUTF("PASS native core identity/trust/URI streaming/permission/C ABI retained lifecycle");
 }catch(const std::exception& e){return env->NewStringUTF(e.what());}catch(const ShareError& e){return env->NewStringUTF(e.message.c_str());}catch(...){return env->NewStringUTF("unknown native test exception");}}

extern "C" JNIEXPORT jstring JNICALL Java_com_unishare_sdk_internal_NativeProbe_catalog(JNIEnv* env,jclass,jobject framework){try{
 auto platform=android::CreatePlatform(env,framework);core::DeviceCatalog catalog(DeviceId{});
 auto received=std::make_shared<std::promise<Result<ServiceRecord>>>();auto delivered=std::make_shared<std::atomic<bool>>(false);platform->DnsSd().BrowseEvents("_share._tcp.local.",[received,delivered](auto r){if(!delivered->exchange(true))received->set_value(std::move(r));},[](auto){});
 auto record=Await(received->get_future());CHECK(record.ok());auto events=catalog.Apply(record.value());
 CHECK(events.size()==1 && events[0].kind==DiscoveryEventKind::Online);
 CHECK(events[0].device.id==*Uuid::Parse("12345678-1234-4234-8234-123456789abc"));
 CHECK(events[0].device.port==12345 && events[0].device.addresses==record.value().addresses);
 platform->DnsSd().Stop();return env->NewStringUTF("PASS resolved Android record reaches catalog Online");
 }catch(const std::exception& e){return env->NewStringUTF(e.what());}catch(...){return env->NewStringUTF("native catalog regression failed");}}