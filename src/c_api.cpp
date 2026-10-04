#include "c_api_internal.hpp"
namespace unishare::abi::detail {
uint64_t Next() {
  static std::mutex mutex;
  static uint64_t next = 1;
  std::lock_guard<std::mutex> l(mutex);
  Require(next != 0, USDK_RESOURCE_LIMIT);
  return next++;
}
} // namespace unishare::abi::detail
using namespace unishare;
using namespace unishare::abi::detail;
namespace unishare::abi {
namespace {
class GuardedStream final : public IStream {
  std::shared_ptr<IStream> stream_;
  template <class T> ResultCallback<T> Completion(ResultCallback<T> cb) {
    struct Pending {
      std::atomic<bool> done{false};
      std::shared_ptr<IStream> owner;
      ResultCallback<T> callback;
    };
    auto pending = std::make_shared<Pending>();
    pending->owner = stream_;
    pending->callback = std::move(cb);
    return [pending](Result<T> r) {
      if (pending->done.exchange(true))
        return;
      auto owner = std::move(pending->owner);
      auto callback = std::move(pending->callback);
      callback(std::move(r));
    };
  }

public:
  explicit GuardedStream(std::shared_ptr<IStream> s) : stream_(std::move(s)) {}
  OperationId Read(size_t n, ResultCallback<std::vector<uint8_t>> cb) override {
    auto done = Completion(std::move(cb));
    try {
      return stream_->Read(n, done);
    } catch (...) {
      done(Result<std::vector<uint8_t>>::Failure(
          core::CurrentException(ErrorCode::StorageFailed)));
      return 0;
    }
  }
  OperationId Write(std::vector<uint8_t> b, ResultCallback<void> cb) override {
    auto done = Completion(std::move(cb));
    try {
      return stream_->Write(std::move(b), done);
    } catch (...) {
      done(Result<void>::Failure(
          core::CurrentException(ErrorCode::StorageFailed)));
      return 0;
    }
  }
  void Close() override {
    try {
      stream_->Close();
    } catch (...) {
    }
  }
  void AbortAndCleanup() override {
    try {
      stream_->AbortAndCleanup();
    } catch (...) {
    }
  }
};
} // namespace
usdk_error RegisterPlatform(std::shared_ptr<IPlatform> p,
                            std::shared_ptr<IExecutor> e,
                            std::shared_ptr<IPermissionAgent> a,
                            usdk_platform_handle *out) noexcept {
  if (out)
    *out = 0;
  return Boundary([&] {
    Require(p && out);
    *out = Registry<PlatformOwner>::Add(std::make_shared<PlatformOwner>(
        PlatformOwner{std::move(p), std::move(e), std::move(a)}));
  });
}
usdk_error RegisterStream(std::shared_ptr<IStream> p,
                          usdk_stream_handle *out) noexcept {
  if (out)
    *out = 0;
  return Boundary([&] {
    Require(p && out);
    *out = Registry<unishare::IStream>::Add(
        std::make_shared<GuardedStream>(std::move(p)));
  });
}
} // namespace unishare::abi
extern "C" {
uint32_t USDK_CALL usdk_abi_version(void) { return USDK_ABI_VERSION; }
usdk_error USDK_CALL usdk_config_init(uint32_t version, usdk_config *c) {
  return Boundary([&] {
    Require(c);
    Require(version == 1, USDK_VERSION_MISMATCH);
    *c = {1,         sizeof(*c),     {},    8,      4,  4194304, 8388608,
          104857600, 21474836480ULL, 65536, 262144, 60, 5};
  });
}
usdk_error USDK_CALL usdk_create(usdk_platform_handle p, const usdk_config *c,
                                 usdk_completion cb, void *ctx,
                                 usdk_handle *out, uint64_t *operation) {
  if (out)
    *out = 0;
  if (operation)
    *operation = 0;
  return Boundary([&] {
    Require(c && cb && out && operation && out != operation);
    Require(c->abi_version == 1 && c->struct_size == sizeof(*c),
            USDK_VERSION_MISMATCH);
    SdkConfig config;
    config.device_name = String(c->device_name, 255);
    config.max_connections = c->max_connections;
    config.max_pending_requests = c->max_pending_requests;
    config.max_buffer_bytes = c->max_buffer_bytes;
    config.max_text_bytes = c->max_text_bytes;
    config.max_image_bytes = c->max_image_bytes;
    config.max_file_bytes = c->max_file_bytes;
    config.control_frame_bytes = c->control_frame_bytes;
    config.data_chunk_bytes = c->data_chunk_bytes;
    config.request_timeout = std::chrono::seconds(c->request_timeout_seconds);
    config.offline_debounce = std::chrono::seconds(c->offline_debounce_seconds);
    Require(c->max_buffer_bytes && c->max_buffer_bytes <= 4194304 &&
            c->max_connections && c->max_connections <= 8 &&
            c->max_pending_requests && c->max_pending_requests <= 4);
    Require(c->control_frame_bytes && c->control_frame_bytes <= 65536 &&
            c->data_chunk_bytes && c->data_chunk_bytes <= 262144 &&
            c->request_timeout_seconds);
    auto s = std::make_shared<State>();
    s->platform = Registry<PlatformOwner>::Get(p);
    config.callback_executor = s->platform->executor;
    config.permissions = s->platform->permissions;
    s->sdk = std::make_shared<core::ShareSdk>(*s->platform->platform);
    auto id = Next();
    auto h = Registry<State>::Add(s);
    try {
      auto done = Callback<void>(s, id, cb, ctx);
      s->sdk->Initialize(std::move(config), [weak = std::weak_ptr<State>(s),
                                             done](Result<void> r) mutable {
        if (auto s = weak.lock()) {
          try {
            auto sdk = s->Core();
            if (r.ok()) {
              auto d = sdk->Discovery();
              auto c = sdk->Connections();
              auto t = sdk->Transfers();
              auto b = sdk->Clipboard();
              {
                std::lock_guard<std::recursive_mutex> l(s->gate);
                if (!s->alive)
                  return;
                s->discovery = d;
                s->connections = c;
                s->transfers = t;
                s->clipboard = b;
              }
              InstallEvents(s);
            }
          } catch (...) {
            auto e = Exception();
            r = Result<void>::Failure(
                {static_cast<ErrorCode>(e.code), "C ABI initialization",
                 e.has_platform_error ? std::optional<int64_t>(e.platform_error)
                                      : std::nullopt});
          }
          done(std::move(r));
        }
      });
    } catch (...) {
      Registry<State>::Remove(h);
      throw;
    }
    *out = h;
    *operation = id;
  });
}
usdk_error USDK_CALL usdk_start(usdk_handle h, usdk_completion cb, void *ctx,
                                uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [](auto, auto sdk, auto done) {
    sdk->Start(std::move(done));
  });
}
usdk_error USDK_CALL usdk_stop(usdk_handle h, usdk_completion cb, void *ctx,
                               uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [](auto, auto sdk, auto done) {
    sdk->Stop(std::move(done));
  });
}
usdk_error USDK_CALL usdk_destroy(usdk_handle h) {
  return Boundary([&] {
    auto s = Registry<State>::Remove(h);
    std::shared_ptr<core::ShareSdk> sdk;
    {
      std::lock_guard<std::recursive_mutex> l(s->gate);
      s->alive = false;
      s->handler = nullptr;
      s->context = nullptr;
      sdk = std::move(s->sdk);
    }
    sdk.reset();
  });
}
usdk_error USDK_CALL usdk_get_state(usdk_handle h, uint32_t *out) {
  return Boundary([&] {
    Require(out);
    *out = static_cast<uint32_t>(Registry<State>::Get(h)->Core()->GetState());
  });
}
usdk_error USDK_CALL usdk_platform_release(usdk_platform_handle h) {
  return Boundary([&] { Registry<PlatformOwner>::Remove(h); });
}
usdk_error USDK_CALL usdk_stream_release(usdk_stream_handle h) {
  return Boundary([&] { Registry<unishare::IStream>::Remove(h); });
}
}
