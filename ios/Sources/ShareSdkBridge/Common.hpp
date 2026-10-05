#pragma once
#include "Ports.hpp"
#include <atomic>
#include <algorithm>
#include <mutex>
#include <map>
#include <cstring>
namespace unishare::ios {
inline OperationId Next() {static std::atomic<OperationId> id{1}; return id++;}
inline ShareError Failure(ErrorCode code,const char* message,std::optional<int64_t> native={}) {return {code,message,native};}
inline ShareError Cancelled() {return Failure(ErrorCode::Cancelled,"operation cancelled");}
template<class F,class... T> void Deliver(const F& callback,T&&... values) noexcept {
 if(callback) try {callback(std::forward<T>(values)...);} catch(...) {}
}
class Queue final : public IExecutor {
 dispatch_queue_t queue_;
public:
 explicit Queue(const char* label):queue_(dispatch_queue_create(label,DISPATCH_QUEUE_SERIAL)) {
  dispatch_queue_set_specific(queue_,this,this,nullptr);
 }
 dispatch_queue_t Native() const {return queue_;}
 void Post(std::function<void()> task) override {dispatch_async(queue_,^{@autoreleasepool {try {task();}catch(...) {}}});}
 void Sync(std::function<void()> task) {
  if(dispatch_get_specific(this)) task(); else dispatch_sync(queue_,^{@autoreleasepool {task();}});
 }
};
inline NSData* Data(const std::vector<uint8_t>& value) {return [NSData dataWithBytes:value.data() length:value.size()];}
inline std::vector<uint8_t> Bytes(NSData* data) {
 if(!data.length)return {}; const auto* p=static_cast<const uint8_t*>(data.bytes); return {p,p+data.length};
}
}
