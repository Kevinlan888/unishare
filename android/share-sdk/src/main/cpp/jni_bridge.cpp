#include "platform/android/adapter.hpp"
#include "c_api_internal.hpp"
namespace {
void Throw(JNIEnv* env){auto error=unishare::abi::detail::Exception();auto cls=env->FindClass("com/unishare/sdk/internal/NativePortException");if(!cls)return;
 auto constructor=env->GetMethodID(cls,"<init>","(IZJ)V");auto exception=static_cast<jthrowable>(env->NewObject(cls,constructor,static_cast<jint>(error.code),static_cast<jboolean>(error.has_platform_error),static_cast<jlong>(error.platform_error)));
 if(exception){env->Throw(exception);env->DeleteLocalRef(exception);}env->DeleteLocalRef(cls);}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_unishare_sdk_internal_NativeBridge_create(JNIEnv* env,jclass,jobject ports){try{auto platform=unishare::android::CreatePlatform(env,ports);usdk_platform_handle handle=0;auto error=unishare::abi::RegisterPlatform(platform,{},unishare::android::Permissions(platform),&handle);if(error.code)throw unishare::ShareError{static_cast<unishare::ErrorCode>(error.code),"register Android platform",{}};return handle;}catch(...){Throw(env);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_NativeBridge_release(JNIEnv* env,jclass,jlong handle){auto error=usdk_platform_release(handle);if(error.code){try{throw unishare::ShareError{static_cast<unishare::ErrorCode>(error.code),"release platform",{}};}catch(...){Throw(env);}}}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_NativeBridge_releaseStream(JNIEnv* env,jclass,jlong handle){auto error=usdk_stream_release(handle);if(error.code){try{throw unishare::ShareError{static_cast<unishare::ErrorCode>(error.code),"release stream",{}};}catch(...){Throw(env);}}}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_NativeBridge_abortStream(JNIEnv* env,jclass,jlong handle){try{unishare::abi::detail::Registry<unishare::IStream>::Get(handle)->AbortAndCleanup();}catch(...){Throw(env);}}
extern "C" JNIEXPORT jlong JNICALL Java_com_unishare_sdk_internal_NativeBridge_open(JNIEnv* env,jclass,jlong handle,jbyteArray uri,jboolean destination){try{auto owner=unishare::abi::detail::Registry<unishare::abi::detail::PlatformOwner>::Get(handle);
 auto length=env->GetArrayLength(uri);if(length>65536)throw unishare::ShareError{unishare::ErrorCode::InvalidInput,"URI length",{}};std::string value(length,'\0');env->GetByteArrayRegion(uri,0,length,reinterpret_cast<jbyte*>(value.data()));
 auto stream=destination?owner->platform->Streams().OpenDestination(value):owner->platform->Streams().OpenSource(value);if(!stream.ok())throw stream.error();usdk_stream_handle token=0;auto error=unishare::abi::RegisterStream(stream.value(),&token);if(error.code)throw unishare::ShareError{static_cast<unishare::ErrorCode>(error.code),"register URI stream",{}};return token;
 }catch(...){Throw(env);return 0;}}
