#pragma once
#include "unishare/platform.hpp"
#include <mutex>
namespace unishare::windows {
class SocketFactory final : public ISocketFactory {
 struct State;
 std::mutex mutex_;
 std::shared_ptr<State> state_;
 std::shared_ptr<State> Current();
public:
 SocketFactory();
 ~SocketFactory();
 OperationId Connect(std::string,std::uint16_t,ResultCallback<std::shared_ptr<ISocket>>) override;
 OperationId Listen(std::uint16_t,std::function<void(Result<std::shared_ptr<ISocket>>)>) override;
 OperationId ListenBound(std::uint16_t,ResultCallback<std::uint16_t>,std::function<void(Result<std::shared_ptr<ISocket>>)>) override;
 void Stop() override;
};
}
