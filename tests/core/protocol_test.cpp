#include "protocol/frame_codec.hpp"
#include <cstdlib>
#include <iostream>
using namespace unishare::protocol;
#define CHECK(x) do { if(!(x)){std::cerr<<"check failed "<<__LINE__<<"\n"; std::exit(1);} } while(0)
int main() {
 Hello h; h.device_id.bytes.fill(0x11); h.nonce.fill(0x22); h.public_key_fingerprint.fill(0x33);
 FrameCodec c; auto bytes=c.Encode(h); CHECK(bytes.size()==95); CHECK(bytes[3]==91); CHECK(bytes[4]==1);
 Bytes hello={0,0,0,91,1,10,16}; hello.insert(hello.end(),16,0x11); Bytes rest={16,1,24,0,34,32}; hello.insert(hello.end(),rest.begin(),rest.end()); hello.insert(hello.end(),32,0x22); hello.insert(hello.end(),{42,32}); hello.insert(hello.end(),32,0x33); CHECK(bytes==hello);
 for(auto b:bytes) { auto r=c.Feed({&b,1}); CHECK(r.ok()); if(!r.value().empty()) CHECK(std::get<Hello>(r.value()[0]).nonce==h.nonce); }
 Offer o; o.transfer_id.bytes.fill(0x44); o.content_type=unishare::ContentType::File; o.display_name="a"; o.mime_type="b"; o.byte_count=150;
 Bytes offer={0,0,0,30,2,10,16}; offer.insert(offer.end(),16,0x44); offer.insert(offer.end(),{16,3,26,1,97,34,1,98,40,150,1}); CHECK(c.Encode(o)==offer); CHECK(c.Feed(offer).ok());
 Data d; d.transfer_id.bytes.fill(0x55); d.sequence=0x0102030405060708ULL; d.payload={0xaa,0xbb}; Bytes data={0,0,0,27,5}; data.insert(data.end(),16,0x55); data.insert(data.end(),{1,2,3,4,5,6,7,8,0xaa,0xbb}); CHECK(c.Encode(d)==data); CHECK(c.Feed(data).ok());
 auto bad=[](Bytes v){FrameCodec f; return f.Feed(v);};
 CHECK(!bad({0,0,0,0}).ok()); CHECK(!bad({0,0,0,1,10}).ok()); CHECK(!bad({0,1,0,1,1}).ok()); CHECK(!bad({0,4,0,26,5}).ok()); CHECK(!bad({0,0,0,24,5}).ok());
 bytes[24]=2; CHECK(bad(bytes).error().code==unishare::ErrorCode::VersionMismatch);
 CHECK(!bad({0,0,0,1,1}).ok()); CHECK(!bad({0,0,0,3,2,8,1}).ok());
 d.payload.resize(MaxDataPayload); CHECK(bad(c.Encode(d)).ok()); d.payload.push_back(0); bool threw=false; try {c.Encode(d);} catch(const std::invalid_argument&) {threw=true;} CHECK(threw);
 // Exact maximum control frame via an optional unknown length-delimited field.
 bytes=hello; bytes.insert(bytes.end(),{0x52,0xa1,0xff,0x03}); bytes.resize(4+MaxControlFrame,0); bytes[0]=0;bytes[1]=1;bytes[2]=0;bytes[3]=0; CHECK(bad(bytes).ok());
 Bytes joined=hello; joined.insert(joined.end(),data.begin(),data.end()); CHECK(bad(joined).value().size()==2);
 for(auto t:{MessageType::Accept,MessageType::Reject,MessageType::Complete,MessageType::Failed,MessageType::Cancel}) { Status s{t,o.transfer_id,""}; CHECK(bad(c.Encode(s)).ok()); }
 Finish f; f.transfer_id=o.transfer_id; CHECK(bad(c.Encode(f)).ok());
 // Unknown optional fields of all legal wire types and a matching group.
 bytes=hello; bytes.insert(bytes.end(),{0x50,0x96,1,0x59,0,0,0,0,0,0,0,0,0x65,0,0,0,0,0x6b,0x70,1,0x6c}); bytes[3]=static_cast<std::uint8_t>(bytes.size()-4);CHECK(bad(bytes).ok());
 bytes=hello;bytes.insert(bytes.end(),{0x52,0xff});bytes[3]=static_cast<std::uint8_t>(bytes.size()-4);CHECK(!bad(bytes).ok());
 bytes=hello;bytes.insert(bytes.end(),{0});bytes[3]=static_cast<std::uint8_t>(bytes.size()-4);CHECK(!bad(bytes).ok());
 bytes=hello;bytes.insert(bytes.end(),{0x6b,0x74});bytes[3]=static_cast<std::uint8_t>(bytes.size()-4);CHECK(!bad(bytes).ok());
 bytes=hello;bytes.insert(bytes.end(),{0x50,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,2});bytes[3]=static_cast<std::uint8_t>(bytes.size()-4);CHECK(!bad(bytes).ok());
 bytes=offer;bytes[27]=0xff;CHECK(!bad(bytes).ok());
 FrameCodec poisoned;CHECK(!poisoned.Feed(Bytes{0,0,0,0}).ok());CHECK(!poisoned.Feed(hello).ok());CHECK(poisoned.BufferedBytes()==0);
 FrameCodec partial; CHECK(partial.Feed(Bytes{0,4,0,25}).ok());CHECK(partial.BufferedBytes()==4);CHECK(partial.Feed(Bytes{5}).ok());CHECK(partial.BufferedBytes()==5);
 Status limit{MessageType::Accept,o.transfer_id,std::string(65513,'x')};CHECK(c.Encode(limit).size()==MaxControlFrame+4);CHECK(bad(c.Encode(limit)).ok());limit.reason.push_back('x');threw=false;try{c.Encode(limit);}catch(const std::invalid_argument&){threw=true;}CHECK(threw);
 for(auto type:{1,2,3,4,6,7,8,9})CHECK(!bad(Bytes{0,0,0,1,static_cast<std::uint8_t>(type)}).ok());
 auto rejects=[](const Message& m){for(bool direct:{false,true}){bool invalid=false;try{if(direct)EncodeBody(m);else FrameCodec{}.Encode(m);}catch(const std::invalid_argument&){invalid=true;}CHECK(invalid);}};
 rejects(limit);Offer oversize=o;oversize.display_name.assign(MaxControlFrame,'x');rejects(oversize);oversize.display_name="a";oversize.mime_type.assign(MaxControlFrame,'x');rejects(oversize);
 // Individually bounded strings whose sum exceeds the frame budget.
 oversize.display_name.assign(MaxControlFrame/2,'x');oversize.mime_type.assign(MaxControlFrame/2,'x');rejects(oversize);
 CHECK(!DecodeBody(MessageType::Offer,{nullptr,static_cast<std::size_t>(-1)}).ok());
 auto rh=std::get<Hello>(bad(hello).value()[0]);CHECK(rh.device_id==h.device_id);CHECK(rh.protocol_major==1&&rh.protocol_minor==0);CHECK(rh.nonce==h.nonce&&rh.public_key_fingerprint==h.public_key_fingerprint);
 auto ro=std::get<Offer>(bad(offer).value()[0]);CHECK(ro.transfer_id==o.transfer_id&&ro.content_type==o.content_type);CHECK(ro.display_name==o.display_name&&ro.mime_type==o.mime_type&&ro.byte_count==o.byte_count);
 auto rd=std::get<Data>(bad(data).value()[0]);CHECK(rd.transfer_id==d.transfer_id);CHECK(rd.sequence==0x0102030405060708ULL);CHECK(rd.payload==Bytes({0xaa,0xbb}));
}
