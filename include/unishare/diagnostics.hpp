#pragma once
#include "types.hpp"
#include <cstdint>
#include <optional>

namespace unishare {
enum class DiagnosticStage : std::uint32_t {
    Permission = 1, Listen = 2, Publish = 3, Browse = 4,
    NetworkChange = 5, Stop = 6, Connect = 7, SocketConnect = 8,
    TlsHandshake = 9, IdentityVerify = 10, Pairing = 11, Offer = 12,
    Accept = 13, TransferStream = 14, IntegrityVerify = 15,
    PeerComplete = 16, Cancel = 17, Transfer = 18
};
enum class DiagnosticOutcome : std::uint32_t {
    Began = 1, Succeeded = 2, Failed = 3, Cancelled = 4
};
enum class DiagnosticDirection : std::uint32_t { None = 0, Send = 1, Receive = 2 };
enum class DiagnosticContentKind : std::uint32_t { None = 0, Text = 1, Image = 2, File = 3 };
struct DiagnosticEvent {
    DiagnosticStage stage;
    DiagnosticOutcome outcome;
    DiagnosticDirection direction = DiagnosticDirection::None;
    DiagnosticContentKind content_kind = DiagnosticContentKind::None;
    std::uint64_t sequence = 0;
    std::optional<ShareError> error;
};
}
