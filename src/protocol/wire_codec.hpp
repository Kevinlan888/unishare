#pragma once
#include "unishare/types.hpp"
namespace unishare::protocol {
using Bytes=std::vector<std::uint8_t>;
struct ByteSpan { const std::uint8_t* data=nullptr; std::size_t size=0; ByteSpan()=default; ByteSpan(const std::uint8_t* p,std::size_t n):data(p),size(n){} ByteSpan(const Bytes& b):data(b.data()),size(b.size()){} };
constexpr std::size_t MaxControlFrame=64*1024, MaxDataPayload=256*1024;
enum class MessageType:std::uint8_t { Hello=1,Offer,Accept,Reject,Data,Finish,Complete,Failed,Cancel };
struct Hello { DeviceId device_id; std::uint32_t protocol_major=1,protocol_minor=0; std::array<std::uint8_t,32> nonce{},public_key_fingerprint{}; };
struct Offer { TransferId transfer_id; ContentType content_type=ContentType::Text; std::string display_name,mime_type; std::uint64_t byte_count=0; };
struct Status { MessageType type=MessageType::Accept; TransferId transfer_id; std::string reason; };
struct Data { TransferId transfer_id; std::uint64_t sequence=0; Bytes payload; };
struct Finish { TransferId transfer_id; std::uint64_t byte_count=0; std::array<std::uint8_t,32> sha256{}; };
using Message=std::variant<Hello,Offer,Status,Data,Finish>;
MessageType TypeOf(const Message&);
Bytes EncodeBody(const Message&); // invalid semantic input throws invalid_argument
Result<Message> DecodeBody(MessageType,ByteSpan);
}
