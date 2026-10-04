#include "core/identity.hpp"
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <openssl/x509.h>
#include <openssl/evp.h>
using namespace unishare;
using namespace unishare::core;
void Check(bool ok, int line) { if (!ok) { std::cerr << "Check failed at line " << line << '\n'; std::exit(1); } }
#define CHECK(...) Check((__VA_ARGS__), __LINE__)
// Platform storage is external: this fake models atomic persistence and failures.
struct Store : IIdentityStore {
 std::optional<IdentityMaterial> identity;
 std::vector<TrustedDevice> trust;
 bool fail_read=false, fail_write=false;
 int identity_writes=0;
 ShareError error{ErrorCode::StorageFailed,"secure store unavailable",42};
 Result<std::optional<IdentityMaterial>> LoadIdentity() override { return fail_read ? Result<std::optional<IdentityMaterial>>::Failure(error) : Result<std::optional<IdentityMaterial>>::Success(identity); }
 Result<void> SaveIdentity(const IdentityMaterial& value) override { ++identity_writes; if(fail_write) return Result<void>::Failure(error); identity=value; return Result<void>::Success(); }
 Result<std::vector<TrustedDevice>> LoadTrust() override { return fail_read ? Result<std::vector<TrustedDevice>>::Failure(error) : Result<std::vector<TrustedDevice>>::Success(trust); }
 Result<void> SaveTrust(const TrustedDevice& value) override { if(fail_write) return Result<void>::Failure(error); for(auto& t:trust) if(t.id==value.id) return t.public_key_fingerprint==value.public_key_fingerprint ? Result<void>::Success() : Result<void>::Failure({ErrorCode::IdentityChanged,"changed",{}}); trust.push_back(value); return Result<void>::Success(); }
 Result<void> RemoveTrust(DeviceId id) override { if(fail_write) return Result<void>::Failure(error); trust.erase(std::remove_if(trust.begin(),trust.end(),[&](auto& t){return t.id==id;}),trust.end()); return Result<void>::Success(); }
};
int main() {
 Store store;
 auto first=IdentityManager::LoadOrCreate(store);
 CHECK(first.ok());
 CHECK((first.value().material.id.bytes[6]&0xf0)==0x40);
 CHECK((first.value().material.id.bytes[8]&0xc0)==0x80);
 CHECK(!first.value().material.private_key.empty() && !first.value().material.certificate.empty());
 // Independently encode the certificate's SubjectPublicKeyInfo: hashing only
 // its BIT STRING or the whole certificate violates the wire trust contract.
 const auto* cert_bytes=first.value().material.certificate.data();
 auto* cert=d2i_X509(nullptr,&cert_bytes,static_cast<long>(first.value().material.certificate.size()));
 CHECK(cert);
 auto* public_key=X509_get_pubkey(cert);
 CHECK(public_key && X509_verify(cert,public_key)==1 && EVP_PKEY_get_bits(public_key)==256);
 unsigned char* spki=nullptr;
 const auto spki_size=i2d_PUBKEY(public_key,&spki);
 CHECK(spki_size>0);
 Fingerprint expected{}; unsigned int digest_size=0;
 CHECK(EVP_Digest(spki,spki_size,expected.data(),&digest_size,EVP_sha256(),nullptr)==1);
 OPENSSL_free(spki); EVP_PKEY_free(public_key); X509_free(cert);
 CHECK(digest_size==32 && first.value().public_key_fingerprint==expected);
 auto second=IdentityManager::LoadOrCreate(store);
 CHECK(second.ok() && second.value().material.id==first.value().material.id);
 CHECK(second.value().material.private_key==first.value().material.private_key);
 CHECK(second.value().material.certificate==first.value().material.certificate);
 CHECK(second.value().public_key_fingerprint==first.value().public_key_fingerprint);
 CHECK(store.identity_writes==1);
 Store fresh;
 auto other=IdentityManager::LoadOrCreate(fresh);
 CHECK(other.ok() && other.value().material.id!=first.value().material.id);
 CHECK(other.value().public_key_fingerprint!=first.value().public_key_fingerprint);
 store.fail_read=true;
 auto read_failure=IdentityManager::LoadOrCreate(store);
 CHECK(!read_failure.ok() && read_failure.error().platform_error==42 && store.identity_writes==1);
 Store denied; denied.fail_write=true;
 auto write_failure=IdentityManager::LoadOrCreate(denied);
 CHECK(!write_failure.ok() && write_failure.error().platform_error==42 && !denied.identity);
 store.fail_read=false;
 store.identity->private_key={0,1,2};
 CHECK(!IdentityManager::LoadOrCreate(store).ok() && store.identity_writes==1);
 store.identity=first.value().material;
 store.identity->certificate=other.value().material.certificate;
 CHECK(!IdentityManager::LoadOrCreate(store).ok() && store.identity_writes==1);
 Store pins;
 const auto id=first.value().material.id;
 const auto fp=first.value().public_key_fingerprint;
 auto changed=fp; changed[0]^=1;
 TrustStore trust(pins);
 CHECK(trust.Match(id,fp).ok() && !trust.Match(id,fp).value());
 CHECK(trust.Pin(id,fp).ok());
 TrustStore restarted(pins);
 CHECK(restarted.Match(id,fp).ok() && restarted.Match(id,fp).value());
 auto mismatch=restarted.Match(id,changed);
 CHECK(!mismatch.ok() && mismatch.error().code==ErrorCode::IdentityChanged);
 auto overwrite=restarted.Pin(id,changed);
 CHECK(!overwrite.ok() && overwrite.error().code==ErrorCode::IdentityChanged);
 CHECK(restarted.Match(id,fp).value());
 CHECK(restarted.Pin(id,fp).ok());
 pins.fail_read=true;
 CHECK(!trust.Match(id,fp).ok() && !trust.Pin(id,fp).ok());
 pins.fail_read=false; pins.fail_write=true;
 CHECK(!trust.Forget(id).ok() && trust.Match(id,fp).value());
 CHECK(!trust.Pin(other.value().material.id,changed).ok());
 pins.fail_write=false;
 CHECK(trust.Forget(id).ok() && !restarted.Match(id,fp).value());
 CHECK(trust.Pin(id,changed).ok());
}
