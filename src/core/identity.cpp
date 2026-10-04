#include "identity.hpp"
#include <openssl/evp.h>
#include <openssl/rand.h>
#include <openssl/x509.h>
#include <openssl/crypto.h>
#include <memory>
#include <cstring>

namespace unishare::core {
namespace {
template<class T, void (*Free)(T*)> using Handle = std::unique_ptr<T, decltype(Free)>;
using Key = Handle<EVP_PKEY, EVP_PKEY_free>;
using Cert = Handle<X509, X509_free>;
ShareError Invalid() { return {ErrorCode::StorageFailed,"Invalid or unavailable identity material",{}}; }
ShareError Changed() { return {ErrorCode::IdentityChanged,"Pinned public key changed",{}}; }
bool Equal(const Fingerprint& a, const Fingerprint& b) { return CRYPTO_memcmp(a.data(),b.data(),a.size())==0; }
Result<LocalIdentity> Validate(const IdentityMaterial& material) {
 if((material.id.bytes[6]&0xf0)!=0x40 || (material.id.bytes[8]&0xc0)!=0x80 ||
    material.private_key.empty() || material.private_key.size()>65536 ||
    material.certificate.empty() || material.certificate.size()>65536)
  return Result<LocalIdentity>::Failure(Invalid());
 const auto* p=material.private_key.data();
 Handle<PKCS8_PRIV_KEY_INFO,PKCS8_PRIV_KEY_INFO_free> info(d2i_PKCS8_PRIV_KEY_INFO(nullptr,&p,static_cast<long>(material.private_key.size())),PKCS8_PRIV_KEY_INFO_free);
 if(!info || p!=material.private_key.data()+material.private_key.size()) return Result<LocalIdentity>::Failure(Invalid());
 Key key(EVP_PKCS82PKEY(info.get()),EVP_PKEY_free);
 const auto* c=material.certificate.data();
 Cert cert(d2i_X509(nullptr,&c,static_cast<long>(material.certificate.size())),X509_free);
 char group[80]{}; size_t length=0;
 if(!key || !cert || c!=material.certificate.data()+material.certificate.size() ||
    !EVP_PKEY_is_a(key.get(),"EC") || EVP_PKEY_get_group_name(key.get(),group,sizeof(group),&length)!=1 ||
    std::strcmp(group,"prime256v1")!=0 || X509_check_private_key(cert.get(),key.get())!=1 ||
    X509_NAME_cmp(X509_get_subject_name(cert.get()),X509_get_issuer_name(cert.get()))!=0 ||
    X509_verify(cert.get(),key.get())!=1) return Result<LocalIdentity>::Failure(Invalid());
 Handle<EVP_PKEY_CTX,EVP_PKEY_CTX_free> ctx(EVP_PKEY_CTX_new_from_pkey(nullptr,key.get(),nullptr),EVP_PKEY_CTX_free);
 if(!ctx || EVP_PKEY_private_check(ctx.get())!=1) return Result<LocalIdentity>::Failure(Invalid());
 LocalIdentity identity{material,{}};
 unsigned int size=0;
 // Hash the complete DER SubjectPublicKeyInfo (algorithm plus public key).
 const int spki_size=i2d_PUBKEY(key.get(),nullptr);
 if(spki_size<=0) return Result<LocalIdentity>::Failure(Invalid());
 std::vector<unsigned char> spki(spki_size);
 auto* out=spki.data();
 if(i2d_PUBKEY(key.get(),&out)!=spki_size ||
    EVP_Digest(spki.data(),spki.size(),identity.public_key_fingerprint.data(),&size,EVP_sha256(),nullptr)!=1 || size!=32)
  return Result<LocalIdentity>::Failure(Invalid());
 return Result<LocalIdentity>::Success(std::move(identity));
}
Result<LocalIdentity> Generate() {
 IdentityMaterial material;
 if(RAND_bytes(material.id.bytes.data(),static_cast<int>(material.id.bytes.size()))!=1) return Result<LocalIdentity>::Failure(Invalid());
 material.id.bytes[6]=(material.id.bytes[6]&0x0f)|0x40;
 material.id.bytes[8]=(material.id.bytes[8]&0x3f)|0x80;
 Key key(EVP_PKEY_Q_keygen(nullptr,nullptr,"EC","prime256v1"),EVP_PKEY_free);
 Cert cert(X509_new(),X509_free);
 std::array<unsigned char,16> serial{};
 if(!key || !cert || RAND_bytes(serial.data(),static_cast<int>(serial.size()))!=1) return Result<LocalIdentity>::Failure(Invalid());
 serial[0]=(serial[0]&0x7f)|1;
 Handle<BIGNUM,BN_free> number(BN_bin2bn(serial.data(),static_cast<int>(serial.size()),nullptr),BN_free);
 Handle<ASN1_INTEGER,ASN1_INTEGER_free> asn(number ? BN_to_ASN1_INTEGER(number.get(),nullptr) : nullptr,ASN1_INTEGER_free);
 auto* subject=X509_get_subject_name(cert.get());
 const auto name=material.id.ToString();
 if(!asn || X509_set_version(cert.get(),2)!=1 || X509_set_serialNumber(cert.get(),asn.get())!=1 ||
    !X509_gmtime_adj(X509_getm_notBefore(cert.get()),-300) ||
    !X509_gmtime_adj(X509_getm_notAfter(cert.get()),60L*60*24*365*10) ||
    X509_set_pubkey(cert.get(),key.get())!=1 ||
    X509_NAME_add_entry_by_txt(subject,"CN",MBSTRING_ASC,reinterpret_cast<const unsigned char*>(name.data()),static_cast<int>(name.size()),-1,0)!=1 ||
    X509_set_issuer_name(cert.get(),subject)!=1 || X509_sign(cert.get(),key.get(),EVP_sha256())<=0)
  return Result<LocalIdentity>::Failure(Invalid());
 Handle<PKCS8_PRIV_KEY_INFO,PKCS8_PRIV_KEY_INFO_free> info(EVP_PKEY2PKCS8(key.get()),PKCS8_PRIV_KEY_INFO_free);
 const int key_size=info ? i2d_PKCS8_PRIV_KEY_INFO(info.get(),nullptr) : -1;
 const int cert_size=i2d_X509(cert.get(),nullptr);
 if(key_size<=0 || cert_size<=0) return Result<LocalIdentity>::Failure(Invalid());
 material.private_key.resize(key_size); material.certificate.resize(cert_size);
 auto* k=material.private_key.data(); auto* c=material.certificate.data();
 if(i2d_PKCS8_PRIV_KEY_INFO(info.get(),&k)!=key_size || i2d_X509(cert.get(),&c)!=cert_size) return Result<LocalIdentity>::Failure(Invalid());
 return Validate(material);
}
}
Result<LocalIdentity> IdentityManager::LoadOrCreate(IIdentityStore& store) {
 auto loaded=store.LoadIdentity();
 if(!loaded.ok()) return Result<LocalIdentity>::Failure(loaded.error());
 if(loaded.value()) return Validate(*loaded.value());
 auto created=Generate();
 if(!created.ok()) return created;
 auto saved=store.SaveIdentity(created.value().material);
 if(!saved.ok()) return Result<LocalIdentity>::Failure(saved.error());
 return created;
}
Result<bool> TrustStore::Match(DeviceId id, Fingerprint fingerprint) {
 auto loaded=store_.LoadTrust();
 if(!loaded.ok()) return Result<bool>::Failure(loaded.error());
 bool found=false;
 for(const auto& peer:loaded.value()) if(peer.id==id) {
  if(!Equal(peer.public_key_fingerprint,fingerprint)) return Result<bool>::Failure(Changed());
  found=true;
 }
 return Result<bool>::Success(found);
}
Result<void> TrustStore::Pin(DeviceId id, Fingerprint fingerprint) {
 auto match=Match(id,fingerprint);
 if(!match.ok()) return Result<void>::Failure(match.error());
 if(match.value()) return Result<void>::Success();
 return store_.SaveTrust({id,fingerprint});
}
Result<void> TrustStore::Forget(DeviceId id) { return store_.RemoveTrust(id); }
}
