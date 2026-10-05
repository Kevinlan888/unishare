# Android Kotlin SDK integration

The AAR supports API 26+, builds against API 36, and packages arm64-v8a,
armeabi-v7a and x86_64 JNI libraries. The consuming application sets its target
SDK; the library instrumentation host targets 36. OpenSSL and libc++ are linked
statically. There is no native test probe in the release AAR.

Add the AAR and Kotlin standard library to an Android Kotlin project:

```kotlin
// app/build.gradle.kts
android { defaultConfig { minSdk = 26; targetSdk = 36 } }
dependencies {
    implementation(files("libs/share-sdk-release.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}
```

## Lifecycle and public services

```kotlin
val sdk = ShareSdk(applicationContext, storageName = "my-sharing-installation")
val discoverySubscription = sdk.discovery.observe { event ->
    when (event) {
        is DiscoveryEvent.Online -> showCandidate(event.device)
        is DiscoveryEvent.Updated -> updateCandidate(event.device)
        is DiscoveryEvent.Offline -> removeCandidate(event.device.id)
        is DiscoveryEvent.Error -> showError(event.error)
        is DiscoveryEvent.State -> showDiscoveryState(event.state)
    }
}
sdk.initialize(SdkConfig(deviceName = "My Android phone")).result.thenAccept { result ->
    if (result.isSuccess) {
        sdk.setForeground(true)
        sdk.start()
    } else showError(result.error!!)
}

// From an explicit user action:
val connect = sdk.connections.connect(selectedDevice.id)
val pairingSubscription = sdk.connections.observe { event ->
    if (event is ConnectionEvent.PairingRequest) {
        // Show all six digits; wait for the user to compare both devices.
        showPairingPrompt(event.verificationCode) { matches ->
            sdk.connections.confirmPairing(event.requestId, matches)
        }
    }
}
val send = sdk.transfers.sendFile(selectedDevice.id, selectedContentUri,
    displayName = "report.pdf", byteCount = selectedByteCount,
    mimeType = "application/pdf")
send.result.thenAccept { result ->
    if (!result.isSuccess) showError(result.error!!)
}

// When leaving foreground:
sdk.setForeground(false)
sdk.stop()
// On returning foreground, setForeground(true) and explicitly start again.
// At final ownership teardown:
discoverySubscription.close()
pairingSubscription.close()
sdk.close()
```

Initialize once and await a successful result before service commands or
snapshots. `state` returns UNINITIALIZED, INITIALIZED, RUNNING, STOPPED or CLOSED.
Storage names identify independent installation identities; reuse a stable name
across application launches. Resource limits in `SdkConfig` may be tightened by
the host. A failed initialization requires closing the instance and handling the
error before creating another instance; corruption never replaces an identity.

`discovery.devices()` and `connections.trustedDevices()` return immutable
snapshots. Events preserve DeviceId, name, platform, protocol major/minor,
addresses (including IPv6 scope), port, pairing flag and last-seen milliseconds.
`connect`, `confirmPairing`, `rejectPairing` and `forgetDevice` cover connection
and trust decisions. A SAS is a six-character string, including leading zeros.
The host must explicitly decide each pairing and incoming transfer offer.

Observe `TransferEvent.IncomingOffer` to inspect request ID, transfer ID, content
type, display name, MIME and declared byte count. Call `transfers.accept(requestId,
stagingContentUri)` or `reject(requestId)` for that offer. Accept completion means
the destination was attached; deliver the staging file to the application only
following a successful `TransferEvent.Result`. Progress carries transferred and
total byte counts. `cancel(transferId)` cancels a known active transfer.
`sendText`, `sendImage` and `sendFile` return operations; file/image data uses an
explicit source content URI and exact declared byte count.

## Callbacks, operations and ownership

