#include "core/device_catalog.hpp"
#include <cassert>
using namespace unishare;
using namespace unishare::core;
ServiceRecord Record(std::string iface="wifi") {
 ServiceRecord r; r.instance="peer"; r.interface_id=iface; r.service_type="_share._tcp.local."; r.srv_target="peer.local."; r.port=1234; r.addresses={"192.168.1.2"};
 r.txt={{"id","11111111-1111-4111-8111-111111111111"},{"name","Peer"},{"platform","android"},{"pv","1.2"}}; return r;
}
int main() {
 auto now=TimePoint{}; auto self=*Uuid::Parse("22222222-2222-4222-8222-222222222222");
 DeviceCatalog c(self, [&]{return now;}); auto r=Record();
 auto e=c.Apply(r); assert(e.size()==1 && e[0].kind==DiscoveryEventKind::Online); assert(e[0].device.name=="Peer" && e[0].device.platform==Platform::Android && e[0].device.protocol.minor==2 && e[0].device.port==1234);
 assert(c.Apply(r).empty()); auto r2=Record("ethernet"); assert(c.Apply(r2).empty());
 assert(c.Remove({r.instance,r.interface_id},now).empty()); assert(c.Expire(now+std::chrono::seconds(10)).empty());
 r2.txt[1].second="Renamed"; r2.addresses={"192.168.1.3"}; e=c.Apply(r2); assert(e.size()==1 && e[0].kind==DiscoveryEventKind::Updated);
 c.Remove({r2.instance,r2.interface_id},now); assert(c.Expire(now+std::chrono::seconds(4)).empty());
 now+=std::chrono::seconds(4); r2.interface_id="wifi2"; r2.addresses={"192.168.1.4"}; e=c.Apply(r2); assert(e.size()==1 && e[0].kind==DiscoveryEventKind::Updated);
 c.Remove({r2.instance,r2.interface_id},now); assert(c.Expire(now+std::chrono::seconds(4)).empty()); e=c.Expire(now+std::chrono::seconds(5)); assert(e.size()==1 && e[0].kind==DiscoveryEventKind::Offline && c.Snapshot().empty());
 for(int bad=0;bad<7;++bad) { DeviceCatalog d(self,[&]{return now;}); r=Record(); if(bad==0) r.txt[1].second="\xc0\xaf"; if(bad==1) r.txt[1].second=std::string(256,'x'); if(bad==2) r.txt[0].second=self.ToString(); if(bad==3) r.txt[3].second="2.0"; if(bad==4) r.txt.push_back(r.txt[0]); if(bad==5) r.txt[3].second="1.0x"; if(bad==6) r.txt_bytes={10,'i','d'}; assert(d.Apply(r).empty() && d.Snapshot().empty()); }
 r=Record(); r.txt_bytes.clear(); for(auto& p:r.txt) {auto s=p.first+"="+p.second; r.txt_bytes.push_back(static_cast<std::uint8_t>(s.size())); r.txt_bytes.insert(r.txt_bytes.end(),s.begin(),s.end());} r.txt.clear(); DeviceCatalog raw(self); assert(raw.Apply(r).size()==1);
}
