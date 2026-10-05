#include "Ports.hpp"
#include <atomic>
#include <future>
#include <stdexcept>
#include <thread>
#include <cstdio>

using namespace unishare;
using namespace unishare::ios;
#define REQUIRE(x) do { if (!(x)) { fprintf(stderr,"FAIL reentrant Stop %d: %s\n",__LINE__,#x); throw std::runtime_error(#x); } } while(0)

namespace {
template<class T,class Submit> Result<T> Wait(Submit submit) {
 auto completion=std::make_shared<std::promise<Result<T>>>();
 auto future=completion->get_future();
 submit([completion](auto result){completion->set_value(std::move(result));});
 REQUIRE(future.wait_for(std::chrono::seconds(10))==std::future_status::ready);
 return future.get();
}
struct Pair {
 std::shared_ptr<ISocket> client,server;
};
Pair ConnectPair(const std::shared_ptr<ISocketFactory>& server,
                 const std::shared_ptr<ISocketFactory>& client) {
 auto accepted=std::make_shared<std::promise<std::shared_ptr<ISocket>>>();
 auto peer=accepted->get_future();
 auto port=Wait<uint16_t>([&](auto ready) {
  server->ListenBound(0,ready,[accepted](auto result) {
   if(result.ok())accepted->set_value(result.value());
  });
 });
 REQUIRE(port.ok());
 auto outgoing=Wait<std::shared_ptr<ISocket>>([&](auto ready) {
  client->Connect("127.0.0.1",port.value(),ready);
 });
 REQUIRE(outgoing.ok());
 REQUIRE(peer.wait_for(std::chrono::seconds(10))==std::future_status::ready);
 return {outgoing.value(),peer.get()};
}
struct Observed {
 std::atomic<bool> insideRecursiveStop{false};
 std::atomic<int> nestedCompletions{0},wrongResults{0};
 std::array<std::atomic<int>,4> calls{};
 Observed(){for(auto& count:calls)count=0;}
 template<class T> void ResultAt(size_t index,const Result<T>& result) {
  ++calls[index];
  if(insideRecursiveStop)++nestedCompletions;
  if(result.ok()||result.error().code!=ErrorCode::Cancelled)++wrongResults;
 }
 void Reenter(const std::shared_ptr<ISocketFactory>& factory) {
  insideRecursiveStop=true;
  factory->Stop();
  insideRecursiveStop=false;
 }
 void Check(size_t total) const {
  REQUIRE(nestedCompletions==0);
  REQUIRE(wrongResults==0);
  for(size_t i=0;i<total;++i)REQUIRE(calls[i]==1);
 }
};
}

void ReentrantSocketStop() {
 auto server=MakeSockets();
 auto client=MakeSockets();
 std::vector<Pair> pairs;
 for(int i=0;i<3;++i)pairs.push_back(ConnectPair(server,client));
 auto observed=std::make_shared<Observed>();
 for(size_t i=0;i<pairs.size();++i) {
  pairs[i].server->Read(1,[observed,server,i](auto result) {
   observed->ResultAt(i,result);
   if(i==0)observed->Reenter(server);
  });
 }
 // All three Read submissions precede this synchronous Stop on the serial queue.
 // A recursive Stop must see already-detached collections, not cancel the other
 // two sockets while the first callback is still executing.
 server->Stop();
 client->Stop();
 std::this_thread::sleep_for(std::chrono::milliseconds(150));
 observed->Check(3);
}

void ReentrantListenerStop() {
 auto server=MakeSockets();
 auto client=MakeSockets();
 auto pair=ConnectPair(server,client);
 auto observed=std::make_shared<Observed>();
 pair.server->Read(1,[observed](auto result){observed->ResultAt(3,result);});

 dispatch_queue_t pausedQueue=nil;
 auto gate=Wait<uint16_t>([&](auto ready) {
  server->ListenBound(0,[&pausedQueue,ready](auto result) {
   if(result.ok()) {
    // Test-only queue capture inside its own native callback. Suspending this
    // known serial queue lets the test enqueue every listener and Stop before
    // Network framework can deliver their ready events. Production has no hook.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    pausedQueue=dispatch_get_current_queue();
#pragma clang diagnostic pop
    dispatch_suspend(pausedQueue);
   }
   ready(result);
  },[](auto){});
 });
 REQUIRE(gate.ok());
 struct Resume {
  dispatch_queue_t queue;
  bool pending=true;
  ~Resume(){if(pending)dispatch_resume(queue);}
  void Now(){pending=false;dispatch_resume(queue);}
 } resume{pausedQueue};

 for(size_t i=0;i<3;++i) {
  server->ListenBound(0,[observed,server,i](auto result) {
   observed->ResultAt(i,result);
   if(i==0)observed->Reenter(server);
  },[](auto){});
 }
 auto stopped=std::make_shared<std::promise<void>>();
 auto finished=stopped->get_future();
 dispatch_async(pausedQueue,^{server->Stop();stopped->set_value();});
 resume.Now();
 REQUIRE(finished.wait_for(std::chrono::seconds(10))==std::future_status::ready);
 client->Stop();
 std::this_thread::sleep_for(std::chrono::milliseconds(150));
 // The active socket must also have been detached before listener callbacks:
 // moving only listeners would cancel this read inside the recursive Stop.
 observed->Check(4);
}
