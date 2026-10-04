#include "files.hpp"
#include "win32_helpers.hpp"
#include "core/serial_scheduler.hpp"
#include <atomic>
namespace unishare::windows { namespace {
ShareError Cancelled(){return {ErrorCode::Cancelled,"stream closed or aborted",ERROR_OPERATION_ABORTED};}
struct StreamState {
 std::mutex mutex;Handle file;bool destination;int terminal=0;std::uint64_t offset=0;OperationId next=0;
 std::optional<ShareError> cleanup_error;
 std::shared_ptr<core::SerialScheduler> worker=std::make_shared<core::SerialScheduler>();
 StreamState(HANDLE h,bool d):file(h),destination(d){}
 void Cleanup(){ // worker only; abort flag has already been published
  std::lock_guard<std::mutex> guard(mutex);
  if(file.value==INVALID_HANDLE_VALUE)return;
  if(destination){FILE_DISPOSITION_INFO info{TRUE};if(!SetFileInformationByHandle(file.value,FileDispositionInfo,&info,sizeof info))cleanup_error=ShareError{ErrorCode::StorageFailed,"remove staging file",GetLastError()};}
  CloseHandle(file.release());
 }
 bool Aborted(){std::lock_guard<std::mutex> guard(mutex);return terminal==2;}
 ShareError AbortError(){Cleanup();return cleanup_error.value_or(Cancelled());}
};
class FileStream final : public IStream {
 std::shared_ptr<StreamState> state_;
public:
 FileStream(HANDLE h,bool d):state_(std::make_shared<StreamState>(h,d)){}
 ~FileStream(){AbortAndCleanup();}
 OperationId Read(std::size_t count,ResultCallback<std::vector<std::uint8_t>> cb) override {
  auto s=state_;std::lock_guard<std::mutex> guard(s->mutex);auto id=++s->next;
  bool reject=s->terminal!=0||s->destination; s->worker->Post([s,count,cb=std::move(cb),reject]{
   using R=Result<std::vector<std::uint8_t>>;
   if(s->Aborted()){cb(R::Failure(s->AbortError()));return;}
   if(reject){cb(R::Failure(Cancelled()));return;}
   if(count==0){cb(R::Failure({ErrorCode::InvalidInput,"read size must be positive",{}}));return;}
   const auto bounded=std::min<std::size_t>(count,4*1024*1024);std::vector<std::uint8_t> bytes(bounded);OVERLAPPED ov{};ov.Offset=static_cast<DWORD>(s->offset);ov.OffsetHigh=static_cast<DWORD>(s->offset>>32);Handle event(CreateEventW(nullptr,TRUE,FALSE,nullptr));ov.hEvent=event.value;
   DWORD n=0,code=0;
   if(!event.value)code=GetLastError();
   else if(!ReadFile(s->file.value,bytes.data(),static_cast<DWORD>(bounded),&n,&ov)){code=GetLastError();if(code==ERROR_IO_PENDING){code=GetOverlappedResult(s->file.value,&ov,&n,TRUE)?0:GetLastError();}}
   if(s->Aborted()){cb(R::Failure(s->AbortError()));return;}
   if(code&&code!=ERROR_HANDLE_EOF){cb(R::Failure({ErrorCode::StorageFailed,"read source",code}));return;}
   bytes.resize(n);s->offset+=n;cb(R::Success(std::move(bytes)));
  });return id;
 }
 OperationId Write(std::vector<std::uint8_t> bytes,ResultCallback<void> cb) override {
  auto s=state_;std::lock_guard<std::mutex> guard(s->mutex);auto id=++s->next;
  bool reject=s->terminal!=0||!s->destination;s->worker->Post([s,bytes=std::move(bytes),cb=std::move(cb),reject]{
   using R=Result<void>;
   if(s->Aborted()){cb(R::Failure(s->AbortError()));return;}
   if(reject){cb(R::Failure(Cancelled()));return;}
   DWORD code=0;std::size_t pos=0;Handle event(CreateEventW(nullptr,TRUE,FALSE,nullptr));if(!event.value)code=GetLastError();
   while(!code&&pos<bytes.size()&&!s->Aborted()){
    OVERLAPPED ov{};ov.Offset=static_cast<DWORD>(s->offset);ov.OffsetHigh=static_cast<DWORD>(s->offset>>32);ov.hEvent=event.value;ResetEvent(event.value);
    auto count=static_cast<DWORD>(std::min<std::size_t>(bytes.size()-pos,1024*1024));DWORD n=0;
    if(!WriteFile(s->file.value,bytes.data()+pos,count,&n,&ov)){code=GetLastError();if(code==ERROR_IO_PENDING)code=GetOverlappedResult(s->file.value,&ov,&n,TRUE)?0:GetLastError();}
    if(!code&&!n)code=ERROR_WRITE_FAULT;s->offset+=n;pos+=n;
   }
   if(!code&&!s->Aborted()&&!FlushFileBuffers(s->file.value))code=GetLastError();
   if(s->Aborted()){cb(R::Failure(s->AbortError()));return;}
   if(code){cb(R::Failure({ErrorCode::StorageFailed,"write or flush staging",code}));return;}
   cb(R::Success());
  });return id;
 }
 void Close() override {
  auto s=state_;std::lock_guard<std::mutex> guard(s->mutex);if(s->terminal)return;s->terminal=1;
  s->worker->Post([s]{std::lock_guard<std::mutex> guard(s->mutex);if(s->file.value!=INVALID_HANDLE_VALUE)CloseHandle(s->file.release());});
 }
 void AbortAndCleanup() override {
  auto s=state_;std::lock_guard<std::mutex> guard(s->mutex);if(s->terminal)return;s->terminal=2;
  if(s->file.value!=INVALID_HANDLE_VALUE)CancelIoEx(s->file.value,nullptr);
  s->worker->Post([s]{s->Cleanup();});
 }
};
}
StreamFactory::~StreamFactory(){for(auto& pair:entries_){auto& e=pair.second;if(e.destination){FILE_DISPOSITION_INFO info{TRUE};SetFileInformationByHandle(e.handle,FileDispositionInfo,&info,sizeof info);}CloseHandle(e.handle);}}
Result<std::string> StreamFactory::Register(HANDLE h,bool destination){try{
 if(GetFileType(h)!=FILE_TYPE_DISK)Fail("disk file handle required",ERROR_INVALID_HANDLE);RequireRegular(h);
 // ReOpenFile gives an independent file position and guarantees overlapped I/O,
 // even when the host's original handle was synchronous.
 Handle owned(ReOpenFile(h,destination?GENERIC_READ|GENERIC_WRITE|DELETE:GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,FILE_FLAG_OVERLAPPED));if(owned.value==INVALID_HANDLE_VALUE)Fail("reopen supplied file handle");RequireRegular(owned.value);
 if(destination){LARGE_INTEGER n;if(!GetFileSizeEx(owned.value,&n))Fail("size staging file");if(n.QuadPart)Fail("destination must be empty staging file",ERROR_INVALID_PARAMETER);}
 std::lock_guard<std::mutex> guard(mutex_);auto token="win-file-"+std::to_string(++next_);entries_.emplace(token,Entry{owned.release(),destination});return Result<std::string>::Success(token);
}catch(const WinError& e){return Result<std::string>::Failure(e.error);}}
Result<std::shared_ptr<IStream>> StreamFactory::Open(const std::string& token,bool destination){
 std::lock_guard<std::mutex> guard(mutex_);auto it=entries_.find(token);if(it==entries_.end()||it->second.destination!=destination)return Result<std::shared_ptr<IStream>>::Failure({ErrorCode::InvalidInput,"unknown or mismatched file token",{}});
 auto h=it->second.handle;
 if(destination&&!FlushFileBuffers(h))return Result<std::shared_ptr<IStream>>::Failure({ErrorCode::StorageFailed,"flush empty staging",GetLastError()});
 auto stream=std::make_shared<FileStream>(h,destination);entries_.erase(it);return Result<std::shared_ptr<IStream>>::Success(stream);
}
}
