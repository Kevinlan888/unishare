#include <unishare/windows.hpp>
#include <winsock2.h>
#include <windows.h>
#include <atomic>
#include <fstream>
#include <future>
#include <iostream>
#include <mutex>
#include <thread>
using namespace unishare;
namespace {
std::mutex output;
void Log(const std::string& s){std::lock_guard<std::mutex> lock(output);std::cout<<s<<std::endl;}
template<class T,class F> Result<T> Await(F f){
 auto promise=std::make_shared<std::promise<Result<T>>>();auto future=promise->get_future();
 f([promise](auto r){promise->set_value(std::move(r));});
 if(future.wait_for(std::chrono::seconds(20))!=std::future_status::ready)throw std::runtime_error("operation timeout");
 auto r=future.get();if(!r.ok())Log("ERROR "+r.error().message+" native="+std::to_string(r.error().platform_error.value_or(0)));return r;
}
void Require(bool b,const char* stage){if(!b)throw std::runtime_error(stage);}
std::string Read(const std::filesystem::path& p){std::ifstream in(p,std::ios::binary);return {std::istreambuf_iterator<char>(in),{}};}
}
int main(int argc,char** argv){
 try{
  if(argc!=4||std::string(argv[3])!="--test-consent")throw std::runtime_error("usage: windows_smoke sender|receiver ABSOLUTE_RUN_DIRECTORY --test-consent");
  std::string role=argv[1];Require(role=="sender"||role=="receiver","role");
  auto root=std::filesystem::u8path(argv[2]);Require(root.is_absolute(),"absolute directory required");std::filesystem::create_directories(root);
  const auto peerRole=role=="sender"?"receiver":"sender";
  SdkConfig config;config.device_name="unishare-smoke-"+role;config.request_timeout=std::chrono::seconds(20);
  WindowsOptions options;options.identity_directory=root/role;
  std::atomic<int> incoming{0};std::atomic<bool> failed{false};std::mutex pairingMutex;std::optional<PairingRequest> pending;
  auto sdk=CreateWindowsShareSdk(config,options);
  Require(Await<void>([&](auto cb){sdk->Initialize({},cb);}).ok(),"initialize");Log("INITIALIZED "+role);

  sdk->Discovery()->SetErrorHandler([](auto e){Log("DISCOVERY_ERROR "+e.message+" native="+std::to_string(e.platform_error.value_or(0)));});
  sdk->Discovery()->SetEventHandler([](auto e){Log("DISCOVERY "+e.device.name+" port="+std::to_string(e.device.port));});
  sdk->Connections()->SetPairingRequestHandler([&](auto request){
   std::ofstream(root/(role+".sas"))<<request.verification_code;
   std::lock_guard<std::mutex> lock(pairingMutex);pending=request;Log("PAIR_REQUEST code="+request.verification_code);
  });
  sdk->Transfers()->SetIncomingOfferHandler([&](auto offer){
   auto path=root/(offer.content_type==ContentType::Text?"received-text":"received-file");
   HANDLE h=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE|DELETE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,CREATE_NEW,0,nullptr);
   if(h==INVALID_HANDLE_VALUE){failed=true;Log("STAGING_ERROR "+std::to_string(GetLastError()));return;}
   auto stream=OpenWindowsDestination(h);CloseHandle(h);
   if(!stream.ok()){failed=true;return;}
   sdk->Transfers()->Accept(offer.request_id,stream.value(),[&](auto r){if(!r.ok())failed=true;});Log("CONSENT "+offer.display_name);
  });
  sdk->Transfers()->SetResultHandler([&](auto result){if(!result.result.ok()){failed=true;Log("TRANSFER_FAILED "+result.result.error().message);}else {++incoming;Log("TRANSFER_COMPLETE");}});
  Require(Await<void>([&](auto cb){sdk->Start(cb);}).ok(),"start");Log("STARTED publication completed");
  auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(30);std::optional<DeviceInfo> peer;
  while(std::chrono::steady_clock::now()<deadline){
   for(auto device:sdk->Discovery()->GetDevices())if(device.name==std::string("unishare-smoke-")+peerRole)peer=device;
   if(peer)break;std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  Require(peer.has_value(),"discovery timeout: production mDNS did not yield peer; pairing/text/file NOT RUN");Log("DISCOVERED "+peer->id.ToString());
  auto connected=std::make_shared<std::promise<Result<void>>>();auto connectFuture=connected->get_future();
  if(role=="sender")sdk->Connections()->Connect(peer->id,[connected](auto r){connected->set_value(std::move(r));});
  deadline=std::chrono::steady_clock::now()+std::chrono::seconds(25);bool confirmed=false;
  while(std::chrono::steady_clock::now()<deadline&&!failed){
   std::optional<PairingRequest> request;{std::lock_guard<std::mutex> lock(pairingMutex);request=pending;}
   if(request&&!confirmed){auto other=Read(root/(std::string(peerRole)+".sas"));if(!other.empty()){
    Require(other==request->verification_code,"SAS mismatch");confirmed=true;
    Require(Await<void>([&](auto cb){sdk->Connections()->ConfirmPairing(request->id,true,cb);}).ok(),"confirm pairing");Log("SAS_MATCH_CONFIRMED");
   }}
   if(confirmed&&!sdk->Connections()->GetTrustedDevices().empty())break;
   std::this_thread::sleep_for(std::chrono::milliseconds(20));
  }
  Require(confirmed&&!sdk->Connections()->GetTrustedDevices().empty(),"pairing timeout");
  if(role=="sender"){
   Require(connectFuture.wait_for(std::chrono::seconds(10))==std::future_status::ready&&connectFuture.get().ok(),"connect");
   Require(Await<TransferId>([&](auto cb){sdk->Transfers()->SendText(peer->id,"Windows production smoke text",cb);}).ok(),"send text");Log("TEXT_PASSED");
   auto path=root/"source-file";{std::ofstream out(path,std::ios::binary);out<<std::string(262145,'F');}
   HANDLE h=CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);
   Require(h!=INVALID_HANDLE_VALUE,"source handle");auto stream=OpenWindowsSource(h);CloseHandle(h);Require(stream.ok(),"source stream");
   TransferSource source{"smoke.bin","application/octet-stream",262145,stream.value()};
   Require(Await<TransferId>([&](auto cb){sdk->Transfers()->SendFile(peer->id,source,cb);}).ok(),"send file");Log("FILE_PASSED");
  }else{
   deadline=std::chrono::steady_clock::now()+std::chrono::seconds(45);
   while(incoming<2&&!failed&&std::chrono::steady_clock::now()<deadline)std::this_thread::sleep_for(std::chrono::milliseconds(20));
   Require(incoming==2&&!failed,"receive timeout/failure");
   Require(Read(root/"received-text")=="Windows production smoke text","text bytes");
   Require(Read(root/"received-file")==std::string(262145,'F'),"file bytes");Log("RECEIVED_BYTES_PASSED");
  }
  Require(Await<void>([&](auto cb){sdk->Stop(cb);}).ok(),"stop");Log("SMOKE_PASSED "+role);return 0;
 }catch(const std::exception& e){Log(std::string("SMOKE_FAILED ")+e.what());return 1;}catch(...){Log("SMOKE_FAILED unexpected exception");return 2;}
}
