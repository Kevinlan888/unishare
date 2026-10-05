#include "Common.hpp"
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
namespace unishare::ios {
namespace {
ShareError FileError(int code) {return Failure(code==EACCES||code==EPERM?ErrorCode::PermissionDenied:ErrorCode::StorageFailed,"file URL operation failed",code);}
struct FileState {
 NSURL* url; bool scoped=false,destination=false,sealed=false; int fd=-1;
 dev_t device=0; ino_t inode=0;
 std::atomic<bool> aborted{false};
 std::shared_ptr<Queue> queue=std::make_shared<Queue>("com.unishare.file");
 ~FileState() {if(fd>=0)::close(fd);if(destination&&!sealed)Remove();if(scoped)[url stopAccessingSecurityScopedResource];}
 std::optional<ShareError> Remove() {
  struct stat st{};
  if(lstat(url.fileSystemRepresentation,&st)) {if(errno==ENOENT)return {};return FileError(errno);}
  if(st.st_dev!=device||st.st_ino!=inode)return FileError(ESTALE);
  if(unlink(url.fileSystemRepresentation))return FileError(errno);
  return {};
 }
};
class File final : public IStream {
 std::shared_ptr<FileState> state_;
public:
 explicit File(std::shared_ptr<FileState> state):state_(std::move(state)) {}
 ~File() {if(!state_->sealed)AbortAndCleanup();else Close();}
 OperationId Read(size_t maximum,ResultCallback<std::vector<uint8_t>> callback) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,maximum,callback] {
   if(s->aborted||s->fd<0){Deliver(callback,Result<std::vector<uint8_t>>::Failure(Cancelled()));return;}
   if(!maximum){Deliver(callback,Result<std::vector<uint8_t>>::Failure(Failure(ErrorCode::InvalidInput,"zero read size")));return;}
   std::vector<uint8_t> bytes(std::min<size_t>(maximum,256*1024));ssize_t n;
   do {n=read(s->fd,bytes.data(),bytes.size());}while(n<0&&errno==EINTR);
   auto error=n<0?std::optional<ShareError>(FileError(errno)):std::nullopt;
   if(s->aborted)error=Cancelled();
   if(error)Deliver(callback,Result<std::vector<uint8_t>>::Failure(*error));
   else {bytes.resize(n);Deliver(callback,Result<std::vector<uint8_t>>::Success(std::move(bytes)));}
  });return id;
 }
 OperationId Write(std::vector<uint8_t> bytes,ResultCallback<void> callback) override {
  auto id=Next();auto s=state_;
  s->queue->Post([s,bytes=std::move(bytes),callback] {
   std::optional<ShareError> error;
   if(s->aborted||s->fd<0)error=Cancelled();
   else if(!s->destination)error=Failure(ErrorCode::InvalidInput,"source is read-only");
   size_t offset=0;
   while(!error&&offset<bytes.size()) {
    if(s->aborted){error=Cancelled();break;}
    auto n=write(s->fd,bytes.data()+offset,std::min<size_t>(bytes.size()-offset,256*1024));
    if(n<0&&errno==EINTR)continue;
    if(n<=0){error=FileError(n<0?errno:EIO);break;}offset+=n;
   }
   if(!error&&fsync(s->fd))error=FileError(errno);
   if(s->aborted)error=Cancelled();
   Deliver(callback,error?Result<void>::Failure(*error):Result<void>::Success());
  });return id;
 }
 void Close() override {
  auto s=state_;s->queue->Sync([s] {s->sealed=true;if(s->fd>=0){close(s->fd);s->fd=-1;}});
 }
 void AbortAndCleanup() override {
  auto s=state_;s->aborted=true;
  s->queue->Sync([s] {if(s->fd>=0){close(s->fd);s->fd=-1;}if(s->destination)s->Remove();s->sealed=true;});
 }
};
class Streams final : public UrlStreams {
 std::mutex mutex_;std::map<std::string,std::shared_ptr<FileState>> entries_;
 Result<std::shared_ptr<IStream>> Open(const std::string& token,bool destination) {
  std::lock_guard<std::mutex> lock(mutex_);auto it=entries_.find(token);
  if(it==entries_.end()||it->second->destination!=destination)return Result<std::shared_ptr<IStream>>::Failure(Failure(ErrorCode::InvalidInput,"unknown file token"));
  auto stream=std::make_shared<File>(it->second);entries_.erase(it);return Result<std::shared_ptr<IStream>>::Success(stream);
 }
public:
 Result<std::string> Register(NSURL* url,bool destination) override { @autoreleasepool {
  if(!url.isFileURL)return Result<std::string>::Failure(Failure(ErrorCode::InvalidInput,"file URL required"));
  auto state=std::make_shared<FileState>();state->url=[url copy];state->scoped=[url startAccessingSecurityScopedResource];
  state->fd=open(url.fileSystemRepresentation,(destination?O_RDWR:O_RDONLY)|O_CLOEXEC|O_NOFOLLOW);
  if(state->fd<0)return Result<std::string>::Failure(FileError(errno));
  struct stat info{};if(fstat(state->fd,&info))return Result<std::string>::Failure(FileError(errno));
  if(!S_ISREG(info.st_mode)||(destination&&info.st_size!=0))return Result<std::string>::Failure(Failure(ErrorCode::InvalidInput,"empty regular staging file required"));
  state->device=info.st_dev;state->inode=info.st_ino;
  if(destination&&fsync(state->fd))return Result<std::string>::Failure(FileError(errno));
  state->destination=destination;
  std::string token=NSUUID.UUID.UUIDString.UTF8String;
  std::lock_guard<std::mutex> lock(mutex_);entries_.emplace(token,state);return Result<std::string>::Success(token);
 }}
 Result<std::shared_ptr<IStream>> OpenSource(std::string token) override {return Open(token,false);}
 Result<std::shared_ptr<IStream>> OpenDestination(std::string token) override {return Open(token,true);}
};
}
std::shared_ptr<UrlStreams> MakeStreams() {return std::make_shared<Streams>();}
}
