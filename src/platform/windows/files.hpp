#pragma once
#include "unishare/platform.hpp"
#include <windows.h>
#include <mutex>
#include <map>
namespace unishare::windows {
// Registration reopens a disk-file HANDLE into an opaque, single-use token.
// Caller closes its original handle after registration and must not perform I/O
// on it while the token/stream lives. Destination is an empty staging file opened
// with GENERIC_READ|GENERIC_WRITE|DELETE and FILE_SHARE_DELETE. Display filenames
// never enter this API. Abandoned destination registrations are cleaned up.
// Read/Write run on a private worker; their owned buffers survive completion.
// Write flushes before success (including empty writes); OpenDestination flushes
// zero-byte staging. After successful writes, Close seals without flush/rename. Abort cancels, then removes
// staging by handle after I/O drains. The void abort interface cannot report a
// cleanup error after all operations already completed; pending callbacks can.
class StreamFactory final : public IStreamFactory {
 struct Entry { HANDLE handle;bool destination; };
 std::mutex mutex_;std::map<std::string,Entry> entries_;std::uint64_t next_=0;
 Result<std::string> Register(HANDLE,bool);
 Result<std::shared_ptr<IStream>> Open(const std::string&,bool);
public:
 ~StreamFactory();
 Result<std::string> RegisterSource(HANDLE h){return Register(h,false);}
 Result<std::string> RegisterDestination(HANDLE h){return Register(h,true);}
 Result<std::shared_ptr<IStream>> OpenSource(std::string h) override {return Open(h,false);}
 Result<std::shared_ptr<IStream>> OpenDestination(std::string h) override {return Open(h,true);}
};
}
