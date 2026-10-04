#pragma once
#include "unishare/platform.hpp"
#include <algorithm>
#include <mutex>
#include <stdexcept>
using namespace unishare;
struct Queue : IExecutor {
  std::mutex mutex;
  std::vector<std::function<void()>> tasks;
  void Post(std::function<void()> f) override {
    std::lock_guard<std::mutex> l(mutex);
    tasks.push_back(std::move(f));
  }
  void Drain() {
    for (;;) {
      std::vector<std::function<void()>> next;
      {
        std::lock_guard<std::mutex> l(mutex);
        next.swap(tasks);
      }
      if (next.empty())
        return;
      for (auto &f : next)
        f();
    }
  }
};
struct Store : IIdentityStore {
  bool throws = false;
  std::optional<IdentityMaterial> identity;
  std::vector<TrustedDevice> trusted;
  Result<std::optional<IdentityMaterial>> LoadIdentity() override {
    if (throws)
      throw std::system_error(std::error_code(71, std::generic_category()));
    return Result<std::optional<IdentityMaterial>>::Success(identity);
  }
  Result<void> SaveIdentity(const IdentityMaterial &i) override {
    identity = i;
    return Result<void>::Success();
  }
  Result<std::vector<TrustedDevice>> LoadTrust() override {
    return Result<std::vector<TrustedDevice>>::Success(trusted);
  }
  Result<void> SaveTrust(const TrustedDevice &t) override {
    for (auto &x : trusted)
      if (x.id == t.id)
        return x.public_key_fingerprint == t.public_key_fingerprint
                   ? Result<void>::Success()
                   : Result<void>::Failure(
                         {ErrorCode::IdentityChanged, "changed", {}});
    trusted.push_back(t);
    return Result<void>::Success();
  }
  Result<void> RemoveTrust(DeviceId id) override {
    trusted.erase(std::remove_if(trusted.begin(), trusted.end(),
                                 [&](auto &t) { return t.id == id; }),
                  trusted.end());
    return Result<void>::Success();
  }
};
struct Dns : IDnsSd {
  bool throw_stop = false;
  ServiceRecord publication;
  std::function<void(Result<ServiceRecord>)> found;
  OperationId Publish(ServiceRecord r, ResultCallback<void> cb) override {
    publication = std::move(r);
    cb(Result<void>::Success());
    return 1;
  }
  OperationId Browse(std::string,
                     std::function<void(Result<ServiceRecord>)> cb) override {
    found = std::move(cb);
    return 1;
  }
  OperationId BrowseEvents(std::string s,
                           std::function<void(Result<ServiceRecord>)> cb,
                           std::function<void(ServiceInstanceId)>) override {
    return Browse(s, std::move(cb));
  }
  void Stop() override {
    if (throw_stop)
      throw std::runtime_error("dns stop");
  }
};
struct Sockets : ISocketFactory {
  std::function<void(std::string, ResultCallback<std::shared_ptr<ISocket>>)>
      connect;
  std::function<void(Result<std::shared_ptr<ISocket>>)> incoming;
  ResultCallback<std::shared_ptr<ISocket>> pending;
  OperationId Connect(std::string address, std::uint16_t,
                      ResultCallback<std::shared_ptr<ISocket>> cb) override {
    if (connect)
      connect(address, std::move(cb));
    else
      pending = std::move(cb);
    return 1;
  }
  OperationId
  Listen(std::uint16_t,
         std::function<void(Result<std::shared_ptr<ISocket>>)>) override {
    return 0;
  }
  OperationId ListenBound(
      std::uint16_t, ResultCallback<std::uint16_t> cb,
      std::function<void(Result<std::shared_ptr<ISocket>>)> f) override {
    incoming = std::move(f);
    cb(Result<std::uint16_t>::Success(43210));
    return 1;
  }
  void Stop() override {}
};
struct PlatformFake : IPlatform, IStreamFactory, IPlatformClipboard {
  Store store;
  Dns dns;
  ::Sockets sockets;
  std::function<void()> network;
  int reads = 0, writes = 0;
  bool throw_read = false;
  ResultCallback<ShareContent> read;
  ResultCallback<void> write;
  ShareContent written;
  IDnsSd &DnsSd() override { return dns; }
  ISocketFactory &Sockets() override { return sockets; }
  IIdentityStore &IdentityStore() override { return store; }
  IStreamFactory &Streams() override { return *this; }
  IPlatformClipboard &Clipboard() override { return *this; }
  Platform PlatformKind() const override { return Platform::Windows; }
  std::shared_ptr<IExecutor> DefaultExecutor() override { return {}; }
  void SetNetworkChangeHandler(std::function<void()> f) override {
    network = std::move(f);
  }
  Result<std::shared_ptr<unishare::IStream>> OpenSource(std::string) override {
    return Result<std::shared_ptr<unishare::IStream>>::Failure(
        {ErrorCode::StorageFailed, "unused", {}});
  }
  Result<std::shared_ptr<unishare::IStream>>
  OpenDestination(std::string s) override {
    return OpenSource(s);
  }
  OperationId Read(ResultCallback<ShareContent> cb) override {
    ++reads;
    if (throw_read)
      throw std::system_error(std::error_code(73, std::generic_category()));
    read = std::move(cb);
    return 1;
  }
  OperationId Write(ShareContent c, ResultCallback<void> cb) override {
    ++writes;
    written = std::move(c);
    write = std::move(cb);
    return 1;
  }
};
struct Socket : ISocket {
  std::mutex mutex;
  std::weak_ptr<Socket> peer;
  std::vector<std::uint8_t> bytes;
  ResultCallback<std::vector<std::uint8_t>> reader;
  std::size_t requested = 0;
  bool closed = false, eof = false;
  std::string RemoteEndpoint() const override { return "192.0.2.9"; }
  void Deliver() {
    ResultCallback<std::vector<std::uint8_t>> cb;
    std::vector<std::uint8_t> out;
    {
      std::lock_guard<std::mutex> l(mutex);
      if (!reader || (bytes.empty() && !closed && !eof))
        return;
      cb = std::move(reader);
      auto n = (std::min)(requested, bytes.size());
      out.assign(bytes.begin(), bytes.begin() + n);
      bytes.erase(bytes.begin(), bytes.begin() + n);
    }
    cb(Result<std::vector<std::uint8_t>>::Success(std::move(out)));
  }
  OperationId Read(std::size_t n,
                   ResultCallback<std::vector<std::uint8_t>> cb) override {
    {
      std::lock_guard<std::mutex> l(mutex);
      CHECK(!reader);
      requested = n;
      reader = std::move(cb);
    }
    Deliver();
    return 1;
  }
  OperationId Write(std::vector<std::uint8_t> b,
                    ResultCallback<void> cb) override {
    auto p = peer.lock();
    if (!p) {
      cb(Result<void>::Failure({ErrorCode::NetworkLost, "closed", {}}));
      return 1;
    }
    {
      std::lock_guard<std::mutex> l(p->mutex);
      p->bytes.insert(p->bytes.end(), b.begin(), b.end());
    }
    p->Deliver();
    cb(Result<void>::Success());
    return 1;
  }
  void Close() override {
    {
      std::lock_guard<std::mutex> l(mutex);
      closed = true;
    }
    Deliver();
    if (auto p = peer.lock()) {
      {
        std::lock_guard<std::mutex> l(p->mutex);
        p->eof = true;
      }
      p->Deliver();
    }
  }
  void AbortAndCleanup() override { Close(); }
  void Cancel(OperationId) override { Close(); }
};
struct Memory : unishare::IStream {
  std::vector<std::uint8_t> bytes;
  std::size_t offset = 0;
  bool closed = false, aborted = false, hold = false, throw_read = false;
  ResultCallback<void> pending;
  OperationId Read(std::size_t n,
                   ResultCallback<std::vector<std::uint8_t>> cb) override {
    if (throw_read)
      throw std::system_error(std::error_code(79, std::generic_category()));
    n = (std::min)(n, bytes.size() - offset);
    std::vector<std::uint8_t> out(bytes.begin() + offset,
                                  bytes.begin() + offset + n);
    offset += n;
    cb(Result<std::vector<std::uint8_t>>::Success(out));
    return 1;
  }
  OperationId Write(std::vector<std::uint8_t> b,
                    ResultCallback<void> cb) override {
    bytes.insert(bytes.end(), b.begin(), b.end());
    if (hold)
      pending = std::move(cb);
    else
      cb(Result<void>::Success());
    return 1;
  }
  void Close() override { closed = true; }
  void AbortAndCleanup() override {
    closed = true;
    aborted = true;
    bytes.clear();
  }
};
