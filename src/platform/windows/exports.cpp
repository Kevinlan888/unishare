#include "unishare/windows.h"
#include "adapter.hpp"
#include "c_api_internal.hpp"
namespace unishare {
Result<std::shared_ptr<IStream>> OpenWindowsSource(void* handle){
 try{windows::StreamFactory streams;auto token=streams.RegisterSource(handle);
  if(!token.ok())return Result<std::shared_ptr<IStream>>::Failure(token.error());
  return streams.OpenSource(token.value());
 }catch(...){return Result<std::shared_ptr<IStream>>::Failure(core::CurrentException());}
}
Result<std::shared_ptr<IStream>> OpenWindowsDestination(void* handle){
 try{windows::StreamFactory streams;auto token=streams.RegisterDestination(handle);
  if(!token.ok())return Result<std::shared_ptr<IStream>>::Failure(token.error());
  return streams.OpenDestination(token.value());
 }catch(...){return Result<std::shared_ptr<IStream>>::Failure(core::CurrentException());}
}
}
extern "C" {
usdk_error USDK_CALL usdk_windows_platform_create(usdk_span directory,usdk_platform_handle* out){
 using namespace unishare;using namespace unishare::abi::detail;
 if(out)*out=0;
 return Boundary([&]{
  Require(out!=nullptr);auto text=String(directory);Require(text.find('\0')==std::string::npos);
  WindowsOptions options;options.identity_directory=std::filesystem::u8path(text);
  auto platform=std::make_shared<windows::WindowsPlatform>(std::move(options));
  auto error=abi::RegisterPlatform(platform,platform->DefaultExecutor(),platform->Permissions(),out);
  if(error.code)throw ShareError{static_cast<ErrorCode>(error.code),"register Windows platform",error.has_platform_error?std::optional<int64_t>(error.platform_error):std::nullopt};
 });
}
usdk_error USDK_CALL usdk_windows_open_source(void* handle,usdk_stream_handle* out){
 using namespace unishare;using namespace unishare::abi::detail;
 if(out)*out=0;
 return Boundary([&]{Require(out!=nullptr);auto stream=OpenWindowsSource(handle);if(!stream.ok())throw stream.error();
  auto error=abi::RegisterStream(stream.value(),out);if(error.code)throw ShareError{static_cast<ErrorCode>(error.code),"register stream",{}};
 });
}
usdk_error USDK_CALL usdk_windows_open_destination(void* handle,usdk_stream_handle* out){
 using namespace unishare;using namespace unishare::abi::detail;
 if(out)*out=0;
 return Boundary([&]{Require(out!=nullptr);auto stream=OpenWindowsDestination(handle);if(!stream.ok())throw stream.error();
  auto error=abi::RegisterStream(stream.value(),out);if(error.code)throw ShareError{static_cast<ErrorCode>(error.code),"register stream",{}};
 });
}
}
