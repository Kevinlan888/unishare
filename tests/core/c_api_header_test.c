#include "unishare/c_api.h"
#include <stddef.h>
_Static_assert(sizeof(usdk_handle) == 8, "fixed width handles");
_Static_assert(sizeof(usdk_uuid) == 16, "UUID wire value");
int main(void) {
  usdk_config config;
  usdk_error e = usdk_config_init(USDK_ABI_VERSION, &config);
  if (e.code || config.abi_version != usdk_abi_version())
    return 1;
  if (usdk_start(0, 0, 0, 0).code != USDK_INVALID_INPUT)
    return 2;
  return usdk_free(NULL).code ? 3 : 0;
}
