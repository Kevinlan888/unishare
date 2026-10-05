#include "platform/android/sockets.hpp"
#include <future>
#include <iostream>
#include <atomic>
using namespace unishare;
template<class T> T wait(std::future<T> f){if(f.wait_for(std::chrono::seconds(10))!=std::future_status::ready)throw std::runtime_error("callback timed out");return f.get();}
#define CHECK(v) do{if(!(v))throw std::runtime_error(#v);}while(false)
int main(){try{
 android::SocketFactory factory;std::promise<Result<std::uint16_t>> bound;std::promise<Result<std::shared_ptr<ISocket>>> accepted;
 std::atomic<bool> got{false};factory.ListenBound(0,[&](auto r){bound.set_value(r);},[&](auto r){if(!got.exchange(true))accepted.set_value(r);});
 auto port=wait(bound.get_future());CHECK(port.ok());CHECK(port.value()!=0);
 std::promise<Result<std::shared_ptr<ISocket>>> connected;factory.Connect("127.0.0.1",port.value(),[&](auto r){connected.set_value(r);});
 auto client=wait(connected.get_future()),server=wait(accepted.get_future());CHECK(client.ok());CHECK(server.ok());
 CHECK(server.value()->RemoteEndpoint()=="127.0.0.1");
 std::promise<Result<std::vector<uint8_t>>> read;server.value()->Read(2,[&](auto r){read.set_value(r);});
 std::promise<Result<void>> write;client.value()->Write({1,2},[&](auto r){write.set_value(r);});CHECK(wait(write.get_future()).ok());
 auto bytes=wait(read.get_future());CHECK(bytes.ok());CHECK(bytes.value()==std::vector<uint8_t>({1,2}));
 std::promise<Result<std::vector<uint8_t>>> pending;std::atomic<int> calls{0};
 auto operation=server.value()->Read(2,[&](auto r){if(++calls==1)pending.set_value(r);});server.value()->Cancel(operation);
 auto cancelled=wait(pending.get_future());CHECK(!cancelled.ok());CHECK(cancelled.error().code==ErrorCode::Cancelled);
 factory.Stop();CHECK(calls==1);
 std::promise<Result<std::shared_ptr<ISocket>>> invalid;factory.Connect("localhost",1,[&](auto r){invalid.set_value(r);});auto bad=wait(invalid.get_future());CHECK(!bad.ok());CHECK(bad.error().code==ErrorCode::InvalidInput);
 std::promise<Result<std::uint16_t>> rebound;factory.ListenBound(0,[&](auto r){rebound.set_value(r);},[](auto){});CHECK(wait(rebound.get_future()).ok());factory.Stop();
 android::SocketFactory ipv6;std::promise<Result<std::uint16_t>> bound6;std::promise<Result<std::shared_ptr<ISocket>>> accepted6;std::atomic<bool> got6{false};
 ipv6.ListenBound(0,[&](auto r){bound6.set_value(r);},[&](auto r){if(!got6.exchange(true))accepted6.set_value(r);});auto port6=wait(bound6.get_future());CHECK(port6.ok());
 std::promise<Result<std::shared_ptr<ISocket>>> connect6;ipv6.Connect("::1",port6.value(),[&](auto r){connect6.set_value(r);});auto client6=wait(connect6.get_future()),server6=wait(accepted6.get_future());CHECK(client6.ok()&&server6.ok());CHECK(server6.value()->RemoteEndpoint()=="::1");
 std::promise<Result<std::vector<uint8_t>>> stopped6;server6.value()->Read(1,[&](auto r){stopped6.set_value(r);});ipv6.Stop();auto stop6=wait(stopped6.get_future());CHECK(!stop6.ok()&&stop6.error().code==ErrorCode::Cancelled);
 std::cout<<"PASS Android numeric TCP bound port/loopback/cancel/Stop/restart\n";return 0;
 }catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}}
