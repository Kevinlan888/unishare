#pragma once
#include "unishare/types.hpp"
#include <system_error>
namespace unishare::core {
// At command/adapter boundaries only: normal platform failures use Result.
inline ShareError
CurrentException(ErrorCode fallback = ErrorCode::InvalidInput) {
  try {
    throw;
  } catch (const ShareError &e) {
    return e;
  } catch (const std::bad_alloc &) {
    return {ErrorCode::ResourceLimit, "allocation failed", {}};
  } catch (const std::system_error &e) {
    return {ErrorCode::StorageFailed, "platform exception", e.code().value()};
  } catch (...) {
    return {fallback, "unexpected platform exception", {}};
  }
}
} // namespace unishare::core
