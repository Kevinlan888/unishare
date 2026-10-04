#pragma once
#include "platform.hpp"
namespace unishare {
enum class SdkState { Uninitialized, Initialized, Running, Stopped };
class IShareSdk { public: virtual ~IShareSdk()=default; virtual OperationId Initialize(SdkConfig,ResultCallback<void>)=0; virtual OperationId Start(ResultCallback<void>)=0; virtual OperationId Stop(ResultCallback<void>)=0; virtual SdkState GetState() const=0; };
}
