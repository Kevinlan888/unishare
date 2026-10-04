#pragma once
#include "core/service_support.hpp"
#include "core/share_sdk.hpp"
#include "unishare/c_api_bridge.hpp"
#include <atomic>
#include <limits>
#include <map>
#include <mutex>
#include <system_error>
namespace unishare::abi::detail {
inline usdk_error Error(uint32_t c) { return {c, 0, 0}; }
inline usdk_error Error(const ShareError &e) {
  return {static_cast<uint32_t>(e.code), e.platform_error ? 1u : 0u,
          e.platform_error.value_or(0)};
}
inline usdk_error Exception() noexcept {
  try {
    throw;
  } catch (const ShareError &e) {
    return Error(e);
  } catch (const std::bad_alloc &) {
    return Error(USDK_RESOURCE_LIMIT);
  } catch (const std::system_error &e) {
    return {USDK_STORAGE_FAILED, 1, e.code().value()};
  } catch (...) {
    return Error(USDK_INVALID_INPUT);
  }
}
inline void Require(bool ok, uint32_t code = USDK_INVALID_INPUT) {
  if (!ok)
    throw ShareError{static_cast<ErrorCode>(code), "C ABI input", {}};
}
template <class F> usdk_error Boundary(F f) noexcept {
  try {
    f();
    return {};
  } catch (...) {
    return Exception();
  }
}
uint64_t Next();
template <class T> struct Registry {
  inline static std::mutex mutex;
  inline static std::map<uint64_t, std::shared_ptr<T>> entries;
  static uint64_t Add(std::shared_ptr<T> value) {
    auto id = Next();
    std::lock_guard<std::mutex> lock(mutex);
    entries.emplace(id, std::move(value));
    return id;
  }
  static std::shared_ptr<T> Get(uint64_t id) {
    std::lock_guard<std::mutex> lock(mutex);
    auto i = entries.find(id);
    Require(i != entries.end());
    return i->second;
  }
  static std::shared_ptr<T> Remove(uint64_t id) {
    std::lock_guard<std::mutex> lock(mutex);
    auto i = entries.find(id);
    Require(i != entries.end());
    auto result = std::move(i->second);
    entries.erase(i);
    return result;
  }
};
struct PlatformOwner {
  std::shared_ptr<IPlatform> platform;
  std::shared_ptr<IExecutor> executor;
  std::shared_ptr<IPermissionAgent> permissions;
};
struct State {
  // Commands only take gate briefly. Application code runs under callbacks,
  // never gate, so another thread may submit while a callback waits for it.
  std::mutex gate;
  std::recursive_mutex callbacks;
  bool alive = true;
  uint64_t generation = 0;
  usdk_event_handler handler = nullptr;
  void *context = nullptr;
  std::shared_ptr<PlatformOwner> platform;
  std::shared_ptr<core::ShareSdk> sdk;
  std::shared_ptr<IDeviceDiscovery> discovery;
  std::shared_ptr<IConnectionManager> connections;
  std::shared_ptr<ITransferService> transfers;
  std::shared_ptr<IClipboardService> clipboard;
  std::shared_ptr<core::ShareSdk> Core() {
    std::lock_guard<std::mutex> l(gate);
    Require(alive);
    return sdk;
  }
};
inline usdk_span View(const std::string &s) {
  return {reinterpret_cast<const uint8_t *>(s.data()), s.size()};
}
inline usdk_span View(const std::vector<uint8_t> &s) {
  return {s.data(), s.size()};
}
inline usdk_uuid View(Uuid id) {
  usdk_uuid out{};
  std::copy(id.bytes.begin(), id.bytes.end(), out.bytes);
  return out;
}
inline Uuid UuidOf(usdk_uuid id) {
  Uuid out;
  std::copy(std::begin(id.bytes), std::end(id.bytes), out.bytes.begin());
  return out;
}
inline usdk_content View(const ShareContent &c) {
  return {static_cast<uint32_t>(c.type), View(c.text), View(c.mime_type),
          View(c.image)};
}
inline void Validate(usdk_span s, uint64_t limit) {
  Require(!s.length || s.data);
  Require(s.length <= limit && s.length <= SIZE_MAX, USDK_RESOURCE_LIMIT);
}
inline std::string String(usdk_span s, uint64_t limit = 65536) {
  Validate(s, limit);
  std::string out;
  if (s.length)
    out.assign(reinterpret_cast<const char *>(s.data),
               static_cast<size_t>(s.length));
  Require(core::ServiceUtf8(out));
  return out;
}
struct DeviceView {
  std::vector<usdk_span> addresses;
  usdk_device value{};
  explicit DeviceView(const DeviceInfo &d) {
    for (auto &a : d.addresses)
      addresses.push_back(View(a));
    value = {View(d.id),
             View(d.name),
             static_cast<uint32_t>(d.platform),
             d.protocol.major,
             d.protocol.minor,
             d.port,
             static_cast<uint16_t>(d.paired),
             std::chrono::duration_cast<std::chrono::milliseconds>(
                 d.last_seen.time_since_epoch())
                 .count(),
             addresses.data(),
             addresses.size()};
  }
};
void InstallEvents(const std::shared_ptr<State> &);
void Emit(const std::weak_ptr<State> &, uint64_t, const usdk_event &);
inline void Populate(usdk_result &, const Result<void> &) {}
inline void Populate(usdk_result &out, const Result<TransferId> &r) {
  if (r.ok())
    out.transfer = View(r.value());
}
inline void Populate(usdk_result &out, const Result<ShareContent> &r) {
  if (r.ok())
    out.content = View(r.value());
}
template <class T>
ResultCallback<T> Callback(const std::shared_ptr<State> &s, uint64_t id,
                           usdk_completion cb, void *ctx) {
  return [weak = std::weak_ptr<State>(s), id, cb, ctx](Result<T> r) {
    auto owner = weak.lock();
    if (!owner)
      return;
    std::lock_guard<std::recursive_mutex> delivery(owner->callbacks);
    {
      std::lock_guard<std::mutex> lock(owner->gate);
      if (!owner->alive)
        return;
    }
    usdk_result out{};
    out.operation = id;
    if (!r.ok())
      out.error = Error(r.error());
    Populate(out, r);
    try {
      cb(ctx, &out);
    } catch (...) {
    } // Host C++ callbacks may not unwind across C.
  };
}
template <class T, class F>
usdk_error Command(usdk_handle h, usdk_completion cb, void *ctx, uint64_t *out,
                   F f) noexcept {
  if (out)
    *out = 0;
  return Boundary([&] {
    Require(cb && out);
    auto s = Registry<State>::Get(h);
    auto sdk = s->Core();
    auto id = Next();
    auto completion = Callback<T>(s, id, cb, ctx);
    f(s, sdk, std::move(completion));
    *out = id;
  });
}
template <class T>
std::shared_ptr<T> Service(const std::shared_ptr<State> &s,
                           std::shared_ptr<T> State::*member) {
  std::lock_guard<std::mutex> lock(s->gate);
  Require(s->alive);
  auto value = s.get()->*member;
  Require(bool(value));
  return value;
}
} // namespace unishare::abi::detail
