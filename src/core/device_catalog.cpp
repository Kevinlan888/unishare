#include "device_catalog.hpp"
#include <algorithm>
#include <charconv>
#include <set>
namespace unishare::core {
namespace {
bool Utf8(const std::string& s) {
 for (std::size_t i=0;i<s.size();) {
  auto c=static_cast<unsigned char>(s[i++]); if(c<128) {if(c==0)return false; continue;}
  unsigned n=0, value=0, minimum=0;
  if(c>=0xc2 && c<=0xdf){n=1;value=c&31;minimum=128;}
  else if(c>=0xe0 && c<=0xef){n=2;value=c&15;minimum=2048;}
  else if(c>=0xf0 && c<=0xf4){n=3;value=c&7;minimum=65536;} else return false;
  if(i+n>s.size())return false;
  while(n--){auto b=static_cast<unsigned char>(s[i++]);if((b&0xc0)!=0x80)return false;value=(value<<6)|(b&63);}
  if(value<minimum || value>0x10ffff || (value>=0xd800 && value<=0xdfff))return false;
 } return true;
}
bool Number(std::string_view s,std::uint16_t& value){ unsigned v=0; auto r=std::from_chars(s.data(),s.data()+s.size(),v);if(s.empty()||r.ec!=std::errc{}||r.ptr!=s.data()+s.size()||v>65535)return false;value=static_cast<std::uint16_t>(v);return true; }
std::optional<DeviceInfo> Parse(const ServiceRecord& r) {
 if(r.service_type!="_share._tcp.local." || r.instance.empty() || r.interface_id.empty() || r.srv_target.empty() || !r.port || r.addresses.empty()) return {};
 auto txt=r.txt;
 if(!r.txt_bytes.empty()) {
  if(r.txt_bytes.size()>1300)return {}; txt.clear();
  for(std::size_t i=0;i<r.txt_bytes.size();) {auto n=r.txt_bytes[i++];if(n==0||i+n>r.txt_bytes.size())return {}; std::string s(r.txt_bytes.begin()+i,r.txt_bytes.begin()+i+n);i+=n;auto eq=s.find('=');if(eq==std::string::npos)return {};txt.emplace_back(s.substr(0,eq),s.substr(eq+1));}
 }
 std::map<std::string,std::string> fields; std::size_t total=0;
 for(auto& p:txt){total+=p.first.size()+p.second.size()+2;if(p.first.empty()||p.first.size()+p.second.size()+1>255||total>1300||!Utf8(p.second))return {}; for(unsigned char c:p.first)if(c<33||c>126||c=='=')return {}; auto key=p.first;std::transform(key.begin(),key.end(),key.begin(),[](unsigned char c){return c>='A'&&c<='Z'?c+32:c;});if(!fields.emplace(key,p.second).second)return {};}
 for(auto key:{"id","name","platform","pv"})if(!fields.count(key))return {};
 auto id=Uuid::Parse(fields["id"]);if(!id||fields["name"].empty())return {};
 DeviceInfo d;d.id=*id;d.name=fields["name"];
 auto p=fields["platform"];if(p=="windows")d.platform=Platform::Windows;else if(p=="android")d.platform=Platform::Android;else if(p=="ios")d.platform=Platform::Ios;else return {};
 auto pv=fields["pv"];auto dot=pv.find('.');if(dot==std::string::npos||!Number(std::string_view(pv).substr(0,dot),d.protocol.major)||!Number(std::string_view(pv).substr(dot+1),d.protocol.minor)||d.protocol.major!=1)return {};
 d.addresses=r.addresses;if(std::any_of(d.addresses.begin(),d.addresses.end(),[](const auto& a){return a.empty();}))return {};std::sort(d.addresses.begin(),d.addresses.end());d.addresses.erase(std::unique(d.addresses.begin(),d.addresses.end()),d.addresses.end());d.port=r.port;return d;
}
bool Same(const DeviceInfo& a,const DeviceInfo& b){return a.id==b.id&&a.name==b.name&&a.platform==b.platform&&a.protocol.major==b.protocol.major&&a.protocol.minor==b.protocol.minor&&a.port==b.port&&a.addresses==b.addresses;}
DeviceInfo Aggregate(const std::map<ServiceInstanceId,DeviceInfo>& records, DeviceInfo selected){std::set<std::string> addresses;for(auto& p:records)if(p.second.port==selected.port)addresses.insert(p.second.addresses.begin(),p.second.addresses.end());selected.addresses.assign(addresses.begin(),addresses.end());return selected;}
}
DeviceCatalog::DeviceCatalog(DeviceId self,std::function<TimePoint()> clock):self_(self),clock_(std::move(clock)){}
std::vector<DeviceEvent> DeviceCatalog::Apply(ServiceRecord record) {
 const auto now = clock_();
 const ServiceInstanceId key{record.instance, record.interface_id};
 auto parsed = Parse(record);
 if (!parsed || parsed->id == self_) return Remove(key, now);
 parsed->last_seen = now;
 const auto device_key = parsed->id.ToString();
 std::vector<DeviceEvent> events;
 // A replacement retires the old owner's reference through the normal removal
 // path, including its surviving address update or final-reference debounce.
 for (const auto& device : devices_) {
  if (device.first != device_key && device.second.records.count(key)) {
   events = Remove(key, now);
   break;
  }
 }
 auto inserted = devices_.try_emplace(device_key);
 auto& entry = inserted.first->second;
 entry.records[key] = *parsed;
 entry.deadline.reset();
 auto visible = Aggregate(entry.records, *parsed);
 if (inserted.second) events.push_back({DiscoveryEventKind::Online, visible});
 else if (!Same(entry.visible, visible)) events.push_back({DiscoveryEventKind::Updated, visible});
 entry.visible = visible;
 return events;
}
std::vector<DeviceEvent> DeviceCatalog::Remove(ServiceInstanceId key,TimePoint now){std::vector<DeviceEvent> events;for(auto& p:devices_){auto& e=p.second;if(!e.records.erase(key))continue;if(e.records.empty())e.deadline=now+std::chrono::seconds(5);else {auto d=Aggregate(e.records,e.records.begin()->second);if(!Same(e.visible,d))events.push_back({DiscoveryEventKind::Updated,d});e.visible=d;}}return events;}
std::vector<DeviceEvent> DeviceCatalog::Expire(TimePoint now){std::vector<DeviceEvent> events;for(auto i=devices_.begin();i!=devices_.end();)if(i->second.deadline&&now>=*i->second.deadline){events.push_back({DiscoveryEventKind::Offline,i->second.visible});i=devices_.erase(i);}else ++i;return events;}
std::vector<DeviceInfo> DeviceCatalog::Snapshot()const{std::vector<DeviceInfo> out;for(auto& p:devices_)out.push_back(p.second.visible);return out;}
}
