#pragma once
#include "unishare/share_sdk.hpp"
#include "serial_scheduler.hpp"
#include "operation_registry.hpp"
#include "identity.hpp"
namespace unishare::core {
// All service wiring and mutable fields below are confined to scheduler.
// Retain this owner through outstanding platform callbacks/Session content acks.
// Task 7b installs real services and these hooks before successful Initialize.
struct SdkRuntime {
 std::shared_ptr<SerialScheduler> scheduler=std::make_shared<SerialScheduler>();
 // Keep the fallback alive across failed Initialize retries and executor swaps.
 // A retry may originate on that queue, so swapping must not join its worker.
 std::shared_ptr<SerialScheduler> default_callbacks=std::make_shared<SerialScheduler>();
 std::shared_ptr<IExecutor> callbacks=default_callbacks;
 std::shared_ptr<OperationRegistry> operations=std::make_shared<OperationRegistry>(callbacks);
 std::shared_ptr<ByteBudget> buffers=std::make_shared<ByteBudget>();
 SessionAdmission admission{buffers};
 SdkConfig config;std::optional<LocalIdentity> identity;
 std::shared_ptr<IConnectionManager> connections;
 std::shared_ptr<ITransferService> transfers;
 std::shared_ptr<IClipboardService> clipboard;
 std::function<void(std::shared_ptr<ISocket>)> accept_socket;
 // Stop/network loss invokes cleanup before cancelling public operations.
 std::function<void(ShareError)> stop_services;
};
class ShareSdk final : public IShareSdk {
 struct State;std::shared_ptr<State> state_;
public:
 explicit ShareSdk(IPlatform&);~ShareSdk();
 ShareSdk(const ShareSdk&)=delete;ShareSdk& operator=(const ShareSdk&)=delete;
 OperationId Initialize(SdkConfig,ResultCallback<void>) override;
 OperationId Initialize(SdkConfig,IPlatform&,ResultCallback<void>);
 OperationId Start(ResultCallback<void>) override;
 OperationId Stop(ResultCallback<void>) override;
 SdkState GetState() const override;
 std::shared_ptr<IDeviceDiscovery> Discovery() const override;
 std::shared_ptr<IConnectionManager> Connections() const override;
 std::shared_ptr<ITransferService> Transfers() const override;
 std::shared_ptr<IClipboardService> Clipboard() const override;
 std::shared_ptr<SdkRuntime> Runtime() const;
};
}
