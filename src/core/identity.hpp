#pragma once
#include "unishare/platform.hpp"

namespace unishare::core {
using Fingerprint = std::array<std::uint8_t, 32>;
struct LocalIdentity {
 IdentityMaterial material;
 Fingerprint public_key_fingerprint;
};
class IdentityManager {
public:
 static Result<LocalIdentity> LoadOrCreate(IIdentityStore& store);
};
// Calls are serialized by the owning SDK. No trust cache can mask storage errors.
class TrustStore {
 IIdentityStore& store_;
public:
 explicit TrustStore(IIdentityStore& store) : store_(store) {}
 Result<void> Pin(DeviceId id, Fingerprint fingerprint);
 // Unknown peers return false; changed keys fail with IdentityChanged.
 Result<bool> Match(DeviceId id, Fingerprint fingerprint);
 Result<void> Forget(DeviceId id);
};
}
