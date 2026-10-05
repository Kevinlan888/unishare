#include <unishare/windows.hpp>
#include <windows.h>
#include <bcrypt.h>
#include <wincodec.h>
#include <wrl/client.h>
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
std::string PixelHash(const std::string& data){
 using Microsoft::WRL::ComPtr;
 auto init=CoInitializeEx(nullptr,COINIT_MULTITHREADED);Require(SUCCEEDED(init)||init==RPC_E_CHANGED_MODE,"COM init");
 ComPtr<IWICImagingFactory> factory;ComPtr<IWICStream> stream;ComPtr<IWICBitmapDecoder> decoder;ComPtr<IWICBitmapFrameDecode> frame;ComPtr<IWICFormatConverter> converter;
 Require(SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory))),"image decoder");
 Require(SUCCEEDED(factory->CreateStream(&stream))&&SUCCEEDED(stream->InitializeFromMemory((BYTE*)data.data(),static_cast<DWORD>(data.size()))),"image memory");
 Require(SUCCEEDED(factory->CreateDecoderFromStream(stream.Get(),nullptr,WICDecodeMetadataCacheOnLoad,&decoder))&&SUCCEEDED(decoder->GetFrame(0,&frame)),"image frame");
 UINT w=0,h=0;Require(SUCCEEDED(frame->GetSize(&w,&h))&&w==2&&h==2,"clipboard dimensions");
 Require(SUCCEEDED(factory->CreateFormatConverter(&converter))&&SUCCEEDED(converter->Initialize(frame.Get(),GUID_WICPixelFormat32bppRGBA,WICBitmapDitherTypeNone,nullptr,0,WICBitmapPaletteTypeCustom)),"image RGBA");
 std::string pixels(16,0);Require(SUCCEEDED(converter->CopyPixels(nullptr,8,16,(BYTE*)pixels.data())),"image pixels");auto result=Hash(pixels);converter.Reset();frame.Reset();decoder.Reset();stream.Reset();factory.Reset();if(SUCCEEDED(init))CoUninitialize();return result;
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
std::shared_ptr<unishare::IStream> Stream(const std::filesystem::path& path,bool write){
 HANDLE h=CreateFileW(path.c_str(),write?GENERIC_READ|GENERIC_WRITE|DELETE:GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,write?CREATE_NEW:OPEN_EXISTING,0,nullptr);
 Require(h!=INVALID_HANDLE_VALUE,"file handle");auto stream=write?OpenWindowsDestination(h):OpenWindowsSource(h);CloseHandle(h);Require(stream.ok(),"public stream");return stream.value();
}
}
int main(int argc,char** argv){try{
 Require(argc==7,"usage: interop sender|receiver RUN PEER_NAME ABS_FIXTURES ABS_STATE --test-consent");
 role=argv[1];run=argv[2];std::string expected=argv[3];Require(role=="sender"||role=="receiver","role");Require(std::string(argv[6])=="--test-consent","explicit test consent required");
 auto fixtures=std::filesystem::u8path(argv[4]), root=std::filesystem::u8path(argv[5]);Require(root.is_absolute()&&fixtures.is_absolute(),"absolute paths required");std::filesystem::create_directories(root);
 Log("begin",{{"platform","windows"},{"sdkPlatform","windows"},{"scenario","extended"},{"expectedPeer",expected},{"name","interop-"+run+"-"+role}});
 SdkConfig config;config.device_name="interop-"+run+"-"+role;config.request_timeout=std::chrono::seconds(30);
 WindowsOptions options;options.identity_directory=root/"identity";
 std::optional<DeviceInfo> peer;std::atomic<int> offered{0};std::vector<PairingRequest> pendingPairing;
 const std::vector<std::string> names={"text.txt","image.png","image.jpg","empty.bin","large.bin","clipboard.txt","text-large.txt","clipboard.png","isolation.txt"};
 std::atomic<bool> cancelIssued{false};std::atomic<bool> peerOffline{false};
 std::string cancelId;
 std::optional<IncomingOffer> isolationOffer;
 std::map<std::string,std::pair<std::filesystem::path,std::string>> received;
 // Destroy/stop SDK before the callback-captured host state, including failure exits.
 auto sdk=CreateWindowsShareSdk(config,options);
 Require(Await<void>("initialize",[&](auto cb){sdk->Initialize({},cb);}).ok(),"initialize");
 sdk->Discovery()->SetErrorHandler([](auto e){Log("discovery_error",{{"error",std::to_string((int)e.code)},{"native",std::to_string(e.platform_error.value_or(0))}});});
 sdk->Discovery()->SetEventHandler([&](auto e){Log(e.kind==DiscoveryEventKind::Offline?"offline":e.kind==DiscoveryEventKind::Updated?"updated":"discovery",{{"id",e.device.id.ToString()},{"name",e.device.name}});if(e.kind==DiscoveryEventKind::Offline&&e.device.name==expected)peerOffline=true;});
 auto confirm=[&](auto p,bool match){Log("pairing",{{"id",p.device.id.ToString()},{"sas",p.verification_code},{"decision",match?"confirm":"reject"}});Submit<void>("confirm",[&](auto cb){sdk->Connections()->ConfirmPairing(p.id,match,cb);});};
 sdk->Connections()->SetPairingRequestHandler([&](auto p){
  bool ready=false,match=false;{std::lock_guard<std::mutex> lock(stateMutex);if(peer){ready=true;match=p.device.id==peer->id;}else pendingPairing.push_back(p);}
  if(ready)confirm(p,match);
 });
 sdk->Transfers()->SetIncomingOfferHandler([&](auto o){try{
  Require(role=="receiver","unexpected offer on sender");bool known=false;
  {std::lock_guard<std::mutex> lock(stateMutex);known=peer&&peer->id==o.device_id;}
  Require(known,"unexpected peer");int index=offered++;
  Log("offer",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"kind",std::to_string((int)o.content_type)},{"size",std::to_string(o.byte_count)}});
  if(index==8){Log("consent",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"decision","reject"}});Submit<void>("reject",[&](auto cb){sdk->Transfers()->Reject(o.request_id,cb);},o.request_id,o.transfer_id.ToString());return;}
  if(index==10){std::lock_guard<std::mutex> lock(stateMutex);isolationOffer=o;return;}
  Require(index<11,"extra offer");auto fixture=index==9?"cancel.bin":names[index==10?8:index];auto expectedBytes=Read(fixtures/fixture);
  Require(index==7?o.byte_count>0:o.byte_count==expectedBytes.size(),"offer size");
  Require(o.content_type==(index==0||index==5||index==6||index==10?ContentType::Text:index<3||index==7?ContentType::Image:ContentType::File),"offer kind");
  auto path=root/("received-"+std::to_string(index));
  {std::lock_guard<std::mutex> lock(stateMutex);received.emplace(o.transfer_id.ToString(),std::make_pair(path,fixture));if(index==9)cancelId=o.transfer_id.ToString();}
  auto stream=Stream(path,true);Log("consent",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"decision","accept"}});
  Submit<void>("accept",[&](auto cb){sdk->Transfers()->Accept(o.request_id,stream,cb);},o.request_id,o.transfer_id.ToString());
 }catch(const std::exception& e){failed=true;Log("failure",{{"message",e.what()}});}});
 sdk->Transfers()->SetProgressHandler([&](auto p){Log("progress",{{"id",p.id.ToString()},{"bytes",std::to_string(p.transferred)},{"size",std::to_string(p.total)}});bool cancel=false;{std::lock_guard<std::mutex> lock(stateMutex);cancel=p.id.ToString()==cancelId&&p.transferred>0&&p.transferred<p.total&&!cancelIssued.exchange(true);}if(cancel){Log("cancel",{{"id",p.id.ToString()},{"bytes",std::to_string(p.transferred)},{"size",std::to_string(p.total)}});Submit<void>("cancel",[&](auto cb){sdk->Transfers()->Cancel(p.id,cb);},0,p.id.ToString());}});
 sdk->Transfers()->SetResultHandler([&](auto r){try{
  std::lock_guard<std::mutex> lock(stateMutex);auto id=r.id.ToString();Require(++terminals[id]==1,"duplicate terminal");
  Log("terminal",{{"id",id},{"error",r.result.ok()?"":std::to_string((int)r.result.error().code)}});
  auto found=received.find(id);
  if(role=="receiver")Require(id==cancelId?!r.result.ok()&&r.result.error().code==ErrorCode::Cancelled:found!=received.end()?r.result.ok():!r.result.ok()&&r.result.error().code==ErrorCode::TransferRejected,"receive terminal outcome");
  if(id==cancelId){Require(!std::filesystem::exists(found->second.first),"cancellation staging cleanup");Log("cleanup",{{"id",id},{"deleted","true"}});}
  if(found!=received.end()&&r.result.ok()){
   auto bytes=Read(found->second.first);auto clipboardImage=found->second.second=="clipboard.png";Require(clipboardImage?PixelHash(bytes)==PixelHash(Read(fixtures/found->second.second)):bytes==Read(fixtures/found->second.second),"received bytes");
   Log("received",{{"id",id},{"name",found->second.second},{"size",std::to_string(bytes.size())},{"sha256",Hash(bytes)},{"pixel_sha256",clipboardImage?PixelHash(bytes):""}});
  }
 }catch(const std::exception& e){failed=true;Log("failure",{{"message",e.what()}});}});
 Require(Await<void>("start",[&](auto cb){sdk->Start(cb);}).ok(),"start");
 Eventually([&]{for(auto d:sdk->Discovery()->GetDevices())if(d.name==expected){std::lock_guard<std::mutex> lock(stateMutex);peer=d;return true;}return false;},"discovery timeout");
 std::vector<PairingRequest> waiting;{std::lock_guard<std::mutex> lock(stateMutex);waiting.swap(pendingPairing);}for(auto p:waiting)confirm(p,p.device.id==peer->id);
 std::string addresses;for(auto a:peer->addresses){if(!addresses.empty())addresses+=',';addresses+=a;}
 Log("discovered",{{"id",peer->id.ToString()},{"name",peer->name},{"platform",std::to_string((int)peer->platform)},{"protocol",std::to_string(peer->protocol.major)+"."+std::to_string(peer->protocol.minor)},{"addresses",addresses},{"port",std::to_string(peer->port)}});
 if(role=="sender"){
  Require(Await<void>("connect",[&](auto cb){sdk->Connections()->Connect(peer->id,cb);}).ok(),"connect");
  for(size_t i=0;i<8;++i){auto bytes=Read(fixtures/names[i]);
   if(i==5){ShareContent content;content.text=bytes;Require(Await<void>("clipboard_write",[&](auto cb){sdk->Clipboard()->WriteContent(content,cb);}).ok(),"clipboard write");auto value=Await<ShareContent>("clipboard_read",[&](auto cb){sdk->Clipboard()->ReadShareableContent(cb);});Require(value.ok()&&value.value().text==bytes,"clipboard read");Log("clipboard",{{"decision","explicit_read_send"}});}
   if(i==7){ShareContent content;content.type=ContentType::Image;content.mime_type="image/png";content.image.assign(bytes.begin(),bytes.end());Require(Await<void>("clipboard_write",[&](auto cb){sdk->Clipboard()->WriteContent(content,cb);}).ok(),"clipboard image write");auto value=Await<ShareContent>("clipboard_read",[&](auto cb){sdk->Clipboard()->ReadShareableContent(cb);});Require(value.ok()&&value.value().type==ContentType::Image&&value.value().mime_type=="image/png","clipboard image read");bytes.assign(value.value().image.begin(),value.value().image.end());Require(PixelHash(bytes)==PixelHash(Read(fixtures/names[i])),"clipboard image pixels");Log("clipboard",{{"decision","explicit_read_send"}});}
   auto sourcePath=fixtures/names[i];if(i==7){sourcePath=root/"clipboard-source.png";std::ofstream out(sourcePath,std::ios::binary);out.write(bytes.data(),bytes.size());Require(bool(out),"clipboard staging");}
   auto result=Await<TransferId>("send",[&](auto cb){if(i==0||i==5)sdk->Transfers()->SendText(peer->id,bytes,cb);else{TransferSource source{names[i],i==1||i==7?"image/png":i==2?"image/jpeg":"application/octet-stream",bytes.size(),Stream(sourcePath,false)};if(i==6)sdk->Transfers()->SendTextStream(peer->id,source,cb);else if(i<3||i==7)sdk->Transfers()->SendImage(peer->id,source,cb);else sdk->Transfers()->SendFile(peer->id,source,cb);}});
   Require(result.ok(),"send failed");Log("sent",{{"id",result.value().ToString()},{"name",names[i]},{"size",std::to_string(bytes.size())},{"sha256",Hash(bytes)},{"pixel_sha256",i==7?PixelHash(bytes):""}});
  }
  auto rejected=Await<TransferId>("send_reject",[&](auto cb){sdk->Transfers()->SendText(peer->id,"reject",cb);});Require(!rejected.ok()&&rejected.error().code==ErrorCode::TransferRejected,"reject outcome");
  auto cancelled=Await<TransferId>("send_cancel",[&](auto cb){sdk->Transfers()->SendFile(peer->id,{"cancel.bin","application/octet-stream",8*1024*1024,Stream(fixtures/"cancel.bin",false)},cb);});Require(!cancelled.ok()&&(cancelled.error().code==ErrorCode::Cancelled||cancelled.error().code==ErrorCode::NetworkLost),"cancel outcome");
 }
 Eventually([&]{std::lock_guard<std::mutex> lock(stateMutex);return terminals.size()==10;},"ten terminal outcomes",120);
 auto trusted=sdk->Connections()->GetTrustedDevices();Require(trusted.size()==1&&trusted[0].id==peer->id,"trust before restart");
 Require(Await<void>("restart_stop",[&](auto cb){sdk->Stop(cb);}).ok(),"restart stop");Require(Await<void>("restart_start",[&](auto cb){sdk->Start(cb);}).ok(),"restart start");auto restored=sdk->Connections()->GetTrustedDevices();Require(restored.size()==1&&restored[0].id==trusted[0].id&&restored[0].public_key_fingerprint==trusted[0].public_key_fingerprint,"trust restart");Log("restart",{{"trustStable","true"}});
 if(role=="sender"){
  Eventually([&]{for(auto d:sdk->Discovery()->GetDevices())if(d.name==expected&&d.id==peer->id&&d.port!=peer->port){peer=d;return true;}return false;},"restart discovery");
  Require(Await<void>("reconnect",[&](auto cb){sdk->Connections()->Connect(peer->id,cb);}).ok(),"reconnect");auto bytes=Read(fixtures/names[8]);auto result=Await<TransferId>("send",[&](auto cb){sdk->Transfers()->SendText(peer->id,bytes,cb);});Require(result.ok(),"isolation send");Log("sent",{{"id",result.value().ToString()},{"name",names[8]},{"size",std::to_string(bytes.size())},{"sha256",Hash(bytes)}});
 }else{
  Eventually([&]{for(auto d:sdk->Discovery()->GetDevices())if(d.id==peer->id&&d.port!=peer->port)return true;return false;},"receiver restart discovery");
  Eventually([&]{std::lock_guard<std::mutex> lock(stateMutex);return isolationOffer.has_value();},"isolation offer");
  IncomingOffer o;{std::lock_guard<std::mutex> lock(stateMutex);o=*isolationOffer;}Require(o.content_type==ContentType::Text&&o.byte_count==14,"isolation metadata");auto path=root/"received-10";{std::lock_guard<std::mutex> lock(stateMutex);received.emplace(o.transfer_id.ToString(),std::make_pair(path,names[8]));}auto stream=Stream(path,true);Log("consent",{{"id",o.transfer_id.ToString()},{"requestId",std::to_string(o.request_id)},{"decision","accept"}});auto acceptance=Submit<void>("accept",[&](auto cb){sdk->Transfers()->Accept(o.request_id,stream,cb);},o.request_id,o.transfer_id.ToString());Require(Wait(acceptance).ok(),"isolation accept");
 }
 Eventually([&]{std::lock_guard<std::mutex> lock(stateMutex);return terminals.size()==11;},"eleven terminal outcomes",120);
 if(role=="receiver")Eventually([&]{return peerOffline.load();},"peer offline",30);
 Require(Await<void>("stop",[&](auto cb){sdk->Stop(cb);}).ok(),"stop");
 {std::lock_guard<std::mutex> lock(stateMutex);for(auto count:callbacks)Require(*count==1,"operation exact once");}
 Require(!failed,"event failure");Log("complete",{{"terminals","11"}});return 0;
 }catch(const std::exception& e){Log("failure",{{"message",e.what()}});return 1;}catch(...){Log("failure",{{"message","unexpected exception"}});return 2;}}
