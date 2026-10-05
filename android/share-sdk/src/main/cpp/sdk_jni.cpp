#include "c_api_internal.hpp"
#include <jni.h>
#include <cstring>
#include <vector>

namespace {
using Bytes = std::vector<uint8_t>;
using namespace unishare::abi::detail;
struct Env {
  JavaVM* vm; JNIEnv* env = nullptr; bool attached = false;
  explicit Env(JavaVM* v) : vm(v) {
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
      if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) throw std::bad_alloc();
      attached = true;
    }
  }
  ~Env() { if (attached) vm->DetachCurrentThread(); }
};
void Check(usdk_error error) { if (error.code) throw error; }
void Throw(JNIEnv* env) {
  usdk_error error;
  try { throw; } catch (usdk_error e) { error = e; } catch (...) { error = Exception(); }
  auto cls = env->FindClass("com/unishare/sdk/internal/NativePortException");
  if (!cls) return;
  auto constructor = env->GetMethodID(cls, "<init>", "(IZJ)V");
  auto value = static_cast<jthrowable>(env->NewObject(cls, constructor, static_cast<jint>(error.code),
      static_cast<jboolean>(error.has_platform_error), static_cast<jlong>(error.platform_error)));
  if (value) { env->Throw(value); env->DeleteLocalRef(value); }
  env->DeleteLocalRef(cls);
}
struct Packet {
  Bytes bytes; size_t offset = 0;
  void Put(uint64_t v, int count) { for (int i = count - 1; i >= 0; --i) bytes.push_back(static_cast<uint8_t>(v >> (i * 8))); }
  uint64_t Get(int count) {
    Require(static_cast<size_t>(count) <= bytes.size() - offset);
    uint64_t v = 0; while (count--) v = (v << 8) | bytes[offset++]; return v;
  }
  void Raw(const uint8_t* data, size_t size) { if (size) bytes.insert(bytes.end(), data, data + size); }
  void Span(usdk_span span) { Require(span.length <= INT32_MAX, USDK_RESOURCE_LIMIT); Put(span.length, 4); Raw(span.data, static_cast<size_t>(span.length)); }
  void Uuid(usdk_uuid uuid) { Raw(uuid.bytes, 16); }
  void Error(usdk_error e) { Put(e.code, 4); Put(e.has_platform_error != 0, 1); Put(static_cast<uint64_t>(e.platform_error), 8); }
  void Device(const usdk_device& d) {
    Uuid(d.id); Span(d.name); Put(d.platform, 4); Put(d.protocol_major, 4); Put(d.protocol_minor, 4);
    Put(d.port, 4); Put(d.paired != 0, 1); Put(static_cast<uint64_t>(d.last_seen_unix_ms), 8);
    Put(d.address_count, 4); for (uint64_t i = 0; i < d.address_count; ++i) Span(d.addresses[i]);
  }
};
Bytes Copy(JNIEnv* env, jbyteArray array) {
  Require(array); auto size = env->GetArrayLength(array); Bytes result(size);
  if (size) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(result.data()));
  if (env->ExceptionCheck()) throw std::bad_alloc();
  return result;
}
usdk_span Span(const Bytes& bytes) { return {bytes.data(), bytes.size()}; }
jbyteArray Array(JNIEnv* env, const Bytes& bytes) {
  Require(bytes.size() <= INT32_MAX, USDK_RESOURCE_LIMIT);
  auto array = env->NewByteArray(static_cast<jsize>(bytes.size())); if (!array) throw std::bad_alloc();
  if (!bytes.empty()) env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
  return array;
}
struct Owner;
struct Completion {
  std::weak_ptr<Owner> owner; uint64_t key;
  // Accepted decision completion only means the destination was attached. Before that,
  // a rejection/Stop/Close must abort staging rather than preserving an empty final file.
  std::shared_ptr<unishare::IStream> destination;
};
struct Owner : std::enable_shared_from_this<Owner> {
  JavaVM* vm; jobject receiver; jmethodID completion, event; usdk_handle sdk = 0;
  std::mutex mutex; std::map<uint64_t, std::shared_ptr<Completion>> pending;
  Owner(JNIEnv* env, jobject value) {
    env->GetJavaVM(&vm); receiver = env->NewGlobalRef(value); if (!receiver) throw std::bad_alloc();
    auto cls = env->GetObjectClass(value);
    completion = env->GetMethodID(cls, "completion", "(J[B)V"); event = env->GetMethodID(cls, "event", "([B)V");
    env->DeleteLocalRef(cls);
    if (!completion || !event) { env->DeleteGlobalRef(receiver); throw std::bad_alloc(); }
  }
  ~Owner() { try { Env e(vm); e.env->DeleteGlobalRef(receiver); } catch (...) {} }
  std::shared_ptr<Completion> Retain(uint64_t key) {
    auto context = std::make_shared<Completion>(Completion{shared_from_this(), key, {}});
    std::lock_guard<std::mutex> lock(mutex); Require(pending.emplace(key, context).second); return context;
  }
  void Forget(uint64_t key) { std::lock_guard<std::mutex> lock(mutex); pending.erase(key); }
  void Deliver(uint64_t key, const Packet& packet, bool isEvent) {
    Env e(vm); auto array = Array(e.env, packet.bytes);
    if (isEvent) e.env->CallVoidMethod(receiver, event, array);
    else e.env->CallVoidMethod(receiver, completion, static_cast<jlong>(key), array);
    e.env->DeleteLocalRef(array);
    // JNI callbacks enter the private queue only. Host callbacks never run here.
    if (e.env->ExceptionCheck()) e.env->ExceptionClear();
  }
};
void USDK_CALL Complete(void* raw, const usdk_result* result) noexcept {
  auto context = static_cast<Completion*>(raw);
  auto owner = context->owner.lock(); if (!owner) return;
  const auto key = context->key;
  try {
    if (result->error.code && context->destination) context->destination->AbortAndCleanup();
    Packet p; p.Error(result->error); p.Uuid(result->transfer); p.Put(result->content.type, 4);
    p.Span(result->content.text); p.Span(result->content.mime_type); p.Span(result->content.image);
    owner->Forget(key); owner->Deliver(key, p, false);
  } catch (...) {
    try { Packet p; p.Error({USDK_RESOURCE_LIMIT, 0, 0}); p.Uuid({}); p.Put(0, 4); p.Span({}); p.Span({}); p.Span({});
      owner->Forget(key); owner->Deliver(key, p, false); } catch (...) {}
  }
}
void USDK_CALL Event(void* raw, const usdk_event* event) noexcept {
  auto owner = static_cast<Owner*>(raw);
  try {
    Packet p; p.Put(event->kind, 4); p.Put(event->state, 4); p.Error(event->error); p.Put(event->request, 8);
    p.Uuid(event->transfer); p.Device(event->device); p.Span(event->verification_code); p.Put(event->content_type, 4);
    p.Span(event->display_name); p.Span(event->mime_type); p.Put(event->byte_count, 8); p.Put(event->transferred, 8);
    owner->Deliver(0, p, true);
  } catch (...) {}
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_unishare_sdk_internal_SdkNative_create(
    JNIEnv* env, jclass, jlong platform, jbyteArray config, jlong key, jobject receiver) {
  std::shared_ptr<Owner> owner; uint64_t token = 0;
  try {
    Packet p; p.bytes = Copy(env, config); auto size = p.Get(4); Require(size <= p.bytes.size() - p.offset);
    Bytes name(p.bytes.begin() + p.offset, p.bytes.begin() + p.offset + size); p.offset += size;
    usdk_config c{}; Check(usdk_config_init(1, &c)); c.device_name = Span(name);
    c.max_connections = p.Get(4); c.max_pending_requests = p.Get(4); c.max_buffer_bytes = p.Get(8);
    c.max_text_bytes = p.Get(8); c.max_image_bytes = p.Get(8); c.max_file_bytes = p.Get(8);
    c.control_frame_bytes = p.Get(4); c.data_chunk_bytes = p.Get(4); c.request_timeout_seconds = p.Get(4); c.offline_debounce_seconds = p.Get(4);
    Require(p.offset == p.bytes.size());
    owner = std::make_shared<Owner>(env, receiver); token = Registry<Owner>::Add(owner);
    auto context = owner->Retain(key); uint64_t operation = 0;
    Check(usdk_create(platform, &c, Complete, context.get(), &owner->sdk, &operation));
    Check(usdk_set_event_handler(owner->sdk, Event, owner.get()));
    return static_cast<jlong>(token);
  } catch (...) {
    if (owner && owner->sdk) usdk_destroy(owner->sdk);
    if (token) Registry<Owner>::Remove(token);
    Throw(env); return 0;
  }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_unishare_sdk_internal_SdkNative_submit(
    JNIEnv* env, jclass, jlong token, jint command, jbyteArray id, jlong request, jlong stream, jlong number,
    jbyteArray text, jbyteArray name, jbyteArray mime, jbyteArray image, jlong size, jlong key) {
  std::shared_ptr<Owner> owner;
  try {
    owner = Registry<Owner>::Get(token); auto uuidBytes = Copy(env, id); Require(uuidBytes.size() == 16);
    usdk_uuid uuid{}; memcpy(uuid.bytes, uuidBytes.data(), 16);
    auto t = Copy(env, text), n = Copy(env, name), m = Copy(env, mime), b = Copy(env, image);
    usdk_source source{static_cast<uint64_t>(stream), Span(n), Span(m), static_cast<uint64_t>(size)};
    usdk_content content{static_cast<uint32_t>(number), Span(t), Span(m), Span(b)};
    auto context = owner->Retain(key); uint64_t operation = 0; usdk_error error{};
    if (command == 10) context->destination = Registry<unishare::IStream>::Get(stream);
    switch (command) {
      case 1: error = usdk_start(owner->sdk, Complete, context.get(), &operation); break;
      case 2: error = usdk_stop(owner->sdk, Complete, context.get(), &operation); break;
      case 3: error = usdk_connect(owner->sdk, uuid, Complete, context.get(), &operation); break;
      case 4: error = usdk_confirm_pairing(owner->sdk, request, number, Complete, context.get(), &operation); break;
      case 5: error = usdk_reject_pairing(owner->sdk, request, Complete, context.get(), &operation); break;
      case 6: error = usdk_forget_device(owner->sdk, uuid, Complete, context.get(), &operation); break;
      case 7: error = usdk_send_text(owner->sdk, uuid, Span(t), Complete, context.get(), &operation); break;
      case 15: error = usdk_send_text_stream(owner->sdk, uuid, &source, Complete, context.get(), &operation); break;
      case 8: error = usdk_send_image(owner->sdk, uuid, &source, Complete, context.get(), &operation); break;
      case 9: error = usdk_send_file(owner->sdk, uuid, &source, Complete, context.get(), &operation); break;
      case 10: error = usdk_accept(owner->sdk, request, stream, Complete, context.get(), &operation); break;
      case 11: error = usdk_reject(owner->sdk, request, Complete, context.get(), &operation); break;
      case 12: error = usdk_cancel(owner->sdk, uuid, Complete, context.get(), &operation); break;
      case 13: error = usdk_clipboard_read(owner->sdk, Complete, context.get(), &operation); break;
      case 14: error = usdk_clipboard_write(owner->sdk, &content, Complete, context.get(), &operation); break;
      default: error = {USDK_INVALID_INPUT, 0, 0};
    }
    if (error.code) { if (context->destination) context->destination->AbortAndCleanup(); owner->Forget(key); Check(error); }
    return static_cast<jlong>(operation);
  } catch (...) { if (owner) owner->Forget(key); Throw(env); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_unishare_sdk_internal_SdkNative_snapshot(JNIEnv* env, jclass, jlong token, jboolean trusted) {
  try {
    auto owner = Registry<Owner>::Get(token); Packet p;
    if (trusted) {
      usdk_trusted_devices* root = nullptr; Check(usdk_get_trusted_devices(owner->sdk, &root));
      try { p.Put(root->count, 4); for (uint64_t i = 0; i < root->count; ++i) { p.Uuid(root->items[i].id); p.Raw(root->items[i].public_key_fingerprint, 32); } }
      catch (...) { usdk_free(root); throw; } Check(usdk_free(root));
    } else {
      usdk_devices* root = nullptr; Check(usdk_get_devices(owner->sdk, &root));
      try { p.Put(root->count, 4); for (uint64_t i = 0; i < root->count; ++i) p.Device(root->items[i]); }
      catch (...) { usdk_free(root); throw; } Check(usdk_free(root));
    }
    return Array(env, p.bytes);
  } catch (...) { Throw(env); return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL Java_com_unishare_sdk_internal_SdkNative_state(JNIEnv* env, jclass, jlong token) {
  try { uint32_t state = 0; Check(usdk_get_state(Registry<Owner>::Get(token)->sdk, &state)); return state; }
  catch (...) { Throw(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_unishare_sdk_internal_SdkNative_destroy(JNIEnv* env, jclass, jlong token) {
  try {
    auto owner = Registry<Owner>::Remove(token); Check(usdk_destroy(owner->sdk));
    for (auto& item : owner->pending) if (item.second->destination) item.second->destination->AbortAndCleanup();
    owner->pending.clear();
  }
  catch (...) { Throw(env); }
}
