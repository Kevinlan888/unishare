#pragma once
#include "types.hpp"
namespace unishare {
class IExecutor { public: virtual ~IExecutor()=default; virtual void Post(std::function<void()> task)=0; };
class IPermissionAgent { public: virtual ~IPermissionAgent()=default; virtual void RequestLocalNetwork(ResultCallback<void>)=0; };
class IStream { public: virtual ~IStream()=default; virtual OperationId Read(std::size_t max_bytes,ResultCallback<std::vector<std::uint8_t>>)=0; virtual OperationId Write(std::vector<std::uint8_t>,ResultCallback<void>)=0; virtual void Close()=0; virtual void AbortAndCleanup()=0; };
class ISocket : public IStream { public: virtual void Cancel(OperationId)=0; };
struct ServiceRecord { std::string instance,service_type; std::vector<std::pair<std::string,std::string>> txt; std::vector<std::string> addresses; std::uint16_t port=0; };
class IDnsSd { public: virtual ~IDnsSd()=default; virtual OperationId Publish(ServiceRecord,ResultCallback<void>)=0; virtual OperationId Browse(std::string service_type,std::function<void(Result<ServiceRecord>)>)=0; virtual void Stop()=0; };
class ISocketFactory { public: virtual ~ISocketFactory()=default; virtual OperationId Connect(std::string address,std::uint16_t port,ResultCallback<std::shared_ptr<ISocket>>)=0; virtual OperationId Listen(std::uint16_t port,std::function<void(Result<std::shared_ptr<ISocket>>)>)=0; virtual void Stop()=0; };
struct IdentityMaterial { DeviceId id; std::vector<std::uint8_t> private_key,certificate; };
struct TrustedDevice { DeviceId id; std::array<std::uint8_t,32> public_key_fingerprint; };
class IIdentityStore { public: virtual ~IIdentityStore()=default; virtual Result<std::optional<IdentityMaterial>> LoadIdentity()=0; virtual Result<void> SaveIdentity(const IdentityMaterial&)=0; virtual Result<std::vector<TrustedDevice>> LoadTrust()=0; virtual Result<void> SaveTrust(const TrustedDevice&)=0; virtual Result<void> RemoveTrust(DeviceId)=0; };
class IStreamFactory { public: virtual ~IStreamFactory()=default; virtual Result<std::shared_ptr<IStream>> OpenSource(std::string opaque_handle)=0; virtual Result<std::shared_ptr<IStream>> OpenDestination(std::string opaque_handle)=0; };
class IPlatformClipboard { public: virtual ~IPlatformClipboard()=default; virtual OperationId Read(ResultCallback<ShareContent>)=0; virtual OperationId Write(ShareContent,ResultCallback<void>)=0; };
class IPlatform { public: virtual ~IPlatform()=default; virtual IDnsSd& DnsSd()=0; virtual ISocketFactory& Sockets()=0; virtual IIdentityStore& IdentityStore()=0; virtual IStreamFactory& Streams()=0; virtual IPlatformClipboard& Clipboard()=0; virtual std::shared_ptr<IExecutor> DefaultExecutor()=0; };
}
