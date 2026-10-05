# iOS integration

`UniShareSDK.xcframework` provides `import UniShareSDK` and the Swift `ShareSdk`, `DeviceDiscovery`, `ConnectionManager`, `TransferService`, and `ClipboardService` APIs. Minimum deployment target: iOS 15. Device and Simulator slices both support arm64. The framework includes the Objective-C++ adapter, C++ core, and static OpenSSL; embed and sign the framework in the host application. It has no application UI or background discovery service.

## Build and integrate

On macOS with Xcode selected, prepare OpenSSL static libraries (`lib/libssl.a`, `lib/libcrypto.a`, and `include/`) separately for arm64 iOS and arm64 iOS Simulator. Then run from the repository:

```sh
OPENSSL_IOS_DEVICE=/tmp/openssl-ios-device \
OPENSSL_IOS_SIM=/tmp/openssl-ios-sim \
OUTPUT_DIR=build-ios-release bash ios/build-xcframework.sh
```

This reproducible compiler project uses the selected SDK, Swift library evolution, and `xcodebuild -create-xcframework`. Use a fresh output directory for each completed archive. The artifact is `build-ios-release/UniShareSDK.xcframework`. Add it to the app target's Frameworks, Libraries, and Embedded Content with **Embed & Sign**. The script's ad-hoc signature is for build validation; device applications use the host's own signing identity and entitlements. Intel Simulator is not included.

Declare the host's reason for local networking and service type in `Info.plist`:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Find nearby devices you choose to share with.</string>
<key>NSBonjourServices</key>
<array><string>_share._tcp</string></array>
```

The host supplies any photo-library or document-picker UI and relevant usage descriptions. The SDK never requests photo-library access by itself. Local-network authorization is exercised by real Bonjour activity; a successful initialization is not a promise that the user has granted it. Missing declarations report `permissionDenied`, background start reports `networkLost`, and native failures preserve `platformError` when supplied by the platform.

## Lifecycle and callbacks

Construction is lightweight and may occur on any thread. `initialize` internally schedules UIKit setup on main, so keep the main run loop running and never block it waiting for completion. Wait for successful initialization before calling services. Use one stable, app-specific Keychain service name per installation identity; reusing it preserves the core DeviceId and trust records. Keychain failures never silently create a replacement identity.

```swift
import UniShareSDK

let sdk = ShareSdk(keychainService: "com.example.myapp.share",
                   callbackQueue: .main)
let events = sdk.observe { event in
    switch event {
    case .pairingRequest(_, let requestId, let code):
        // Present the six-digit code, preserving leading zeros.
        // Save requestId for your UI. Only after explicit user approval,
        // call sdk.connections.confirmPairing(requestId, codeMatches: true, ...).
        // A decline uses rejectPairing(requestId, ...).
    default: break
    }
}
sdk.initialize(SdkConfig(deviceName: "My iPhone")) { result in
    guard result.isSuccess else { return }
    sdk.start { result in /* Ready to discover and share after success. */ }
}
```

Keep observation tokens alive; `EventObservation.close()` or token deinitialization unregisters the listener. Discovery exposes snapshots and Online/Updated/Offline/error/state events. Devices preserve UUID, protocol version, addresses including IPv6 scopes, and discovery timestamp. Connections expose Connect, Confirm/RejectPairing, trusted-device snapshots, and Forget. Pairing and incoming transfer approval are explicit host decisions.

Every asynchronous submission returns an instance-scoped `UInt64` operation ID. Its completion carries the same ID, optional `ShareError`, transfer ID, and clipboard content. Accepted submissions and validation failures complete once. All host callbacks run asynchronously in order on an SDK serial queue targeted at `callbackQueue`; the default is an SDK serial queue. Even synchronous native rejection never invokes host code on the submitting stack. Do not block that queue waiting for another completion.

`stop` cancels unfinished native work and delivers its terminal results. UIKit background notifications interrupt native operations and leave the SDK stopped. After the app becomes active, explicitly call `start` again. The host owns its application lifecycle; there is no guarantee of work while suspended.

`close` retires pending completions and observations, discards queued callbacks, and waits for a host callback already running on another thread. Calling it inside a callback is safe. No new host callback begins after it returns. Native cancellation/file cleanup proceeds on a worker and retains its resources through late callbacks. Close intentionally suppresses undelivered results; call and await `stop` first if the host needs cancellation results. After close, only `state` and repeated `close` are supported; new async submissions are programmer errors.

## Explicit content and file ownership

```swift
sdk.transfers.sendText("Hello 👋", to: deviceId) { result in /* terminal send */ }
sdk.transfers.sendImage(imageURL, to: deviceId, mimeType: "image/png",
                        byteCount: imageBytes) { result in /* terminal send */ }
