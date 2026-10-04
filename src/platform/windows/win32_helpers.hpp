#pragma once
#include "unishare/platform.hpp"
#include <windows.h>
#include <stdexcept>
namespace unishare::windows {
struct WinError { ShareError error; };
[[noreturn]] inline void Fail(const char* message,DWORD code=GetLastError()) {
 throw WinError{{ErrorCode::StorageFailed,message,code}};
}
struct Handle {
 HANDLE value=INVALID_HANDLE_VALUE;
 explicit Handle(HANDLE h=INVALID_HANDLE_VALUE):value(h){}
 ~Handle(){if(value!=INVALID_HANDLE_VALUE&&value)CloseHandle(value);}
 Handle(const Handle&)=delete;Handle& operator=(const Handle&)=delete;
 Handle(Handle&& other) noexcept:value(other.value){other.value=INVALID_HANDLE_VALUE;}
 HANDLE release(){auto h=value;value=INVALID_HANDLE_VALUE;return h;}
};
inline void RequireRegular(HANDLE h) {
 FILE_ATTRIBUTE_TAG_INFO info{};
 if(!GetFileInformationByHandleEx(h,FileAttributeTagInfo,&info,sizeof info))Fail("inspect file");
 if(info.FileAttributes&(FILE_ATTRIBUTE_REPARSE_POINT|FILE_ATTRIBUTE_DIRECTORY))Fail("regular file required",ERROR_INVALID_PARAMETER);
}
}
