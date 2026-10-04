#pragma once
#include "platform.hpp"

namespace unishare {
enum class SdkState { Uninitialized, Initialized, Running, Stopped };

// All asynchronous methods follow ResultCallback's one-shot completion contract.
// Initialize selects SdkConfig::callback_executor (or the SDK serial queue).
class IShareSdk {
public:
    virtual ~IShareSdk() = default;
    virtual OperationId Initialize(SdkConfig, ResultCallback<void>) = 0;
    virtual OperationId Start(ResultCallback<void>) = 0;

    // Stop cancels every unfinished SDK operation, including active transfers,
    // completing each with Cancelled exactly once. Previously completed results
    // retain their outcome. Stop's own callback succeeds after cancellation and
    // shutdown complete and all resulting callbacks have been queued; delivery
    // still depends on the executor running its queue. No new recurring events
    // are generated after shutdown; already queued callbacks may still execute.
    virtual OperationId Stop(ResultCallback<void>) = 0;
    virtual SdkState GetState() const = 0;
};
}
