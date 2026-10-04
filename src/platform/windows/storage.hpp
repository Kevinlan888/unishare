#pragma once
#include "unishare/platform.hpp"
#include <filesystem>
namespace unishare::windows {
// Dedicated, local NTFS per-user directory. Calls serialize across processes.
class IdentityStore final : public IIdentityStore {
 std::filesystem::path root_;
public:
 explicit IdentityStore(std::filesystem::path root):root_(std::move(root)){}
 Result<std::optional<IdentityMaterial>> LoadIdentity() override;
 Result<void> SaveIdentity(const IdentityMaterial&) override;
 Result<std::vector<TrustedDevice>> LoadTrust() override;
 Result<void> SaveTrust(const TrustedDevice&) override;
 Result<void> RemoveTrust(DeviceId) override;
};
}
