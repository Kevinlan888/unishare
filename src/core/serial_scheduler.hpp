#pragma once
#include "session.hpp"
#include <future>
#include <thread>
namespace unishare::core {
// Independent instances provide the core queue and default public callback queue.
class SerialScheduler final : public ISessionScheduler {
 struct State; std::shared_ptr<State> state_; std::thread worker_;
public:
 SerialScheduler(); ~SerialScheduler();
 void Post(std::function<void()>) override;
 Time Now() const override;
 OperationId ScheduleAt(Time,std::function<void()>) override;
 void Cancel(OperationId) override;
 // Synchronize destruction/snapshots with the core queue; executes inline only
 // when already on that queue. Public callbacks still always use Post.
 void Invoke(std::function<void()>);
};
}
