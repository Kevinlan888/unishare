#include "platform/windows/storage.hpp"
#include "platform/windows/files.hpp"
#include "platform/windows/clipboard.hpp"
#include <windows.h>
#include <aclapi.h>
#include <filesystem>
#include <fstream>
#include <future>
#include <atomic>
#include <iostream>
#include <stdexcept>
using namespace unishare;
namespace fs=std::filesystem;
#define CHECK(x) do {if(!(x)) throw std::runtime_error("CHECK failed: " #x);} while(false)
struct MemoryClipboard : windows::ClipboardBoundary {
 std::vector<windows::ClipboardData> data;bool denied=false;
 Result<std::vector<windows::ClipboardData>> Read() override {if(denied)return Result<std::vector<windows::ClipboardData>>::Failure({ErrorCode::PermissionDenied,"clipboard busy",ERROR_ACCESS_DENIED});return Result<std::vector<windows::ClipboardData>>::Success(data);}
 Result<void> Write(std::vector<windows::ClipboardData> d) override {if(denied)return Result<void>::Failure({ErrorCode::PermissionDenied,"clipboard busy",ERROR_ACCESS_DENIED});data=std::move(d);return Result<void>::Success();}
};
template<class T,class F> Result<T> Await(F start){std::promise<Result<T>> p;auto f=p.get_future();start([&](auto r){p.set_value(std::move(r));});CHECK(f.wait_for(std::chrono::seconds(5))==std::future_status::ready);return f.get();}
int main(){try {
 auto root=fs::current_path()/ ("windows-port-test-"+std::to_string(GetCurrentProcessId())+"-"+std::to_string(GetTickCount64()));
 CHECK(!fs::exists(root)); fs::create_directory(root);
 windows::IdentityStore store(root/"identity");
 CHECK(store.LoadIdentity().ok()); CHECK(!store.LoadIdentity().value());
 IdentityMaterial m; m.id.bytes[0]=42; m.private_key={1,2,3};m.certificate={4,5};
 CHECK(store.SaveIdentity(m).ok());
 CHECK(!store.SaveIdentity(m).ok());
 // Protected DACL grants only the current process user, with inheritance for records.
 PACL dacl=nullptr;PSECURITY_DESCRIPTOR sd=nullptr;CHECK(GetNamedSecurityInfoW(const_cast<wchar_t*>((root/L"identity").c_str()),SE_FILE_OBJECT,DACL_SECURITY_INFORMATION,nullptr,nullptr,&dacl,nullptr,&sd)==ERROR_SUCCESS);
 SECURITY_DESCRIPTOR_CONTROL control{};DWORD revision=0;CHECK(GetSecurityDescriptorControl(sd,&control,&revision));CHECK(control&SE_DACL_PROTECTED);CHECK(dacl&&dacl->AceCount==1);void* ace=nullptr;CHECK(GetAce(dacl,0,&ace));CHECK(static_cast<ACE_HEADER*>(ace)->AceType==ACCESS_ALLOWED_ACE_TYPE);
 HANDLE userToken=nullptr;CHECK(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&userToken));DWORD userSize=0;GetTokenInformation(userToken,TokenUser,nullptr,0,&userSize);std::vector<std::uint8_t> user(userSize);CHECK(GetTokenInformation(userToken,TokenUser,user.data(),userSize,&userSize));CHECK(EqualSid(&static_cast<ACCESS_ALLOWED_ACE*>(ace)->SidStart,reinterpret_cast<TOKEN_USER*>(user.data())->User.Sid));CloseHandle(userToken);LocalFree(sd);
 windows::IdentityStore restarted(root/"identity");
 auto loaded=restarted.LoadIdentity(); CHECK(loaded.ok()); CHECK(loaded.value());
 CHECK(loaded.value()->id==m.id);CHECK(loaded.value()->private_key==m.private_key);CHECK(loaded.value()->certificate==m.certificate);
 TrustedDevice t{m.id,{}};t.public_key_fingerprint[0]=7;
 CHECK(store.SaveTrust(t).ok());CHECK(restarted.SaveTrust(t).ok());
 auto changed=t;changed.public_key_fingerprint[0]=8;
 auto conflict=store.SaveTrust(changed);CHECK(!conflict.ok());CHECK(conflict.error().code==ErrorCode::IdentityChanged);
 CHECK(restarted.LoadTrust().value().size()==1);CHECK(restarted.LoadTrust().value()[0].public_key_fingerprint==t.public_key_fingerprint);
 CHECK(store.RemoveTrust(t.id).ok());CHECK(store.RemoveTrust(t.id).ok());CHECK(store.LoadTrust().value().empty());
 windows::IdentityStore a(root/"race"),b(root/"race");
 auto one=std::async(std::launch::async,[&]{return a.SaveIdentity(m);});
 auto two=std::async(std::launch::async,[&]{return b.SaveIdentity(m);});
 CHECK(one.get().ok()!=two.get().ok());CHECK(a.LoadIdentity().value()->private_key==m.private_key);
 TrustedDevice first{m.id,{}},second{m.id,{}};first.public_key_fingerprint[0]=55;second.public_key_fingerprint[0]=66;
 auto bindingOne=std::async(std::launch::async,[&]{return a.SaveTrust(first);});auto bindingTwo=std::async(std::launch::async,[&]{return b.SaveTrust(second);});CHECK(bindingOne.get().ok()!=bindingTwo.get().ok());CHECK(a.LoadTrust().value().size()==1);
 // Failed replace must retain the preceding identity/trust record.
 CHECK(store.SaveTrust(t).ok());
 HANDLE blocked=CreateFileW((root/L"identity"/L"state.dpapi").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,0,nullptr);CHECK(blocked!=INVALID_HANDLE_VALUE);
 auto removed=store.RemoveTrust(t.id);CHECK(!removed.ok());CHECK(removed.error().platform_error.has_value());CloseHandle(blocked);
 CHECK(store.LoadTrust().value().size()==1);
 blocked=CreateFileW((root/L"identity"/L"state.dpapi").c_str(),GENERIC_READ,0,nullptr,OPEN_EXISTING,0,nullptr);CHECK(blocked!=INVALID_HANDLE_VALUE);auto deniedRead=store.LoadIdentity();CHECK(!deniedRead.ok());CHECK(deniedRead.error().platform_error==ERROR_SHARING_VIOLATION);CloseHandle(blocked);
 {HANDLE corruptFile=CreateFileW((root/L"identity"/L"state.dpapi").c_str(),GENERIC_WRITE,0,nullptr,OPEN_EXISTING,0,nullptr);CHECK(corruptFile!=INVALID_HANDLE_VALUE);DWORD n=0;CHECK(WriteFile(corruptFile,"not a DPAPI blob",16,&n,nullptr));CHECK(SetEndOfFile(corruptFile));CloseHandle(corruptFile);}
 auto corrupt=store.LoadIdentity();CHECK(!corrupt.ok());CHECK(corrupt.error().platform_error.has_value());CHECK(!store.LoadTrust().ok());CHECK(!store.SaveIdentity(m).ok());
 windows::StreamFactory files;
 CHECK(!files.OpenDestination("../../escape.txt").ok());
 auto stage=root/L"stage.bin";
 HANDLE h=CreateFileW(stage.c_str(),GENERIC_READ|GENERIC_WRITE|DELETE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,CREATE_NEW,FILE_FLAG_OVERLAPPED,nullptr);CHECK(h!=INVALID_HANDLE_VALUE);
 auto token=files.RegisterDestination(h);CloseHandle(h);CHECK(token.ok());
 auto opened=files.OpenDestination(token.value());CHECK(opened.ok());CHECK(!files.OpenDestination(token.value()).ok());
 auto stream=opened.value();std::promise<Result<void>> wrote;auto writeFuture=wrote.get_future();
 stream->Write({0,1,2,255},[&](auto r){wrote.set_value(r);});CHECK(writeFuture.wait_for(std::chrono::seconds(5))==std::future_status::ready);CHECK(writeFuture.get().ok());stream->Close();
 {std::ifstream in(stage,std::ios::binary);std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(in)),{});CHECK(bytes==std::vector<unsigned char>({0,1,2,255}));}
 h=CreateFileW(stage.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_FLAG_OVERLAPPED,nullptr);CHECK(h!=INVALID_HANDLE_VALUE);
 auto sourceToken=files.RegisterSource(h);CloseHandle(h);CHECK(sourceToken.ok());auto source=files.OpenSource(sourceToken.value());CHECK(source.ok());
 std::promise<Result<std::vector<std::uint8_t>>> read;auto readFuture=read.get_future();source.value()->Read(8,[&](auto r){read.set_value(r);});CHECK(readFuture.get().value()==std::vector<std::uint8_t>({0,1,2,255}));
 std::promise<Result<std::vector<std::uint8_t>>> eof;auto eofFuture=eof.get_future();source.value()->Read(8,[&](auto r){eof.set_value(r);});CHECK(eofFuture.get().value().empty());CHECK(Await<std::vector<std::uint8_t>>([&](auto cb){source.value()->Read(5*1024*1024,cb);}).ok());source.value()->Close();
 // A queued write must retain its callback after its public stream is destroyed.
 auto openStage=[&](const wchar_t* name){auto path=root/name;HANDLE fd=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE|DELETE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,CREATE_NEW,0,nullptr);CHECK(fd!=INVALID_HANDLE_VALUE);auto token=files.RegisterDestination(fd);CloseHandle(fd);CHECK(token.ok());return files.OpenDestination(token.value()).value();};
 auto pending=openStage(L"pending.bin");std::promise<void> entered,release;auto enteredFuture=entered.get_future();auto releaseFuture=release.get_future().share();
 pending->Write({},[&](auto r){CHECK(r.ok());entered.set_value();releaseFuture.wait();});CHECK(enteredFuture.wait_for(std::chrono::seconds(5))==std::future_status::ready);
 std::promise<Result<void>> cancelled;auto cancelledFuture=cancelled.get_future();std::atomic<int> completions{0};
 pending->Write(std::vector<std::uint8_t>(1024*1024,42),[&](auto r){++completions;cancelled.set_value(r);});pending.reset();release.set_value();
 CHECK(cancelledFuture.wait_for(std::chrono::seconds(5))==std::future_status::ready);auto cancel=cancelledFuture.get();CHECK(!cancel.ok());CHECK(cancel.error().code==ErrorCode::Cancelled);CHECK(completions==1);CHECK(!fs::exists(root/L"pending.bin"));
 auto empty=openStage(L"empty.bin");empty->Close();CHECK(fs::file_size(root/L"empty.bin")==0);
 auto failed=openStage(L"failed.bin");HANDLE locker=CreateFileW((root/L"failed.bin").c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);CHECK(locker!=INVALID_HANDLE_VALUE);OVERLAPPED locked{};CHECK(LockFileEx(locker,LOCKFILE_EXCLUSIVE_LOCK|LOCKFILE_FAIL_IMMEDIATELY,0,4,0,&locked));
 auto failure=Await<void>([&](auto cb){failed->Write({1,2,3,4},cb);});CHECK(!failure.ok());CHECK(failure.error().code==ErrorCode::StorageFailed);CHECK(failure.error().platform_error==ERROR_LOCK_VIOLATION);CloseHandle(locker);
 // Force an actual delete failure while a callback is still outstanding.
 std::promise<void> blockedCallback,unblock;auto blockedFuture=blockedCallback.get_future();auto unblockFuture=unblock.get_future().share();failed->Write({},[&](auto r){CHECK(r.ok());blockedCallback.set_value();unblockFuture.wait();});CHECK(blockedFuture.wait_for(std::chrono::seconds(5))==std::future_status::ready);
 CHECK(SetFileAttributesW((root/L"failed.bin").c_str(),FILE_ATTRIBUTE_READONLY));std::promise<Result<void>> cleanup;auto cleanupFuture=cleanup.get_future();failed->Write({7},[&](auto r){cleanup.set_value(r);});failed->AbortAndCleanup();unblock.set_value();
 CHECK(cleanupFuture.wait_for(std::chrono::seconds(5))==std::future_status::ready);auto cleanupResult=cleanupFuture.get();CHECK(!cleanupResult.ok());CHECK(cleanupResult.error().code==ErrorCode::StorageFailed);CHECK(cleanupResult.error().platform_error.has_value());CHECK(fs::exists(root/L"failed.bin"));CHECK(SetFileAttributesW((root/L"failed.bin").c_str(),FILE_ATTRIBUTE_NORMAL));
 auto large=openStage(L"large.bin");for(int i=0;i<32;i++)CHECK(Await<void>([&](auto cb){large->Write(std::vector<std::uint8_t>(256*1024,static_cast<std::uint8_t>(i)),cb);}).ok());large->Close();CHECK(fs::file_size(root/L"large.bin")==8*1024*1024);
 auto memory=std::make_shared<MemoryClipboard>();windows::Clipboard clipboard(memory);
 ShareContent text;text.text="hello \xe4\xb8\x96\xe7\x95\x8c";
 CHECK(Await<void>([&](auto cb){clipboard.Write(text,cb);}).ok());
 auto clipboardText=Await<ShareContent>([&](auto cb){clipboard.Read(cb);});CHECK(clipboardText.ok());CHECK(clipboardText.value().text==text.text);
 CHECK(memory->data.size()==1);CHECK(memory->data[0].format==windows::ClipboardFormat::UnicodeText);CHECK(memory->data[0].bytes.size()==18);
 text.text="\xff";CHECK(!Await<void>([&](auto cb){clipboard.Write(text,cb);}).ok());
 memory->denied=true;auto denied=Await<ShareContent>([&](auto cb){clipboard.Read(cb);});CHECK(!denied.ok());CHECK(denied.error().platform_error==ERROR_ACCESS_DENIED);memory->denied=false;
 memory->data={{windows::ClipboardFormat::UnicodeText,{65,0}}};CHECK(!Await<ShareContent>([&](auto cb){clipboard.Read(cb);}).ok());
 ShareContent image;image.type=ContentType::Image;image.mime_type="image/png";
 // A literal 1x1 transparent PNG fixture, decoded by WIC, not by a test codec.
 image.image={137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,196,137,0,0,0,11,73,68,65,84,120,156,99,96,0,2,0,0,5,0,1,165,246,69,64,0,0,0,0,73,69,78,68,174,66,96,130};
 CHECK(Await<void>([&](auto cb){clipboard.Write(image,cb);}).ok());CHECK(memory->data.size()==2);
 CHECK(memory->data[1].format==windows::ClipboardFormat::DibV5);CHECK(memory->data[1].bytes.size()==128);
 auto clipboardImage=Await<ShareContent>([&](auto cb){clipboard.Read(cb);});CHECK(clipboardImage.ok());CHECK(clipboardImage.value().image==image.image);
 memory->data.erase(memory->data.begin());auto dibImage=Await<ShareContent>([&](auto cb){clipboard.Read(cb);});CHECK(dibImage.ok());CHECK(dibImage.value().mime_type=="image/png");
 image.mime_type="image/jpeg";image.image={255,216,255,224,0,16,74,70,73,70,0,1,1,1,0,96,0,96,0,0,255,219,0,67,0,3,2,2,3,2,2,3,3,3,3,4,3,3,4,5,8,5,5,4,4,5,10,7,7,6,8,12,10,12,12,11,10,11,11,13,14,18,16,13,14,17,14,11,11,16,22,16,17,19,20,21,21,21,12,15,23,24,22,20,24,18,20,21,20,255,219,0,67,1,3,4,4,5,4,5,9,5,5,9,20,13,11,13,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,20,255,192,0,17,8,0,1,0,1,3,1,34,0,2,17,1,3,17,1,255,196,0,31,0,0,1,5,1,1,1,1,1,1,0,0,0,0,0,0,0,0,1,2,3,4,5,6,7,8,9,10,11,255,196,0,181,16,0,2,1,3,3,2,4,3,5,5,4,4,0,0,1,125,1,2,3,0,4,17,5,18,33,49,65,6,19,81,97,7,34,113,20,50,129,145,161,8,35,66,177,193,21,82,209,240,36,51,98,114,130,9,10,22,23,24,25,26,37,38,39,40,41,42,52,53,54,55,56,57,58,67,68,69,70,71,72,73,74,83,84,85,86,87,88,89,90,99,100,101,102,103,104,105,106,115,116,117,118,119,120,121,122,131,132,133,134,135,136,137,138,146,147,148,149,150,151,152,153,154,162,163,164,165,166,167,168,169,170,178,179,180,181,182,183,184,185,186,194,195,196,197,198,199,200,201,202,210,211,212,213,214,215,216,217,218,225,226,227,228,229,230,231,232,233,234,241,242,243,244,245,246,247,248,249,250,255,196,0,31,1,0,3,1,1,1,1,1,1,1,1,1,0,0,0,0,0,0,1,2,3,4,5,6,7,8,9,10,11,255,196,0,181,17,0,2,1,2,4,4,3,4,7,5,4,4,0,1,2,119,0,1,2,3,17,4,5,33,49,6,18,65,81,7,97,113,19,34,50,129,8,20,66,145,161,177,193,9,35,51,82,240,21,98,114,209,10,22,36,52,225,37,241,23,24,25,26,38,39,40,41,42,53,54,55,56,57,58,67,68,69,70,71,72,73,74,83,84,85,86,87,88,89,90,99,100,101,102,103,104,105,106,115,116,117,118,119,120,121,122,130,131,132,133,134,135,136,137,138,146,147,148,149,150,151,152,153,154,162,163,164,165,166,167,168,169,170,178,179,180,181,182,183,184,185,186,194,195,196,197,198,199,200,201,202,210,211,212,213,214,215,216,217,218,226,227,228,229,230,231,232,233,234,242,243,244,245,246,247,248,249,250,255,218,0,12,3,1,0,2,17,3,17,0,63,0,249,210,138,40,175,195,15,245,76,255,217};
 CHECK(Await<void>([&](auto cb){clipboard.Write(image,cb);}).ok());CHECK(memory->data[0].format==windows::ClipboardFormat::Jpeg);CHECK(memory->data[1].bytes.size()==128);
 auto jpeg=Await<ShareContent>([&](auto cb){clipboard.Read(cb);});CHECK(jpeg.ok());CHECK(jpeg.value().mime_type=="image/jpeg");CHECK(jpeg.value().image==image.image);
 image.mime_type="image/png";CHECK(!Await<void>([&](auto cb){clipboard.Write(image,cb);}).ok());
 image.image={1,2,3};CHECK(!Await<void>([&](auto cb){clipboard.Write(image,cb);}).ok());
 // Test artifacts deliberately stay under this build directory for diagnostics.
 std::cout<<"Windows storage/stream/clipboard tests passed\n";
 return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
