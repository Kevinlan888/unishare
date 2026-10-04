#pragma once
#include "share_sdk.hpp"
#include "c_api.h"
#include <filesystem>
namespace unishare {
// Empty selects current user's LocalAppData/UniShare. An explicit path must be
// absolute with an existing trusted parent; each installation needs its own path.
struct WindowsOptions { std::filesystem::path identity_directory; };
// Returns an uninitialized SDK; performs no identity or network I/O. Initialize
// remains asynchronous. Initialize(SdkConfig{}, cb) uses these factory defaults;
// any other configuration replaces ALL defaults, without partial field merging.
// Construction can throw ShareError/allocation exceptions; C entry points do not.
USDK_API std::unique_ptr<IShareSdk> CreateWindowsShareSdk(SdkConfig,WindowsOptions = {});
// Borrow a host-selected disk HANDLE; the resulting stream owns a reopened handle.
// Close the original after success. Destination requires empty staging, read/write/
// DELETE access and compatible sharing. A display filename is never a path.
USDK_API Result<std::shared_ptr<IStream>> OpenWindowsSource(void* native_handle);
USDK_API Result<std::shared_ptr<IStream>> OpenWindowsDestination(void* native_handle);
}
