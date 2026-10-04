#include <unishare/windows.hpp>
#include <future>
#include <iostream>
extern "C" int check_c_abi(void);
int main(){
 if(!check_c_abi())return 1;
 unishare::SdkConfig config;config.device_name="Installed Windows package";
 unishare::WindowsOptions options;options.identity_directory=std::filesystem::current_path()/"installed-consumer-identity";
 auto sdk=unishare::CreateWindowsShareSdk(config,options);if(!sdk)return 2;
 std::promise<bool> completed;auto future=completed.get_future();
 sdk->Initialize({},[&](auto r){completed.set_value(r.ok());});
 if(future.wait_for(std::chrono::seconds(10))!=std::future_status::ready||!future.get())return 3;
 std::cout<<"Installed C/C++ DLL consumer passed, ABI "<<usdk_abi_version()<<"\n";
}