`sendText(String)` accepts UTF-8 up to the smaller of `maxTextBytes` and 8 MiB.
It counts and encodes on the SDK preparation worker into a private cache file,
then calls the additive v1 `usdk_send_text_stream` API. No full text byte array
crosses JNI. Encoding buffers are bounded and native transfers keep the existing
256 KiB chunks and 4 MiB shared budget. Over-limit text returns ResourceLimit
before file creation/native submission. Temporary sources are removed after
success, failure, cancellation, Stop, Close, and late preparation retirement.
Disk/encoding I/O failures report StorageFailed. App termination may leave cache
files for the OS to evict; the SDK never deletes host source URIs.

The original C ABI `usdk_send_text` remains a direct copy API with a 4 MiB span
cap and available native-budget restrictions. Clipboard writes remain direct:
combined text/MIME/image spans are capped at 4 MiB and can fail earlier when the
shared budget is occupied. This release does not promise 8 MiB clipboard values.
Image/file transfers use streaming URIs.

All asynchronous methods return immediately with an `Operation` containing an
ID and `CompletableFuture<OperationResult>`. IDs are scoped to the Kotlin SDK
instance and differ from internal native IDs. Accepted facade operations have
one terminal result, including preparation/submission failures. Native rejection
is reported in that future with the original stable error category and optional
platform code. Snapshots throw `ShareException` on native failure. Calls after
Close throw `IllegalStateException`.

The default callback executor is serial. An optional `callbackExecutor` in the
ShareSdk constructor selects host delivery; a private serial trampoline prevents
JNI callbacks and an inline host executor from running user code on the
submitting stack. Device events, observers and SDK future completion are
serialized. An executor must eventually run accepted tasks; rejected tasks use
the private serial fallback. Do not block a callback waiting for another callback
on the same executor. UI changes require a UI executor or explicit UI posting.
CompletableFuture's normal rules apply: continuations attached to an already
completed future may execute on the attaching thread; host-requested async
continuations are scheduled and owned by the host.

Stop completes pending native operations and URI preparation as Cancelled. URI
acquisition runs separately so a blocked provider does not delay native Stop.
Close suppresses queued
listeners/results, drains an already-running SDK callback, and completes unresolved
futures as Cancelled while Close is executing. After Close returns the SDK starts
no listener or future-completion delivery. Close from an SDK callback is safe.
Future continuations scheduled independently by host code remain host-owned.
An in-flight URI acquisition can finish after Close; its returned stream is
closed exactly once, and a late destination is aborted/deleted without submitting
to the destroyed SDK or delivering another result.

Borrowed C ABI spans are copied in JNI before native callbacks return. Kotlin
models defensively copy mutable byte arrays/lists. Native callback contexts and
stream references remain retained through terminal completion/cancellation.
Hosts must close the ShareSdk instance to release native, platform and executor
ownership; Close is idempotent. No background service or automatic clipboard
watch is installed. Discovery/listening/transfers are guaranteed only while the
host is foreground and the SDK is started.

## Permissions by Android version and target

| Device/API and host target | Host action |
| --- | --- |
| API 26–35 | INTERNET and ACCESS_NETWORK_STATE (merged from AAR); no LAN runtime prompt. |
| API 36, target 36 or lower | Normal LAN access uses INTERNET. The optional Android 16 local-network restriction test requires declaring/granting NEARBY_WIFI_DEVICES. |
| API 37+, target 36 or lower | INTERNET supplies the legacy implicit LAN grant; do not request ACCESS_LOCAL_NETWORK. |
| API 37+, target 37+ | Host declares ACCESS_LOCAL_NETWORK, requests its runtime grant, and handles denial/revocation before Start. |

