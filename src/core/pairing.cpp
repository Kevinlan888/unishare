#include "pairing.hpp"
#include <openssl/evp.h>
namespace unishare::core {
Result<std::string> Pairing::ComputeSas(const std::array<std::uint8_t,32>& exporter,const protocol::Hello& initiator,const protocol::Hello& responder) {
 std::array<std::uint8_t,192> transcript{};auto out=transcript.begin();
 auto append=[&](const auto& field){out=std::copy(field.begin(),field.end(),out);};
 append(exporter);for(const auto* h:{&initiator,&responder}){append(h->device_id.bytes);append(h->public_key_fingerprint);append(h->nonce);}
 std::array<unsigned char,32> digest{};unsigned int size=0;
 if(EVP_Digest(transcript.data(),transcript.size(),digest.data(),&size,EVP_sha256(),nullptr)!=1||size!=32)
  return Result<std::string>::Failure({ErrorCode::AuthenticationFailed,"SAS digest failed",{}});
 std::uint32_t value=0;for(unsigned i=0;i<4;++i)value=(value<<8)|digest[i];
 auto code=std::to_string(value%1000000);return Result<std::string>::Success(std::string(6-code.size(),'0')+code);
}
}
