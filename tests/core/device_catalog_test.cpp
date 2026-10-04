#include "core/device_catalog.hpp"
#include <cstdlib>
#include <iostream>
void Check(bool condition, int line) {
 if (!condition) { std::cerr << "Check failed at line " << line << '\n'; std::exit(1); }
}
#define CHECK(...) Check((__VA_ARGS__), __LINE__)
using namespace unishare;
using namespace unishare::core;
ServiceRecord Record(std::string iface="wifi") {
 ServiceRecord r; r.instance="peer"; r.interface_id=iface; r.service_type="_share._tcp.local."; r.srv_target="peer.local."; r.port=1234; r.addresses={"192.168.1.2"};
 r.txt={{"id","11111111-1111-4111-8111-111111111111"},{"name","Peer"},{"platform","android"},{"pv","1.2"}}; return r;
}
void ReplacementTests() {
 auto now = TimePoint{};
 const auto self = *Uuid::Parse("22222222-2222-4222-8222-222222222222");
 // Rejected replacements must retire their prior record just like removal.
 for (int replacement = 0; replacement < 3; ++replacement) {
  DeviceCatalog catalog(self, [&] { return now; });
  auto record = Record();
  CHECK(catalog.Apply(record).size() == 1);
  if (replacement == 0) record.txt[1].second = "\xc0\xaf";
  if (replacement == 1) record.txt[3].second = "2.0";
  if (replacement == 2) record.txt[0].second = self.ToString();
  CHECK(catalog.Apply(record).empty());
  CHECK(catalog.Snapshot().size() == 1);
  CHECK(catalog.Expire(now + std::chrono::seconds(4)).empty());
  auto events = catalog.Expire(now + std::chrono::seconds(5));
  CHECK(events.size() == 1 && events[0].kind == DiscoveryEventKind::Offline);
  CHECK(catalog.Snapshot().empty());
 }
 // Reassigning one interface updates A's surviving endpoints and brings B online.
 DeviceCatalog catalog(self, [&] { return now; });
 auto wifi = Record();
 auto ethernet = Record("ethernet");
 ethernet.addresses = {"192.168.1.3"};
 CHECK(catalog.Apply(wifi).size() == 1);
 CHECK(catalog.Apply(ethernet).size() == 1);
 const auto original = *Uuid::Parse(wifi.txt[0].second);
 wifi.txt[0].second = "33333333-3333-4333-8333-333333333333";
 auto events = catalog.Apply(wifi);
 CHECK(events.size() == 2);
 CHECK(events[0].kind == DiscoveryEventKind::Updated && events[0].device.id == original);
 CHECK(events[0].device.addresses == ethernet.addresses);
 CHECK(events[1].kind == DiscoveryEventKind::Online);
 auto snapshot = catalog.Snapshot();
 CHECK(snapshot.size() == 2 && snapshot[0].addresses == ethernet.addresses);
 CHECK(catalog.Expire(now + std::chrono::seconds(5)).empty());
 // An invalid replacement on one of two interfaces also drops only its addresses.
 DeviceCatalog partial(self, [&] { return now; });
 CHECK(partial.Apply(Record()).size() == 1);
 CHECK(partial.Apply(ethernet).size() == 1);
 auto invalid = Record(); invalid.txt[3].second = "2.0";
 events = partial.Apply(invalid);
 CHECK(events.size() == 1 && events[0].kind == DiscoveryEventKind::Updated);
 CHECK(events[0].device.addresses == ethernet.addresses);
 CHECK(partial.Expire(now + std::chrono::seconds(5)).empty());
}
int main() {
 ReplacementTests();
 auto now=TimePoint{}; auto self=*Uuid::Parse("22222222-2222-4222-8222-222222222222");
 DeviceCatalog c(self, [&]{return now;}); auto r=Record();
 auto e=c.Apply(r); CHECK(e.size()==1 && e[0].kind==DiscoveryEventKind::Online); CHECK(e[0].device.name=="Peer" && e[0].device.platform==Platform::Android && e[0].device.protocol.minor==2 && e[0].device.port==1234);
 CHECK(c.Apply(r).empty()); auto r2=Record("ethernet"); CHECK(c.Apply(r2).empty());
 CHECK(c.Remove({r.instance,r.interface_id},now).empty()); CHECK(c.Expire(now+std::chrono::seconds(10)).empty());
 r2.txt[1].second="Renamed"; r2.addresses={"192.168.1.3"}; e=c.Apply(r2); CHECK(e.size()==1 && e[0].kind==DiscoveryEventKind::Updated);
 c.Remove({r2.instance,r2.interface_id},now); CHECK(c.Expire(now+std::chrono::seconds(4)).empty());
 now+=std::chrono::seconds(4); r2.interface_id="wifi2"; r2.addresses={"192.168.1.4"}; e=c.Apply(r2); CHECK(e.size()==1 && e[0].kind==DiscoveryEventKind::Updated);
 c.Remove({r2.instance,r2.interface_id},now); CHECK(c.Expire(now+std::chrono::seconds(4)).empty()); e=c.Expire(now+std::chrono::seconds(5)); CHECK(e.size()==1 && e[0].kind==DiscoveryEventKind::Offline && c.Snapshot().empty());
 for(int bad=0;bad<7;++bad) { DeviceCatalog d(self,[&]{return now;}); r=Record(); if(bad==0) r.txt[1].second="\xc0\xaf"; if(bad==1) r.txt[1].second=std::string(256,'x'); if(bad==2) r.txt[0].second=self.ToString(); if(bad==3) r.txt[3].second="2.0"; if(bad==4) r.txt.push_back(r.txt[0]); if(bad==5) r.txt[3].second="1.0x"; if(bad==6) r.txt_bytes={10,'i','d'}; CHECK(d.Apply(r).empty() && d.Snapshot().empty()); }
 r=Record(); r.txt_bytes.clear(); for(auto& p:r.txt) {auto s=p.first+"="+p.second; r.txt_bytes.push_back(static_cast<std::uint8_t>(s.size())); r.txt_bytes.insert(r.txt_bytes.end(),s.begin(),s.end());} r.txt.clear(); DeviceCatalog raw(self); CHECK(raw.Apply(r).size()==1);
}
