#include "wire_codec.hpp"
#include <algorithm>
#include <stdexcept>
#include <type_traits>
namespace unishare::protocol {
namespace {
void var(Bytes& b,std::uint64_t v){while(v>=128){b.push_back(static_cast<std::uint8_t>(v)|128);v>>=7;}b.push_back(static_cast<std::uint8_t>(v));}
void number(Bytes& b,unsigned f,std::uint64_t v){var(b,f*8);var(b,v);}
void blob(Bytes& b,unsigned f,const std::uint8_t* p,std::size_t n){var(b,f*8+2);var(b,n);b.insert(b.end(),p,p+n);}
template<std::size_t N> void blob(Bytes& b,unsigned f,const std::array<std::uint8_t,N>& a){blob(b,f,a.data(),N);}
void text(Bytes& b,unsigned f,const std::string& s){blob(b,f,reinterpret_cast<const std::uint8_t*>(s.data()),s.size());}
bool utf8(const std::string& s){std::size_t i=0;while(i<s.size()){auto c=static_cast<unsigned char>(s[i++]);if(c<128)continue;unsigned n=c>=0xc2&&c<=0xdf?1:c>=0xe0&&c<=0xef?2:c>=0xf0&&c<=0xf4?3:0;if(!n||n>s.size()-i)return false;unsigned cp=c&((1<<(6-n))-1);for(unsigned j=0;j<n;++j){auto d=static_cast<unsigned char>(s[i++]);if((d&0xc0)!=0x80)return false;cp=(cp<<6)|(d&63);}if(cp<(n==1?128:n==2?2048:65536)||cp>0x10ffff||(cp>=0xd800&&cp<=0xdfff))return false;}return true;}
bool statusType(MessageType t){return t==MessageType::Accept||t==MessageType::Reject||t==MessageType::Complete||t==MessageType::Failed||t==MessageType::Cancel;}
std::size_t varintSize(std::uint64_t v){std::size_t n=1;while(v>=128){++n;v>>=7;}return n;}
// Add against the remaining budget: never sum attacker-controlled sizes first.
struct ControlSize {
 std::size_t size=0;
 void add(std::size_t n){if(n>MaxControlFrame-1-size)throw std::invalid_argument("control frame limit");size+=n;}
 void bytes(std::size_t n){add(1);add(varintSize(n));add(n);}
 void number(std::uint64_t n){add(1);add(varintSize(n));}
};
struct Reader {
 ByteSpan b;std::size_t p=0;
 bool varint(std::uint64_t& v){v=0;for(unsigned i=0;i<10;++i){if(p==b.size)return false;auto c=b.data[p++];if(i==9&&c>1)return false;v|=std::uint64_t(c&127)<<(i*7);if(!(c&128))return true;}return false;}
 bool skip(unsigned w,unsigned f,unsigned depth=0){std::uint64_t v;switch(w){case 0:return varint(v);case 1:if(b.size-p<8)return false;p+=8;return true;case 2:if(!varint(v)||v>b.size-p)return false;p+=static_cast<std::size_t>(v);return true;case 5:if(b.size-p<4)return false;p+=4;return true;case 3:if(depth>=32)return false;while(p<b.size){if(!varint(v)||!(v>>3)||v>>3>0x1fffffff)return false;auto field=static_cast<unsigned>(v>>3);auto wire=unsigned(v&7);if(wire==4)return field==f;if(!skip(wire,field,depth+1))return false;}return false;default:return false;}}
};
}
MessageType TypeOf(const Message& m){return std::visit([](const auto& x){using T=std::decay_t<decltype(x)>;if constexpr(std::is_same_v<T,PairDecision>)return MessageType::PairDecision;else if constexpr(std::is_same_v<T,Hello>)return MessageType::Hello;else if constexpr(std::is_same_v<T,Offer>)return MessageType::Offer;else if constexpr(std::is_same_v<T,Data>)return MessageType::Data;else if constexpr(std::is_same_v<T,Finish>)return MessageType::Finish;else return x.type;},m);}
Bytes EncodeBody(const Message& m){
 // Preflight before allocating output, copying strings, or scanning UTF-8.
 std::visit([](const auto& x){using T=std::decay_t<decltype(x)>;ControlSize s;
  if constexpr(std::is_same_v<T,PairDecision>){s.number(x.confirmed);}
  else if constexpr(std::is_same_v<T,Data>){if(x.payload.size()>MaxDataPayload)throw std::invalid_argument("data limit");}
  else if constexpr(std::is_same_v<T,Hello>){s.bytes(16);s.number(x.protocol_major);s.number(x.protocol_minor);s.bytes(32);s.bytes(32);}
  else {s.bytes(16);if constexpr(std::is_same_v<T,Offer>){s.number(static_cast<unsigned>(x.content_type));s.bytes(x.display_name.size());s.bytes(x.mime_type.size());s.number(x.byte_count);}else if constexpr(std::is_same_v<T,Finish>){s.number(x.byte_count);s.bytes(32);}else{s.bytes(x.reason.size());}}
 },m);
 Bytes b;std::visit([&](const auto& x){using T=std::decay_t<decltype(x)>;
 if constexpr(std::is_same_v<T,PairDecision>){number(b,1,x.confirmed);}
 else if constexpr(std::is_same_v<T,Hello>){if(x.protocol_major!=1)throw std::invalid_argument("major version");blob(b,1,x.device_id.bytes);number(b,2,x.protocol_major);number(b,3,x.protocol_minor);blob(b,4,x.nonce);blob(b,5,x.public_key_fingerprint);}
 else if constexpr(std::is_same_v<T,PairDecision>){s.number(x.confirmed);}
  else if constexpr(std::is_same_v<T,Data>){if(x.payload.size()>MaxDataPayload)throw std::invalid_argument("data limit");b.insert(b.end(),x.transfer_id.bytes.begin(),x.transfer_id.bytes.end());for(int i=7;i>=0;--i)b.push_back(static_cast<std::uint8_t>(x.sequence>>(i*8)));b.insert(b.end(),x.payload.begin(),x.payload.end());}
 else {blob(b,1,x.transfer_id.bytes);if constexpr(std::is_same_v<T,Offer>){auto t=static_cast<unsigned>(x.content_type);if(t<1||t>3||!utf8(x.display_name)||!utf8(x.mime_type))throw std::invalid_argument("offer fields");number(b,2,t);text(b,3,x.display_name);text(b,4,x.mime_type);number(b,5,x.byte_count);}else if constexpr(std::is_same_v<T,Finish>){number(b,2,x.byte_count);blob(b,3,x.sha256);}else {if(!statusType(x.type)||!utf8(x.reason))throw std::invalid_argument("status fields");text(b,2,x.reason);}}
 },m);return b;}
Result<Message> DecodeBody(MessageType t,ByteSpan b){auto fail=[](){return Result<Message>::Failure({ErrorCode::InvalidInput,"malformed protobuf or mandatory field",{}});};
 if((b.size&&!b.data)||static_cast<unsigned>(t)<1||static_cast<unsigned>(t)>10)return fail();
 if(t==MessageType::PairDecision){
  if(b.size>MaxControlFrame-1)return fail(); Reader r{b}; bool seen=false,confirmed=false;
  while(r.p<b.size){std::uint64_t key,v;if(!r.varint(key)||!(key>>3)||key>>3>0x1fffffff)return fail();auto f=static_cast<unsigned>(key>>3),w=static_cast<unsigned>(key&7);
   if(f==1){if(w!=0||!r.varint(v)||v>1)return fail();seen=true;confirmed=v!=0;}else if(!r.skip(w,f))return fail();
  }return seen?Result<Message>::Success(PairDecision{confirmed}):fail();
 }
 if(t==MessageType::Data){if(b.size<24||b.size>MaxDataPayload+24)return fail();Data d;std::copy_n(b.data,16,d.transfer_id.bytes.begin());for(unsigned i=16;i<24;++i)d.sequence=(d.sequence<<8)|b.data[i];d.payload.assign(b.data+24,b.data+b.size);return Result<Message>::Success(d);}
 if(b.size>MaxControlFrame-1)return fail();
 std::array<Bytes,6> strings;std::array<std::uint64_t,6> nums{};unsigned seen=0;Reader r{b};
 while(r.p<b.size){std::uint64_t key;if(!r.varint(key)||!(key>>3)||key>>3>0x1fffffff)return fail();auto f=static_cast<unsigned>(key>>3),w=static_cast<unsigned>(key&7);bool known=f<6&&(t==MessageType::Hello||t==MessageType::Offer?true:t==MessageType::Finish?f<=3:f<=2);
  if(!known){if(!r.skip(w,f))return fail();continue;}
  bool numeric=(t==MessageType::Hello&&(f==2||f==3))||(t==MessageType::Offer&&(f==2||f==5))||(t==MessageType::Finish&&f==2);
  if(w!=(numeric?0:2))return fail();std::uint64_t v;if(!r.varint(v))return fail();if(numeric)nums[f]=v;else{if(v>b.size-r.p)return fail();strings[f].assign(b.data+r.p,b.data+r.p+static_cast<std::size_t>(v));r.p+=static_cast<std::size_t>(v);}seen|=1<<f;
 }
 unsigned need=t==MessageType::Hello||t==MessageType::Offer?62:t==MessageType::Finish?14:6;if((seen&need)!=need||strings[1].size()!=16)return fail();
 Uuid id;std::copy(strings[1].begin(),strings[1].end(),id.bytes.begin());auto str=[&](unsigned f){return std::string(strings[f].begin(),strings[f].end());};
 if(t==MessageType::Hello){if(nums[2]>UINT32_MAX||nums[3]>UINT32_MAX||strings[4].size()!=32||strings[5].size()!=32)return fail();if(nums[2]!=1)return Result<Message>::Failure({ErrorCode::VersionMismatch,"unsupported protocol major",{}});Hello h;h.device_id=id;h.protocol_major=static_cast<std::uint32_t>(nums[2]);h.protocol_minor=static_cast<std::uint32_t>(nums[3]);std::copy(strings[4].begin(),strings[4].end(),h.nonce.begin());std::copy(strings[5].begin(),strings[5].end(),h.public_key_fingerprint.begin());return Result<Message>::Success(h);}
 if(t==MessageType::Offer){if(nums[2]<1||nums[2]>3||!utf8(str(3))||!utf8(str(4)))return fail();return Result<Message>::Success(Offer{id,static_cast<ContentType>(nums[2]),str(3),str(4),nums[5]});}
 if(t==MessageType::Finish){if(strings[3].size()!=32)return fail();Finish f;f.transfer_id=id;f.byte_count=nums[2];std::copy(strings[3].begin(),strings[3].end(),f.sha256.begin());return Result<Message>::Success(f);}
 if(!utf8(str(2)))return fail();return Result<Message>::Success(Status{t,id,str(2)});
}
}
