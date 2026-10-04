#pragma once
#include <cstddef>
#include <memory>
#include <mutex>

namespace unishare::core {
// Shared by every session and transfer belonging to one SDK instance. Charges
// are conservative upper bounds, acquired BEFORE SDK buffer allocation. Leases
// may outlive the session (e.g. a consumer still owns a received chunk).
class ByteBudget {
    struct State {
        explicit State(std::size_t n) : limit(n) {}
        std::mutex mutex;
        const std::size_t limit;
        std::size_t used = 0;
    };
public:
    class Reservation {
    public:
        ~Reservation() { Shrink(0); }
        Reservation(const Reservation&) = delete;
        Reservation& operator=(const Reservation&) = delete;
        void Shrink(std::size_t bytes) {
            std::lock_guard<std::mutex> lock(state_->mutex);
            if (bytes < size_) { state_->used -= size_ - bytes; size_ = bytes; }
        }
        // State is private to ByteBudget; consumers obtain leases via Reserve.
        friend class ByteBudget;
        Reservation(std::shared_ptr<State> state, std::size_t size)
            : state_(std::move(state)), size_(size) {}
    private:
        std::shared_ptr<State> state_;
        std::size_t size_;
    };
    explicit ByteBudget(std::size_t limit = 4 * 1024 * 1024)
        : state_(std::make_shared<State>(limit)) {}
    std::shared_ptr<Reservation> Reserve(std::size_t bytes) {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (bytes > state_->limit - state_->used) return {};
        // Allocate the lease before modifying accounting: bad_alloc leaves it intact.
        auto lease = std::make_shared<Reservation>(state_, bytes);
        state_->used += bytes;
        return lease;
    }
    std::size_t Used() const {
        std::lock_guard<std::mutex> lock(state_->mutex);
        return state_->used;
    }
    std::size_t Limit() const { return state_->limit; }
private:
    std::shared_ptr<State> state_;
};
}
