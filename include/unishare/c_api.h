#ifndef UNISHARE_C_API_H
#define UNISHARE_C_API_H
#include <stdint.h>
#if defined(_WIN32) && defined(USDK_SHARED)
#if defined(USDK_BUILD)
#define USDK_API __declspec(dllexport)
#else
#define USDK_API __declspec(dllimport)
#endif
#else
#define USDK_API
#endif
#ifdef _WIN32
#define USDK_CALL __cdecl
#else
#define USDK_CALL
#endif
#ifdef __cplusplus
extern "C" {
#endif
/* ABI 1 is frozen: existing layouts, values, calling convention and semantics
 * never change. Incompatible extensions require a new ABI version and symbols.
 * The library accepts only USDK_ABI_VERSION and sizeof(usdk_config) for v1.
 * All handles are opaque registry tokens, never addresses; 0 is invalid.
 * Request/transfer IDs are scoped to their SDK instance. Output parameters
 * must use distinct storage. Input spans are copied before submission returns.
 * All pointers must reference accessible storage for the stated length. Null
 * with length zero is valid. UTF-8 spans are not NUL terminated; embedded NUL
 * is data. SDK validates lengths/UTF-8, not arbitrary memory addresses.
 * Accepted asynchronous calls return code=0 and a nonzero operation ID; their
 * terminal result runs once on the configured executor. Rejected calls return
 * an error, set output handles/IDs to zero, and do not call the callback.
 * Wait for successful create completion before submitting service commands.
 * Start/Stop govern all services together; snapshots are synchronous.
 * Callbacks may run concurrently with the submitting call, but never inline on
 * that call's stack. Callback data is borrowed only for the duration of the
 * call. Destroy invalidates all undelivered callbacks and waits for callbacks
 * already running on other threads; destroying/unregistering inside a callback
 * is safe. After destroy returns, callback contexts may be released (after the
 * current callback returns when destroying from that callback). Stop instead
 * completes pending callbacks with Cancelled. No C++ exception crosses any C
 * entry point.
 */
#define USDK_ABI_VERSION UINT32_C(1)
typedef uint64_t usdk_handle;
typedef uint64_t usdk_platform_handle;
typedef uint64_t usdk_stream_handle;
typedef uint64_t usdk_operation_id;
typedef uint64_t usdk_request_id;
typedef struct usdk_uuid {
  uint8_t bytes[16];
} usdk_uuid;
typedef struct usdk_span {
  const uint8_t *data;
  uint64_t length;
} usdk_span;
#define USDK_OK 0u
#define USDK_PERMISSION_DENIED 1u
#define USDK_DISCOVERY_FAILED 2u
#define USDK_VERSION_MISMATCH 3u
#define USDK_PAIRING_REJECTED 4u
#define USDK_AUTHENTICATION_FAILED 5u
#define USDK_IDENTITY_CHANGED 6u
#define USDK_TRANSFER_REJECTED 7u
#define USDK_CANCELLED 8u
#define USDK_TIMEOUT 9u
#define USDK_NETWORK_LOST 10u
#define USDK_STORAGE_FAILED 11u
#define USDK_INTEGRITY_FAILED 12u
#define USDK_INVALID_INPUT 13u
#define USDK_RESOURCE_LIMIT 14u
typedef struct usdk_error {
  uint32_t code;
  uint32_t has_platform_error;
  int64_t platform_error;
} usdk_error;
#define USDK_TEXT 1u
#define USDK_IMAGE 2u
#define USDK_FILE 3u
typedef struct usdk_content {
  uint32_t type;
  usdk_span text;
  usdk_span mime_type;
  usdk_span image;
} usdk_content;
typedef struct usdk_config {
  uint32_t abi_version;
  uint32_t struct_size;
  usdk_span device_name;
  uint32_t max_connections;
  uint32_t max_pending_requests;
  uint64_t max_buffer_bytes;
  uint64_t max_text_bytes;
  uint64_t max_image_bytes;
  uint64_t max_file_bytes;
  uint32_t control_frame_bytes;
  uint32_t data_chunk_bytes;
  uint32_t request_timeout_seconds;
  uint32_t offline_debounce_seconds;
} usdk_config;
typedef struct usdk_device {
  usdk_uuid id;
  usdk_span name;
  uint32_t platform; /* Windows=0, Android=1, iOS=2 */
  uint16_t protocol_major;
  uint16_t protocol_minor;
  uint16_t port;
  uint16_t paired;
  int64_t last_seen_unix_ms;
  const usdk_span *addresses;
  uint64_t address_count;
} usdk_device;
/* Snapshot roots and all nested storage remain valid until usdk_free(root).
 * Only pass a live root to free; free(NULL) succeeds. After free, the root and
 * all nested pointers are invalid and must never be used or freed again.
 * Detection of stale pointers is not guaranteed: allocator addresses may be
 * reused. A pointer absent from the live allocation registry is rejected.
 */
typedef struct usdk_devices {
  const usdk_device *items;
  uint64_t count;
} usdk_devices;
typedef struct usdk_trusted_device {
  usdk_uuid id;
  uint8_t public_key_fingerprint[32];
} usdk_trusted_device;
typedef struct usdk_trusted_devices {
  const usdk_trusted_device *items;
  uint64_t count;
} usdk_trusted_devices;
typedef struct usdk_result {
  usdk_operation_id operation;
  usdk_error error;
  usdk_uuid transfer;
  usdk_content content; /* populated only by successful clipboard read */
} usdk_result;
typedef void(USDK_CALL *usdk_completion)(void *context,
                                         const usdk_result *result);
#define USDK_EVENT_ONLINE 1u
#define USDK_EVENT_UPDATED 2u
#define USDK_EVENT_OFFLINE 3u
#define USDK_EVENT_DISCOVERY_ERROR 4u
#define USDK_EVENT_DISCOVERY_STATE 5u
#define USDK_EVENT_CONNECTION 6u
#define USDK_EVENT_PAIRING_REQUEST 7u
#define USDK_EVENT_PAIRING_RESULT 8u
#define USDK_EVENT_IDENTITY_CHANGED 9u
#define USDK_EVENT_INCOMING_OFFER 10u
#define USDK_EVENT_PROGRESS 11u
#define USDK_EVENT_TRANSFER_RESULT 12u
typedef struct usdk_event {
  uint32_t kind;
  uint32_t state; /* discovery: 0 starting,1 browsing,2 changed,3 stopped;
                     connection: 0 connected,1 disconnected */
  usdk_error error;
  usdk_request_id request;
  usdk_uuid transfer;
  usdk_device device;
  usdk_span verification_code;
  uint32_t content_type;
  usdk_span display_name;
  usdk_span mime_type;
  uint64_t byte_count;
  uint64_t transferred;
} usdk_event;
typedef void(USDK_CALL *usdk_event_handler)(void *context,
                                            const usdk_event *event);
/* Diagnostic records contain only fixed numeric fields. A sequence is local
 * to one SDK process and must not be interpreted as a device/transfer ID. */
#define USDK_DIAGNOSTIC_PERMISSION 1u
#define USDK_DIAGNOSTIC_LISTEN 2u
#define USDK_DIAGNOSTIC_PUBLISH 3u
#define USDK_DIAGNOSTIC_BROWSE 4u
#define USDK_DIAGNOSTIC_NETWORK_CHANGE 5u
#define USDK_DIAGNOSTIC_STOP 6u
#define USDK_DIAGNOSTIC_CONNECT 7u
#define USDK_DIAGNOSTIC_SOCKET_CONNECT 8u
#define USDK_DIAGNOSTIC_TLS_HANDSHAKE 9u
#define USDK_DIAGNOSTIC_IDENTITY_VERIFY 10u
#define USDK_DIAGNOSTIC_PAIRING 11u
#define USDK_DIAGNOSTIC_OFFER 12u
#define USDK_DIAGNOSTIC_ACCEPT 13u
#define USDK_DIAGNOSTIC_TRANSFER_STREAM 14u
#define USDK_DIAGNOSTIC_INTEGRITY_VERIFY 15u
#define USDK_DIAGNOSTIC_PEER_COMPLETE 16u
#define USDK_DIAGNOSTIC_CANCEL 17u
#define USDK_DIAGNOSTIC_TRANSFER 18u
#define USDK_DIAGNOSTIC_BEGAN 1u
#define USDK_DIAGNOSTIC_SUCCEEDED 2u
#define USDK_DIAGNOSTIC_FAILED 3u
#define USDK_DIAGNOSTIC_CANCELLED 4u
#define USDK_DIAGNOSTIC_NONE 0u
#define USDK_DIAGNOSTIC_SEND 1u
#define USDK_DIAGNOSTIC_RECEIVE 2u
#define USDK_DIAGNOSTIC_TEXT 1u
#define USDK_DIAGNOSTIC_IMAGE 2u
#define USDK_DIAGNOSTIC_FILE 3u
typedef struct usdk_diagnostic_event {
  uint32_t stage;
  uint32_t outcome;
  uint32_t direction;
  uint32_t content_type;
  uint64_t sequence;
  usdk_error error;
} usdk_diagnostic_event;
typedef void(USDK_CALL *usdk_diagnostic_handler)(void *context,
                                                  const usdk_diagnostic_event *event);
typedef struct usdk_source {
  usdk_stream_handle stream;
  usdk_span display_name;
  usdk_span mime_type;
  uint64_t byte_count;
} usdk_source;
USDK_API uint32_t USDK_CALL usdk_abi_version(void);
USDK_API usdk_error USDK_CALL usdk_config_init(uint32_t version,
                                               usdk_config *config);
USDK_API usdk_error USDK_CALL usdk_create(usdk_platform_handle platform,
                                          const usdk_config *config,
                                          usdk_completion callback,
                                          void *context, usdk_handle *sdk,
                                          usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_start(usdk_handle sdk,
                                         usdk_completion callback,
                                         void *context,
                                         usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_stop(usdk_handle sdk,
                                        usdk_completion callback, void *context,
                                        usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_destroy(usdk_handle sdk);
USDK_API usdk_error USDK_CALL usdk_get_state(
    usdk_handle sdk,
    uint32_t *state); /* 0 uninitialized,1 initialized,2 running,3 stopped */
/* Set one observer; NULL unregisters. Replacement invalidates queued events
 * for the previous registration, and waits for any callback already running. */
USDK_API usdk_error USDK_CALL usdk_set_event_handler(usdk_handle sdk,
                                                     usdk_event_handler handler,
                                                     void *context);
/* NULL unregisters; replacement and destroy wait for in-flight delivery. */
USDK_API usdk_error USDK_CALL usdk_set_diagnostic_handler(
    usdk_handle sdk, usdk_diagnostic_handler handler, void *context);
USDK_API usdk_error USDK_CALL usdk_get_devices(usdk_handle sdk,
                                               usdk_devices **result);
USDK_API usdk_error USDK_CALL
usdk_get_trusted_devices(usdk_handle sdk, usdk_trusted_devices **result);
USDK_API usdk_error USDK_CALL usdk_connect(usdk_handle sdk, usdk_uuid device,
                                           usdk_completion callback,
                                           void *context,
                                           usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_confirm_pairing(
    usdk_handle sdk, usdk_request_id request, uint32_t code_matches,
    usdk_completion callback, void *context, usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_reject_pairing(usdk_handle sdk,
                                                  usdk_request_id request,
                                                  usdk_completion callback,
                                                  void *context,
                                                  usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_forget_device(usdk_handle sdk,
                                                 usdk_uuid device,
                                                 usdk_completion callback,
                                                 void *context,
                                                 usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_send_text(usdk_handle sdk, usdk_uuid device,
                                             usdk_span text,
                                             usdk_completion callback,
                                             void *context,
                                             usdk_operation_id *operation);
/* The SDK retains streams through completion/cleanup. The caller may release
 * its handle immediately after successful submission. Use each stream for one
 * transfer only; source Close preserves source data, sink Abort removes
 * staging. Releasing a handle never closes a stream still retained by the SDK.
 */
/* Additive v1 extension. Bounded UTF-8 stream, byte_count <= min(max_text_bytes, 8 MiB).
 * Normalizes Text metadata; retains the stream before returning success. Caller
 * may release its token immediately. The terminal callback owns no span data.
 * Requires a library exporting this symbol; existing v1 entry points unchanged. */
USDK_API usdk_error USDK_CALL usdk_send_text_stream(usdk_handle sdk, usdk_uuid device,
    const usdk_source* source, usdk_completion callback, void* context,
    usdk_operation_id* operation);
USDK_API usdk_error USDK_CALL usdk_send_image(usdk_handle sdk, usdk_uuid device,
                                              const usdk_source *source,
                                              usdk_completion callback,
                                              void *context,
                                              usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_send_file(usdk_handle sdk, usdk_uuid device,
                                             const usdk_source *source,
                                             usdk_completion callback,
                                             void *context,
                                             usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_accept(
    usdk_handle sdk, usdk_request_id request, usdk_stream_handle destination,
    usdk_completion callback, void *context, usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_reject(usdk_handle sdk,
                                          usdk_request_id request,
                                          usdk_completion callback,
                                          void *context,
                                          usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_cancel(usdk_handle sdk, usdk_uuid transfer,
                                          usdk_completion callback,
                                          void *context,
                                          usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_clipboard_read(usdk_handle sdk,
                                                  usdk_completion callback,
                                                  void *context,
                                                  usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL usdk_clipboard_write(
    usdk_handle sdk, const usdk_content *content, usdk_completion callback,
    void *context, usdk_operation_id *operation);
USDK_API usdk_error USDK_CALL
usdk_platform_release(usdk_platform_handle platform);
USDK_API usdk_error USDK_CALL usdk_stream_release(usdk_stream_handle stream);
USDK_API usdk_error USDK_CALL usdk_free(void *allocation);
#ifdef __cplusplus
}
#endif
#endif
