# Windows local ports

Task 8a supplies `unishare_windows_ports`, a Windows-only internal C++ target for Windows 10 desktop and later. It contains `windows::IdentityStore`, `windows::StreamFactory`, and `windows::Clipboard`. Network discovery, sockets, the assembled `WindowsPlatform`, and the public Windows package are subsequent work.

## Identity and trust

Construct `IdentityStore` with an absolute path to a dedicated per-user directory on a local NTFS volume, under an existing trusted parent (for example the application's LocalAppData directory). The adapter creates the final directory and restricts its DACL to the current process user, with inheritance for its records. Do not use an arbitrary shared or attacker-controlled parent. Final-component reparse points and non-regular record/lock files are rejected.

A user-scoped DPAPI blob contains the DeviceId, DER PKCS#8 private key, DER X.509 certificate, and trust bindings. No machine-wide DPAPI option is used. A byte-range file lock serializes readers and mutations across instances/processes. Mutations write a new private file, flush it, and perform a write-through replacement in the same directory. Failed replacement leaves the preceding record intact. A crash before replacement can leave an encrypted `.tmp` file; it is never treated as committed identity. The SDK does not silently replace unreadable/corrupt identity.

The filesystem and hardware must honor Windows flush semantics. Tests exercise real files, DPAPI, access errors and replacement rollback; they do not simulate physical power loss. Hosts must not modify the store while the SDK is using it. Win32 error codes are retained in `platform_error`.

## Source and destination handles

Register a native regular disk-file handle with `RegisterSource` or `RegisterDestination`, then pass the returned single-use token to `OpenSource` or `OpenDestination`. Tokens belong to that factory. A remote/display filename is never accepted as a path.

The factory uses `ReOpenFile` to obtain its own handle with `FILE_FLAG_OVERLAPPED` and an independent offset starting at zero. The original handle may be synchronous. Open the original with compatible `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE`; close it after successful registration. The host must not write to the registered file while the stream is active. Source requires read access. Destination must be a host-selected, empty staging file that permits read, write, and DELETE access; it must not have delete-on-close set. Final publication/rename of the staged file belongs to the host.

Each stream has a private serial worker. Read/Write enqueue work and callbacks; they do not execute disk I/O on SDK core/network queues. Reads return at most 4 MiB per call, use 64-bit offsets, and return empty bytes only for EOF. A zero read request is invalid. Write takes ownership of its buffer, writes in bounded chunks, and flushes before success. Opening a destination flushes the empty file, so zero-byte transfers need no fallible action at Close. Platform callbacks run on the worker; the SDK then applies its public callback-executor policy. Callbacks must not block waiting for more work on the same stream.

Call Close after all writes have completed successfully. It seals the stream and queues handle release, without a flush or rename. Abort publishes cancellation, calls `CancelIoEx`, waits for issued I/O completion on the worker, then marks the staging file for deletion by handle and closes it. Buffers/OVERLAPPED structures survive until completion; queued operations complete once. Destroying an unsealed stream aborts it. Destruction of the factory cleans up unused destination registrations.

An OS deletion failure (for example a file made read-only) is returned as StorageFailed to outstanding callbacks. The public `void AbortAndCleanup()` signature has no error channel after every callback has already finished; factory destruction has the same limitation. The adapter cannot promise deletion against external permission/attribute changes. The host must keep staging under its control and may reconcile abandoned staging files at startup. Source files are never deleted by stream abort.

## Explicit clipboard access

`Clipboard` uses a private worker and a Win32 clipboard boundary. It has no listener, polling or automatic synchronization. UTF-8 text is validated and published as terminated CF_UNICODETEXT. Embedded NULs and malformed UTF-8/UTF-16 are rejected. PNG and JPEG bytes are decoded with WIC before clipboard mutation; images are published in the registered PNG/JFIF format plus an interoperable CF_DIBV5 representation. Image MIME must match the container. Encoded and decoded images are limited to 100 MiB.

Reads support Unicode text, PNG, JFIF, and common uncompressed 24/32-bit DIB/DIBV5 images. Unsupported bitmap encodings, profiles, or malformed payloads return InvalidInput. DIB images are encoded as PNG. Clipboard denial retains the Win32 code. The boundary creates a temporary message-only owner window, uses GMEM_MOVEABLE, and transfers allocation ownership only after successful SetClipboardData. A failure during a multi-format clipboard publication may leave the formats already published; Win32 clipboard publication is not transactional.

Tests substitute only the OS clipboard boundary; UTF conversion and WIC codecs remain real. No live desktop clipboard test has been run because it would require capturing/restoring every native and delayed-rendered format reliably. Cross-application clipboard behavior remains an integration gate.

## Microsoft API references

- [DPAPI protection](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata) and [unprotection](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptunprotectdata)
- [LockFileEx](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex), [FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers), and [MoveFileEx](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw)
- [ReOpenFile](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-reopenfile), [CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex), and [SetFileInformationByHandle](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfileinformationbyhandle)
- [SetClipboardData ownership](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setclipboarddata), [clipboard formats](https://learn.microsoft.com/en-us/windows/win32/dataxchg/clipboard-formats), and [WIC memory streams](https://learn.microsoft.com/en-us/windows/win32/api/wincodec/nf-wincodec-iwicstream-initializefrommemory)
