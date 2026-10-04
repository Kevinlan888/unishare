#include "frame_codec.hpp"
#include <stdexcept>
namespace unishare::protocol {
Bytes FrameCodec::Encode(const Message& m) const {
 auto b=EncodeBody(m); auto type=TypeOf(m); auto n=b.size()+1;
 if(n>(type==MessageType::Data?MaxDataPayload+25:MaxControlFrame)) throw std::invalid_argument("frame exceeds limit");
 Bytes out; for(int i=3;i>=0;--i) out.push_back(static_cast<std::uint8_t>(n>>(i*8))); out.push_back(static_cast<std::uint8_t>(type)); out.insert(out.end(),b.begin(),b.end()); return out;
}
Result<std::vector<Message>> FrameCodec::Feed(ByteSpan input) {
 auto fail=[&](ErrorCode code,const char* why){failed_=true;buffer_.clear();return Result<std::vector<Message>>::Failure({code,why,{}});};
 if(failed_) return fail(ErrorCode::InvalidInput,"parser is poisoned");
 if(input.size && !input.data) return fail(ErrorCode::InvalidInput,"null span");
 std::vector<Message> messages;
 for(std::size_t p=0;p<input.size;) {
  if(buffer_.size()<4) {buffer_.push_back(input.data[p++]); if(buffer_.size()<4) continue; length_=0;for(auto b:buffer_) length_=(length_<<8)|b; if(!length_ || length_>MaxDataPayload+25) return fail(ErrorCode::InvalidInput,"invalid frame length");}
  if(buffer_.size()==4 && p==input.size) break;
  if(buffer_.size()==4) {buffer_.push_back(input.data[p++]); auto t=buffer_[4]; if(t<1||t>10) return fail(ErrorCode::InvalidInput,"unknown message type"); if(t==5?length_<25||length_>MaxDataPayload+25:length_>MaxControlFrame) return fail(ErrorCode::InvalidInput,"type length limit");}
  const auto n=std::min(input.size-p,static_cast<std::size_t>(length_+4-buffer_.size()));
  buffer_.insert(buffer_.end(),input.data+p,input.data+p+n);p+=n;
  if(buffer_.size()==length_+4) {auto r=DecodeBody(static_cast<MessageType>(buffer_[4]),{buffer_.data()+5,length_-1}); if(!r.ok()) {auto e=r.error();failed_=true;buffer_.clear();return Result<std::vector<Message>>::Failure(e);} messages.push_back(r.value());buffer_.clear();length_=0;}
 }
 return Result<std::vector<Message>>::Success(std::move(messages));
}
}
