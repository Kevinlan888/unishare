# Android internal platform ports (Task 9a)

Minimum API 26; compile and target API 36. This split provides production
`IPlatform` ports, their retained JNI/C ABI bridge and an internal Gradle library.
The public Kotlin service API and multi-ABI distributable AAR belong to Task 9b.
Physical device and LAN interoperability acceptance belongs to Task 9c/11.

## Permissions and lifecycle

The library manifest declares `INTERNET`, `ACCESS_NETWORK_STATE` and
`ACCESS_LOCAL_NETWORK`. Unknown newer permission names are ignored on older
Android versions. Android 17 requires the last permission at runtime for hosts
targeting API 37+, as documented by
[Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission).
The adapter checks the host's actual target SDK and permission grant. The host
must present the runtime permission UI; the internal agent never opens an
Activity or treats denial as approval. Android 16's optional local network
restriction testing can also reject NSD/socket operations; those errors propagate.

The host marks the bridge foreground before Start and marks it background when
the foreground lifecycle ends. Background notification stops native sockets and
DNS registration and notifies the core, which interrupts sessions and refuses
network restart while the host is background. On returning foreground, the
host explicitly starts the SDK again. No background discovery guarantee or
automatic clipboard synchronization is provided. Task 9b owns the Kotlin
lifecycle binding and Task 9c owns real lifecycle transition tests.

ConnectivityManager watches available networks and link properties. Registration
errors are held for the permission/start gate. Initial network snapshots are
excluded from change events to avoid a restart loop. Old watcher generations
and queued old NSD submissions are retired. Teardown is best effort and never
throws through native destructor/Close/Stop boundaries.

## DNS-SD and TCP

NsdManager publishes `_share._tcp.` (Android's representation of the core's
`_share._tcp.local.`), the actual bound TCP port and the core's id/name/platform/pv
TXT attributes. API 33+ uses NetworkRequest discovery and Network handles for
removal scope. API 34+ uses ServiceInfoCallback for continuous address updates
and all returned host addresses. The adapter preserves native NSD error codes,
including internal error 0, bad parameters 6 and permission denial 7.

The controller approved the following minimum-version limitation. API 26–32
provides no authoritative interface identifier in legacy NSD results. It uses
an aggregate `legacy` scope and serializes legacy resolution, returning the
resolved host address. Ambiguous lost callbacks do not remove a confirmed
device. A connectivity change restarts discovery and the core reconciles its
DeviceId catalog. Null-Network results on newer APIs use the same conservative
aggregate treatment (`unscoped`). This can delay Offline until network
reconciliation on those paths. API 33 legacy resolution provides one host;
full address lists and continuous updates begin at API 34. See
[NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)
and [NsdServiceInfo](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo).

Native POSIX TCP sockets accept numeric IPv4/IPv6 addresses only, including IPv6
scope. An IPv6 dual-stack listener returns its OS-selected bound port before
accepted sockets. Remote endpoints come from getpeername, exclude ports and
normalize IPv4-mapped IPv6 to IPv4. Read returns bounded bytes or EOF; Write
completes only after the whole buffer is sent. Cancel closes the affected socket
and completes pending operations once. Factory Stop shuts down listeners,
connecting and established sockets; workers retain descriptors until their
completion, preventing descriptor reuse races. Connect polls a nonblocking
descriptor so Stop does not depend on the OS's long connect timeout.

## Identity and trust

The C++ core generates the UUID, DER PKCS#8 key and DER certificate. DER key bytes
are exportable in runtime memory. Android Keystore retains only the AES-256-GCM
wrapping key; this does **not** make the TLS private key hardware-backed.
Encrypted, authenticated identity and trust records live in an app-private
SQLite database under `Context.getNoBackupFilesDir()`. Each encrypted row binds
its row identity with GCM AAD. SQLite FULL-synchronous transactions provide
atomic commits; a JVM mutex plus app-private file lock serialize SDK instances
and processes, including wrapping-key creation. See
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).

An identity is created once. Read/decryption/corruption/missing wrapping-key
errors fail startup rather than generating a replacement. Trust insertion is
idempotent only for the identical binding; changed fingerprints return
IdentityChanged. Removing trust is idempotent. Native packets and transient
Java identity response buffers are cleared where practical; JVM/SQLite/crypto
internals may retain copies and the runtime core necessarily retains its key.
Hardware-backed key characteristics and power-loss recovery require physical
verification. No Keystore export or stronger key-protection claim is made.

