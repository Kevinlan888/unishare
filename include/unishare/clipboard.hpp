#pragma once
#include "types.hpp"
namespace unishare {
// Value payloads share the SDK buffer budget. Available capacity (including
// concurrent operations) can be lower than the text/image type size limit;
// insufficient capacity returns ResourceLimit. Read results remain charged
// until callback delivery; platform writes remain charged until completion.
class IClipboardService { public: virtual ~IClipboardService()=default; virtual OperationId Start(ResultCallback<void>)=0; virtual OperationId Stop(ResultCallback<void>)=0; virtual OperationId ReadShareableContent(ResultCallback<ShareContent>)=0; virtual OperationId WriteContent(ShareContent,ResultCallback<void>)=0; };
}
