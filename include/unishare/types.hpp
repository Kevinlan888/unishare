#pragma once
#include <array>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>
namespace unishare {
struct Uuid {
 std::array<std::uint8_t,16> bytes{};
 static std::optional<Uuid> Parse(std::string_view s) {
  if(s.size()!=36) return std::nullopt;
  Uuid id; std::size_t n=0; int high=-1;
  for(std::size_t i=0;i<s.size();++i) {
   if(i==8||i==13||i==18||i==23) { if(s[i]!='-') return std::nullopt; continue; }
   char c=s[i]; int v=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;
   if(v<0) return std::nullopt;
   if(high<0) high=v; else { id.bytes[n++]=static_cast<std::uint8_t>((high<<4)|v); high=-1; }
  }
  return id;
 }
 std::string ToString() const { const char* h="0123456789abcdef"; std::string s; for(std::size_t i=0;i<16;++i) { if(i==4||i==6||i==8||i==10) s+='-'; s+=h[bytes[i]>>4]; s+=h[bytes[i]&15]; } return s; }
 bool operator==(const Uuid& other) const { return bytes==other.bytes; }
 bool operator!=(const Uuid& other) const { return !(*this==other); }
};
using DeviceId=Uuid; using TransferId=Uuid; using OperationId=std::uint64_t; using RequestId=std::uint64_t;
enum class ErrorCode : std::uint32_t { PermissionDenied=1, DiscoveryFailed=2, VersionMismatch=3, PairingRejected=4, AuthenticationFailed=5, IdentityChanged=6, TransferRejected=7, Cancelled=8, Timeout=9, NetworkLost=10, StorageFailed=11, IntegrityFailed=12, InvalidInput=13, ResourceLimit=14 };
struct ShareError { ErrorCode code; std::string message; std::optional<std::int64_t> platform_error; };
template<class T> class Result { std::variant<T,ShareError> data_; explicit Result(T v):data_(std::move(v)){} explicit Result(ShareError e):data_(std::move(e)){} public: static Result Success(T v){return Result(std::move(v));} static Result Failure(ShareError e){return Result(std::move(e));} bool ok() const{return std::holds_alternative<T>(data_);} const T& value() const{return std::get<T>(data_);} const ShareError& error() const{return std::get<ShareError>(data_);} };
template<> class Result<void> { std::optional<ShareError> error_; explicit Result(std::optional<ShareError> e):error_(std::move(e)){} public: static Result Success(){return Result(std::nullopt);} static Result Failure(ShareError e){return Result(std::move(e));} bool ok() const{return !error_;} const ShareError& error() const{return error_.value();} };
template<class T> using ResultCallback=std::function<void(Result<T>)>;
enum class ContentType : std::uint32_t { Text=1, Image=2, File=3 };
enum class Platform { Windows, Android, Ios };
struct ProtocolVersion { std::uint16_t major=1, minor=0; };
struct DeviceInfo { DeviceId id; std::string name; Platform platform=Platform::Windows; ProtocolVersion protocol; std::vector<std::string> addresses; std::uint16_t port=0; bool paired=false; std::chrono::system_clock::time_point last_seen; };
class IExecutor; class IPermissionAgent;
struct SdkConfig { std::string device_name; std::uint32_t max_connections=8,max_pending_requests=4; std::uint64_t max_buffer_bytes=4ULL*1024*1024,max_text_bytes=8ULL*1024*1024,max_image_bytes=100ULL*1024*1024,max_file_bytes=20ULL*1024*1024*1024; std::uint32_t control_frame_bytes=64*1024,data_chunk_bytes=256*1024; std::chrono::seconds request_timeout{60},offline_debounce{5}; std::shared_ptr<IExecutor> callback_executor; std::shared_ptr<IPermissionAgent> permissions; };
struct ShareContent { ContentType type=ContentType::Text; std::string text,mime_type; std::vector<std::uint8_t> image; };
}
