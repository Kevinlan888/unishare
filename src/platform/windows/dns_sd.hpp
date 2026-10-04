#pragma once
#include <winsock2.h>
#include <windns.h>
#include "unishare/platform.hpp"
#include <mutex>
namespace unishare::windows {
// Narrow boundary preserves the Win32 allocation and asynchronous callback contract.
class DnsApi {
public:
 virtual ~DnsApi()=default;
 virtual Result<std::vector<ULONG>> Interfaces();
 virtual Result<std::wstring> HostName();
 virtual DWORD Register(DNS_SERVICE_REGISTER_REQUEST*,DNS_SERVICE_CANCEL*);
 virtual DWORD Deregister(DNS_SERVICE_REGISTER_REQUEST*);
 virtual DWORD Browse(DNS_SERVICE_BROWSE_REQUEST*,DNS_SERVICE_CANCEL*);
 virtual DWORD Resolve(DNS_SERVICE_RESOLVE_REQUEST*,DNS_SERVICE_CANCEL*);
 virtual DWORD CancelRegister(DNS_SERVICE_CANCEL*);
 virtual DWORD CancelBrowse(DNS_SERVICE_CANCEL*);
 virtual DWORD CancelResolve(DNS_SERVICE_CANCEL*);
 virtual void FreeInstance(DNS_SERVICE_INSTANCE*);
 virtual void FreeRecords(DNS_RECORD*);
};
class DnsSd final : public IDnsSd {
 struct State;std::mutex mutex_;std::shared_ptr<State> state_;std::shared_ptr<DnsApi> api_;
 std::shared_ptr<State> Current();
public:
 explicit DnsSd(std::shared_ptr<DnsApi> api=std::make_shared<DnsApi>());
 ~DnsSd();
 OperationId Publish(ServiceRecord,ResultCallback<void>) override;
 OperationId Browse(std::string,std::function<void(Result<ServiceRecord>)>) override;
 OperationId BrowseEvents(std::string,std::function<void(Result<ServiceRecord>)>,std::function<void(ServiceInstanceId)>) override;
 void Stop() override;
};
}
