# Windows SDK integration

The package supports Windows 10+ desktop applications. `unishare.dll`, its import library, public C/C++ headers, OpenSSL runtime DLLs and a CMake package are installed together. `unishare_windows_ports` remains an internal test target; applications link `unishare::windows`.

## Build and import

Use an x64 MSVC developer shell with CMake 3.21+, a Windows 10 SDK, and an existing OpenSSL 3 installation. Dependencies are not downloaded by the build. Match architecture, MSVC runtime, configuration and C++ toolchain across the DLL and C++ host. The C ABI is the supported boundary for other toolchains/languages.

```powershell
cmake -S . -B build -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release -DOPENSSL_ROOT_DIR=C:/deps/openssl
cmake --build build
ctest --test-dir build --output-on-failure
cmake --install build --prefix C:/sdk/unishare
```

The install includes `bin/unishare.dll`, `lib/unishare.lib`, `include/unishare`, and `lib/cmake/unishare`. Ship the installed `bin` DLLs beside the application and the matching Microsoft Visual C++ runtime. When redistributing OpenSSL, retain the license/notices from the OpenSSL distribution used for the build. The build copies matching runtime DLLs found under its OpenSSL prefix; a custom dependency layout must supply its runtime DLLs separately.

```cmake
find_package(unishare CONFIG REQUIRED)
target_link_libraries(my_host PRIVATE unishare::windows)
```

Set `CMAKE_PREFIX_PATH` to the install prefix. `tests/windows/package` is a standalone installed-package C and C++ consumer; it does not include private sources or link static ports. `windows_package` CTest verifies the same public API through the build's DLL. Never link both the static core/ports and DLL into one host: their handle registries are distinct.

## Factory and lifetime

```cpp
#include <unishare/windows.hpp>
unishare::SdkConfig config;
config.device_name = "My desktop";
unishare::WindowsOptions options;
options.identity_directory = L"C:/Users/me/AppData/Local/MyApp/ShareIdentity";
auto sdk = unishare::CreateWindowsShareSdk(config, options);
sdk->Initialize({}, [/* retain host state */](auto result) {
    // On success, install event handlers and call Start asynchronously.
});
```

The factory returns an **uninitialized** SDK and retains its WindowsPlatform. It performs no identity-store or network initialization. `Initialize(SdkConfig{}, callback)` selects the complete configuration saved by the factory. Any configuration differing from the exact default aggregate replaces all saved fields; no partial merging occurs. A nondefault configuration with an empty device name is rejected normally. Construction may throw `ShareError` or allocation exceptions; ordinary asynchronous failures use `Result`.

The default identity location is the current user's `FOLDERID_LocalAppData/UniShare`, obtained using `SHGetKnownFolderPath`. Specify an application/installation-specific absolute directory to isolate identities. Its parent must already exist and be trusted. The adapter creates and protects the final directory during Initialize. A caller-selected directory also permits two independent test installations on one machine.

WindowsPlatform owns all ports, the interface watcher, permission policy and a serial callback executor. Start waits asynchronously for interface-watcher registration before requesting any host permission policy, binding TCP, or publishing DNS. Watcher registration errors retain their native code. Windows desktop firewall access is not a consent result the SDK can grant: the host/administrator manages firewall rules. A custom `SdkConfig.permissions` may enforce host policy but does not alter firewall settings.

SDK callbacks run on the owned serial executor unless `callback_executor` is supplied. A supplied executor must enqueue in order and keep draining after Stop/destruction; it must not invoke inline. Keep callback capture state alive until queued callbacks drain. Do not block callbacks waiting for other callbacks on that executor. Destroying the SDK first stops/cancels core work, then releases platform ports. Native callback contexts retain their own generations through completion and ignore late notifications. Platform destruction retires the internal permission gate without allocating or delivering its pending internal callbacks; the core has already cancelled public SDK operations. A permission-agent handle used independently of the SDK does not retain pending callbacks past platform destruction. Subsequent requests return Cancelled. Normal Stop and watcher replacement continue to deliver cancellation. Native cancellation is asynchronous; Stop does not promise that OS deregistration has completed before its callback.

For C hosts, include `unishare/windows.h`, call `usdk_windows_platform_create` with an absolute UTF-8 path (empty selects the default), then `usdk_create`. The SDK retains the platform, so `usdk_platform_release` may follow accepted creation. Use `usdk_start`, `usdk_stop`, and `usdk_destroy`; `usdk_abi_version()` is 1. All Windows C entry points catch exceptions. `usdk_windows_open_source/destination` return stream tokens usable by the frozen C ABI; release tokens after accepted submission. C++ hosts use `OpenWindowsSource/Destination(HANDLE)` and pass the returned streams to SendFile/Accept.

