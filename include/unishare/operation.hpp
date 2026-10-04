#pragma once
#include "platform.hpp"
#include <atomic>
#include <stdexcept>
namespace unishare {
// The executor must accept each posted task exactly once. Completion survives this object's destruction.
template<class T> class OperationCompletion {
 std::shared_ptr<IExecutor> executor_; ResultCallback<T> callback_; std::atomic<bool> completed_{false};
public:
 OperationCompletion(std::shared_ptr<IExecutor> executor,ResultCallback<T> callback):executor_(std::move(executor)),callback_(std::move(callback)) { if(!executor_||!callback_) throw std::invalid_argument("completion requires executor and callback"); }
 bool Complete(Result<T> result) { if(completed_.exchange(true)) return false; auto callback=std::move(callback_); executor_->Post([callback=std::move(callback),result=std::move(result)]() mutable { callback(std::move(result)); }); return true; }
 bool Cancel() { return Complete(Result<T>::Failure({ErrorCode::Cancelled,"Operation cancelled",std::nullopt})); }
};
}


