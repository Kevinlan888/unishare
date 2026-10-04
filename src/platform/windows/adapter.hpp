#pragma once
#include "dns_sd.hpp"
#include "network_change.hpp"
#include "files.hpp"
#include "clipboard.hpp"
#include "storage.hpp"
#include "sockets.hpp"
#include "unishare/windows.hpp"
namespace unishare::windows {
struct NativeBoundaries {
 std::shared_ptr<DnsApi> dns=std::make_shared<DnsApi>();
 std::shared_ptr<NetworkChangeApi> network=std::make_shared<NetworkChangeApi>();
 std::shared_ptr<ClipboardBoundary> clipboard=CreateWin32ClipboardBoundary();
};
class PermissionGate;
class WindowsPlatform final : public IPlatform {
 std::mutex watch_mutex_;
 std::shared_ptr<PermissionGate> permission_;
 windows::DnsSd dns_;
 windows::SocketFactory sockets_;
 windows::IdentityStore identity_;
 windows::StreamFactory streams_;
 windows::Clipboard clipboard_;
 std::shared_ptr<IExecutor> executor_;
 windows::NetworkChange network_;
public:
 explicit WindowsPlatform(WindowsOptions,NativeBoundaries = {});
 ~WindowsPlatform();
 IDnsSd& DnsSd() override{return dns_;}
 ISocketFactory& Sockets() override{return sockets_;}
 IIdentityStore& IdentityStore() override{return identity_;}
 IStreamFactory& Streams() override{return streams_;}
 IPlatformClipboard& Clipboard() override{return clipboard_;}
 std::shared_ptr<IExecutor> DefaultExecutor() override{return executor_;}
 Platform PlatformKind() const override{return Platform::Windows;}
 std::shared_ptr<IPermissionAgent> Permissions(std::shared_ptr<IPermissionAgent> host = {});
 void SetNetworkChangeHandler(std::function<void()>) override;
};
std::unique_ptr<IShareSdk> CreateRetainedSdk(SdkConfig,std::shared_ptr<WindowsPlatform>);
}
