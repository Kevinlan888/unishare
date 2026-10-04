#include <unishare/share_sdk.hpp>
#include <unishare/discovery.hpp>
#include <unishare/connection.hpp>
#include <unishare/transfer.hpp>
#include <unishare/clipboard.hpp>
#include <unishare/operation.hpp>
#include <deque>
#include <stdexcept>
using namespace unishare;
void check(bool b) { if (!b) throw std::runtime_error("contract assertion failed"); }
struct FakeExecutor : IExecutor { std::deque<std::function<void()>> pending; void Post(std::function<void()> f) override { pending.push_back(std::move(f)); } void Drain() { while (!pending.empty()) { auto f=std::move(pending.front()); pending.pop_front(); f(); } } };
int main() {
 static_assert(static_cast<int>(ContentType::Text)==1 && static_cast<int>(ContentType::Image)==2 && static_cast<int>(ContentType::File)==3);
 static_assert(static_cast<int>(ErrorCode::PermissionDenied)==1 && static_cast<int>(ErrorCode::ResourceLimit)==14);
 SdkConfig c; check(c.max_connections==8 && c.max_pending_requests==4 && c.max_buffer_bytes==4ULL*1024*1024); check(c.max_text_bytes==8ULL*1024*1024 && c.max_image_bytes==100ULL*1024*1024 && c.max_file_bytes==20ULL*1024*1024*1024); check(c.control_frame_bytes==65536 && c.data_chunk_bytes==262144);
 auto id=Uuid::Parse("01234567-89ab-4def-8123-456789abcdef"); check(id.has_value()); check(id->ToString()=="01234567-89ab-4def-8123-456789abcdef"); check(!Uuid::Parse("0123456789ab4def8123456789abcdef")); check(!Uuid::Parse("01234567-89ab-4def-8123-456789abcdeg"));
 auto executor=std::make_shared<FakeExecutor>(); int calls=0;
 OperationCompletion<void> operation(executor,[&](Result<void> result){ ++calls; check(!result.ok()); check(result.error().code==ErrorCode::Cancelled); });
 check(operation.Cancel()); check(!operation.Complete(Result<void>::Success())); check(!operation.Cancel()); check(calls==0); executor->Drain(); check(calls==1);
}
