# SDK integration

- [Windows C++ package](windows.md)
- [Windows native application: staging, MSIX, and acceptance](windows-app.md)
- [Android Kotlin AAR](android.md)
- [Android application: debug build, tests, and blocked release gates](android-app.md)
- [iOS Swift XCFramework](ios.md)
- [Public cross-platform test hosts](../../tests/interop/README.md)
- [Measured interoperability matrix](../../tests/interop/matrix.md)

The public package hosts now exercise real LAN discovery, TLS/SAS confirmation,
recipient decisions, text, PNG/JPEG, empty and multichunk files, explicit clipboard
text/image, active cancellation and staging cleanup, trust across Stop/Start,
rediscovery, failure isolation and Offline. The matrix distinguishes physical
Pixel and iPhone results from iOS Simulator results. All six physical directions
passed; the matrix retains an earlier Windows→iPhone discovery timeout and the
scope of remaining acceptance.

Those historical SDK host results do not establish acceptance of the new Android
application. Its [current matrix](../../android/tests/app-acceptance.md) separates
automated API 37 emulator evidence from blocked API-version/operator gates and
all physical Android application ↔ Windows application scenarios.

Text transfer supports the configured 8 MiB default through bounded streaming
on every platform while native buffers remain capped at 4 MiB. Kotlin/Swift
`sendText(String)` stage UTF-8 privately and clean up on terminal/Stop/Close;
Windows C++ uses `SendTextStream` with an exact-length source. The additive C ABI
v1 `usdk_send_text_stream` entry point preserves the existing wire Text type and
all previous ABI layouts/functions. Direct C++/C text and clipboard value APIs
remain constrained by available buffer capacity; clipboard is still manual and
does not advertise 8 MiB values. See each guide for ownership and failure rules.

Windows browse queries are adapted to the native DNS-SD service-name form without
the final root dot; the public service type remains `_share._tcp.local.`.
Transfer IDs are random 128-bit values preserved across public events and both
peers' wire records. Deploy matching headers and binary packages together.
The session defers consent-qualified content through pairing write completion
and the queued connection handoff before delivering it to the transfer handler.

For receiving hosts, show each offer's kind and declared size before accepting,
provide locally generated staging destinations, and publish content only after
successful integrity verification. A cancelled connection must be reconnected
through the discovered DeviceId. After Stop/Start, wait for rediscovery before
using current listener metadata. Clipboard image encodings may change during OS
conversion; compare decoded pixels when validating clipboard roundtrips.
