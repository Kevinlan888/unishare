#pragma once
#include "unishare/platform.hpp"
namespace unishare::windows {
// Raw Win32 clipboard boundary: production copies global memory while open;
// tests substitute it to avoid changing the desktop clipboard. No listeners.
// Format buffers use little-endian UTF-16 with a terminator, PNG/JFIF bytes,
// or packed DIB/DIBV5 (no BITMAPFILEHEADER).
enum class ClipboardFormat {UnicodeText,Png,Jpeg,DibV5,Dib};
struct ClipboardData {ClipboardFormat format;std::vector<std::uint8_t> bytes;};
class ClipboardBoundary {
public:
 virtual ~ClipboardBoundary()=default;
 virtual Result<std::vector<ClipboardData>> Read()=0;
 virtual Result<void> Write(std::vector<ClipboardData>)=0;
};
std::shared_ptr<ClipboardBoundary> CreateWin32ClipboardBoundary();
// Owns a private serial worker. Read/Write return immediately; the worker owns
// content and callback through exactly one completion, including after destruction.
// UTF-8 text must not contain NUL; images are PNG/JPEG, decoded before mutation.
// Decoded images are capped at 100 MiB. Writes publish DIBV5 plus PNG or JFIF.
class Clipboard final : public IPlatformClipboard {
 struct State;std::shared_ptr<State> state_;
public:
 explicit Clipboard(std::shared_ptr<ClipboardBoundary> = CreateWin32ClipboardBoundary());
 ~Clipboard();
 OperationId Read(ResultCallback<ShareContent>) override;
 OperationId Write(ShareContent,ResultCallback<void>) override;
};
}