## Network prerequisites and smoke

Enable applicable application inbound TCP rules and UDP multicast DNS traffic on the selected Windows network profile. The SDK uses an OS-selected TCP port and advertises that exact port, so firewall rules should normally target the application rather than one hardcoded port. DNS-SD uses `_share._tcp.local.`; peers need multicast reachability (UDP 5353), compatible IPv4/IPv6 connectivity, and no wireless client isolation. Publication success alone does not prove discovery or inbound connectivity. VPNs and virtual interfaces may affect native interface selection. The SDK does not change firewall configuration.

Run the production-port two-process harness from a built tree:

```powershell
./tests/windows/run-smoke.ps1 -BuildDirectory ./build
```

The script creates a fresh run directory and launches hidden sender/receiver processes linked to the public DLL. Both publish and browse using production DNS-SD and TCP. The explicit `--test-consent` mode compares the two SAS values through local test files before confirming both requests, accepts the two offers into host-selected HANDLE staging, sends text and a 262145-byte file, verifies received bytes, and stops. The files carry test consent only; peer discovery and content always use the shipped protocol. This harness is a test host, not a recommended application consent UI. Each process can also be launched manually using `windows_smoke sender|receiver ABSOLUTE_RUN_DIRECTORY --test-consent` with the same fresh directory. Logs name the failing stage and retain native errors. It never touches the user's desktop clipboard.

Earlier Task 8 runs on this host (Windows 10.0.26200, WLAN plus virtual adapters)
published but timed out browsing. Task 11 isolated the native query's final root
dot as the discovery defect: actual Android announcements reached Windows, and
the same `DnsServiceBrowse` query without the dot received callbacks. The adapter
now supplies Microsoft's native service-name form `_share._tcp.local` while its
public service type stays `_share._tcp.local.`. The regression and public package
LAN matrix pass Windows↔physical Pixel and Windows↔iOS Simulator, including
discovery, TLS/SAS, all content flows, both clipboard formats, cancellation,
cleanup, rediscovery, trust, isolation and Offline. See the
[matrix](../../tests/interop/matrix.md) for exact scope and physical iPhone gates.

## Identity and trust

