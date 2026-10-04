#include "serial_scheduler.hpp"
#include <condition_variable>
#include <mutex>
namespace unishare::core {
struct SerialScheduler::State {
 std::mutex mutex; std::condition_variable wake; bool stopping=false;
 OperationId next=1; std::map<std::pair<Time,OperationId>,std::function<void()>> tasks;
};
SerialScheduler::SerialScheduler():state_(std::make_shared<State>()),worker_([s=state_] {
 std::unique_lock<std::mutex> lock(s->mutex);
 for(;;) {
  if(s->tasks.empty()) {if(s->stopping)return;s->wake.wait(lock);continue;}
  auto first=s->tasks.begin();auto now=std::chrono::steady_clock::now();
  if(first->first.first>now) {if(s->stopping){s->tasks.clear();return;}const auto deadline=first->first.first;s->wake.wait_until(lock,deadline);continue;}
  auto f=std::move(first->second);s->tasks.erase(first);lock.unlock();
  // Host callbacks cannot terminate the queue or strand later completions.
  try {f();} catch(...) {} lock.lock();
 }
}) {}
SerialScheduler::~SerialScheduler() {
 {std::lock_guard<std::mutex> l(state_->mutex);state_->stopping=true;}state_->wake.notify_all();
 if(worker_.get_id()==std::this_thread::get_id())worker_.detach();else worker_.join();
}
void SerialScheduler::Post(std::function<void()> f) {ScheduleAt(Now(),std::move(f));}
ISessionScheduler::Time SerialScheduler::Now() const {return std::chrono::steady_clock::now();}
OperationId SerialScheduler::ScheduleAt(Time t,std::function<void()> f) {std::lock_guard<std::mutex> l(state_->mutex);auto id=state_->next++;state_->tasks.emplace(std::make_pair(t,id),std::move(f));state_->wake.notify_all();return id;}
void SerialScheduler::Cancel(OperationId id) {std::lock_guard<std::mutex> l(state_->mutex);for(auto i=state_->tasks.begin();i!=state_->tasks.end();++i)if(i->first.second==id){state_->tasks.erase(i);break;}state_->wake.notify_all();}
void SerialScheduler::Invoke(std::function<void()> f) {
 if(worker_.get_id()==std::this_thread::get_id()){f();return;}
 auto done=std::make_shared<std::promise<void>>();auto result=done->get_future();
 Post([done,f=std::move(f)]{try{f();done->set_value();}catch(...){done->set_exception(std::current_exception());}});result.get();
}
}
