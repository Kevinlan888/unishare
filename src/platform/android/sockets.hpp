#pragma once
#include "unishare/platform.hpp"
namespace unishare::android {
class SocketFactory final : public ISocketFactory {
 struct State; std::shared_ptr<State> state_;
public:
 SocketFactory();~SocketFactory();
 OperationId Connect(std::string,std::uint16_t,ResultCallback<std::shared_ptr<ISocket>>) override;
 OperationId Listen(std::uint16_t,std::function<void(Result<std::shared_ptr<ISocket>>)>) override;
 OperationId ListenBound(std::uint16_t,ResultCallback<std::uint16_t>,std::function<void(Result<std::shared_ptr<ISocket>>)>) override;
 void Stop() override;
};
}