Construct `IdentityStore` with an absolute path to a dedicated per-user directory on a local NTFS volume, under an existing trusted parent (for example the application's LocalAppData directory). The adapter creates the final directory and restricts its DACL to the current process user, with inheritance for its records. Do not use an arbitrary shared or attacker-controlled parent. Final-component reparse points and non-regular record/lock files are rejected.

A user-scoped DPAPI blob contains the DeviceId, DER PKCS#8 private key, DER X.509 certificate, and trust bindings. No machine-wide DPAPI option is used. A byte-range file lock serializes readers and mutations across instances/processes. Mutations write a new private file, flush it, and perform a write-through replacement in the same directory. Failed replacement leaves the preceding record intact. A crash before replacement can leave an encrypted `.tmp` file; it is never treated as committed identity. The SDK does not silently replace unreadable/corrupt identity.

The filesystem and hardware must honor Windows flush semantics. Tests exercise real files, DPAPI, access errors and replacement rollback; they do not simulate physical power loss. Hosts must not modify the store while the SDK is using it. Win32 error codes are retained in `platform_error`.

## Source and destination handles

Register a native regular disk-file handle with `RegisterSource` or `RegisterDestination`, then pass the returned single-use token to `OpenSource` or `OpenDestination`. Tokens belong to that factory. A remote/display filename is never accepted as a path.

The factory uses `ReOpenFile` to obtain its own handle with `FILE_FLAG_OVERLAPPED` and an independent offset starting at zero. The original handle may be synchronous. Open the original with compatible `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`; close it after successful registration. The host must not write to the registered file while the stream is active. Source requires read access. Destination must be a host-selected, empty staging file that permits read, write, and DELETE access; it must not have delete-on-close set. Final publication/rename of the staged file belongs to the host.

Each stream has a private serial worker. Read/Write enqueue work and callbacks; they do not execute disk I/O on SDK core/network queues. Reads return at most 4 MiB per call, use 64-bit offsets, and return empty bytes only for EOF. A zero read request is invalid. Write takes ownership of its buffer, writes in bounded chunks, and flushes before success. Opening a destination flushes the empty file, so zero-byte transfers need no fallible action at Close. Platform callbacks run on the worker; the SDK then applies its public callback-executor policy. Callbacks must not block waiting for more work on the same stream.

Call Close after all writes have completed successfully. It seals the stream and queues handle release, without a flush or rename. Abort publishes cancellation and calls `CancelIoEx`. The worker rechecks cancellation after native submission and cancels the specific OVERLAPPED request if abort preceded submission, then waits for issued I/O completion on the worker, then marks the staging file for deletion by handle and closes it. Buffers/OVERLAPPED structures survive until completion; queued operations complete once. Destroying an unsealed stream aborts it. Destruction of the factory cleans up unused destination registrations.

An OS deletion failure (for example a file made read-only) is returned as StorageFailed to outstanding callbacks. The public `void AbortAndCleanup()` signature has no error channel after every callback has already finished; factory destruction has the same limitation. The adapter cannot promise deletion against external permission/attribute changes. The host must keep staging under its control and may reconcile abandoned staging files at startup. Source files are never deleted by stream abort.

## Explicit clipboard access

`Clipboard` uses a private worker and a Win32 clipboard boundary. It has no listener, polling or automatic synchronization. UTF-8 text is validated and published as terminated CF_UNICODETEXT. The encoded UTF-16 buffer, including its terminator, must fit the same 100 MiB limit used for reads. Embedded NULs and malformed UTF-8/UTF-16 are rejected. PNG and JPEG bytes are decoded with WIC before clipboard mutation; images are published in the registered PNG/JFIF format plus an interoperable CF_DIBV5 representation. Image MIME must match the container. Encoded and decoded images are limited to 100 MiB.

Reads support Unicode text, PNG, JFIF, and common uncompressed 24/32-bit DIB/DIBV5 images. Unsupported bitmap encodings, profiles, or malformed payloads return InvalidInput. DIB images are encoded as PNG. Clipboard denial retains the Win32 code. The boundary creates a temporary message-only owner window, uses GMEM_MOVEABLE, and transfers allocation ownership only after successful SetClipboardData. A failure during a multi-format clipboard publication may leave the formats already published; Win32 clipboard publication is not transactional.

Tests substitute only the OS clipboard boundary; UTF conversion and WIC codecs remain real. No live desktop clipboard test has been run because it would require capturing/restoring every native and delayed-rendered format reliably. Cross-application clipboard behavior remains an integration gate.

## Microsoft API references

- [DPAPI protection](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata) and [unprotection](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptunprotectdata)
- [LockFileEx](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex), [FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers), and [MoveFileEx](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw)
- [ReOpenFile](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-reopenfile), [CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex), and [SetFileInformationByHandle](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfileinformationbyhandle)
- [SetClipboardData ownership](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setclipboarddata), [clipboard formats](https://learn.microsoft.com/en-us/windows/win32/dataxchg/clipboard-formats), and [WIC memory streams](https://learn.microsoft.com/en-us/windows/win32/api/wincodec/nf-wincodec-iwicstream-initializefrommemory)

## Windows network ports (Task 8b)

`SocketFactory` uses a serial worker per start/stop generation and nonblocking Winsock calls. Every native socket call, including close, runs on that worker. Writes retain their vector until the entire buffer has been sent, and reads return EOF as empty success. Cancel removes a pending operation exactly once; cancelling a partially sent write closes the stream to avoid sending later frames after a truncated frame. Stop retires the generation asynchronously and cancels pending reads, writes, connects and listeners. A subsequent Listen/Connect creates a fresh generation. The listener is dual-stack and reports its OS-selected port before accepted sockets. Connect uses `AI_NUMERICHOST | AI_NUMERICSERV`, never hostname DNS resolution. Remote endpoints come from accept/getpeername, with mapped IPv4 normalization and IPv6 scope.

`DnsSd` publishes the bound port and supplied TXT pairs using `DnsServiceRegister` with `unicastEnabled = FALSE`. Publication succeeds only in its completion callback. Browsing enumerates real active multicast interfaces and starts a Win32 browse on each distinct interface index. PTR TTL=0 produces an interface-scoped removal. Each positive PTR starts a scoped resolve; a revision prevents a late resolve from resurrecting a removed record. Native SRV host/port, TXT, IPv4 and IPv6 populate ServiceRecord; link-local IPv6 receives the browse interface scope. Validation of device metadata stays in the core.

DNS requests and callback contexts retain their owning generation until native completion. BrowseCancel's final ERROR_CANCELLED releases browse context; resolve completion frees the returned instance, including after Stop. A successful registration racing with Stop is deregistered; cancellation never implies that the native context is immediately safe to free. Returned RR lists use DnsRecordListFree and instances use DnsServiceFreeInstance. Stop cancels outstanding native operations on the worker. If a native cancellation unexpectedly fails without a terminal callback, context is retained for safety; this can retain that generation until OS completion. Deregistration failures have no error result channel after the public void Stop.

`NetworkChange::Start(handler, ready)` registers NotifyIpInterfaceChange and reports registration status; it replaces any old watch. WindowsPlatform can call it when installing its network-change handler and call Stop for an empty handler. Notifications are forwarded from a worker; Stop marks them inert immediately and runs CancelMibChangeNotify2 away from the native callback. Native context survives until cancellation drains. A cancellation failure retains context rather than allowing a dangling native pointer. Start/Stop replacement is synchronized for concurrent callers.

Native APIs and ownership references:

- [closesocket ownership and concurrent-call restrictions](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-closesocket)
- [DnsServiceRegister request and mDNS flag](https://learn.microsoft.com/en-us/windows/win32/api/windns/ns-windns-dns_service_register_request)
- [Browse cancellation terminal callback](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsservicebrowsecancel)
- [Resolve cancellation](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsserviceresolvecancel)
- [Registration cancellation](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsserviceregistercancel)
- [Deregistration is asynchronous](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsservicederegister)
- [Browse result ownership](https://learn.microsoft.com/en-us/windows/win32/api/windns/nc-windns-dns_service_browse_callback)
- [Resolve instance ownership](https://learn.microsoft.com/en-us/windows/win32/api/windns/nc-windns-dns_service_resolve_complete)
- [Register instance ownership](https://learn.microsoft.com/en-us/windows/win32/api/windns/nc-windns-dns_service_register_complete)
- [NotifyIpInterfaceChange and callback/cancellation threading](https://learn.microsoft.com/en-us/windows/win32/api/netioapi/nf-netioapi-notifyipinterfacechange)

The ordinary windows_network CTest uses real TCP loopback, an injectable Win32 DNS
boundary (including native query normalization), and actual interface-change
registration. An optional same-host native DNS check runs with
`UNISHARE_LIVE_MDNS=1`; it advertises a unique temporary service and requires native
browse/resolve within 20 seconds. Task 11's independent public DLL consumers
provide measured cross-host discovery and transfer evidence. Hosts still configure
their own firewall/LAN policy; the test launchers do not change firewall rules.

- [SHGetKnownFolderPath current-user path and memory ownership](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath)
- [DnsServiceBrowse Windows 10 requirement and asynchronous contract](https://learn.microsoft.com/en-us/windows/win32/api/windns/nf-windns-dnsservicebrowse)

## Streamed text capacity

Use `ITransferService::SendTextStream(device, source, callback)` for text up to
`min(max_text_bytes, 8 MiB)`, including values larger than the 4 MiB shared
native buffer budget. `source.byte_count` is the exact UTF-8 byte count and
`source.stream` supplies bounded reads. Display name and MIME are normalized to
`text` and `text/plain;charset=utf-8`. The stream is retained until completion,
closed on all terminal/early-failure paths, and never read before acceptance.
The existing Text wire type, incremental UTF-8 validation, SHA-256, 256 KiB chunks,
and terminal receipt semantics apply. Invalid streamed UTF-8 is IntegrityFailed;
configured size overflow is ResourceLimit before the offer reaches the peer.

```cpp
unishare::TransferSource source;
source.byte_count = utf8_file_size;
source.stream = opened_source; // e.g. the existing Windows source-file adapter
sdk->Transfers()->SendTextStream(peer_id, std::move(source), on_terminal);
```

`SendText(device, std::string, callback)` remains the direct convenience API: it
charges retained string capacity plus its terminator to the shared budget and
can return ResourceLimit below `max_text_bytes`. Use the stream route for the
full configured capacity. This avoids implicit disk policy in the C++ core.

C callers use additive ABI v1 symbol `usdk_send_text_stream` with `usdk_source`;
release the registered source token immediately after successful submission if
desired. The core retains ownership until terminal completion. Deploy headers and
libraries together: older v1 libraries do not export this new symbol. Existing
v1 layouts and functions are unchanged. C++ consumers must rebuild with matching
headers/library. The original C direct text span is capped at 4 MiB. Clipboard's
direct value API remains limited by the shared budget and its C aggregate 4 MiB
copy cap; 8 MiB transfer capacity does not imply 8 MiB clipboard capacity.
