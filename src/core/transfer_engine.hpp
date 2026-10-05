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
 static std::shared_ptr<TransferEngine> Create(std::shared_ptr<Session>,ISessionScheduler&,SessionAdmission&,SdkConfig,OfferHandler,ProgressHandler,ResultHandler,std::shared_ptr<ISessionScheduler> scheduler_owner = {});
 ~TransferEngine();
 TransferId Offer(ContentSource, std::optional<TransferId> = {});
 void Accept(TransferId,ContentSink);void Reject(TransferId);void Cancel(TransferId);
 // Internal SDK observers run synchronously on the core queue, before public
 // delivery. Install before Offer; terminal operation arbitration must never
 // depend on an application executor being drained.
 void SetCoreObservers(OfferHandler o,ProgressHandler p,ResultHandler r){core_offer_=std::move(o);core_progress_=std::move(p);core_result_=std::move(r);}
private:
 struct Transfer;struct Send;
 TransferEngine(std::shared_ptr<Session>,ISessionScheduler&,SessionAdmission&,SdkConfig,OfferHandler,ProgressHandler,ResultHandler,std::shared_ptr<ISessionScheduler> scheduler_owner = {});
 std::shared_ptr<ISessionScheduler> scheduler_owner_;
 std::shared_ptr<Session> session_;ISessionScheduler& queue_;ByteBudget& budget_;SessionAdmission& admission_;
 SdkConfig config_;std::shared_ptr<IExecutor> executor_;OfferHandler offer_;ProgressHandler progress_;ResultHandler result_;
 OfferHandler core_offer_;ProgressHandler core_progress_;ResultHandler core_result_;
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
