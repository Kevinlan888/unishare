#pragma once
#include "service_support.hpp"
#include "transfer_engine.hpp"
namespace unishare::core {
struct TransferWiring {
    std::shared_ptr<ITransferService> service;
    std::function<void(std::uint64_t, std::shared_ptr<Session>)> attach;
    std::function<void(std::uint64_t, ShareError)> detach;
    std::function<void(ShareError)> stop;
    std::function<void()> start;
};
TransferWiring
MakeTransferService(const std::shared_ptr<SdkRuntime> &,
                    std::function<std::optional<std::uint64_t>(DeviceId)> connection);
} // namespace unishare::core
