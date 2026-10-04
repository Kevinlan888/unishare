#ifndef UNISHARE_WINDOWS_H
#define UNISHARE_WINDOWS_H
#include "c_api.h"
#ifdef __cplusplus
extern "C" {
#endif
/* UTF-8 absolute per-user identity directory; empty selects LocalAppData/UniShare.
   A returned platform token may be released after usdk_create accepts it. */
USDK_API usdk_error USDK_CALL usdk_windows_platform_create(usdk_span directory,usdk_platform_handle*);
/* Native HANDLE is borrowed for registration only; close the original on success.
   See windows.md for access/sharing/staging requirements. */
USDK_API usdk_error USDK_CALL usdk_windows_open_source(void* native_handle,usdk_stream_handle*);
USDK_API usdk_error USDK_CALL usdk_windows_open_destination(void* native_handle,usdk_stream_handle*);
#ifdef __cplusplus
}
#endif
#endif
