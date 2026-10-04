#pragma once
#include "unishare/operation.hpp"
#include <map>
#include <mutex>
namespace unishare::core {
class OperationRegistry : public std::enable_shared_from_this<OperationRegistry> {
 // Ordinary SDK operations may be submitted while Initialize is still pending.
 // Bind their executor when completing, after the accepted configuration has
 // been committed. Explicit BeginOn completions retain their invocation policy.
 class ExecutorRoute final : public IExecutor {
  std::mutex mutex_;
  std::shared_ptr<IExecutor> executor_;
 public:
  explicit ExecutorRoute(std::shared_ptr<IExecutor> executor):executor_(std::move(executor)){}
  void Set(std::shared_ptr<IExecutor> executor) {
   std::lock_guard<std::mutex> lock(mutex_);
   executor_=std::move(executor);
  }
  void Post(std::function<void()> task) override {
   std::shared_ptr<IExecutor> executor;
   {std::lock_guard<std::mutex> lock(mutex_);executor=executor_;}
   executor->Post(std::move(task));
  }
 };
 std::mutex mutex_; OperationId next_=1;std::shared_ptr<ExecutorRoute> executor_;
 struct Pending {std::function<void()> cancel;bool cancellable;};
 std::map<OperationId,Pending> pending_;
 void Erase(OperationId id) {std::lock_guard<std::mutex> l(mutex_);pending_.erase(id);}
public:
 template<class T> struct Ticket {OperationId id;std::function<bool(Result<T>)> Complete;std::function<bool()> Active;};
 explicit OperationRegistry(std::shared_ptr<IExecutor> executor):executor_(std::make_shared<ExecutorRoute>(std::move(executor))){}
 void SetExecutor(std::shared_ptr<IExecutor> executor) {executor_->Set(std::move(executor));}
 template<class T> Ticket<T> Begin(ResultCallback<T> cb,bool cancellable=true) {
  return BeginOn<T>({},std::move(cb),cancellable);
 }
 // Initialize must bind its own completion before a configuration has been
 // accepted. This override never changes the executor of other operations.
 template<class T> Ticket<T> BeginOn(std::shared_ptr<IExecutor> executor,ResultCallback<T> cb,bool cancellable=true) {
  std::lock_guard<std::mutex> l(mutex_);auto id=next_++;
  if(!cb)cb=[](Result<T>){};
  auto completion=std::make_shared<OperationCompletion<T>>(executor?std::move(executor):executor_,std::move(cb));
  pending_.emplace(id,Pending{[completion]{completion->Cancel();},cancellable});
  return {id,[weak=weak_from_this(),completion,id](Result<T> result){bool won=completion->Complete(std::move(result));if(auto registry=weak.lock())registry->Erase(id);return won;},[weak=weak_from_this(),id]{if(auto r=weak.lock()){std::lock_guard<std::mutex> l(r->mutex_);return r->pending_.count(id)!=0;}return false;}};
 }
 void CancelAll();
};
}
