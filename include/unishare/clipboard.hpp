#pragma once
#include "types.hpp"
namespace unishare {
class IClipboardService { public: virtual ~IClipboardService()=default; virtual OperationId Start(ResultCallback<void>)=0; virtual OperationId Stop(ResultCallback<void>)=0; virtual OperationId ReadShareableContent(ResultCallback<ShareContent>)=0; virtual OperationId WriteContent(ShareContent,ResultCallback<void>)=0; };
}
