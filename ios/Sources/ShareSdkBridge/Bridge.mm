#include "Ports.hpp"
#import "include/USBridge.h"
#include <mutex>
#include <map>

namespace {
using Packet = NSDictionary<NSString*,id>*;
using Reply = void (^)(Packet);
NSData* Bytes(usdk_span s) { return [NSData dataWithBytes:s.data length:static_cast<NSUInteger>(s.length)]; }
NSString* Text(usdk_span s) { return [[NSString alloc] initWithData:Bytes(s) encoding:NSUTF8StringEncoding] ?: @""; }
NSUUID* UUID(usdk_uuid id) { return [[NSUUID alloc] initWithUUIDBytes:id.bytes]; }
usdk_uuid NativeUUID(NSUUID* id) { usdk_uuid value{}; if([id isKindOfClass:NSUUID.class])[id getUUIDBytes:value.bytes];return value; }
usdk_span Span(NSData* data) { return {static_cast<const uint8_t*>(data.bytes),data.length}; }
NSData* UTF8(NSString* text) { return [text dataUsingEncoding:NSUTF8StringEncoding] ?: NSData.data; }
NSMutableDictionary* Error(usdk_error e) { auto d=[NSMutableDictionary dictionaryWithObject:@(e.code) forKey:@"error"];if(e.has_platform_error)d[@"platformError"]=@(e.platform_error);return d; }
Packet Device(const usdk_device& d) {
 auto addresses=[NSMutableArray array];for(uint64_t i=0;i<d.address_count;++i)[addresses addObject:Text(d.addresses[i])];
 return @{@"id":UUID(d.id),@"name":Text(d.name),@"platform":@(d.platform),@"major":@(d.protocol_major),@"minor":@(d.protocol_minor),@"port":@(d.port),@"paired":@(d.paired),@"lastSeen":@(d.last_seen_unix_ms),@"addresses":addresses};
}
Packet Result(const usdk_result& r) {
 auto d=Error(r.error);d[@"transfer"]=UUID(r.transfer);
 if(r.content.type){d[@"contentType"]=@(r.content.type);d[@"text"]=Text(r.content.text);d[@"mime"]=Text(r.content.mime_type);d[@"bytes"]=Bytes(r.content.image);}return d;
}
Packet Event(const usdk_event& e) {
 auto d=Error(e.error);[d addEntriesFromDictionary:@{@"kind":@(e.kind),@"state":@(e.state),@"request":@(e.request),@"transfer":UUID(e.transfer),@"device":Device(e.device),@"code":Text(e.verification_code),@"contentType":@(e.content_type),@"name":Text(e.display_name),@"mime":Text(e.mime_type),@"size":@(e.byte_count),@"transferred":@(e.transferred)}];return d;
}
struct Pending;
struct Callbacks {
 std::mutex mutex;uint64_t next=1;std::map<uint64_t,std::unique_ptr<Pending>> pending;Reply event;
};
struct Pending { std::shared_ptr<Callbacks> owner; uint64_t key; Reply reply; };
Pending* Add(std::shared_ptr<Callbacks> owner,Reply reply) {
 std::lock_guard<std::mutex> lock(owner->mutex);auto key=owner->next++;
 auto p=std::make_unique<Pending>(Pending{owner,key,[reply copy]});auto raw=p.get();owner->pending.emplace(key,std::move(p));return raw;
}
void Finish(void* context,const usdk_result* result) {
 @autoreleasepool {
 auto raw=static_cast<Pending*>(context);auto owner=raw->owner;std::unique_ptr<Pending> pending;
 {std::lock_guard<std::mutex> lock(owner->mutex);auto it=owner->pending.find(raw->key);if(it==owner->pending.end())return;pending=std::move(it->second);owner->pending.erase(it);}
 pending->reply(Result(*result));
 }
}
void Rejected(Pending* pending,usdk_error error) { if(error.code){usdk_result r{};r.error=error;Finish(pending,&r);} }
void OnEvent(void* context,const usdk_event* event) {
 @autoreleasepool {auto owner=static_cast<Callbacks*>(context);Reply callback;{std::lock_guard<std::mutex> lock(owner->mutex);callback=owner->event;}if(callback)callback(Event(*event));}
}
usdk_error NativeError(const unishare::ShareError& e) {return {static_cast<uint32_t>(e.code),e.platform_error?1u:0u,e.platform_error.value_or(0)};}
}

