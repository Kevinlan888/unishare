#include "c_api_internal.hpp"
using namespace unishare;
using namespace unishare::abi::detail;
namespace unishare::abi::detail {
void Emit(const std::weak_ptr<State> &weak, uint64_t generation,
          const usdk_event &event) {
  if (auto s = weak.lock()) {
    std::lock_guard<std::recursive_mutex> l(s->gate);
    if (s->alive && s->generation == generation && s->handler) {
      try {
        s->handler(s->context, &event);
      } catch (...) {
      }
    }
  }
}
void InstallEvents(const std::shared_ptr<State> &s) {
  std::lock_guard<std::recursive_mutex> l(s->gate);
  if (!s->alive || !s->discovery)
    return;
  auto weak = std::weak_ptr<State>(s);
  auto g = s->generation;
  s->discovery->SetEventHandler([weak, g](DiscoveryEvent e) {
    DeviceView d(e.device);
    usdk_event v{};
    v.kind = 1 + static_cast<uint32_t>(e.kind);
    v.device = d.value;
    Emit(weak, g, v);
  });
  s->discovery->SetErrorHandler([weak, g](ShareError e) {
    usdk_event v{};
    v.kind = USDK_EVENT_DISCOVERY_ERROR;
    v.error = Error(e);
    Emit(weak, g, v);
  });
  s->discovery->SetStateHandler([weak, g](DiscoveryState e) {
    usdk_event v{};
    v.kind = USDK_EVENT_DISCOVERY_STATE;
    v.state = static_cast<uint32_t>(e);
    Emit(weak, g, v);
  });
  s->connections->SetConnectionHandler([weak, g](ConnectionEvent e) {
    usdk_event v{};
    v.kind = USDK_EVENT_CONNECTION;
    v.device.id = View(e.device);
    v.state = static_cast<uint32_t>(e.state);
    if (e.error)
      v.error = Error(*e.error);
    Emit(weak, g, v);
  });
  s->connections->SetPairingRequestHandler([weak, g](PairingRequest e) {
    DeviceView d(e.device);
    usdk_event v{};
    v.kind = USDK_EVENT_PAIRING_REQUEST;
    v.request = e.id;
    v.device = d.value;
    v.verification_code = View(e.verification_code);
    Emit(weak, g, v);
  });
  s->connections->SetPairingResultHandler([weak, g](PairingResult e) {
    usdk_event v{};
    v.kind = USDK_EVENT_PAIRING_RESULT;
    v.request = e.id;
    v.device.id = View(e.device);
    if (!e.result.ok())
      v.error = Error(e.result.error());
    Emit(weak, g, v);
  });
  s->connections->SetIdentityChangedHandler([weak, g](DeviceId e) {
    usdk_event v{};
    v.kind = USDK_EVENT_IDENTITY_CHANGED;
    v.device.id = View(e);
    Emit(weak, g, v);
  });
  s->transfers->SetIncomingOfferHandler([weak, g](IncomingOffer e) {
    usdk_event v{};
    v.kind = USDK_EVENT_INCOMING_OFFER;
    v.request = e.request_id;
    v.transfer = View(e.transfer_id);
    v.device.id = View(e.device_id);
    v.content_type = static_cast<uint32_t>(e.content_type);
    v.display_name = View(e.display_name);
    v.mime_type = View(e.mime_type);
    v.byte_count = e.byte_count;
    Emit(weak, g, v);
  });
  s->transfers->SetProgressHandler([weak, g](TransferProgress e) {
    usdk_event v{};
    v.kind = USDK_EVENT_PROGRESS;
    v.transfer = View(e.id);
    v.transferred = e.transferred;
    v.byte_count = e.total;
    Emit(weak, g, v);
  });
  s->transfers->SetResultHandler([weak, g](TransferResult e) {
    usdk_event v{};
    v.kind = USDK_EVENT_TRANSFER_RESULT;
    v.transfer = View(e.id);
    if (!e.result.ok())
      v.error = Error(e.result.error());
    Emit(weak, g, v);
  });
}
} // namespace unishare::abi::detail
namespace {
std::mutex allocations_mutex;
std::map<void *, std::shared_ptr<void>> allocations;
template <class T> void Keep(const std::shared_ptr<T> &owner, void *root) {
  std::lock_guard<std::mutex> l(allocations_mutex);
  allocations.emplace(root, owner);
}
struct DevicesOwner {
  std::vector<DeviceInfo> data;
  std::vector<DeviceView> views;
  std::vector<usdk_device> items;
  usdk_devices root{};
  explicit DevicesOwner(std::vector<DeviceInfo> value)
      : data(std::move(value)) {
    views.reserve(data.size());
    items.reserve(data.size());
    for (auto &d : data) {
      views.emplace_back(d);
      items.push_back(views.back().value);
    }
    root = {items.data(), items.size()};
  }
};
struct TrustOwner {
  std::vector<usdk_trusted_device> items;
  usdk_trusted_devices root{};
};
} // namespace
extern "C" {
usdk_error USDK_CALL usdk_set_event_handler(usdk_handle h,
                                            usdk_event_handler cb, void *ctx) {
  return Boundary([&] {
    auto s = Registry<State>::Get(h);
    auto sdk = s->Core();
    std::lock_guard<std::recursive_mutex> l(s->gate);
    Require(s->alive);
    Require(s->generation != UINT64_MAX, USDK_RESOURCE_LIMIT);
    ++s->generation;
    s->handler = cb;
    s->context = ctx;
    InstallEvents(s);
  });
}
usdk_error USDK_CALL usdk_get_devices(usdk_handle h, usdk_devices **out) {
  if (out)
    *out = nullptr;
  return Boundary([&] {
    Require(out);
    auto s = Registry<State>::Get(h);
    auto sdk = s->Core();
    auto owner = std::make_shared<DevicesOwner>(sdk->Discovery()->GetDevices());
    Keep(owner, &owner->root);
    *out = &owner->root;
  });
}
usdk_error USDK_CALL usdk_get_trusted_devices(usdk_handle h,
                                              usdk_trusted_devices **out) {
  if (out)
    *out = nullptr;
  return Boundary([&] {
    Require(out);
    auto s = Registry<State>::Get(h);
    auto sdk = s->Core();
    auto owner = std::make_shared<TrustOwner>();
    // C++ vector-only snapshot loses read errors; preserve Result here.
    sdk->Runtime()->scheduler->Invoke([&] {
      auto result = s->platform->platform->IdentityStore().LoadTrust();
      if (!result.ok())
        throw result.error();
      for (auto &t : result.value()) {
        usdk_trusted_device d{};
        d.id = View(t.id);
        std::copy(t.public_key_fingerprint.begin(),
                  t.public_key_fingerprint.end(), d.public_key_fingerprint);
        owner->items.push_back(d);
      }
    });
    owner->root = {owner->items.data(), owner->items.size()};
    Keep(owner, &owner->root);
    *out = &owner->root;
  });
}
usdk_error USDK_CALL usdk_free(void *allocation) {
  return Boundary([&] {
    if (!allocation)
      return;
    std::shared_ptr<void> owner;
    {
      std::lock_guard<std::mutex> l(allocations_mutex);
      auto i = allocations.find(allocation);
      Require(i != allocations.end());
      owner = std::move(i->second);
      allocations.erase(i);
    }
  });
}
}
