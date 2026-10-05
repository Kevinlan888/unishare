#pragma once
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#include "unishare/c_api_bridge.hpp"

namespace unishare::ios {
// Internal Objective-C++ interfaces, compiled with the core's toolchain.
std::shared_ptr<IIdentityStore> MakeIdentityStore(NSString* service);
std::shared_ptr<ISocketFactory> MakeSockets();
std::shared_ptr<IDnsSd> MakeBonjour();
std::shared_ptr<IPlatformClipboard> MakeClipboard(UIPasteboard* board);
class UrlStreams : public IStreamFactory {
public:
 virtual Result<std::string> Register(NSURL* url, bool destination) = 0;
};
std::shared_ptr<UrlStreams> MakeStreams();
class Platform : public IPlatform {
public:
 virtual std::shared_ptr<IPermissionAgent> Permissions() = 0;
 virtual UrlStreams& FileUrls() = 0;
 // Notification entry point also used by the deterministic lifecycle test.
 virtual void Foreground(bool active) = 0;
};
std::shared_ptr<Platform> MakePlatform(NSString* keychainService, NSDictionary* info,
                                     UIPasteboard* board = nil);
usdk_error Register(const std::shared_ptr<Platform>&, usdk_platform_handle*) noexcept;
}
