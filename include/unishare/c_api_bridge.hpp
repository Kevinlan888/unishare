#pragma once
#include "c_api.h"
#include "platform.hpp"
namespace unishare::abi {
// In-process adapter bridge, NOT a stable C++ binary ABI. Build adapters with
// the same toolchain/runtime as the core. C clients receive only integer
// tokens. The platform and its ports are retained until SDK destruction.
// Adapter I/O must retain its own state through late completions, complete each
// operation once, and use Result for normal failures. Stop/Close/Abort must not
// throw. SDK destruction cancels native operations but does not join external
// I/O. executor must enqueue (never invoke inline) exactly once and preserve
// order; it and permissions are retained by the SDK. Null executor selects SDK
// queue. Its queued tasks must eventually be drained, even after SDK
// destruction.
USDK_API usdk_error RegisterPlatform(std::shared_ptr<IPlatform>,
                                     std::shared_ptr<IExecutor>,
                                     std::shared_ptr<IPermissionAgent>,
                                     usdk_platform_handle *) noexcept;
// Native file/URI wrappers implement bounded asynchronous IStream reads/writes.
// A read result may not exceed its requested size; write buffers live through
// completion. AbortAndCleanup must remove partial destination data even with
// pending I/O. No entire-file buffering is introduced by this bridge.
USDK_API usdk_error RegisterStream(std::shared_ptr<IStream>,
                                   usdk_stream_handle *) noexcept;
} // namespace unishare::abi
