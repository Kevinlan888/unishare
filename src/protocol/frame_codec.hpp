#pragma once
#include "wire_codec.hpp"
namespace unishare::protocol {
// Serialize calls externally. A malformed stream permanently poisons this parser.
class FrameCodec {
public:
 Bytes Encode(const Message&) const;
 Result<std::vector<Message>> Feed(ByteSpan);
 std::size_t BufferedBytes() const {return buffer_.size();}
private:
 Bytes buffer_; std::uint32_t length_=0; bool failed_=false;
};
}
