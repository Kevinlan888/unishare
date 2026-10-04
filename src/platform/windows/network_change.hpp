#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include "unishare/platform.hpp"
#include <mutex>
namespace unishare::windows {
class NetworkChangeApi {
public:
 virtual ~NetworkChangeApi()=default;
 virtual DWORD Register(PIPINTERFACE_CHANGE_CALLBACK,void*,HANDLE*);
 virtual DWORD Cancel(HANDLE);
};
// Start replaces the previous watch. Ready reports native registration failure.
// Stop is nonblocking; native cancellation drains on a dedicated worker.
class NetworkChange {
 struct State;std::mutex mutex_;std::shared_ptr<State> state_;std::shared_ptr<NetworkChangeApi> api_;
public:
 explicit NetworkChange(std::shared_ptr<NetworkChangeApi> api=std::make_shared<NetworkChangeApi>());
 ~NetworkChange();
 void Start(std::function<void()> handler,ResultCallback<void> ready);
 void Stop();
};
}
