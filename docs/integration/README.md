# SDK integration

- [Windows C++ package](windows.md)
- [Android Kotlin AAR](android.md)
- [iOS Swift XCFramework](ios.md)
- [Public cross-platform test hosts](../../tests/interop/README.md)
- [Measured interoperability matrix](../../tests/interop/matrix.md)

Platform-local acceptance and package compilation are separate from LAN
interoperability. The matrix currently records Android→iOS Simulator acceptance
and an unresolved Windows browse timeout; it is not a three-platform release gate.

Text transfer supports the configured 8 MiB default through bounded streaming
on every platform while native buffers remain capped at 4 MiB. Kotlin/Swift
`sendText(String)` stage UTF-8 privately and clean up on terminal/Stop/Close;
Windows C++ uses `SendTextStream` with an exact-length source. The additive C ABI
v1 `usdk_send_text_stream` entry point preserves the existing wire Text type and
all previous ABI layouts/functions. Direct C++/C text and clipboard value APIs
remain constrained by available buffer capacity; clipboard is still manual and
does not advertise 8 MiB values. See each guide for ownership and failure rules.
