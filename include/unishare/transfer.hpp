#pragma once
#include "platform.hpp"
namespace unishare {
struct TransferSource { std::string display_name,mime_type; std::uint64_t byte_count=0; std::shared_ptr<IStream> stream; };
struct IncomingOffer { RequestId request_id; TransferId transfer_id; DeviceId device_id; ContentType content_type; std::string display_name,mime_type; std::uint64_t byte_count=0; };
struct TransferProgress { TransferId id; std::uint64_t transferred=0,total=0; };
struct TransferResult { TransferId id; Result<void> result; };
class ITransferService { public: virtual ~ITransferService()=default; virtual OperationId Start(ResultCallback<void>)=0; virtual OperationId Stop(ResultCallback<void>)=0; virtual OperationId SendText(DeviceId,std::string,ResultCallback<TransferId>)=0; virtual OperationId SendImage(DeviceId,TransferSource,ResultCallback<TransferId>)=0; virtual OperationId SendFile(DeviceId,TransferSource,ResultCallback<TransferId>)=0; virtual OperationId Accept(RequestId,std::shared_ptr<IStream> destination,ResultCallback<void>)=0; virtual OperationId Reject(RequestId,ResultCallback<void>)=0; virtual OperationId Cancel(TransferId,ResultCallback<void>)=0; virtual void SetIncomingOfferHandler(std::function<void(IncomingOffer)>)=0; virtual void SetProgressHandler(std::function<void(TransferProgress)>)=0; virtual void SetResultHandler(std::function<void(TransferResult)>)=0; };
}
