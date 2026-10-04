#include "operation_registry.hpp"
namespace unishare::core {
void OperationRegistry::CancelAll() {
 std::vector<std::function<void()>> pending;
 {std::lock_guard<std::mutex> l(mutex_);for(auto i=pending_.begin();i!=pending_.end();)if(i->second.cancellable){pending.push_back(std::move(i->second.cancel));i=pending_.erase(i);}else ++i;}
 for(auto& p:pending)p();
}
}
