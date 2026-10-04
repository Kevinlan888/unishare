#pragma once
#include "../protocol/wire_codec.hpp"
namespace unishare::core {
class Pairing {
public:
 static Result<std::string> ComputeSas(const std::array<std::uint8_t,32>& exporter,
   const protocol::Hello& initiator,const protocol::Hello& responder);
};
}