## URI streams and clipboard

Source and destination handles are explicit host-selected `content://` URIs.
Reads and writes are asynchronous and bounded. A destination must be a regular,
app-controlled staging file exposed by a provider supporting deletion. Pipes
and providers that finalize only on Close are rejected: every Write flushes and
syncs the file descriptor before success, so a finalization failure cannot be
hidden behind the void Close interface. Abort closes the descriptor and deletes
the staging URI. The host controls final naming/move after verified receipt;
display names are never used to construct paths.

The host must ensure its provider's delete succeeds. Abort's inherited void
interface cannot report a failed delete to the core; native cleanup is best
effort when the provider or OS refuses cleanup. Host reconciliation of abandoned
staging files is still required. Successful Close leaves the staging file for
the host. These contracts deliberately exclude arbitrary commit-on-close SAF
destination providers. See
[ContentResolver](https://developer.android.com/reference/android/content/ContentResolver).

Clipboard access occurs only on explicit Read/Write requests and runs on the
main looper. A single plain-text clip preserves UTF-8, including embedded NUL;
an image URI is read as its MIME and bytes. Unsupported formats return
InvalidInput. Image writes create app-cache data and a read-only, grantable
ImageProvider URI with `${applicationId}.unishare.images` authority. Cache data
can be removed by Android; hosts may clean unused clipboard images when safe.
Android's focus/privacy rules can prevent reads or show system UI; hosts must
trigger this from an eligible foreground user action. See
[ClipboardManager](https://developer.android.com/reference/android/content/ClipboardManager).

## Internal ownership and building

`com.unishare.sdk.internal.NativeBridge` is an implementation detail for Task 9b.
It registers an opaque C ABI platform token and optional URI stream tokens.
The C ABI SDK retains the platform after the original token is released.
Each independently registered stream token requires `releaseStream`, normally
by a Kotlin closeable wrapper. Releasing a token twice is InvalidInput.
NativePortException preserves the stable code and optional platform code.
Recurring/one-shot callbacks use monotonic IDs and weak native-owner lookup;
late completion of retired IDs cannot dereference a released native object.
Consumers receive JNI keep rules. Default public callbacks use the core's
serial queue. Keystore/Errno/NSD codes are kept when their Android APIs expose
a numeric code; Java exceptions without one do not invent an OS code.

Provision SDK API 36/Build Tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, JDK 21,
Gradle 9.4.1 and Android OpenSSL 3 before building. Dependencies are not
downloaded by CMake. In this workspace, arm64 OpenSSL is provisioned at
`build-deps/vcpkg_installed/arm64-android`. Set ANDROID_HOME and optionally
GRADLE_USER_HOME to your provisioned locations, then run:

```powershell
./android/gradlew.bat -p android :share-sdk:assembleRelease
./android/gradlew.bat -p android :share-sdk:connectedDebugAndroidTest -PportTests=ON
```

Task 9a enables only arm64-v8a. Use `-PportTests=ON` only for internal probes;
normal Release excludes the native test library. The standalone installed-SDK
gate is `android/tests/run-ports.ps1 -NativeBuild <native-build-dir>` and avoids
downloaded Android test frameworks. `tests/android/run-native.ps1` can serve
as CMAKE_CROSSCOMPILING_EMULATOR to run the full portable core CTest on a device.
Generated builds, SDKs, caches, keys and APKs are ignored. No multi-ABI AAR or
physical interoperability claim is established by Task 9a.

### Resolved target and NSD concurrency

On API 36+, resolved records use NsdServiceInfo.getHostname() plus the framework's
omitted `.local.` suffix. On API 26–35, which cannot expose that hostname, the
adapter uses a framework-resolved numeric host address (including IPv6 scope)
as target endpoint metadata. This value is not an authenticated peer identity;
DeviceId is the advertised identifier and TLS/SAS authenticates the connection.
No target is synthesized from the service instance, TXT identifier or reverse DNS.

NSD state monitors protect generations, callback ownership and the legacy resolve
queue. Framework submissions, cleanup and cross-boundary callbacks run outside
those monitors. Stop retires the generation before cleanup; submissions finishing
after Stop unregister their late registrations and cannot advance a new resolve
queue. This lets synchronous framework submission failures race with Stop without
holding opposing adapter monitors.