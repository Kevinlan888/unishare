#pragma once
#include "platform.hpp"
namespace unishare {
enum class ConnectionState { Connected,Disconnected };
struct ConnectionEvent { DeviceId device; ConnectionState state; std::optional<ShareError> error; };
struct PairingRequest { RequestId id; DeviceInfo device; std::string verification_code; };
struct PairingResult { RequestId id; DeviceId device; Result<void> result; };
class IConnectionManager { public: virtual ~IConnectionManager()=default; virtual OperationId Start(ResultCallback<void>)=0; virtual OperationId Stop(ResultCallback<void>)=0; virtual OperationId Connect(DeviceId,ResultCallback<void>)=0; virtual OperationId ConfirmPairing(RequestId,bool code_matches,ResultCallback<void>)=0; virtual OperationId RejectPairing(RequestId,ResultCallback<void>)=0; virtual std::vector<TrustedDevice> GetTrustedDevices() const=0; virtual OperationId ForgetDevice(DeviceId,ResultCallback<void>)=0; virtual void SetConnectionHandler(std::function<void(ConnectionEvent)>)=0; virtual void SetPairingRequestHandler(std::function<void(PairingRequest)>)=0; virtual void SetPairingResultHandler(std::function<void(PairingResult)>)=0; virtual void SetIdentityChangedHandler(std::function<void(DeviceId)>)=0; };
}
