#include <unishare/windows.h>
int check_c_abi(void){
 usdk_config config;
 return usdk_abi_version()==USDK_ABI_VERSION&&usdk_config_init(USDK_ABI_VERSION,&config).code==0;
}
