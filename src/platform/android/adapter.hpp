#pragma once
#include <jni.h>
#include "unishare/platform.hpp"
namespace unishare::android {
std::shared_ptr<IPlatform> CreatePlatform(JNIEnv*,jobject framework);
std::shared_ptr<IPermissionAgent> Permissions(const std::shared_ptr<IPlatform>&);
}
