#include "unishare/c_api.h"
#include "unishare/c_api_bridge.hpp"
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <system_error>
#include <thread>
#define CHECK(...)                                                             \
  do {                                                                         \
    if (!(__VA_ARGS__)) {                                                      \
      std::cerr << "check failed " << __LINE__ << ": " << #__VA_ARGS__         \
                << '\n';                                                       \
      std::exit(1);                                                            \
    }                                                                          \
  } while (false)
#include "c_api_fakes.hpp"
using namespace unishare;
using namespace unishare::abi;
static usdk_span Span(const char *s, size_t n) {
  return {reinterpret_cast<const uint8_t *>(s), n};
}
struct Completion {
  int calls = 0;
  usdk_error error{};
  usdk_operation_id id = 0;
  usdk_uuid transfer{};
  std::string text;
  static void Done(void *ctx, const usdk_result *r) {
    auto &s = *static_cast<Completion *>(ctx);
    ++s.calls;
    s.error = r->error;
    s.id = r->operation;
    s.transfer = r->transfer;
    if (r->content.text.length)
      s.text.assign(reinterpret_cast<const char *>(r->content.text.data),
                    size_t(r->content.text.length));
  }
};
void Drain(const std::shared_ptr<Queue> &q, const std::function<bool()> &done) {
  for (int n = 0; n < 5000; ++n) {
    q->Drain();
    if (done())
      return;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  CHECK(done());
}
// Wrong version acceptance, dangling handles, or losing length-bearing data
// fails this test.
void Lifecycle() {
  CHECK(usdk_abi_version() == 1);
  auto p = std::make_shared<PlatformFake>();
  auto q = std::make_shared<Queue>();
  usdk_platform_handle platform = 0;
  CHECK(!RegisterPlatform(p, q, {}, &platform).code);
  usdk_config config;
  CHECK(!usdk_config_init(1, &config).code);
  config.device_name = Span("api", 3);
  usdk_handle sdk = 0;
  usdk_operation_id op = 0;
  Completion init;
  CHECK(usdk_create(platform, &config, &Completion::Done, &init, &sdk, &sdk)
            .code == USDK_INVALID_INPUT);
  config.abi_version = 2;
  CHECK(usdk_create(platform, &config, &Completion::Done, &init, &sdk, &op)
            .code == USDK_VERSION_MISMATCH);
  CHECK(!sdk && !op && !init.calls);
  config.abi_version = 1;
  CHECK(!usdk_create(platform, &config, &Completion::Done, &init, &sdk, &op)
             .code);
  CHECK(sdk && op);
  Drain(q, [&] { return init.calls == 1; });
  CHECK(!init.error.code && init.id == op);
  CHECK(!usdk_platform_release(platform).code);
  CHECK(usdk_platform_release(platform).code == USDK_INVALID_INPUT);
  usdk_devices *devices = nullptr;
  CHECK(!usdk_get_devices(sdk, &devices).code);
  CHECK(devices && devices->count == 0);
  CHECK(!usdk_free(devices).code);
  CHECK(usdk_free(devices).code == USDK_INVALID_INPUT);
  Completion start;
  CHECK(!usdk_start(sdk, &Completion::Done, &start, &op).code);
  Drain(q, [&] { return start.calls == 1; });
  CHECK(!start.error.code);
  Completion read;
  CHECK(!usdk_clipboard_read(sdk, &Completion::Done, &read, &op).code);
  // Snapshot is a core queue barrier, not a sleep-based completion assertion.
  CHECK(!usdk_get_devices(sdk, &devices).code);
  usdk_free(devices);
  CHECK(p->reads == 1);
  ShareContent c;
  c.text = std::string("a\0b", 3);
  p->read(Result<ShareContent>::Success(c));
  Drain(q, [&] { return read.calls == 1; });
  CHECK(!read.error.code && read.text == std::string("a\0b", 3));
  CHECK(usdk_send_text(sdk, {}, Span(nullptr, 1), &Completion::Done, &read, &op)
            .code == USDK_INVALID_INPUT);
  Completion late;
  CHECK(!usdk_clipboard_read(sdk, &Completion::Done, &late, &op).code);
  CHECK(!usdk_get_devices(sdk, &devices).code);
  usdk_free(devices);
  auto pending = p->read;
  CHECK(!usdk_destroy(sdk).code);
  pending(Result<ShareContent>::Success(c));
  q->Drain();
  CHECK(!late.calls);
  CHECK(usdk_destroy(sdk).code == USDK_INVALID_INPUT);
  CHECK(usdk_start(sdk, &Completion::Done, &late, &op).code ==
        USDK_INVALID_INPUT);
}

struct ApiPeer {
  std::shared_ptr<PlatformFake> p = std::make_shared<PlatformFake>();
  std::shared_ptr<Queue> q = std::make_shared<Queue>();
  usdk_handle sdk = 0;
  usdk_operation_id op = 0;
  std::vector<usdk_event> events;
  std::vector<std::string> codes;
  static void Event(void *ctx, const usdk_event *e) {
    auto &a = *static_cast<ApiPeer *>(ctx);
    a.events.push_back(*e);
    if (e->kind == USDK_EVENT_PAIRING_REQUEST)
      a.codes.emplace_back(
          reinterpret_cast<const char *>(e->verification_code.data),
          size_t(e->verification_code.length));
  }
  ApiPeer() {
    usdk_platform_handle platform = 0;
    CHECK(!RegisterPlatform(p, q, {}, &platform).code);
    usdk_config c;
    CHECK(!usdk_config_init(1, &c).code);
    c.device_name = Span("peer", 4);
    Completion init;
    CHECK(!usdk_create(platform, &c, Completion::Done, &init, &sdk, &op).code);
    CHECK(!usdk_platform_release(platform).code);
    Drain(q, [&] { return init.calls; });
    CHECK(!init.error.code);
    CHECK(!usdk_set_event_handler(sdk, Event, this).code);
    Completion start;
    CHECK(!usdk_start(sdk, Completion::Done, &start, &op).code);
    Drain(q, [&] { return start.calls; });
    CHECK(!start.error.code);
  }
  ~ApiPeer() {
    if (sdk)
      CHECK(!usdk_destroy(sdk).code);
    q->Drain();
  }
  void Barrier() {
    usdk_devices *d = nullptr;
    CHECK(!usdk_get_devices(sdk, &d).code);
    CHECK(!usdk_free(d).code);
  }
  usdk_uuid Id() {
    usdk_uuid id{};
    std::copy(p->store.identity->id.bytes.begin(),
              p->store.identity->id.bytes.end(), id.bytes);
    return id;
  }
  usdk_event Last(uint32_t kind) {
    for (auto i = events.rbegin(); i != events.rend(); ++i)
      if (i->kind == kind)
        return *i;
    CHECK(false);
    return {};
  }
  size_t Count(uint32_t kind) {
    return std::count_if(events.begin(), events.end(),
                         [&](auto &e) { return e.kind == kind; });
  }
};
void Both(ApiPeer &a, ApiPeer &b, const std::function<bool()> &done) {
  static int stage = 0;
  ++stage;
  for (int n = 0; n < 5000; ++n) {
    a.q->Drain();
    b.q->Drain();
    if (done())
      return;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  std::cerr << "TLS stage " << stage << " events A/B " << a.events.size() << "/"
            << b.events.size() << "\n";
  CHECK(done());
}
// Bypassing either peer's consent, losing IDs/bytes, or releasing live streams
// breaks this real TLS flow.
void TlsFlow() {
  ApiPeer a, b;
  auto record = b.p->dns.publication;
  record.interface_id = "wifi";
  record.srv_target = "b.local.";
  record.addresses = {"192.0.2.2"};
  a.p->dns.found(Result<ServiceRecord>::Success(record));
  a.Barrier();
  a.q->Drain();
  CHECK(a.Count(USDK_EVENT_ONLINE) == 1);
  usdk_devices *devices = nullptr;
  CHECK(!usdk_get_devices(a.sdk, &devices).code);
  CHECK(devices->count == 1 && devices->items[0].address_count == 1 &&
        devices->items[0].name.length == 4);
  CHECK(!usdk_free(devices).code);
  a.p->sockets.connect = [&b](std::string, auto cb) {
    auto x = std::make_shared<Socket>(), y = std::make_shared<Socket>();
    x->peer = y;
    y->peer = x;
    b.p->sockets.incoming(Result<std::shared_ptr<ISocket>>::Success(y));
    cb(Result<std::shared_ptr<ISocket>>::Success(x));
  };
  Completion connected;
  CHECK(!usdk_connect(a.sdk, b.Id(), Completion::Done, &connected, &a.op).code);
  Both(a, b, [&] { return !a.codes.empty() && !b.codes.empty(); });
  CHECK(a.codes[0] == b.codes[0] && a.codes[0].size() == 6 && !connected.calls);
  Completion ca, cb;
  CHECK(!usdk_confirm_pairing(a.sdk, a.Last(USDK_EVENT_PAIRING_REQUEST).request,
                              1, Completion::Done, &ca, &a.op)
             .code);
  CHECK(!usdk_confirm_pairing(b.sdk, b.Last(USDK_EVENT_PAIRING_REQUEST).request,
                              1, Completion::Done, &cb, &b.op)
             .code);
  Both(a, b, [&] {
    return connected.calls && ca.calls && cb.calls &&
           a.Count(USDK_EVENT_PAIRING_RESULT) &&
           b.Count(USDK_EVENT_PAIRING_RESULT);
  });
  CHECK(!connected.error.code);
  usdk_trusted_devices *trusted = nullptr;
  CHECK(!usdk_get_trusted_devices(a.sdk, &trusted).code);
  CHECK(trusted->count == 1 &&
        !std::memcmp(trusted->items[0].id.bytes, b.Id().bytes, 16));
  CHECK(!usdk_free(trusted).code);
  Completion sent;
  CHECK(!usdk_send_text(a.sdk, b.Id(), Span("a\0b", 3), Completion::Done, &sent,
                        &a.op)
             .code);
  auto send_op = a.op;
  Both(a, b, [&] {
    return b.Count(USDK_EVENT_INCOMING_OFFER) == 1 &&
           a.Count(USDK_EVENT_PROGRESS);
  });
  CHECK(!sent.calls);
  auto offer = b.Last(USDK_EVENT_INCOMING_OFFER);
  CHECK(offer.content_type == USDK_TEXT && offer.byte_count == 3 &&
        offer.request);
  auto sink = std::make_shared<Memory>();
  usdk_stream_handle sink_handle = 0;
  CHECK(!RegisterStream(sink, &sink_handle).code);
  Completion accepted;
  CHECK(!usdk_accept(b.sdk, offer.request, sink_handle, Completion::Done,
                     &accepted, &b.op)
             .code);
  CHECK(!usdk_stream_release(sink_handle).code);
  Both(a, b, [&] {
    return sent.calls && accepted.calls && b.Count(USDK_EVENT_TRANSFER_RESULT);
  });
  CHECK(!sent.error.code && sent.id == send_op && sent.calls == 1 &&
        sink->bytes == std::vector<uint8_t>({'a', 0, 'b'}) && sink->closed &&
        !sink->aborted);
  CHECK(!std::memcmp(sent.transfer.bytes,
                     a.Last(USDK_EVENT_PROGRESS).transfer.bytes, 16));
  auto source = std::make_shared<Memory>();
  source->bytes = {9, 0, 7};
  usdk_stream_handle source_handle = 0;
  CHECK(!RegisterStream(source, &source_handle).code);
  usdk_source file{source_handle, Span("file", 4),
                   Span("application/octet-stream", 24), 3};
  Completion file_sent;
  CHECK(
      !usdk_send_file(a.sdk, b.Id(), &file, Completion::Done, &file_sent, &a.op)
           .code);
  CHECK(!usdk_stream_release(source_handle).code);
  Both(a, b, [&] { return b.Count(USDK_EVENT_INCOMING_OFFER) == 2; });
  CHECK(!file_sent.calls && !source->offset);
  auto file_sink = std::make_shared<Memory>();
  file_sink->hold = true;
  CHECK(!RegisterStream(file_sink, &sink_handle).code);
  Completion file_accept;
  CHECK(!usdk_accept(b.sdk, b.Last(USDK_EVENT_INCOMING_OFFER).request,
                     sink_handle, Completion::Done, &file_accept, &b.op)
             .code);
  CHECK(!usdk_stream_release(sink_handle).code);
  Both(a, b, [&] {
    b.Barrier();
    return bool(file_sink->pending);
  });
  CHECK(!file_sent.calls);
  Completion cancelled;
  CHECK(!usdk_cancel(a.sdk, a.Last(USDK_EVENT_PROGRESS).transfer,
                     Completion::Done, &cancelled, &a.op)
             .code);
  Both(a, b, [&] { return cancelled.calls && file_sent.calls; });
  CHECK(file_sent.error.code == USDK_CANCELLED);
  file_sink->pending(Result<void>::Success());
  Both(a, b, [&] { return b.Count(USDK_EVENT_TRANSFER_RESULT) == 2; });
  CHECK(file_sent.calls == 1 && file_sink->aborted && file_sink->bytes.empty());
  Completion reconnected, reconfirmed;
  CHECK(
      !usdk_connect(a.sdk, b.Id(), Completion::Done, &reconnected, &a.op).code);
  Both(a, b, [&] { return b.codes.size() == 2 || reconnected.calls; });
  CHECK(!reconnected.calls);
  CHECK(!usdk_confirm_pairing(b.sdk, b.Last(USDK_EVENT_PAIRING_REQUEST).request,
                              1, Completion::Done, &reconfirmed, &b.op)
             .code);
  Both(a, b, [&] { return reconnected.calls && reconfirmed.calls; });
  CHECK(!reconnected.error.code);
  Completion image_sent;
  auto image = std::make_shared<Memory>();
  CHECK(!RegisterStream(image, &source_handle).code);
  usdk_source img{source_handle, Span("image", 5), Span("image/png", 9), 0};
  CHECK(!usdk_send_image(a.sdk, b.Id(), &img, Completion::Done, &image_sent,
                         &a.op)
             .code);
  CHECK(!usdk_stream_release(source_handle).code);
  Both(a, b, [&] { return b.Count(USDK_EVENT_INCOMING_OFFER) == 3; });
  Completion rejected;
  CHECK(!usdk_reject(b.sdk, b.Last(USDK_EVENT_INCOMING_OFFER).request,
                     Completion::Done, &rejected, &b.op)
             .code);
  Both(a, b, [&] { return image_sent.calls && rejected.calls; });
  CHECK(image_sent.error.code == USDK_TRANSFER_REJECTED);

  auto throwing_source = std::make_shared<Memory>();
  throwing_source->throw_read = true;
  CHECK(!RegisterStream(throwing_source, &source_handle).code);
  usdk_source throwing_file{source_handle, Span("throw", 5),
                            Span("application/octet-stream", 24), 1};
  Completion throwing_sent, throwing_accept;
  CHECK(!usdk_send_file(a.sdk, b.Id(), &throwing_file, Completion::Done,
                        &throwing_sent, &a.op)
             .code);
  CHECK(!usdk_stream_release(source_handle).code);
  Both(a, b, [&] { return b.Count(USDK_EVENT_INCOMING_OFFER) == 4; });
  auto throw_sink = std::make_shared<Memory>();
  CHECK(!RegisterStream(throw_sink, &sink_handle).code);
  CHECK(!usdk_accept(b.sdk, b.Last(USDK_EVENT_INCOMING_OFFER).request,
                     sink_handle, Completion::Done, &throwing_accept, &b.op)
             .code);
  CHECK(!usdk_stream_release(sink_handle).code);
  Both(a, b, [&] { return throwing_sent.calls; });
  CHECK(throwing_sent.error.code == USDK_STORAGE_FAILED &&
        throwing_sent.error.platform_error == 79);
  Completion forgotten;
  CHECK(!usdk_forget_device(a.sdk, b.Id(), Completion::Done, &forgotten, &a.op)
             .code);
  Both(a, b, [&] { return forgotten.calls; });
  CHECK(!forgotten.error.code);
  CHECK(!usdk_get_trusted_devices(a.sdk, &trusted).code);
  CHECK(trusted->count == 0);
  CHECK(!usdk_free(trusted).code);
}
// Swallowing a thrown platform call strands initialization/read: both must
// finish with platform error intact.
void Exceptions() {
  auto p = std::make_shared<PlatformFake>();
  auto q = std::make_shared<Queue>();
  p->store.throws = true;
  usdk_platform_handle platform = 0;
  CHECK(!RegisterPlatform(p, q, {}, &platform).code);
  usdk_config c;
  usdk_config_init(1, &c);
  c.device_name = Span("throws", 6);
  usdk_handle sdk = 0;
  uint64_t op = 0;
  Completion init;
  CHECK(!usdk_create(platform, &c, Completion::Done, &init, &sdk, &op).code);
  Drain(q, [&] { return init.calls; });
  CHECK(init.error.code == USDK_STORAGE_FAILED &&
        init.error.has_platform_error && init.error.platform_error == 71);
  CHECK(!usdk_destroy(sdk).code);
  CHECK(!usdk_platform_release(platform).code);
  ApiPeer a;
  a.p->throw_read = true;
  Completion read;
  CHECK(!usdk_clipboard_read(a.sdk, Completion::Done, &read, &a.op).code);
  Drain(a.q, [&] { return read.calls; });
  CHECK(read.calls == 1 && read.error.code == USDK_STORAGE_FAILED &&
        read.error.platform_error == 73);
}

// Replacing observers discards queued contexts; callback reentry and
// destruction must be safe.
void CallbackLifetime() {
  ApiPeer a;
  auto record = a.p->dns.publication;
  record.instance = "other";
  record.interface_id = "wifi";
  record.srv_target = "x.local.";
  record.addresses = {"192.0.2.8"};
  for (auto &field : record.txt)
    if (field.first == "id")
      field.second = "12345678-1234-4234-8234-123456789abc";
  record.txt_bytes.clear();
  for (auto &field : record.txt) {
    auto value = field.first + "=" + field.second;
    record.txt_bytes.push_back(uint8_t(value.size()));
    record.txt_bytes.insert(record.txt_bytes.end(), value.begin(), value.end());
  }
  a.p->dns.found(Result<ServiceRecord>::Success(record));
  a.Barrier();
  auto before = a.events.size();
  CHECK(!usdk_set_event_handler(a.sdk, nullptr, nullptr).code);
  a.q->Drain();
  CHECK(a.events.size() == before);
  struct Observer {
    usdk_handle sdk;
    int calls = 0;
  };
  Observer observer{a.sdk};
  CHECK(!usdk_set_event_handler(
             a.sdk,
             [](void *ctx, const usdk_event *) {
               auto &o = *static_cast<Observer *>(ctx);
               ++o.calls;
               CHECK(!usdk_set_event_handler(o.sdk, nullptr, nullptr).code);
             },
             &observer)
             .code);
  for (auto &field : record.txt)
    if (field.first == "name")
      field.second = "renamed";
  record.txt_bytes.clear();
  for (auto &field : record.txt) {
    auto value = field.first + "=" + field.second;
    record.txt_bytes.push_back(uint8_t(value.size()));
    record.txt_bytes.insert(record.txt_bytes.end(), value.begin(), value.end());
  }
  a.p->dns.found(Result<ServiceRecord>::Success(record));
  a.Barrier();
  a.q->Drain();
  CHECK(observer.calls == 1);
  Completion read;
  CHECK(!usdk_clipboard_read(a.sdk, Completion::Done, &read, &a.op).code);
  a.Barrier();
  auto late = a.p->read;
  ShareContent c;
  c.text = "queued";
  late(Result<ShareContent>::Success(c));
  a.Barrier();
  CHECK(!read.calls);
  CHECK(!usdk_destroy(a.sdk).code);
  a.sdk = 0;
  a.q->Drain();
  CHECK(!read.calls);
  ApiPeer reentrant;
  uint64_t op = 0;
  CHECK(!usdk_clipboard_read(
             reentrant.sdk,
             [](void *ctx, const usdk_result *) {
               auto &peer = *static_cast<ApiPeer *>(ctx);
               CHECK(!usdk_destroy(peer.sdk).code);
               peer.sdk = 0;
             },
             &reentrant, &op)
             .code);
  reentrant.Barrier();
  reentrant.p->read(Result<ShareContent>::Success(c));
  Drain(reentrant.q, [&] { return !reentrant.sdk; });
}
void StopAndWrite() {
  ApiPeer a;
  usdk_content content{USDK_TEXT, Span("x\0y", 3), {}, {}};
  Completion write;
  CHECK(!usdk_clipboard_write(a.sdk, &content, Completion::Done, &write, &a.op)
             .code);
  a.Barrier();
  CHECK(a.p->written.text == std::string("x\0y", 3));
  a.p->write(Result<void>::Failure({ErrorCode::PermissionDenied, "no", 97}));
  Drain(a.q, [&] { return write.calls; });
  CHECK(write.error.code == USDK_PERMISSION_DENIED &&
        write.error.platform_error == 97);
  Completion read, stop;
  CHECK(!usdk_clipboard_read(a.sdk, Completion::Done, &read, &a.op).code);
  a.Barrier();
  auto late = a.p->read;
  a.p->dns.throw_stop = true;
  CHECK(!usdk_stop(a.sdk, Completion::Done, &stop, &a.op).code);
  Drain(a.q, [&] { return stop.calls && read.calls; });
  CHECK(stop.error.code == USDK_INVALID_INPUT &&
        read.error.code == USDK_CANCELLED);
  late(Result<ShareContent>::Success({}));
  a.q->Drain();
  CHECK(read.calls == 1);
}
int main(int argc, char **argv) {
  if (argc > 1 && std::string(argv[1]) == "exceptions") {
    Exceptions();
    return 0;
  }
  Lifecycle();
  TlsFlow();
  Exceptions();
  CallbackLifetime();
  StopAndWrite();
}
