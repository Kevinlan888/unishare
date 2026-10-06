#include "transfer_test_support.hpp"
#include "core/transfer_engine.hpp"

// Adapter fake: asynchronous I/O with controllable failures and held callbacks.
// Engine assertions below detect missing consent, truncation, missing cleanup,
// duplicate outcomes, wrong wire ordering, and unbounded source read-ahead.
struct Stream : unishare::IStream {
 Scheduler& q; std::vector<std::uint8_t> bytes; std::size_t offset=0,reads=0,writes=0,max_read=0;
 bool closed=false,aborted=false,hold=false,fail_write=false;
 std::function<void()> pending;
 explicit Stream(Scheduler& s,std::size_t size=0):q(s),bytes(size,0xa5){}
 OperationId Read(std::size_t n,ResultCallback<std::vector<std::uint8_t>> cb) override {
  CHECK(!pending);++reads;max_read=std::max<std::size_t>(max_read,n);
  auto count=std::min<std::size_t>(n,bytes.size()-offset);std::vector<std::uint8_t> out(bytes.begin()+offset,bytes.begin()+offset+count);offset+=count;
  auto fn=[cb,out]{cb(Result<std::vector<std::uint8_t>>::Success(out));};if(hold)pending=fn;else q.Post(fn);return q.next++;
 }
 OperationId Write(std::vector<std::uint8_t> b,ResultCallback<void> cb) override {
  CHECK(!pending);++writes;
  auto fn=[this,cb,b]{if(fail_write)cb(Result<void>::Failure({ErrorCode::StorageFailed,"disk full",28}));else {if(!aborted)bytes.insert(bytes.end(),b.begin(),b.end());cb(Result<void>::Success());}};
  if(hold)pending=fn;else q.Post(fn);return q.next++;
 }
 void Release(){auto fn=std::move(pending);CHECK(fn);q.Post(std::move(fn));}
 void Close() override{closed=true;}
 void AbortAndCleanup() override{aborted=true;bytes.clear();closed=true;}
};
struct Rig {
 Pair p;std::vector<DiagnosticEvent> ad,bd;
 std::shared_ptr<TransferEngine> a,b;std::vector<IncomingOffer> offers;
 std::vector<TransferResult> ar,br;std::vector<TransferProgress> ap,bp;
 Rig(bool receiver=true,SdkConfig config={}) {p.Start();p.ConfirmA();p.ConfirmB();
  config.callback_executor=std::shared_ptr<IExecutor>(&p.q,[](IExecutor*){});
  a=TransferEngine::Create(p.a,p.q,p.a_limit,config,{},[this](auto x){ap.push_back(x);},[this](auto x){ar.push_back(x);},{},[this](auto x){ad.push_back(x);});
  if(receiver)b=TransferEngine::Create(p.b,p.q,p.b_limit,config,[this](auto x){offers.push_back(x);},[this](auto x){bp.push_back(x);},[this](auto x){br.push_back(x);},{},[this](auto x){bd.push_back(x);});
 }
 ~Rig(){p.Close();}
 ContentSource Source(ContentType type,std::shared_ptr<Stream> s){return {type,{"sample.bin","application/octet-stream",s->bytes.size(),s}};}
 TransferId Send(ContentType type,std::shared_ptr<Stream> s){auto id=a->Offer(Source(type,s));p.q.Run();return id;}
};
void DiagnosticStages(){
 auto count=[](const auto& events,DiagnosticStage stage,DiagnosticOutcome outcome){return std::count_if(events.begin(),events.end(),[&](const auto& e){return e.stage==stage&&e.outcome==outcome;});};
 {
  Rig r;auto src=std::make_shared<Stream>(r.p.q,262145),dst=std::make_shared<Stream>(r.p.q);
  auto id=r.Send(ContentType::File,src);r.b->Accept(id,dst);r.p.q.Run();
  CHECK(count(r.ad,DiagnosticStage::TransferStream,DiagnosticOutcome::Began)==1);
  CHECK(count(r.ad,DiagnosticStage::TransferStream,DiagnosticOutcome::Succeeded)==1);
  CHECK(count(r.ad,DiagnosticStage::Transfer,DiagnosticOutcome::Succeeded)==1);
  CHECK(count(r.bd,DiagnosticStage::Transfer,DiagnosticOutcome::Succeeded)==1);
  CHECK(r.ad.front().sequence!=0&&r.ad.front().content_kind==DiagnosticContentKind::File);
 }
 {
  Rig r;auto src=std::make_shared<Stream>(r.p.q,3),dst=std::make_shared<Stream>(r.p.q);dst->fail_write=true;
  auto id=r.Send(ContentType::File,src);r.b->Accept(id,dst);r.p.q.Run();
  CHECK(count(r.bd,DiagnosticStage::TransferStream,DiagnosticOutcome::Failed)==1);
  CHECK(std::any_of(r.bd.begin(),r.bd.end(),[](const auto& e){return e.stage==DiagnosticStage::Transfer&&e.outcome==DiagnosticOutcome::Failed&&e.error&&e.error->code==ErrorCode::StorageFailed&&e.error->platform_error==28;}));
 }
 {
  Rig r;auto src=std::make_shared<Stream>(r.p.q,2),dst=std::make_shared<Stream>(r.p.q);
  auto source=r.Source(ContentType::File,src);source.content.byte_count=3;
  auto id=r.a->Offer(source);r.p.q.Run();r.b->Accept(id,dst);r.p.q.Run();
  CHECK(count(r.ad,DiagnosticStage::IntegrityVerify,DiagnosticOutcome::Failed)==1);
  CHECK(count(r.ad,DiagnosticStage::Transfer,DiagnosticOutcome::Failed)==1);
 }
 {
  Rig r;auto src=std::make_shared<Stream>(r.p.q,1);auto source=r.Source(ContentType::File,src);
  source.content.display_name="../private.txt";r.a->Offer(source);r.p.q.Run();
  CHECK(count(r.ad,DiagnosticStage::Offer,DiagnosticOutcome::Failed)==1);
  CHECK(count(r.ad,DiagnosticStage::Transfer,DiagnosticOutcome::Failed)==1);
 }
 {
  Rig r;auto src=std::make_shared<Stream>(r.p.q,3),dst=std::make_shared<Stream>(r.p.q);
  auto id=r.Send(ContentType::File,src);src->hold=true;r.b->Accept(id,dst);r.p.q.Run();r.a->Cancel(id);src->Release();r.p.q.Run();
  CHECK(count(r.ad,DiagnosticStage::Cancel,DiagnosticOutcome::Succeeded)==1);
  CHECK(count(r.ad,DiagnosticStage::Transfer,DiagnosticOutcome::Cancelled)==1);
 }
}
void RoundTrips(){
 for(auto type:{ContentType::Text,ContentType::Image,ContentType::File})for(auto n:{0u,3u,262144u,262145u}){
  Rig r;auto source=std::make_shared<Stream>(r.p.q,n),sink=std::make_shared<Stream>(r.p.q);
  if(type==ContentType::Text)std::fill(source->bytes.begin(),source->bytes.end(),'a');
  auto id=r.Send(type,source);CHECK(r.offers.size()==1&&r.offers[0].transfer_id==id);CHECK(r.offers[0].device_id==r.p.a_id.material.id);CHECK(source->reads==0&&sink->writes==0&&r.ar.empty());
  r.b->Accept(id,sink);r.p.q.Run();CHECK(r.ar.size()==1&&r.br.size()==1&&r.ar[0].result.ok()&&r.br[0].result.ok());
  CHECK(sink->bytes==source->bytes&&sink->closed&&!sink->aborted);CHECK(source->max_read<=262144);
  CHECK(!r.ap.empty()&&!r.bp.empty()&&r.ap.back().transferred==n&&r.bp.back().transferred==n);
 }
}
void DecisionsAndFailures(){
 for(int mode=0;mode<6;++mode){Rig r;auto src=std::make_shared<Stream>(r.p.q,262145),dst=std::make_shared<Stream>(r.p.q);auto id=r.Send(ContentType::File,src);
  if(mode==0)r.b->Reject(id);
  if(mode==1)r.p.q.Advance(std::chrono::seconds(60));
  if(mode==2){dst->fail_write=true;r.b->Accept(id,dst);}
  if(mode==3){dst->hold=true;r.b->Accept(id,dst);r.p.q.Run();r.b->Cancel(id);CHECK(dst->aborted);dst->Release();}
  if(mode==4){src->hold=true;r.b->Accept(id,dst);r.p.q.Run();r.a->Cancel(id);src->Release();}
  if(mode==5){dst->hold=true;r.b->Accept(id,dst);r.p.q.Run();r.p.b->Close({ErrorCode::NetworkLost,"disconnect",{}});CHECK(dst->aborted);dst->Release();}
  r.p.q.Run();CHECK(r.ar.size()==1&&r.br.size()==1&&!r.ar[0].result.ok()&&!r.br[0].result.ok());
  if(mode>=2)CHECK(dst->aborted&&dst->bytes.empty());
  if(mode==0)CHECK(r.ar[0].result.error().code==ErrorCode::TransferRejected);
  if(mode==1)CHECK(r.br[0].result.error().code==ErrorCode::Timeout);
  if(mode==2)CHECK(r.br[0].result.error().code==ErrorCode::StorageFailed&&r.br[0].result.error().platform_error==28);
 }
}
void Limits(){
 Rig r;auto s=std::make_shared<Stream>(r.p.q,1);auto id=r.Send(ContentType::File,s);auto second=r.a->Offer(r.Source(ContentType::File,s));r.p.q.Run();
 CHECK(second!=id&&r.ar.size()==1&&r.ar[0].result.error().code==ErrorCode::ResourceLimit&&r.offers.size()==1);
 r.b->Reject(id);r.p.q.Run();CHECK(r.ar.size()==2);
 for(auto name:{"../escape","C:\\escape","a/b",".."}){auto x=r.Source(ContentType::File,s);x.content.display_name=name;r.a->Offer(x);r.p.q.Run();CHECK(r.ar.back().result.error().code==ErrorCode::InvalidInput);}
 auto x=r.Source(ContentType::File,s);x.content.byte_count=20ULL*1024*1024*1024+1;r.a->Offer(x);r.p.q.Run();CHECK(r.ar.back().result.error().code==ErrorCode::ResourceLimit);
}
void HostilePeer(){
 // Receiver is the real engine; the sender uses only the authenticated port.
 for(int mode=0;mode<7;++mode){Pair p;p.Start();p.ConfirmA();p.ConfirmB();std::vector<IncomingOffer> offers;std::vector<TransferResult> results;std::vector<DiagnosticEvent> diagnostics;
  auto e=TransferEngine::Create(p.b,p.q,p.b_limit,{},[&](auto x){offers.push_back(x);},{},[&](auto x){results.push_back(x);},{},[&](auto x){diagnostics.push_back(x);});
  p.a->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[](auto){});
  protocol::Offer o;o.transfer_id.bytes[0]=1;o.content_type=ContentType::File;o.display_name="ok";o.byte_count=1;
  if(mode==4)o.byte_count=20ULL*1024*1024*1024+1;if(mode==5)o.display_name="../escape";
  p.a->SendContent(o,[](auto x){CHECK(x.ok());});p.q.Run();auto dst=std::make_shared<Stream>(p.q);
  if(mode>=4&&mode<=5){CHECK(offers.empty()&&results.size()==1&&!results[0].result.ok());
   CHECK(std::any_of(diagnostics.begin(),diagnostics.end(),[](const auto& event){return event.stage==DiagnosticStage::Offer&&event.outcome==DiagnosticOutcome::Failed&&event.direction==DiagnosticDirection::Receive&&event.error&&(event.error->code==ErrorCode::ResourceLimit||event.error->code==ErrorCode::InvalidInput);}));
   p.Close();continue;}
  CHECK(offers.size()==1);if(mode!=0)e->Accept(o.transfer_id,dst);p.q.Run();
  protocol::Data d;d.transfer_id=o.transfer_id;d.payload={0xa5};if(mode==2)d.payload.push_back(0xa5);if(mode==3)d.sequence=1;
  p.a->SendContent(d,[](auto){});p.q.Run();
  if(mode==1||mode==6){protocol::Finish f;f.transfer_id=o.transfer_id;f.byte_count=mode==6?0:1;p.a->SendContent(f,[](auto){});p.q.Run();}
  CHECK(results.size()==1&&!results[0].result.ok());CHECK(dst->bytes.empty());if(mode!=0)CHECK(dst->aborted);p.Close();
 }
}
void TerminalWriteFailure(){
 // Receipt is not published if the Complete socket write fails.
 Rig r;auto src=std::make_shared<Stream>(r.p.q,0),dst=std::make_shared<Stream>(r.p.q);
 auto id=r.Send(ContentType::File,src);src->hold=true;r.b->Accept(id,dst);r.p.q.Run();
 r.p.bx->hold_writes=true;src->Release();r.p.q.Run();
 CHECK(r.p.bx->held_write);CHECK(r.br.empty()&&!dst->closed);
 r.p.bx->CompleteHeld(Result<void>::Failure({ErrorCode::NetworkLost,"complete write failed",{}}));r.p.q.Run();
 CHECK(r.br.size()==1&&!r.br[0].result.ok()&&dst->aborted);
}
void FinishWriteFailure(){
 Rig r;auto src=std::make_shared<Stream>(r.p.q,0),dst=std::make_shared<Stream>(r.p.q);
 auto id=r.Send(ContentType::File,src);src->hold=true;r.b->Accept(id,dst);r.p.q.Run();
 r.p.ax->hold_writes=true;src->Release();r.p.q.Run();
 CHECK(r.p.ax->held_write);CHECK(r.ar.empty());
 r.p.ax->CompleteHeld(Result<void>::Failure({ErrorCode::NetworkLost,"finish write failed",{}}));r.p.q.Run();
 CHECK(r.ar.size()==1&&!r.ar[0].result.ok());
}
void AsyncAndBounds(){
 for(auto declared:{0u,2u}){Rig r;auto src=std::make_shared<Stream>(r.p.q,1),dst=std::make_shared<Stream>(r.p.q);auto source=r.Source(ContentType::File,src);source.content.byte_count=declared;
  auto id=r.a->Offer(source);r.p.q.Run();r.b->Accept(id,dst);r.p.q.Run();CHECK(r.ar.size()==1&&!r.ar[0].result.ok()&&dst->aborted&&r.br.size()==1);}
 {Rig r;auto src=std::make_shared<Stream>(r.p.q,262145),dst=std::make_shared<Stream>(r.p.q);auto id=r.Send(ContentType::File,src);
  dst->hold=true;r.b->Accept(id,dst);r.p.q.Run();CHECK(dst->writes==1);auto baseline=r.p.b_limit.Buffers().Used();CHECK(baseline<=4*1024*1024&&baseline>262144);
  r.b->Cancel(id);CHECK(dst->aborted);CHECK(r.p.b_limit.Buffers().Used()>0);dst->Release();r.p.q.Run();CHECK(r.p.b_limit.Buffers().Used()==0);CHECK(r.br.size()==1);}
 {Rig r;auto src=std::make_shared<Stream>(r.p.q,1),dst=std::make_shared<Stream>(r.p.q);auto id=r.Send(ContentType::File,src);src->hold=true;r.b->Accept(id,dst);r.p.q.Run();
  r.a.reset();r.p.q.Run();CHECK(r.ar.size()==1&&!r.ar[0].result.ok()&&src->closed);CHECK(r.br.size()==1&&dst->aborted);src->Release();r.p.q.Run();CHECK(r.ar.size()==1);}
 {SdkConfig c;c.max_file_bytes=2;Rig r(true,c);auto src=std::make_shared<Stream>(r.p.q,3);r.Send(ContentType::File,src);CHECK(r.offers.empty()&&r.ar.size()==1&&r.ar[0].result.error().code==ErrorCode::ResourceLimit);}
}
void StaleDeadline(){
 Rig r;auto src=std::make_shared<Stream>(r.p.q,1),dst=std::make_shared<Stream>(r.p.q);auto id=r.Send(ContentType::File,src);
 std::vector<std::function<void()>> old;for(auto& timer:r.p.q.timers)old.push_back(timer.second.fn);
 src->hold=true;r.b->Accept(id,dst);r.p.q.Run();for(auto& fn:old)fn();r.p.q.Run();CHECK(r.ar.empty()&&r.br.empty());
 src->hold=false;src->Release();r.p.q.Run();CHECK(r.ar.size()==1&&r.ar[0].result.ok());
}
void SharedOfferLimit(){
 SessionAdmission admission;std::vector<std::unique_ptr<Pair>> pairs;std::vector<std::shared_ptr<TransferEngine>> engines;int offers=0,failed=0;
 for(int i=0;i<5;++i){auto p=std::make_unique<Pair>();p->external_b_limit=&admission;p->source="192.0.2."+std::to_string(i+1);p->Start();p->ConfirmA();p->ConfirmB();
  auto engine=TransferEngine::Create(p->b,p->q,admission,{},[&](auto){++offers;},{},[&](auto r){if(!r.result.ok())++failed;});
  p->a->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[](auto){});
  protocol::Offer o;o.transfer_id.bytes[0]=static_cast<std::uint8_t>(i+1);p->a->SendContent(o,[](auto r){CHECK(r.ok());});p->q.Run();engines.push_back(engine);pairs.push_back(std::move(p));
 }
 CHECK(offers==4&&failed==1);
 for(auto& p:pairs)p->Close();engines.clear();
 CHECK(admission.Buffers().Used()==0);
}
void ExecutorsUtf8AndBudgets(){
 {Rig r;auto src=std::make_shared<Stream>(r.p.q,1),dst=std::make_shared<Stream>(r.p.q);dst->hold=true;dst->fail_write=true;
  auto id=r.Send(ContentType::File,src);r.b->Accept(id,dst);r.p.q.Run();auto lease=r.p.b_limit.Buffers().Reserve(4*1024*1024-r.p.b_limit.Buffers().Used());CHECK(lease);
  dst->Release();r.p.q.Run();CHECK(r.br.size()==1&&r.br[0].result.error().code==ErrorCode::StorageFailed&&r.br[0].result.error().platform_error==28);}
 {Rig r;auto baseline=r.p.a_limit.Buffers().Used();auto src=std::make_shared<Stream>(r.p.q);auto source=r.Source(ContentType::File,src);source.content.display_name.assign(4096,'a');
  auto id=r.a->Offer(source);r.p.q.Run();CHECK(r.p.a_limit.Buffers().Used()>=baseline+4096);r.b->Reject(id);r.p.q.Run();CHECK(r.p.a_limit.Buffers().Used()==baseline);}
 {Pair p;p.Start();p.ConfirmA();p.ConfirmB();Scheduler callbacks;SdkConfig c;c.callback_executor=std::shared_ptr<IExecutor>(&callbacks,[](IExecutor*){});
  int offers=0,results=0;auto e=TransferEngine::Create(p.b,p.q,p.b_limit,c,[&](auto){++offers;},{},[&](auto){++results;});
  p.a->SetContentHandler([](auto,auto ack){ack(Result<void>::Success());},[](auto){});protocol::Offer offer;offer.transfer_id.bytes[0]=1;
  p.a->SendContent(offer,[](auto r){CHECK(r.ok());});p.q.Run();CHECK(!offers);callbacks.Run();CHECK(offers==1);
  e->Reject(offer.transfer_id);p.q.Run();CHECK(!results);callbacks.Run();CHECK(results==1);p.Close();}
 for(bool valid:{false,true}){SdkConfig c;c.data_chunk_bytes=1;Rig r(true,c);auto src=std::make_shared<Stream>(r.p.q),dst=std::make_shared<Stream>(r.p.q);
  src->bytes=valid?std::vector<std::uint8_t>{0xf0,0x9f,0x98,0x80}:std::vector<std::uint8_t>{0xed,0xa0,0x80};auto id=r.Send(ContentType::Text,src);r.b->Accept(id,dst);r.p.q.Run();
  CHECK(r.ar.size()==1&&r.ar[0].result.ok()==valid&&r.br.size()==1&&r.br[0].result.ok()==valid);if(!valid)CHECK(dst->aborted);}
 {Rig r;auto src=std::make_shared<Stream>(r.p.q,262144),dst=std::make_shared<Stream>(r.p.q);auto id=r.Send(ContentType::File,src);
  auto lease=r.p.a_limit.Buffers().Reserve(4*1024*1024-r.p.a_limit.Buffers().Used()-400*1024);CHECK(lease);
  r.b->Accept(id,dst);r.p.q.Run();CHECK(r.ar.size()==1&&!r.ar[0].result.ok()&&src->reads==0&&dst->aborted);lease.reset();}
}
int main(){DiagnosticStages();RoundTrips();DecisionsAndFailures();Limits();HostilePeer();TerminalWriteFailure();FinishWriteFailure();StaleDeadline();AsyncAndBounds();SharedOfferLimit();ExecutorsUtf8AndBudgets();std::cout<<"transfer engine passed\n";}
