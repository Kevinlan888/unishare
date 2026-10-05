# iOS sharing SDK

Public Swift services ship as `UniShareSDK.xcframework`. See [iOS integration](../docs/integration/ios.md) for the API, packaging, host permissions and callback/ownership rules. Build the framework with `bash ios/build-xcframework.sh` on macOS.

The remaining notes describe the internal Objective-C++ platform ports and their standalone regression harness.

Build on macOS with Xcode selected, an arm64 static OpenSSL installation, and an iOS Simulator runtime:

```sh
bash ios/build-ports.sh iphonesimulator /tmp/openssl-ios-sim
xcrun simctl install booted build-ios-iphonesimulator/PortsTest.app
xcrun simctl launch --console-pty booted com.unishare.ports
bash ios/build-ports.sh iphoneos /tmp/openssl-ios-device
bash ios/test-core.sh /tmp/openssl-ios-sim booted
```

The harness is an actual UIKit application so Keychain, UIPasteboard, application lifecycle and Network framework run in an iOS application context. Build outputs use ignored `build-ios-*` directories. Both slices target iOS 15.0. Tests use a unique Keychain service and private pasteboard.

The simulator harness embeds `Tests/Simulator.entitlements` in its `__TEXT,__entitlements` section, then uses ordinary ad-hoc signing. Its synthetic simulator application identifier is only a test fixture. Do not apply those entitlements to a physical device or consumer app; the host's Xcode signing configuration supplies its real application identifier and Keychain access group. A missing simulator entitlement returns the real Keychain error `-34018`, and is never treated as an absent identity. The device gate compiles and links an unsigned arm64 application to validate the library/OpenSSL/framework dependencies; it does not install or run on a phone.

## Integration contract

The host must include `NSLocalNetworkUsageDescription` and `NSBonjourServices` containing `_share._tcp`. Apple's local-network authorization is evaluated by system Bonjour operations. Configuration validation is not an authorization-status query. System policy denial is returned as `PermissionDenied`, preserving the DNSService error. The app must be foreground and explicitly call Start again after interruption.

The API mix approved for this split is DNSService system Bonjour for registration of the separately bound TCP port and interface-scoped browse/resolve/TXT/address events; Network framework for TCP and path monitoring. No custom multicast sockets are used.

Register actual host-selected file URLs through the internal `UrlStreams` object. Registration yields a single-use opaque token. The host must exclusively own an empty regular staging file and its pathname until completion or abort. Security-scoped access is held through the stream lifetime and balanced on release. Display filenames never become paths. Successful destination writes include a fallible durable flush; Close seals the already flushed bytes. Abort drains I/O and removes staging. The existing void abort contract cannot report a deletion failure after every callback has completed; hosts must reconcile leftovers. Do not supply a final user document as staging.

UIPasteboard access occurs only through explicit Read/Write calls and may trigger system prompts. No clipboard observer or synchronization is installed.

Internal native callbacks use serial queues, and asynchronous operations retain their state until completion/cancellation. Socket cancellation retires the connection and completes each pending read/write once. C ABI registration retains the platform, permission agent and callback executor independently of the original platform token. File registration and synchronous Close/Abort may wait for filesystem I/O; wrappers should perform them off the main thread. UIKit work is dispatched to the main queue, which the host must keep running.

Keychain records are scoped by a host-selected service string, protected with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, and excluded from synchronization. Identity is create-once; trust uses one atomic Keychain item per device binding. A corrupt/unreadable item is an error, never a missing identity.

Simulator coverage does not establish physical-device local-network prompt/denial behavior, external document-provider grants, locked-device Keychain behavior, physical power-loss durability, multiple active network interfaces, or cross-platform LAN interoperability. Those remain integration checks.

## Platform references

- [Apple DNS-SD resolution and TXT monitoring](https://developer.apple.com/library/archive/documentation/Networking/Conceptual/dns_discovery_api/Articles/resolving.html)
- [Apple local-network privacy](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)
- [Apple Keychain insertion](https://developer.apple.com/documentation/security/secitemadd(_:_:))
- [Apple security-scoped URLs](https://developer.apple.com/documentation/foundation/nsurl/startaccessingsecurityscopedresource())
