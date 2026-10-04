#include "storage.hpp"
#include "win32_helpers.hpp"
#include <wincrypt.h>
#include <objbase.h>
#include <sddl.h>
#include <aclapi.h>
#include <algorithm>
#include <cstring>
namespace unishare::windows { namespace {
using Bytes=std::vector<std::uint8_t>;
constexpr DWORD MaxRecord=16*1024*1024;
struct LocalMemory {void* p=nullptr;~LocalMemory(){if(p)LocalFree(p);}};
struct Security {
 LocalMemory descriptor;
 SECURITY_ATTRIBUTES attributes{sizeof(SECURITY_ATTRIBUTES),nullptr,FALSE};
 Security(){
  HANDLE raw=nullptr;if(!OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&raw))Fail("open user token");Handle token(raw);
  DWORD n=0;GetTokenInformation(raw,TokenUser,nullptr,0,&n);Bytes data(n);
  if(!GetTokenInformation(raw,TokenUser,data.data(),n,&n))Fail("read user SID");
  LPWSTR sid=nullptr;if(!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(data.data())->User.Sid,&sid))Fail("format SID");LocalMemory sidOwner;sidOwner.p=sid;
  auto sddl=L"D:P(A;OICI;FA;;;"+std::wstring(sid)+L")";
  if(!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(),SDDL_REVISION_1,&descriptor.p,nullptr))Fail("create private ACL");
  attributes.lpSecurityDescriptor=descriptor.p;
 }
};
struct LockedRoot {
 Security security;Handle directory;Handle lock;
 explicit LockedRoot(const std::filesystem::path& root):directory(),lock(){
  if(!root.is_absolute())Fail("absolute identity root required",ERROR_INVALID_PARAMETER);
  if(!CreateDirectoryW(root.c_str(),&security.attributes)&&GetLastError()!=ERROR_ALREADY_EXISTS)Fail("create identity directory");
  directory.value=CreateFileW(root.c_str(),READ_CONTROL|WRITE_DAC,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
  if(directory.value==INVALID_HANDLE_VALUE)Fail("open identity directory");
  FILE_ATTRIBUTE_TAG_INFO tag{};if(!GetFileInformationByHandleEx(directory.value,FileAttributeTagInfo,&tag,sizeof tag))Fail("inspect identity directory");
  if(!(tag.FileAttributes&FILE_ATTRIBUTE_DIRECTORY)||(tag.FileAttributes&FILE_ATTRIBUTE_REPARSE_POINT))Fail("unsafe identity root",ERROR_INVALID_PARAMETER);
  PACL acl=nullptr;BOOL present=FALSE,defaulted=FALSE;
  if(!GetSecurityDescriptorDacl(security.descriptor.p,&present,&acl,&defaulted))Fail("read private ACL");
  auto code=SetSecurityInfo(directory.value,SE_FILE_OBJECT,DACL_SECURITY_INFORMATION|PROTECTED_DACL_SECURITY_INFORMATION,nullptr,nullptr,acl,nullptr);
  if(code!=ERROR_SUCCESS)Fail("restrict identity root",code);
  lock.value=CreateFileW((root/L"store.lock").c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,&security.attributes,OPEN_ALWAYS,FILE_ATTRIBUTE_HIDDEN|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
  if(lock.value==INVALID_HANDLE_VALUE)Fail("open identity lock");RequireRegular(lock.value);
  OVERLAPPED ov{};if(!LockFileEx(lock.value,LOCKFILE_EXCLUSIVE_LOCK,0,1,0,&ov))Fail("lock identity store");
 }
 // Closing the lock handle releases the byte lock, including after a crash.
};
void Append32(Bytes& b,std::size_t n){if(n>MaxRecord)Fail("record too large",ERROR_INVALID_DATA);for(int i=0;i<4;i++)b.push_back(static_cast<std::uint8_t>(n>>(8*i)));}
struct Reader {const Bytes& b;std::size_t pos=0;void take(void* p,std::size_t n){if(n>b.size()-pos)Fail("invalid identity record",ERROR_INVALID_DATA);memcpy(p,b.data()+pos,n);pos+=n;}std::uint32_t number(){std::uint8_t p[4];take(p,4);return p[0]|(std::uint32_t(p[1])<<8)|(std::uint32_t(p[2])<<16)|(std::uint32_t(p[3])<<24);}Bytes blob(){auto n=number();if(n>b.size()-pos)Fail("invalid record length",ERROR_INVALID_DATA);Bytes v(n);take(v.data(),n);return v;}};
struct State {std::optional<IdentityMaterial> identity;std::vector<TrustedDevice> trust;};
Bytes Protect(Bytes input,bool decrypt){
 DATA_BLOB in{static_cast<DWORD>(input.size()),input.data()},out{};
 BOOL ok=decrypt?CryptUnprotectData(&in,nullptr,nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&out):CryptProtectData(&in,L"UniShare identity",nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&out);
 auto code=GetLastError();SecureZeroMemory(input.data(),input.size());
 if(!ok)Fail(decrypt?"decrypt identity record":"protect identity record",code);
 LocalMemory owner;owner.p=out.pbData;Bytes result(out.pbData,out.pbData+out.cbData);SecureZeroMemory(out.pbData,out.cbData);return result;
}
State Load(const std::filesystem::path& root){
 Handle file(CreateFileW((root/L"state.dpapi").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
 if(file.value==INVALID_HANDLE_VALUE){auto code=GetLastError();if(code==ERROR_FILE_NOT_FOUND)return {};Fail("open identity record",code);}RequireRegular(file.value);
 LARGE_INTEGER size;if(!GetFileSizeEx(file.value,&size))Fail("size identity record");if(size.QuadPart<=0||size.QuadPart>MaxRecord)Fail("invalid identity record size",ERROR_INVALID_DATA);
 Bytes b(static_cast<std::size_t>(size.QuadPart));DWORD n=0;if(!ReadFile(file.value,b.data(),static_cast<DWORD>(b.size()),&n,nullptr))Fail("read identity record");if(n!=b.size())Fail("short identity record",ERROR_HANDLE_EOF);
 b=Protect(std::move(b),true);Reader r{b};if(r.number()!=0x31534955)Fail("identity version",ERROR_INVALID_DATA);State s;auto present=r.number();if(present>1)Fail("identity marker",ERROR_INVALID_DATA);
 if(present){IdentityMaterial m;r.take(m.id.bytes.data(),16);m.private_key=r.blob();m.certificate=r.blob();s.identity=std::move(m);}
 auto count=r.number();if(count>(b.size()-r.pos)/48)Fail("trust record length",ERROR_INVALID_DATA);
 for(std::uint32_t i=0;i<count;i++){TrustedDevice t;r.take(t.id.bytes.data(),16);r.take(t.public_key_fingerprint.data(),32);s.trust.push_back(t);}if(r.pos!=b.size())Fail("trailing identity data",ERROR_INVALID_DATA);SecureZeroMemory(b.data(),b.size());return s;
}
void Save(const std::filesystem::path& root,const State& s,Security& security){
 Bytes b={0x55,0x49,0x53,0x31}; // version magic, then bounded record fields
 Append32(b,s.identity?1:0);
 if(s.identity){
  const auto& m=*s.identity;
  b.insert(b.end(),m.id.bytes.begin(),m.id.bytes.end());
  Append32(b,m.private_key.size());
  b.insert(b.end(),m.private_key.begin(),m.private_key.end());
  Append32(b,m.certificate.size());
  b.insert(b.end(),m.certificate.begin(),m.certificate.end());
 }
 Append32(b,s.trust.size());
 for(const auto& t:s.trust){
  b.insert(b.end(),t.id.bytes.begin(),t.id.bytes.end());
  b.insert(b.end(),t.public_key_fingerprint.begin(),t.public_key_fingerprint.end());
 }
 if(b.size()>MaxRecord-1024)Fail("identity record too large",ERROR_INVALID_DATA);
 b=Protect(std::move(b),false);
 GUID id;
 if(FAILED(CoCreateGuid(&id)))Fail("create temporary record name",ERROR_GEN_FAILURE);
 wchar_t name[40];StringFromGUID2(id,name,40);
 auto temp=root/(std::wstring(name)+L".tmp");
 Handle file(CreateFileW(temp.c_str(),GENERIC_WRITE,0,&security.attributes,
                         CREATE_NEW,FILE_ATTRIBUTE_HIDDEN|FILE_FLAG_WRITE_THROUGH,nullptr));
 if(file.value==INVALID_HANDLE_VALUE)Fail("create identity staging file");
 try {
  DWORD n=0;
  if(!WriteFile(file.value,b.data(),static_cast<DWORD>(b.size()),&n,nullptr))
   Fail("write identity record");
  if(n!=b.size())Fail("short identity write",ERROR_WRITE_FAULT);
  if(!FlushFileBuffers(file.value))Fail("flush identity record");
  CloseHandle(file.release());
  if(!MoveFileExW(temp.c_str(),(root/L"state.dpapi").c_str(),
                   MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))
   Fail("commit identity record");
 }catch(...){
  CloseHandle(file.release());
  DeleteFileW(temp.c_str());
  throw;
 }
}
template<class T,class F> Result<T> Guard(F f){try{return f();}catch(const WinError& e){return Result<T>::Failure(e.error);}}
}
Result<std::optional<IdentityMaterial>> IdentityStore::LoadIdentity(){return Guard<std::optional<IdentityMaterial>>([&]{LockedRoot lock(root_);return Result<std::optional<IdentityMaterial>>::Success(Load(root_).identity);});}
Result<std::vector<TrustedDevice>> IdentityStore::LoadTrust(){return Guard<std::vector<TrustedDevice>>([&]{LockedRoot lock(root_);return Result<std::vector<TrustedDevice>>::Success(Load(root_).trust);});}
Result<void> IdentityStore::SaveIdentity(const IdentityMaterial& m){return Guard<void>([&]{LockedRoot lock(root_);auto s=Load(root_);if(s.identity)Fail("identity already exists",ERROR_ALREADY_EXISTS);if(m.private_key.empty()||m.certificate.empty())return Result<void>::Failure({ErrorCode::InvalidInput,"empty identity material",{}});s.identity=m;Save(root_,s,lock.security);return Result<void>::Success();});}
Result<void> IdentityStore::SaveTrust(const TrustedDevice& t){return Guard<void>([&]{LockedRoot lock(root_);auto s=Load(root_);for(auto& prior:s.trust)if(prior.id==t.id){if(prior.public_key_fingerprint==t.public_key_fingerprint)return Result<void>::Success();return Result<void>::Failure({ErrorCode::IdentityChanged,"changed trust binding",{}});}s.trust.push_back(t);Save(root_,s,lock.security);return Result<void>::Success();});}
Result<void> IdentityStore::RemoveTrust(DeviceId id){return Guard<void>([&]{LockedRoot lock(root_);auto s=Load(root_);auto it=std::find_if(s.trust.begin(),s.trust.end(),[&](auto& t){return t.id==id;});if(it==s.trust.end())return Result<void>::Success();s.trust.erase(it);Save(root_,s,lock.security);return Result<void>::Success();});}
}
