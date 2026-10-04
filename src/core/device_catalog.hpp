#pragma once
#include "unishare/discovery.hpp"
#include "unishare/platform.hpp"
#include <map>
namespace unishare::core {
using TimePoint = std::chrono::system_clock::time_point;
using DeviceEvent = DiscoveryEvent;
class DeviceCatalog {
public:
 explicit DeviceCatalog(DeviceId self, std::function<TimePoint()> clock = [] { return std::chrono::system_clock::now(); }, std::chrono::seconds debounce=std::chrono::seconds(5));
 std::vector<DeviceEvent> Apply(ServiceRecord record);
 std::vector<DeviceEvent> Remove(ServiceInstanceId instance, TimePoint now);
 std::vector<DeviceEvent> Expire(TimePoint now);
 void InvalidateNetwork(TimePoint now);
 std::vector<DeviceInfo> Snapshot() const;
private:
 struct Entry { DeviceInfo visible; std::map<ServiceInstanceId, DeviceInfo> records; std::optional<TimePoint> deadline; };
 DeviceId self_; std::function<TimePoint()> clock_;std::chrono::seconds debounce_;
 std::map<std::string, Entry> devices_;
};
}
