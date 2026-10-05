#include <unishare/windows.hpp>
#include <windows.h>
#include <bcrypt.h>
#include <atomic>
#include <fstream>
#include <future>
#include <iostream>
#include <map>
#include <mutex>
#include <set>
#include <thread>
using namespace unishare;
namespace {
std::mutex logMutex, stateMutex;
std::atomic<bool> failed{false};
std::map<std::string,int> terminals;
std::vector<std::shared_ptr<std::atomic<int>>> callbacks;
std::string role, run;
std::string Q(const std::string& s) {
 std::string out="\""; const char* hex="0123456789abcdef";
 for(unsigned char c:s) { if(c=='"'||c=='\\'){out+='\\';out+=c;} else if(c<32){out+="\\u00";out+=hex[c>>4];out+=hex[c&15];} else out+=c; } return out+'"';
}
void Log(std::string event,std::initializer_list<std::pair<std::string,std::string>> fields={}) {
 std::lock_guard<std::mutex> lock(logMutex);
 std::cout<<"{\"event\":"<<Q(event)<<",\"role\":"<<Q(role)<<",\"run\":"<<Q(run);
 for(auto& p:fields)std::cout<<','<<Q(p.first)<<':'<<Q(p.second);std::cout<<"}"<<std::endl;
}
void Require(bool value,const std::string& why){if(!value)throw std::runtime_error(why);}
std::string Read(const std::filesystem::path& p){std::ifstream in(p,std::ios::binary);Require(bool(in),"read "+p.string());return {std::istreambuf_iterator<char>(in),{}};}
std::string Hash(const std::string& data){
 BCRYPT_ALG_HANDLE alg=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;unsigned char digest[32];
 Require(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)==0,"hash provider");
 auto status=BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0);
 if(status==0)status=BCryptHashData(hash,(PUCHAR)data.data(),static_cast<ULONG>(data.size()),0);
 if(status==0)status=BCryptFinishHash(hash,digest,32,0);
 if(hash)BCryptDestroyHash(hash);BCryptCloseAlgorithmProvider(alg,0);Require(status==0,"sha256");
 std::string result;const char* h="0123456789abcdef";for(auto b:digest){result+=h[b>>4];result+=h[b&15];}return result;
}
template<class T,class F> std::future<Result<T>> Submit(std::string name,F f,RequestId request=0,std::string transfer=""){
 auto promise=std::make_shared<std::promise<Result<T>>>();auto future=promise->get_future();auto count=std::make_shared<std::atomic<int>>(0);
 {std::lock_guard<std::mutex> lock(stateMutex);callbacks.push_back(count);}
 f([promise,count,name,request,transfer](auto r){if(++*count!=1){failed=true;Log("duplicate_operation",{{"operation",name}});return;}
  Log("operation",{{"operation",name},{"requestId",std::to_string(request)},{"id",transfer},{"error",r.ok()?"":std::to_string((int)r.error().code)}});promise->set_value(std::move(r));});return future;
}
template<class T> Result<T> Wait(std::future<Result<T>>& f){Require(f.wait_for(std::chrono::seconds(35))==std::future_status::ready,"operation timeout");return f.get();}
template<class T,class F> Result<T> Await(std::string name,F f){auto v=Submit<T>(name,f);return Wait(v);}
template<class F> void Eventually(F f,const char* message,int seconds=60){auto end=std::chrono::steady_clock::now()+std::chrono::seconds(seconds);while(!f()){Require(!failed,message);Require(std::chrono::steady_clock::now()<end,message);std::this_thread::sleep_for(std::chrono::milliseconds(20));}}
std::shared_ptr<IStream> Stream(const std::filesystem::path& path,bool write){
 HANDLE h=CreateFileW(path.c_str(),write?GENERIC_READ|GENERIC_WRITE|DELETE:GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,write?CREATE_NEW:OPEN_EXISTING,0,nullptr);
 Require(h!=INVALID_HANDLE_VALUE,"file handle");auto stream=write?OpenWindowsDestination(h):OpenWindowsSource(h);CloseHandle(h);Require(stream.ok(),"public stream");return stream.value();
}
}
int main(int argc,char** argv){try{
 Require(argc==7,"usage: interop sender|receiver RUN PEER_NAME ABS_FIXTURES ABS_STATE --test-consent");
 role=argv[1];run=argv[2];std::string expected=argv[3];Require(role=="sender"||role=="receiver","role");Require(std::string(argv[6])=="--test-consent","explicit test consent required");
 auto fixtures=std::filesystem::u8path(argv[4]), root=std::filesystem::u8path(argv[5]);Require(root.is_absolute()&&fixtures.is_absolute(),"absolute paths required");std::filesystem::create_directories(root);
 Log("begin",{{"platform","windows"},{"sdkPlatform","windows"},{"expectedPeer",expected},{"name","interop-"+run+"-"+role}});
 SdkConfig config;config.device_name="interop-"+run+"-"+role;config.request_timeout=std::chrono::seconds(30);
 WindowsOptions options;options.identity_directory=root/"identity";
 std::optional<DeviceInfo> peer;std::atomic<int> offered{0};
 const std::vector<std::string> names={"text.txt","image.png","image.jpg","empty.bin","large.bin","clipboard.txt"};
 std::map<std::string,std::pair<std::filesystem::path,std::string>> received;
 // Destroy/stop SDK before the callback-captured host state, including failure exits.
 auto sdk=CreateWindowsShareSdk(config,options);
 Require(Await<void>("initialize",[&](auto cb){sdk->Initialize({},cb);}).ok(),"initialize");
 sdk->Discovery()->SetErrorHandler([](auto e){Log("discovery_error",{{"error",std::to_string((int)e.code)},{"native",std::to_string(e.platform_error.value_or(0))}});});
 sdk->Discovery()->SetEventHandler([&](auto e){Log("discovery",{{"id",e.device.id.ToString()},{"name",e.device.name}});});
 sdk->Connections()->SetPairingRequestHandler([&](auto p){
  bool match=p.device.name==expected;Log("pairing",{{"id",p.device.id.ToString()},{"sas",p.verification_code},{"decision",match?"confirm":"reject"}});
  Submit<void>("confirm",[&](auto cb){sdk->Connections()->ConfirmPairing(p.id,match,cb);});
 });
 sdk->Transfers()->SetIncomingOfferHandler([&](auto o){try{
  Require(role=="receiver","unexpected offer on sender");bool known=false;
  for(auto d:sdk->Discovery()->GetDevices())if(d.id==o.device_id&&d.name==expected)known=true;
  Require(known,"unexpected peer");int index=offered++;
  Log("offer",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"kind",std::to_string((int)o.content_type)},{"size",std::to_string(o.byte_count)}});
  if(index==6){Log("consent",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"decision","reject"}});Submit<void>("reject",[&](auto cb){sdk->Transfers()->Reject(o.request_id,cb);},o.request_id,o.transfer_id.ToString());return;}
  Require(index<6,"extra offer");auto expectedBytes=Read(fixtures/names[index]);
  Require(o.byte_count==expectedBytes.size(),"offer size");
  Require(o.content_type==(index==0||index==5?ContentType::Text:index<3?ContentType::Image:ContentType::File),"offer kind");
  auto path=root/("received-"+std::to_string(index));
  {std::lock_guard<std::mutex> lock(stateMutex);received.emplace(o.transfer_id.ToString(),std::make_pair(path,names[index]));}
  auto stream=Stream(path,true);Log("consent",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"decision","accept"}});
  Submit<void>("accept",[&](auto cb){sdk->Transfers()->Accept(o.request_id,stream,cb);},o.request_id,o.transfer_id.ToString());
 }catch(const std::exception& e){failed=true;Log("failure",{{"message",e.what()}});}});
 sdk->Transfers()->SetProgressHandler([](auto p){Log("progress",{{"id",p.id.ToString()},{"bytes",std::to_string(p.transferred)},{"size",std::to_string(p.total)}});});
 sdk->Transfers()->SetResultHandler([&](auto r){try{
  std::lock_guard<std::mutex> lock(stateMutex);auto id=r.id.ToString();Require(++terminals[id]==1,"duplicate terminal");
  Log("terminal",{{"id",id},{"error",r.result.ok()?"":std::to_string((int)r.result.error().code)}});
  auto found=received.find(id);
  if(role=="receiver")Require(found!=received.end()?r.result.ok():!r.result.ok()&&r.result.error().code==ErrorCode::TransferRejected,"receive terminal outcome");
  if(found!=received.end()&&r.result.ok()){
   auto bytes=Read(found->second.first);Require(bytes==Read(fixtures/found->second.second),"received bytes");
   Log("received",{{"id",id},{"name",found->second.second},{"size",std::to_string(bytes.size())},{"sha256",Hash(bytes)}});
  }
 }catch(const std::exception& e){failed=true;Log("failure",{{"message",e.what()}});}});
 Require(Await<void>("start",[&](auto cb){sdk->Start(cb);}).ok(),"start");
 Eventually([&]{for(auto d:sdk->Discovery()->GetDevices())if(d.name==expected){peer=d;return true;}return false;},"discovery timeout");
 std::string addresses;for(auto a:peer->addresses){if(!addresses.empty())addresses+=',';addresses+=a;}
 Log("discovered",{{"id",peer->id.ToString()},{"name",peer->name},{"platform",std::to_string((int)peer->platform)},{"protocol",std::to_string(peer->protocol.major)+"."+std::to_string(peer->protocol.minor)},{"addresses",addresses},{"port",std::to_string(peer->port)}});
 if(role=="sender"){
  Require(Await<void>("connect",[&](auto cb){sdk->Connections()->Connect(peer->id,cb);}).ok(),"connect");
  for(size_t i=0;i<names.size();++i){auto bytes=Read(fixtures/names[i]);
   if(i==5){ShareContent content;content.text=bytes;Require(Await<void>("clipboard_write",[&](auto cb){sdk->Clipboard()->WriteContent(content,cb);}).ok(),"clipboard write");auto value=Await<ShareContent>("clipboard_read",[&](auto cb){sdk->Clipboard()->ReadShareableContent(cb);});Require(value.ok()&&value.value().text==bytes,"clipboard read");Log("clipboard",{{"decision","explicit_read_send"}});}
   auto result=Await<TransferId>("send",[&](auto cb){if(i==0||i==5)sdk->Transfers()->SendText(peer->id,bytes,cb);else{TransferSource source{names[i],i==1?"image/png":i==2?"image/jpeg":"application/octet-stream",bytes.size(),Stream(fixtures/names[i],false)};if(i<3)sdk->Transfers()->SendImage(peer->id,source,cb);else sdk->Transfers()->SendFile(peer->id,source,cb);}});
   Require(result.ok(),"send failed");Log("sent",{{"id",result.value().ToString()},{"name",names[i]},{"size",std::to_string(bytes.size())},{"sha256",Hash(bytes)}});
  }
  auto rejected=Await<TransferId>("send_reject",[&](auto cb){sdk->Transfers()->SendText(peer->id,"reject",cb);});Require(!rejected.ok()&&rejected.error().code==ErrorCode::TransferRejected,"reject outcome");
 }
 Eventually([&]{std::lock_guard<std::mutex> lock(stateMutex);return terminals.size()==7;},"seven terminal outcomes",120);
 Require(Await<void>("stop",[&](auto cb){sdk->Stop(cb);}).ok(),"stop");
 {std::lock_guard<std::mutex> lock(stateMutex);for(auto count:callbacks)Require(*count==1,"operation exact once");}
 Require(!failed,"event failure");Log("complete",{{"terminals","7"}});return 0;
 }catch(const std::exception& e){Log("failure",{{"message",e.what()}});return 1;}catch(...){Log("failure",{{"message","unexpected exception"}});return 2;}}