sdk.transfers.sendFile(fileURL, to: deviceId, displayName: "report.pdf",
                       byteCount: fileBytes) { result in /* terminal send */ }
```

Images accept an optional displayName (default "image"); files accept an optional mimeType (default "application/octet-stream"). Sources must be explicit regular file URLs and sizes must describe the actual content. The adapter streams bounded chunks and retains security-scoped access for the stream lifetime. For persisted document-provider access, resolve the host's security-scoped bookmark with Foundation, handle stale bookmarks in the host, and pass the resulting file URL. Do not revoke grants or modify source content during a transfer. SDK source close preserves the file.

For `.incomingOffer`, create an empty regular staging file in a location the host owns exclusively and call `accept(requestId, stagingURL:)`, or call `reject`. Never construct the staging pathname from the peer's display name. Accept completion acknowledges the decision; `.transferResult` reports the eventual incoming transfer terminal outcome. After successful transfer result the host decides the final filename/location. Failed Accept, cancellation, and failed transfer discard partial staging content. The inherited void cleanup interface cannot report deletion failure; the host should reconcile leftovers in its exclusively owned staging directory. `cancel(transferId:)` interrupts active transfers. Progress events include bytes transferred and total.

Clipboard access is explicit, generally from a user action:

```swift
sdk.clipboard.readShareableContent { result in /* .text or .image */ }
sdk.clipboard.writeContent(.text("Chosen text")) { result in /* done */ }
```

Only UTF-8 text and PNG/JPEG images are supported. Unsupported clipboard content returns `invalidInput`. System pasteboard prompts remain under iOS control. No clipboard observer or automatic synchronization is installed.

Default configured limits are text 8 MiB, image 100 MiB, file 20 GiB, subject to core resource limits and stricter host configuration. **Current C ABI v1 direct text submission copies at most 4 MiB**, so `sendText` over 4 MiB returns `resourceLimit` even with the default 8 MiB configured text limit. C ABI v1 clipboard writes also impose a 4 MiB aggregate limit on text, image bytes and MIME metadata. These inherited cross-layer limits do not restrict the file-URL streaming path to 4 MiB, and this is not an 8 MiB direct-text capability claim.

## Verification and remaining acceptance

```sh
bash ios/test-swift-contracts.sh
bash ios/test-consumer.sh build-ios-release/UniShareSDK.xcframework
bash ios/test-consumer.sh build-ios-release/UniShareSDK.xcframework \
  071982EE-2278-42D1-AEEF-B1DDE4E53FF4 permission
```

The independent UIKit consumer compiles using only the delivered XCFramework. Tests exercise lifecycle, UTF-8 and image clipboard, unsupported formats, explicit file URLs/rejection cleanup, foreground interruption, and missing host declarations. Native port tests remain in `ios/build-ports.sh`; core gates remain in `ios/test-core.sh`.

Simulator coverage is not physical-device or cross-platform LAN acceptance. Actual local-network consent/denial prompts, real app suspension, locked Keychain, external document-provider grants, physical interface changes, and Windows/Android/iOS pairing/transfers remain the broader acceptance gate. Framework device compile/link does not claim physical execution.

## Public Swift acceptance host

Use the delivered production XCFramework, an Apple Silicon Mac and a booted iOS Simulator. No SDK source files or internal APIs are compiled into the host:

```sh
bash ios/test-acceptance.sh build-ios-release/UniShareSDK.xcframework \
  071982EE-2278-42D1-AEEF-B1DDE4E53FF4 all