@interface USFileStream ()
- (usdk_stream_handle)handle;
- (void)submitted;
@end
@implementation USFileStream {
 std::shared_ptr<unishare::IStream> _file;
 usdk_stream_handle _handle;
 BOOL _destination;
 BOOL _submitted;
}
+ (instancetype)openURL:(NSURL*)url destination:(BOOL)destination error:(NSError**)output {
 auto streams=unishare::ios::MakeStreams();auto token=streams->Register(url,destination);
 usdk_error error{};std::shared_ptr<unishare::IStream> file;
 if(!token.ok())error=NativeError(token.error());
 else {
  auto opened=destination?streams->OpenDestination(token.value()):streams->OpenSource(token.value());
  if(!opened.ok())error=NativeError(opened.error());else file=opened.value();
 }
 USFileStream* result=nil;
 if(!error.code){result=[USFileStream new];result->_file=file;result->_destination=destination;error=unishare::abi::RegisterStream(file,&result->_handle);}
 if(error.code){if(destination&&file)file->AbortAndCleanup();if(output)*output=[NSError errorWithDomain:@"UniShare" code:error.code userInfo:Error(error)];return nil;}
 return result;
}
- (usdk_stream_handle)handle {return _handle;}
- (void)submitted {_submitted=YES;}
- (void)close {
 if(_file&&!_submitted&&_destination)_file->AbortAndCleanup();
 if(_handle){usdk_stream_release(_handle);_handle=0;}_file.reset();
}
- (void)dealloc {[self close];}
@end
@implementation USBridge {
 std::shared_ptr<unishare::ios::Platform> _platform;
 std::shared_ptr<Callbacks> _callbacks;
 usdk_handle _sdk;
}
- (instancetype)initWithKeychainService:(NSString*)service {
 if((self=[super init])){_callbacks=std::make_shared<Callbacks>();_platform=unishare::ios::MakePlatform(service,NSBundle.mainBundle.infoDictionary);}return self;
}
- (void)setEvent:(Reply)event {std::lock_guard<std::mutex> lock(_callbacks->mutex);_callbacks->event=[event copy];}
- (Reply)event {std::lock_guard<std::mutex> lock(_callbacks->mutex);return _callbacks->event;}
- (void)create:(Packet)values completion:(Reply)completion {
 if(_sdk){completion(Error({USDK_INVALID_INPUT,0,0}));return;}
 usdk_config config{};usdk_config_init(1,&config);NSData* name=UTF8(values[@"deviceName"]);config.device_name=Span(name);
 #define SET(field) if(values[@#field])config.field=[values[@#field] unsignedLongLongValue]
 SET(max_connections);SET(max_pending_requests);SET(max_buffer_bytes);SET(max_text_bytes);SET(max_image_bytes);SET(max_file_bytes);SET(control_frame_bytes);SET(data_chunk_bytes);SET(request_timeout_seconds);SET(offline_debounce_seconds);
 #undef SET
 usdk_platform_handle token=0;auto error=unishare::ios::Register(_platform,&token);
 if(error.code){completion(Error(error));return;}
 auto pending=Add(_callbacks,completion);usdk_operation_id operation=0;
 error=usdk_create(token,&config,Finish,pending,&_sdk,&operation);usdk_platform_release(token);
 if(!error.code)usdk_set_event_handler(_sdk,OnEvent,_callbacks.get());Rejected(pending,error);
}
- (void)submit:(NSInteger)command arguments:(Packet)a completion:(Reply)completion {
 USFileStream* prepared=a[@"stream"];usdk_stream_handle stream=prepared.handle;
 auto pending=Add(_callbacks,completion);usdk_operation_id operation=0;usdk_error error{USDK_INVALID_INPUT,0,0};
 auto id=NativeUUID(a[@"id"]);uint64_t request=[a[@"request"] unsignedLongLongValue];
 NSData* text=UTF8(a[@"text"]),*name=UTF8(a[@"name"]),*mime=UTF8(a[@"mime"]);NSData* bytes=a[@"bytes"]?:NSData.data;
 usdk_source source{stream,Span(name),Span(mime),[a[@"size"] unsignedLongLongValue]};
 usdk_content content{[a[@"contentType"] unsignedIntValue],Span(text),Span(mime),Span(bytes)};
 switch(command){
 case 1:error=usdk_start(_sdk,Finish,pending,&operation);break;
 case 2:error=usdk_stop(_sdk,Finish,pending,&operation);break;
 case 3:error=usdk_connect(_sdk,id,Finish,pending,&operation);break;
 case 4:error=usdk_confirm_pairing(_sdk,request,[a[@"matches"] boolValue],Finish,pending,&operation);break;
 case 5:error=usdk_reject_pairing(_sdk,request,Finish,pending,&operation);break;
 case 6:error=usdk_forget_device(_sdk,id,Finish,pending,&operation);break;
 case 7:error=usdk_send_text(_sdk,id,Span(text),Finish,pending,&operation);break;
 case 15:error=usdk_send_text_stream(_sdk,id,&source,Finish,pending,&operation);break;
 case 8:error=usdk_send_image(_sdk,id,&source,Finish,pending,&operation);break;
 case 9:error=usdk_send_file(_sdk,id,&source,Finish,pending,&operation);break;
 case 10:error=usdk_accept(_sdk,request,stream,Finish,pending,&operation);break;
 case 11:error=usdk_reject(_sdk,request,Finish,pending,&operation);break;
 case 12:error=usdk_cancel(_sdk,id,Finish,pending,&operation);break;
 case 13:error=usdk_clipboard_read(_sdk,Finish,pending,&operation);break;
 case 14:error=usdk_clipboard_write(_sdk,&content,Finish,pending,&operation);break;
 }
 if(prepared){if(!error.code)[prepared submitted];[prepared close];}Rejected(pending,error);
}
- (NSArray*)snapshot:(BOOL)trusted error:(NSError**)output {
 auto result=[NSMutableArray array];usdk_error error{};
 if(trusted){usdk_trusted_devices* values=nullptr;error=usdk_get_trusted_devices(_sdk,&values);if(!error.code){for(uint64_t i=0;i<values->count;++i){auto& d=values->items[i];[result addObject:@{@"id":UUID(d.id),@"fingerprint":[NSData dataWithBytes:d.public_key_fingerprint length:32]}];}usdk_free(values);}}
 else {usdk_devices* values=nullptr;error=usdk_get_devices(_sdk,&values);if(!error.code){for(uint64_t i=0;i<values->count;++i)[result addObject:Device(values->items[i])];usdk_free(values);}}
 if(error.code){if(output)*output=[NSError errorWithDomain:@"UniShare" code:error.code userInfo:Error(error)];return nil;}return result;
}
- (NSUInteger)state {uint32_t state=0;usdk_get_state(_sdk,&state);return state;}
- (void)close {
 if(_sdk){usdk_destroy(_sdk);_sdk=0;}
 {std::lock_guard<std::mutex> lock(_callbacks->mutex);_callbacks->pending.clear();_callbacks->event=nil;}
 _platform.reset();
}
- (void)dealloc {[self close];}
@end
