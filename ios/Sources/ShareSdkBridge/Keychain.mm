#include "Common.hpp"
#import <Security/Security.h>
namespace unishare::ios {
namespace {
ShareError KeyError(OSStatus status) {return Failure(ErrorCode::StorageFailed,"Keychain operation failed",status);}
class Keychain final : public IIdentityStore {
 NSString* service_;
 NSMutableDictionary* Query(NSString* account=nil) const {
  NSMutableDictionary* query=[@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService:service_,(__bridge id)kSecAttrSynchronizable:@NO} mutableCopy];
  if(account)query[(__bridge id)kSecAttrAccount]=account;
  return query;
 }
 Result<std::optional<std::vector<uint8_t>>> Load(NSString* account) const {
  auto query=Query(account); query[(__bridge id)kSecReturnData]=@YES;
  CFTypeRef raw=nullptr; auto status=SecItemCopyMatching((__bridge CFDictionaryRef)query,&raw);
  id data=CFBridgingRelease(raw);
  if(status==errSecItemNotFound)return Result<std::optional<std::vector<uint8_t>>>::Success({});
  if(status!=errSecSuccess)return Result<std::optional<std::vector<uint8_t>>>::Failure(KeyError(status));
  if(![data isKindOfClass:NSData.class])return Result<std::optional<std::vector<uint8_t>>>::Failure(KeyError(errSecDecode));
  return Result<std::optional<std::vector<uint8_t>>>::Success(Bytes(data));
 }
 OSStatus Add(NSString* account,const std::vector<uint8_t>& bytes) const {
  auto query=Query(account);
  query[(__bridge id)kSecAttrAccessible]=(__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
  query[(__bridge id)kSecValueData]=Data(bytes);
  return SecItemAdd((__bridge CFDictionaryRef)query,nullptr);
 }
 static NSString* Account(DeviceId id) {return [@"trust:" stringByAppendingString:[NSString stringWithUTF8String:id.ToString().c_str()]];}
 static void Length(std::vector<uint8_t>& b,size_t n) {for(int i=3;i>=0;--i)b.push_back(static_cast<uint8_t>(n>>(8*i)));}
public:
 explicit Keychain(NSString* service):service_([service copy]) {}
 Result<std::optional<IdentityMaterial>> LoadIdentity() override { @autoreleasepool {
  auto result=Load(@"identity"); if(!result.ok())return Result<std::optional<IdentityMaterial>>::Failure(result.error());
  if(!result.value())return Result<std::optional<IdentityMaterial>>::Success({});
  auto& b=*result.value(); size_t offset=20;
  auto fail=[] {return Result<std::optional<IdentityMaterial>>::Failure(KeyError(errSecDecode));};
  if(b.size()<28||memcmp(b.data(),"USI1",4))return fail();
  IdentityMaterial material; std::copy_n(b.begin()+4,16,material.id.bytes.begin());
  auto field=[&](std::vector<uint8_t>& out) {
   if(b.size()-offset<4)return false;
   uint32_t n=0;for(int i=0;i<4;++i)n=(n<<8)|b[offset++];
   if(!n||n>1024*1024||n>b.size()-offset)return false;
   out.assign(b.begin()+offset,b.begin()+offset+n);offset+=n;return true;
  };
  if(!field(material.private_key)||!field(material.certificate)||offset!=b.size())return fail();
  return Result<std::optional<IdentityMaterial>>::Success(std::move(material));
 }}
 Result<void> SaveIdentity(const IdentityMaterial& m) override { @autoreleasepool {
  if(m.private_key.empty()||m.certificate.empty()||m.private_key.size()>1024*1024||m.certificate.size()>1024*1024)
   return Result<void>::Failure(Failure(ErrorCode::InvalidInput,"identity size"));
  std::vector<uint8_t> b={'U','S','I','1'};b.insert(b.end(),m.id.bytes.begin(),m.id.bytes.end());
  Length(b,m.private_key.size());b.insert(b.end(),m.private_key.begin(),m.private_key.end());
  Length(b,m.certificate.size());b.insert(b.end(),m.certificate.begin(),m.certificate.end());
  auto status=Add(@"identity",b); std::fill(b.begin(),b.end(),0);
  return status==errSecSuccess?Result<void>::Success():Result<void>::Failure(KeyError(status));
 }}
 Result<std::vector<TrustedDevice>> LoadTrust() override { @autoreleasepool {
  auto query=Query();query[(__bridge id)kSecReturnData]=@YES;query[(__bridge id)kSecReturnAttributes]=@YES;
  query[(__bridge id)kSecMatchLimit]=(__bridge id)kSecMatchLimitAll;
  CFTypeRef raw=nullptr;auto status=SecItemCopyMatching((__bridge CFDictionaryRef)query,&raw);id records=CFBridgingRelease(raw);
  if(status==errSecItemNotFound)return Result<std::vector<TrustedDevice>>::Success({});
  if(status!=errSecSuccess)return Result<std::vector<TrustedDevice>>::Failure(KeyError(status));
  std::vector<TrustedDevice> result;
  for(NSDictionary* item in (NSArray*)records) {
   NSString* account=item[(__bridge id)kSecAttrAccount];
   if(![account isKindOfClass:NSString.class])return Result<std::vector<TrustedDevice>>::Failure(KeyError(errSecDecode));
   if([account isEqualToString:@"identity"])continue;
   NSData* data=item[(__bridge id)kSecValueData];
   auto id=[account hasPrefix:@"trust:"]?Uuid::Parse([[account substringFromIndex:6] UTF8String]):std::nullopt;
   if(!id||![data isKindOfClass:NSData.class]||data.length!=32)return Result<std::vector<TrustedDevice>>::Failure(KeyError(errSecDecode));
   TrustedDevice device{*id,{}};memcpy(device.public_key_fingerprint.data(),data.bytes,32);result.push_back(device);
  }
  return Result<std::vector<TrustedDevice>>::Success(std::move(result));
 }}
 Result<void> SaveTrust(const TrustedDevice& device) override { @autoreleasepool {
  std::vector<uint8_t> bytes(device.public_key_fingerprint.begin(),device.public_key_fingerprint.end());
  auto account=Account(device.id);auto status=Add(account,bytes);
  if(status==errSecSuccess)return Result<void>::Success();
  if(status!=errSecDuplicateItem)return Result<void>::Failure(KeyError(status));
  auto old=Load(account);if(!old.ok())return Result<void>::Failure(old.error());
  if(!old.value()||old.value()->size()!=32)return Result<void>::Failure(KeyError(errSecDecode));
  if(*old.value()!=bytes)return Result<void>::Failure(Failure(ErrorCode::IdentityChanged,"stored fingerprint differs"));
  return Result<void>::Success();
 }}
 Result<void> RemoveTrust(DeviceId id) override { @autoreleasepool {
  auto status=SecItemDelete((__bridge CFDictionaryRef)Query(Account(id)));
  return status==errSecSuccess||status==errSecItemNotFound?Result<void>::Success():Result<void>::Failure(KeyError(status));
 }}
};
}
std::shared_ptr<IIdentityStore> MakeIdentityStore(NSString* service) {return std::make_shared<Keychain>(service);}
}