bash ios/test-acceptance.sh build-ios-release/UniShareSDK.xcframework \
  071982EE-2278-42D1-AEEF-B1DDE4E53FF4 permission
```

Replace the UUID with a booted arm64 Simulator from `xcrun simctl list devices`. Modes `network` and `clipboard` run the focused subsets. The script compiles for iOS 15, installs a minimal UIKit application and launches after it becomes active. It uses the existing test bundle identifier `com.unishare.ports` and replaces that test application. Its synthetic Keychain entitlement is Simulator-only. Generated apps and complete console logs are kept under ignored `build-ios-acceptance-<mode>/`; a missing result marker, crash or 240-second process timeout fails the script.

The network host creates two independent Keychain identities inside one foreground process. They discover each other through system Bonjour and connect through the production TCP/TLS stack. It checks DeviceId deduplication, snapshots/address/version metadata, matching six-digit SAS and explicit bilateral pairing, incoming offer consent, UTF-8/NUL text, PNG/JPEG bytes, empty and multichunk files, rejection, cancellation after received data, staging cleanup, terminal counts, Offline/Stop/restart and Keychain identity/trust across SDK recreation. The runner then starts a separate `trust-read` process, which verifies the same identities and public trust fingerprints persisted. Received bytes and SHA-256 are compared with the source. This success-path checksum coverage does not inject a malicious wire checksum; negative checksum coverage is in the core transfer tests.

The clipboard subset first writes UTF-8/NUL text and a valid PNG using the public API, then exits the writer process. Each run uses fresh opaque pixel values and saves the expected decoded pixels for the next process. A separately launched reader compares all four pixels and consumes that expectation, so an image left by an earlier run cannot satisfy the check. Logs include both process IDs. This goes beyond immediate same-process readback but uses the same application bundle; it does not exercise another app's paste-consent prompt.

The background case posts UIKit lifecycle notifications and verifies that Stop cancels pending transfers on both peers, denied background Start, that the foreground notification alone leaves both SDKs stopped, and explicit foreground restart. It does not claim actual OS suspension. Failure cases match terminal events to the sender's initial progress transfer ID and completion error; terminal ID sets must contain exactly the expected transfers. Receiver-initiated cancellation reports local `cancelled` while the sender observes `networkLost` after the TLS socket closes; the raw OS status can vary. The permission mode removes the Bonjour declaration and expects `permissionDenied`; an actual OS local-network grant/denial requires a signed physical host. Physical iPhone execution, external document-provider URLs, locked Keychain and Windows/Android interoperability remain pending.

For a connected iPhone, stage the same host as an Xcode project:

```sh
bash ios/build-acceptance-device.sh build-ios-release/UniShareSDK.xcframework com.example.shareacceptance
open build-ios-acceptance-device/AcceptanceHost.xcodeproj
```

In Xcode select your team, a unique bundle identifier and the connected iPhone; use the `Acceptance` scheme. The staging script deliberately supplies no Simulator entitlements. With an existing development certificate/profile, optional third and fourth script arguments supply the team identifier and Xcode device identifier for an automatic-signing build. The app is produced at `build-ios-acceptance-device/DerivedData/Build/Products/Debug-iphoneos/Acceptance.app` for the command-line build. User account credentials and signing material are never included in this project.

Run with the scheme launch argument `network` and grant Local Network access when prompted, then separately run `trust-read`. Run `clipboard-write`, let it exit, then run `clipboard-read` to test clipboard persistence. For real OS denial, disable the app's Local Network access in iOS Settings and run with `denied`; it expects `permissionDenied` from Start or a discovery error and prints the native status when available. Restore permission before further network tests. These are manual physical-device steps; the Simulator runner cannot grant or deny the iPhone's permission.