The AAR declares INTERNET and ACCESS_NETWORK_STATE. The host targeting 37+
adds `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK"/>`.
The adapter checks the actual host target and permission; the SDK opens no
permission UI. Framework/errno failures preserve available original numeric
codes. A foreground prerequisite failure is PermissionDenied; an NSD failure is
DiscoveryFailed unless the platform identifies denial. Java exceptions without a
numeric platform code do not invent one. See the official
[local network permission guide](https://developer.android.com/privacy-and-security/local-network-permission),
verified 2026-10-05. The SDK uses broad discovery and explicit pairing; it does
not substitute a system picker flow.

## URI streams and explicit clipboard

Source and destination URIs must use `content://`; obtain the appropriate
read/write URI grants in the host. Pass an exact nonnegative byte count and an
explicit display name/MIME. Names are display-only and never become paths.
Sources are closed without deleting their data. Destination URIs must identify
regular, app-controlled **staging** files exposed by a provider that supports
deletion; never supply a final user document. Writes flush and sync before
success. Pipes and providers that finalize only on Close are rejected.

After verified successful TransferEvent.Result, the host chooses final naming
and moves/publishes staging. Failed decisions, cancellation, checksum failure and
Close abort staging. Provider deletion is best effort because the platform's void
Abort interface cannot surface a refused delete; hosts should reconcile abandoned
staging files. Arbitrary commit-on-close SAF providers are outside this destination
contract. See [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver).

`clipboard.readShareableContent()` and `writeContent(ShareContent.Text(...))` /
`writeContent(ShareContent.Image(mime, bytes))` are explicit text/image operations.
They use the main looper. Unsupported clipboard types return InvalidInput.
Android focus/privacy restrictions can prevent reads or show system UI, so invoke
reads only from an eligible foreground user action. Read content is in the
operation result; the host separately chooses a device and sends it. Text uses
sendText. To send a clipboard image, the host exposes the returned bytes through
its own read-granted content provider and uses sendImage.

Image clipboard writes use the AAR's read-only grantable ImageProvider at
`${applicationId}.unishare.images`; keep that authority unique. The host's
applicationId supplies the manifest placeholder. Android may evict cached image
data, and hosts can clean unused clipboard images when safe. No storage-wide
permission or automatic clipboard synchronization is needed. See
[ClipboardManager](https://developer.android.com/reference/android/content/ClipboardManager).

## Discovery, sockets and identity limits

NsdManager publishes `_share._tcp.` with the actual listener port and core TXT
metadata. API 33+ uses network-scoped discovery; API 34+ receives all resolved
addresses and continuous updates. API 26–32 has no authoritative interface ID,
so ambiguous legacy lost notifications do not remove confirmed sightings.
Connectivity reconciliation restarts discovery; this may delay Offline. Null-
Network results follow the same conservative rule. API 33 resolves one host.
API 36 exposes the resolved hostname; earlier versions preserve the resolved
numeric endpoint, including IPv6 scope. These addresses are discovery candidates,
not authenticated identities. See [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager).

TCP uses numeric IPv4/IPv6 and TLS identity verification. Network changes,
background suspension and Stop interrupt sessions. Keystore wraps the core's
DER identity with a non-exportable AES-GCM key; the TLS private key itself is
exportable into runtime core memory. Encrypted identity/trust records live in
app-private no-backup SQLite with authenticated row identity, FULL synchronous
transactions and a cross-process lock. This does not claim hardware-backed TLS
key custody. See [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## Build and verification

Provision API 36/Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, JDK 21,
Gradle 9.4.1, and architecture-matched static OpenSSL 3. CMake downloads nothing.
The build selects these ignored dependency roots per ABI:

- `build-deps/vcpkg_installed/arm64-android`
- `build-deps/vcpkg_arm_neon_android/arm-neon-android`
- `build-deps/vcpkg_x64_android/x64-android`

```powershell
./android/gradlew.bat -p android :share-sdk:assembleRelease
./android/gradlew.bat -p android :share-sdk:connectedDebugAndroidTest -PpublicTests=ON
./android/gradlew.bat -p android :share-sdk:connectedDebugAndroidTest -PportTests=ON
./android/tests/verify-aar.ps1
./android/tests/run-consumer.ps1
```

Release output: `android/share-sdk/build/outputs/aar/share-sdk-release.aar`.
Release always excludes the native test probe, including when portTests=ON is
passed. Public tests exercise the facade and native initialization, lifecycle,
URI ownership, callback races and mapping. The independent consuming project
compiles and shrinks against the release AAR. Physical LAN interop, older API
coverage and network-switch/large-file acceptance belong to Task 9c/11.
Generated SDKs, caches, binaries, keys, consumer builds and APKs remain ignored.

## Android acceptance evidence (2026-10-05)

MuMu Android 15/API 35 (`127.0.0.1:16384`, reported model SM_G9900) and an
attached physical Pixel 10a Android 17/API 37 (`62041JEA316847`) exercised two
independent native SDK instances in one app. Real NSD produced one matching
Online record with UUID/name/platform/version, resolved numeric addresses and
the usable bound port; TLS pairing agreed on six digits. Approved UTF-8 text,
image and a 524289-byte file matched destination bytes. Offers delivered no
result or staging data before approval. Rejection, cancellation before approval,
and receiver cancellation during a 16 MiB file were checked. Active cancellation
removed staging and retained source data. The cancelling receiver reports
Cancelled; the remote sender reports NetworkLost because core cancellation
immediately closes the TLS session. Reconnect requires receiving consent.

Real service Stop produced Offline, and restarted storage retained DeviceId,
certificate material and trust fingerprints; trusted reconnect succeeded.
Corrupt encrypted identity and an unusable identity directory reported
StorageFailed through public initialization without silently replacing identity.
URI acquisition/Stop/Close and late callback ownership are covered by the public
instrumentation cases. Foreground-denied Start reports PermissionDenied.

An empty focused test activity on MuMu and the unlocked physical Pixel 10a
verified actual ClipboardManager text, image ContentResolver bytes, and unsupported
intent clipboard InvalidInput. The Pixel focused rerun returned instrumentation
code -1. The phone clipboard focus case requires an unlocked, awake phone; headless
instrumentation alone does not meet Android's focus requirement. Adapter tests
also cover explicit clipboard conversion and unsupported formats at an injected
framework boundary.

The independently compiled, R8-minified release AAR consumer uses a configured
host Executor and observes Browsing/Start/Stop/Close. A target 37 consumer on the
physical API 37 phone verified actual ACCESS_LOCAL_NETWORK denial and successful
Start after an ADB test grant, then restored the denied permission. This tests
permission state handling, not the host's runtime permission dialog UI.

These same-device sessions use NSD-advertised local endpoints. They do not prove
two-device LAN delivery, physical multicast across peers, interface switching,
real service metadata Updated events, API 26 behavior, or hardware-backed TLS
key custody. No API 26 device was attached. Task 11 owns cross-platform pairwise
acceptance. A MuMu combined run observed one NetworkLost before text delivery;
the isolated connected gate passed without a network workaround. Keep that
transient emulator result distinct from the physical passing cases.

For a focused installed public test run:

```powershell
adb -s 127.0.0.1:16384 shell am instrument -w -r -e case acceptance com.unishare.sdk.test/com.unishare.sdk.PublicApiTests
adb -s 127.0.0.1:16384 shell am instrument -w -r -e case focusedClipboard com.unishare.sdk.test/com.unishare.sdk.PublicApiTests
```

`identityAcceptance` checks real core-generated identity and storage failures.
The test activity and provider are instrumentation-only; release contains no
business UI or acceptance probe.

The Task11b public release-AAR hosts also passed physical Pixel ↔ Windows and
Pixel ↔ iOS Simulator, including actual clipboard text/image reads, 5 MiB UTF-8
text, cancellation cleanup, restart/trust, isolation and Offline. See the
[measured matrix](../../tests/interop/matrix.md); physical iPhone evidence remains
pending. Clipboard image checks compare decoded RGBA pixels and transfer the
actual public readback bytes, since an OS may change the image encoding.
