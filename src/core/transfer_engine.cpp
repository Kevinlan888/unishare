#include "transfer_engine.hpp"
#include <algorithm>
#include <openssl/rand.h>
namespace unishare::core {
namespace {
ShareError Error(ErrorCode code,const char* message){return {code,message,{}};}
struct Utf8 {
 unsigned remaining=0;std::uint32_t code=0,minimum=0;
 bool Add(const std::uint8_t* data,std::size_t size){for(std::size_t i=0;i<size;++i){auto c=data[i];
  if(remaining){if((c&0xc0)!=0x80)return false;code=(code<<6)|(c&63);if(!--remaining&&(code<minimum||code>0x10ffff||(code>=0xd800&&code<=0xdfff)))return false;}
  else if(c<128){}else if(c>=0xc2&&c<=0xdf){remaining=1;code=c&31;minimum=128;}
  else if(c>=0xe0&&c<=0xef){remaining=2;code=c&15;minimum=2048;}
  else if(c>=0xf0&&c<=0xf4){remaining=3;code=c&7;minimum=65536;}else return false;
 }return true;}
};
bool ValidUtf8(const std::string& s){Utf8 u;return u.Add(reinterpret_cast<const std::uint8_t*>(s.data()),s.size())&&!u.remaining;}
}
struct TransferEngine::Transfer {
 // Covers bounded metadata and its posted offer event; dies after the strings.
 std::shared_ptr<ByteBudget::Reservation> metadata_charge;
 enum class Phase { Offered,Receiving,Sending,AwaitComplete,Completing } phase=Phase::Offered;
 protocol::Offer offer;bool outgoing=false,terminal=false,finish_written=false,complete_received=false,offer_slot=false;std::shared_ptr<IStream> stream;
 std::uint64_t bytes=0,sequence=0,deadline_generation=0;OperationId deadline=0;Utf8 utf8;
 EVP_MD_CTX* digest=EVP_MD_CTX_new();
 Transfer(){if(digest&&EVP_DigestInit_ex(digest,EVP_sha256(),nullptr)!=1){EVP_MD_CTX_free(digest);digest=nullptr;}}
 ~Transfer(){EVP_MD_CTX_free(digest);}
};
struct TransferEngine::Send {
 std::shared_ptr<ByteBudget::Reservation> charge;
 protocol::Message message;std::function<void(Result<void>)> done;
};
TransferEngine::TransferEngine(std::shared_ptr<Session> s,ISessionScheduler& q,SessionAdmission& a,SdkConfig c,
 OfferHandler o,ProgressHandler p,ResultHandler r,std::shared_ptr<ISessionScheduler> owner):scheduler_owner_(std::move(owner)),session_(std::move(s)),queue_(q),budget_(a.Buffers()),admission_(a),config_(std::move(c)),
 executor_(config_.callback_executor?config_.callback_executor:std::shared_ptr<IExecutor>(&q,[](IExecutor*){})),
 offer_(std::move(o)),progress_(std::move(p)),result_(std::move(r)){}
std::shared_ptr<TransferEngine> TransferEngine::Create(std::shared_ptr<Session> s,ISessionScheduler& q,SessionAdmission& a,
 SdkConfig c,OfferHandler o,ProgressHandler p,ResultHandler r,std::shared_ptr<ISessionScheduler> owner){
 auto e=std::shared_ptr<TransferEngine>(new TransferEngine(std::move(s),q,a,std::move(c),std::move(o),std::move(p),std::move(r),std::move(owner)));
 std::weak_ptr<TransferEngine> w=e;e->session_->SetContentHandler([w](auto m,auto ack){if(auto e=w.lock())e->Receive(std::move(m),std::move(ack));else ack(Result<void>::Failure(Error(ErrorCode::Cancelled,"transfer engine destroyed")));},
 [w](auto error){if(auto e=w.lock())e->Closed(error);});return e;
}
TransferEngine::~TransferEngine(){auto error=Error(ErrorCode::Cancelled,"transfer engine destroyed");if(active_)Finish(active_,Result<void>::Failure(error));session_->Close(error);}
bool TransferEngine::Current(const std::shared_ptr<Transfer>& t)const{return active_==t&&!t->terminal;}
std::optional<ShareError> TransferEngine::Validate(const protocol::Offer& o)const{
 std::uint64_t limit=0;switch(o.content_type){case ContentType::Text:limit=std::min<std::uint64_t>(config_.max_text_bytes,8ULL*1024*1024);break;
 case ContentType::Image:limit=std::min<std::uint64_t>(config_.max_image_bytes,100ULL*1024*1024);break;
 case ContentType::File:limit=std::min<std::uint64_t>(config_.max_file_bytes,20ULL*1024*1024*1024);break;
 default:return Error(ErrorCode::InvalidInput,"invalid content type");}
 if(o.byte_count>limit)return Error(ErrorCode::ResourceLimit,"content size limit");
 if(o.display_name.size()>4096||o.mime_type.size()>1024||!ValidUtf8(o.display_name)||!ValidUtf8(o.mime_type))return Error(ErrorCode::InvalidInput,"invalid metadata");
 if(o.display_name=="."||o.display_name==".."||o.display_name.find_first_of("/\\:")!=std::string::npos)return Error(ErrorCode::InvalidInput,"unsafe display name");
 for(unsigned char c:o.display_name)if(c<32||c==127)return Error(ErrorCode::InvalidInput,"unsafe display name");
 for(unsigned char c:o.mime_type)if(c<32||c==127)return Error(ErrorCode::InvalidInput,"invalid MIME type");
 return {};
}
void TransferEngine::Emit(TransferId id,Result<void> result){if(core_result_)core_result_({id,result});auto fn=result_;if(fn)executor_->Post([fn,id,result]{fn({id,result});});}
void TransferEngine::Progress(const std::shared_ptr<Transfer>& t){if(!Current(t))return;auto fn=progress_;auto p=TransferProgress{t->offer.transfer_id,t->bytes,t->offer.byte_count};if(core_progress_)core_progress_(p);if(fn)executor_->Post([fn,p]{fn(p);});}
void TransferEngine::Deadline(const std::shared_ptr<Transfer>& t){
 queue_.Cancel(t->deadline);std::weak_ptr<TransferEngine> w=weak_from_this();
 const auto generation=++t->deadline_generation;
 t->deadline=queue_.ScheduleAt(queue_.Now()+std::min<std::chrono::seconds>(config_.request_timeout,std::chrono::seconds(60)),[w,t,generation]{if(auto e=w.lock())if(e->Current(t)&&t->deadline_generation==generation){
  auto error=Error(ErrorCode::Timeout,"transfer timed out");e->Finish(t,Result<void>::Failure(error));e->session_->Close(error);
 }});
}
TransferId TransferEngine::Offer(ContentSource source){
 TransferId id;if(RAND_bytes(id.bytes.data(),static_cast<int>(id.bytes.size()))!=1){Emit(id,Result<void>::Failure(Error(ErrorCode::ResourceLimit,"random ID unavailable")));return id;}
 if(active_||sending_||!sends_.empty()){Emit(id,Result<void>::Failure(Error(ErrorCode::ResourceLimit,"one active transfer per session")));return id;}
 if(closed_||!session_->Connected()){Emit(id,Result<void>::Failure(Error(ErrorCode::AuthenticationFailed,"session is not authenticated")));return id;}
 protocol::Offer offer{id,source.type,std::move(source.content.display_name),std::move(source.content.mime_type),source.content.byte_count};
 auto error=Validate(offer);if(!source.content.stream)error=Error(ErrorCode::InvalidInput,"missing source");
 if(error){Emit(id,Result<void>::Failure(*error));return id;}
 auto metadata=budget_.Reserve(16384);if(!metadata){Emit(id,Result<void>::Failure(Error(ErrorCode::ResourceLimit,"metadata buffer limit")));return id;}
 auto t=std::make_shared<Transfer>();t->metadata_charge=std::move(metadata);t->offer=offer;t->outgoing=true;t->stream=std::move(source.content.stream);active_=t;
 if(!t->digest){Fail(t,Error(ErrorCode::ResourceLimit,"digest unavailable"),false);return id;}
 Deadline(t);SendMessage(t->offer);return id;
}
void TransferEngine::Accept(TransferId id,ContentSink sink){
 auto t=active_;if(!t||t->offer.transfer_id!=id||t->outgoing||t->phase!=Transfer::Phase::Offered)return;
 if(!sink){Fail(t,Error(ErrorCode::InvalidInput,"missing destination"));return;}
 if(t->offer_slot){admission_.ReleaseOffer();t->offer_slot=false;}
 t->stream=std::move(sink);t->phase=Transfer::Phase::Receiving;Deadline(t);Progress(t);
 SendMessage(protocol::Status{protocol::MessageType::Accept,id,{}});
}
void TransferEngine::Reject(TransferId id){auto t=active_;if(!t||t->offer.transfer_id!=id||t->outgoing||t->phase!=Transfer::Phase::Offered)return;
 SendMessage(protocol::Status{protocol::MessageType::Reject,id,"rejected"});Finish(t,Result<void>::Failure(Error(ErrorCode::TransferRejected,"rejected")));}
void TransferEngine::Cancel(TransferId id){auto t=active_;if(!t||t->offer.transfer_id!=id)return;
 auto error=Error(ErrorCode::Cancelled,"cancelled");Finish(t,Result<void>::Failure(error));
 // Close interrupts stalled writes/paused reads immediately.
 session_->Close(error);
}
void TransferEngine::Finish(const std::shared_ptr<Transfer>& input,Result<void> result){
 auto t=input;if(!Current(t))return;t->terminal=true;queue_.Cancel(t->deadline);
 if(t->offer_slot){admission_.ReleaseOffer();t->offer_slot=false;}
 if(t->stream){if(t->outgoing||result.ok())t->stream->Close();else t->stream->AbortAndCleanup();t->stream.reset();}
 active_.reset();Emit(t->offer.transfer_id,result);
}
void TransferEngine::Fail(const std::shared_ptr<Transfer>& t,ShareError error,bool notify){if(!Current(t))return;
 const auto id=t->offer.transfer_id;
 Finish(t,Result<void>::Failure(std::move(error)));
 if(notify)SendMessage(protocol::Status{protocol::MessageType::Failed,id,"transfer failed"});
}
void TransferEngine::Closed(ShareError error){closed_=true;sends_.clear();if(active_)Finish(active_,Result<void>::Failure(std::move(error)));}
void TransferEngine::SendMessage(protocol::Message message,std::function<void(Result<void>)> done){
 if(closed_)return;
 auto bytes=std::holds_alternative<protocol::Data>(message)?std::get<protocol::Data>(message).payload.size()+128:8192;
 auto charge=budget_.Reserve(bytes);if(!charge||sends_.size()>=4){session_->Close(Error(ErrorCode::ResourceLimit,"outbound queue limit"));return;}
 auto send=std::make_shared<Send>();send->charge=std::move(charge);send->message=std::move(message);send->done=std::move(done);sends_.push_back(std::move(send));Pump();
}
void TransferEngine::Pump(){if(sending_||sends_.empty()||closed_)return;sending_=true;auto send=sends_.front();sends_.pop_front();
 std::weak_ptr<TransferEngine> w=weak_from_this();session_->SendContent(send->message,[w,send](auto result){if(auto e=w.lock()){
  e->sending_=false;if(!result.ok()){e->session_->Close(result.error());return;}
  if(send->done)send->done(result);e->Pump();
 }});
}
void TransferEngine::Read(const std::shared_ptr<Transfer>& t){
 if(!Current(t)||t->phase!=Transfer::Phase::Sending)return;
 auto max=std::min<std::size_t>(config_.data_chunk_bytes,protocol::MaxDataPayload);if(!max){Fail(t,Error(ErrorCode::InvalidInput,"zero chunk limit"));return;}
 // One extra byte after declared length proves exact EOF.
 auto remaining=t->offer.byte_count-t->bytes;auto n=static_cast<std::size_t>(std::min<std::uint64_t>(remaining?remaining:1,max));
 auto charge=budget_.Reserve(2*n);if(!charge){Fail(t,Error(ErrorCode::ResourceLimit,"source buffer limit"));return;}
 std::weak_ptr<TransferEngine> w=weak_from_this();auto* q=&queue_;auto stream=t->stream;
 stream->Read(n,[w,q,t,charge,stream,n,owner=scheduler_owner_](Result<std::vector<std::uint8_t>> result){
  struct ReadResult {std::shared_ptr<ByteBudget::Reservation> charge;Result<std::vector<std::uint8_t>> result;};
  auto held=std::make_shared<ReadResult>(ReadResult{charge,std::move(result)});
  q->Post([w,t,held,n]{auto e=w.lock();if(!e||!e->Current(t))return;auto& r=held->result;
   if(!r.ok()){e->Fail(t,r.error());return;}auto& b=r.value();
   if(b.size()>n||b.size()>t->offer.byte_count-t->bytes){e->Fail(t,Error(ErrorCode::IntegrityFailed,"source exceeds declared size"));return;}
   if(b.empty()){
    if(t->bytes!=t->offer.byte_count||t->utf8.remaining){e->Fail(t,Error(ErrorCode::IntegrityFailed,"source truncated or invalid UTF-8"));return;}
    protocol::Finish f;f.transfer_id=t->offer.transfer_id;f.byte_count=t->bytes;unsigned length=0;
    if(EVP_DigestFinal_ex(t->digest,f.sha256.data(),&length)!=1||length!=32){e->Fail(t,Error(ErrorCode::IntegrityFailed,"digest failure"));return;}
    t->phase=Transfer::Phase::AwaitComplete;e->Progress(t);e->Deadline(t);
    e->SendMessage(f,[w,t](auto){if(auto e=w.lock())if(e->Current(t)){t->finish_written=true;if(t->complete_received)e->Finish(t,Result<void>::Success());}});return;
   }
   if((t->offer.content_type==ContentType::Text&&!t->utf8.Add(b.data(),b.size()))||EVP_DigestUpdate(t->digest,b.data(),b.size())!=1){e->Fail(t,Error(ErrorCode::IntegrityFailed,"invalid source bytes"));return;}
   auto count=b.size();protocol::Data d{t->offer.transfer_id,t->sequence++,b};
   e->SendMessage(std::move(d),[w,t,count](auto){if(auto e=w.lock())if(e->Current(t)){t->bytes+=count;e->Progress(t);e->Deadline(t);e->Read(t);}});
  });
 });
}
void TransferEngine::Receive(std::shared_ptr<const protocol::Message> message,Session::ContentAck ack){
 auto ok=[&]{message.reset();ack(Result<void>::Success());};
 if(auto offer=std::get_if<protocol::Offer>(message.get())){
  if(active_){SendMessage(protocol::Status{protocol::MessageType::Reject,offer->transfer_id,"busy"});ok();return;}
  auto error=Validate(*offer);if(error){auto id=offer->transfer_id;SendMessage(protocol::Status{protocol::MessageType::Reject,id,"invalid offer"});Emit(id,Result<void>::Failure(*error));ok();return;}
  auto metadata=budget_.Reserve(16384);
  if(!metadata||!admission_.AcquireOffer(config_.max_pending_requests)){auto id=offer->transfer_id;SendMessage(protocol::Status{protocol::MessageType::Reject,id,"request limit"});Emit(id,Result<void>::Failure(Error(ErrorCode::ResourceLimit,"pending offer limit")));ok();return;}
  auto t=std::make_shared<Transfer>();t->metadata_charge=std::move(metadata);t->offer_slot=true;t->offer=*offer;active_=t;if(!t->digest){Fail(t,Error(ErrorCode::ResourceLimit,"digest unavailable"));ok();return;}
  Deadline(t);IncomingOffer event{next_request_++,offer->transfer_id,session_->PeerDevice().value(),offer->content_type,offer->display_name,offer->mime_type,offer->byte_count};
  // Destroy posted strings before dropping the metadata lease, including when
  // the executor discards its queue after engine destruction.
  struct OfferEvent {std::shared_ptr<Transfer> transfer;IncomingOffer event;};
  auto posted=std::make_shared<OfferEvent>(OfferEvent{t,std::move(event)});
  if(core_offer_)core_offer_(posted->event);
  auto fn=offer_;if(fn)executor_->Post([fn,posted]{fn(posted->event);});ok();return;
 }
 TransferId id=std::visit([](const auto& m)->TransferId{using T=std::decay_t<decltype(m)>;if constexpr(std::is_same_v<T,protocol::Data>||std::is_same_v<T,protocol::Finish>||std::is_same_v<T,protocol::Status>)return m.transfer_id;else return {};},*message);
 auto t=active_;if(!t||t->offer.transfer_id!=id){ok();return;}
 if(auto status=std::get_if<protocol::Status>(message.get())){
  switch(status->type){
  case protocol::MessageType::Accept:
   if(t->outgoing&&t->phase==Transfer::Phase::Offered){t->phase=Transfer::Phase::Sending;Deadline(t);Progress(t);Read(t);}else Fail(t,Error(ErrorCode::InvalidInput,"unexpected acceptance"));break;
  case protocol::MessageType::Reject:Finish(t,Result<void>::Failure(Error(ErrorCode::TransferRejected,"peer rejected")));break;
  case protocol::MessageType::Cancel:Finish(t,Result<void>::Failure(Error(ErrorCode::Cancelled,"peer cancelled")));break;
  case protocol::MessageType::Failed:Finish(t,Result<void>::Failure(Error(ErrorCode::IntegrityFailed,"peer failed transfer")));break;
  case protocol::MessageType::Complete:
   if(t->outgoing&&t->phase==Transfer::Phase::AwaitComplete){t->complete_received=true;if(t->finish_written)Finish(t,Result<void>::Success());}else Fail(t,Error(ErrorCode::InvalidInput,"premature completion"));break;
  default:Fail(t,Error(ErrorCode::InvalidInput,"unexpected status"));break;
  }ok();return;
 }
 if(auto data=std::get_if<protocol::Data>(message.get())){
  if(t->outgoing||t->phase!=Transfer::Phase::Receiving||data->sequence!=t->sequence||data->payload.empty()||data->payload.size()>t->offer.byte_count-t->bytes){Fail(t,Error(ErrorCode::IntegrityFailed,"unapproved or invalid data"));ok();return;}
  if((t->offer.content_type==ContentType::Text&&!t->utf8.Add(data->payload.data(),data->payload.size()))||EVP_DigestUpdate(t->digest,data->payload.data(),data->payload.size())!=1){Fail(t,Error(ErrorCode::IntegrityFailed,"invalid received bytes"));ok();return;}
  auto charge=budget_.Reserve(data->payload.size());if(!charge){Fail(t,Error(ErrorCode::ResourceLimit,"destination buffer limit"));ok();return;}
  struct WriteHold {std::shared_ptr<ByteBudget::Reservation> charge;std::shared_ptr<const protocol::Message> message;Session::ContentAck ack;};
  auto held=std::make_shared<WriteHold>(WriteHold{std::move(charge),std::move(message),std::move(ack)});
  auto count=data->payload.size();auto stream=t->stream;std::weak_ptr<TransferEngine> w=weak_from_this();auto* q=&queue_;
  stream->Write(data->payload,[w,q,t,held,stream,count,owner=scheduler_owner_](auto result){q->Post([w,t,held,result,count]{
   if(auto e=w.lock())if(e->Current(t)){if(!result.ok())e->Fail(t,result.error());else{t->bytes+=count;++t->sequence;e->Progress(t);e->Deadline(t);}}
   held->message.reset();held->ack(Result<void>::Success());
  });});return;
 }
 if(auto finish=std::get_if<protocol::Finish>(message.get())){
  std::array<std::uint8_t,32> hash{};unsigned length=0;
  if(t->outgoing||t->phase!=Transfer::Phase::Receiving||t->bytes!=t->offer.byte_count||finish->byte_count!=t->bytes||t->utf8.remaining||
   EVP_DigestFinal_ex(t->digest,hash.data(),&length)!=1||length!=32||hash!=finish->sha256){Fail(t,Error(ErrorCode::IntegrityFailed,"checksum or size mismatch"));ok();return;}
  t->phase=Transfer::Phase::Completing;Progress(t);
  std::weak_ptr<TransferEngine> w=weak_from_this();
  SendMessage(protocol::Status{protocol::MessageType::Complete,id,{}},[w,t](auto){if(auto e=w.lock())e->Finish(t,Result<void>::Success());});ok();return;
 }
 Fail(t,Error(ErrorCode::InvalidInput,"unexpected transfer message"));ok();
}
}
