#pragma once
// Deterministic native DNS boundary. Actual Windows DNS adapter, TCP, TLS,
// identity, streams and SDK services remain in use; this is not live mDNS.
#include "platform/windows/dns_sd.hpp"
#include <map>
namespace unishare::test {
class DnsBus final : public windows::DnsApi {
 std::mutex mutex_;
 std::map<std::wstring,DNS_SERVICE_REGISTER_REQUEST*> registrations_;
 std::map<DNS_SERVICE_CANCEL*,DNS_SERVICE_BROWSE_REQUEST> browsers_;
 static void Ptr(const DNS_SERVICE_BROWSE_REQUEST& b,const std::wstring& name,DWORD ttl){
  DNS_RECORD record{};record.wType=DNS_TYPE_PTR;record.dwTtl=ttl;record.Flags.S.CharSet=DnsCharSetUnicode;
  record.Data.PTR.pNameHost=const_cast<wchar_t*>(name.c_str());b.pBrowseCallback(0,b.pQueryContext,&record);
 }
public:
 Result<std::wstring> HostName() override{return Result<std::wstring>::Success(L"assembly.local.");}
 Result<std::vector<ULONG>> Interfaces() override{return Result<std::vector<ULONG>>::Success({1});}
 void FreeRecords(DNS_RECORD*) override{}
 DWORD Register(DNS_SERVICE_REGISTER_REQUEST* r,DNS_SERVICE_CANCEL*) override{
  std::lock_guard<std::mutex> lock(mutex_);std::wstring name=r->pServiceInstance->pszInstanceName;registrations_[name]=r;
  r->pRegisterCompletionCallback(0,r->pQueryContext,nullptr);
  for(auto& b:browsers_)Ptr(b.second,name,120);return DNS_REQUEST_PENDING;
 }
 DWORD Deregister(DNS_SERVICE_REGISTER_REQUEST* r) override{
  std::lock_guard<std::mutex> lock(mutex_);std::wstring name=r->pServiceInstance->pszInstanceName;
  registrations_.erase(name);for(auto& b:browsers_)Ptr(b.second,name,0);return 0;
 }
 DWORD Browse(DNS_SERVICE_BROWSE_REQUEST* r,DNS_SERVICE_CANCEL* c) override{
  std::lock_guard<std::mutex> lock(mutex_);browsers_[c]=*r;for(auto& entry:registrations_)Ptr(*r,entry.first,120);return DNS_REQUEST_PENDING;
 }
 DWORD CancelBrowse(DNS_SERVICE_CANCEL* c) override{
  std::lock_guard<std::mutex> lock(mutex_);auto i=browsers_.find(c);if(i!=browsers_.end()){
   auto b=i->second;browsers_.erase(i);b.pBrowseCallback(ERROR_CANCELLED,b.pQueryContext,nullptr);
  }return 0;
 }
 DWORD Resolve(DNS_SERVICE_RESOLVE_REQUEST* r,DNS_SERVICE_CANCEL*) override{
  std::lock_guard<std::mutex> lock(mutex_);auto i=registrations_.find(r->QueryName);if(i==registrations_.end())return DNS_ERROR_NAME_DOES_NOT_EXIST;
  auto p=i->second->pServiceInstance;IP4_ADDRESS address=htonl(0x7f000001);
  auto copy=DnsServiceConstructInstance(p->pszInstanceName,p->pszHostName,&address,nullptr,p->wPort,0,0,p->dwPropertyCount,const_cast<const wchar_t**>(p->keys),const_cast<const wchar_t**>(p->values));
  if(!copy)return ERROR_NOT_ENOUGH_MEMORY;copy->dwInterfaceIndex=1;r->pResolveCompletionCallback(0,r->pQueryContext,copy);return DNS_REQUEST_PENDING;
 }
};
}
