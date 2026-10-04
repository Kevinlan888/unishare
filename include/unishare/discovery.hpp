#pragma once
#include "types.hpp"
namespace unishare {
enum class DiscoveryEventKind { Online, Updated, Offline };
struct DiscoveryEvent { DiscoveryEventKind kind; DeviceInfo device; };
class IDeviceDiscovery { public: virtual ~IDeviceDiscovery()=default; virtual OperationId Start(ResultCallback<void>)=0; virtual OperationId Stop(ResultCallback<void>)=0; virtual std::vector<DeviceInfo> GetDevices() const=0; virtual void SetEventHandler(std::function<void(DiscoveryEvent)>)=0; virtual void SetErrorHandler(std::function<void(ShareError)>)=0; };
}
