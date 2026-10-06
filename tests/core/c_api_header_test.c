#include "unishare/c_api.h"
#include <stddef.h>
_Static_assert(sizeof(usdk_handle) == 8, "fixed width handles");
_Static_assert(sizeof(usdk_uuid) == 16, "UUID wire value");
_Static_assert(sizeof(usdk_diagnostic_event) == 40, "diagnostic ABI layout");
_Static_assert(offsetof(usdk_diagnostic_event, stage) == 0, "stage offset");
_Static_assert(offsetof(usdk_diagnostic_event, sequence) == 16, "sequence offset");
_Static_assert(offsetof(usdk_diagnostic_event, error) == 24, "error offset");
_Static_assert(USDK_DIAGNOSTIC_TRANSFER == 18u, "stable stage values");
_Static_assert(USDK_DIAGNOSTIC_CANCELLED == 4u, "stable outcome values");
_Static_assert(USDK_DIAGNOSTIC_RECEIVE == 2u, "stable direction values");
_Static_assert(USDK_DIAGNOSTIC_FILE == 3u, "stable content values");
int main(void) {
  usdk_config config;
  usdk_error e = usdk_config_init(USDK_ABI_VERSION, &config);
  if (e.code || config.abi_version != usdk_abi_version())
    return 1;
  if (usdk_start(0, 0, 0, 0).code != USDK_INVALID_INPUT)
    return 2;
  if (usdk_set_diagnostic_handler(0, NULL, NULL).code != USDK_INVALID_INPUT)
    return 4;
  return usdk_free(NULL).code ? 3 : 0;
}
