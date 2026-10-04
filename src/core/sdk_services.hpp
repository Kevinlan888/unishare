#pragma once
#include "share_sdk.hpp"
namespace unishare::core {
// Called on the core queue after identity/configuration commit.
void InstallServices(const std::shared_ptr<SdkRuntime> &, IPlatform &,
                     std::function<std::vector<DeviceInfo>()> devices,
                     std::function<void()> trust_changed);
} // namespace unishare::core
