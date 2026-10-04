#pragma once
#include "session.hpp"
#include "unishare/transfer.hpp"
#include <openssl/evp.h>
namespace unishare::core {
// Call on Session's serial queue. Dependencies outlive adapter callbacks.
// Create attaches the sole content handler; keep engine alive for the session.
class TransferEngine : public std::enable_shared_from_this<TransferEngine> {
public:
 using OfferHandler=std::function<void(IncomingOffer)>;
 using ProgressHandler=std::function<void(TransferProgress)>;
 using ResultHandler=std::function<void(TransferResult)>;
 static std::shared_ptr<TransferEngine> Create(std::shared_ptr<Session>,ISessionScheduler&,SessionAdmission&,SdkConfig,OfferHandler,ProgressHandler,ResultHandler);
 ~TransferEngine();
 TransferId Offer(ContentSource);
 void Accept(TransferId,ContentSink);void Reject(TransferId);void Cancel(TransferId);
private:
 struct Transfer;struct Send;
 TransferEngine(std::shared_ptr<Session>,ISessionScheduler&,SessionAdmission&,SdkConfig,OfferHandler,ProgressHandler,ResultHandler);
 std::shared_ptr<Session> session_;ISessionScheduler& queue_;ByteBudget& budget_;SessionAdmission& admission_;
 SdkConfig config_;std::shared_ptr<IExecutor> executor_;OfferHandler offer_;ProgressHandler progress_;ResultHandler result_;
 std::shared_ptr<Transfer> active_;std::deque<std::shared_ptr<Send>> sends_;bool sending_=false,closed_=false;RequestId next_request_=1;
 bool Current(const std::shared_ptr<Transfer>&)const;
 std::optional<ShareError> Validate(const protocol::Offer&)const;
 void Receive(std::shared_ptr<const protocol::Message>,Session::ContentAck);
 void SendMessage(protocol::Message,std::function<void(Result<void>)> = {});
 void Pump();void Read(const std::shared_ptr<Transfer>&);
 void Finish(const std::shared_ptr<Transfer>&,Result<void>);
 void Fail(const std::shared_ptr<Transfer>&,ShareError,bool notify=true);
 void Progress(const std::shared_ptr<Transfer>&);void Deadline(const std::shared_ptr<Transfer>&);
 void Closed(ShareError);void Emit(TransferId,Result<void>);
};
}
