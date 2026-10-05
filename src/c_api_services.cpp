#include "c_api_internal.hpp"
using namespace unishare;
using namespace unishare::abi::detail;
namespace {
TransferSource Source(const usdk_source *source) {
  Require(source);
  TransferSource result;
  result.display_name = String(source->display_name);
  result.mime_type = String(source->mime_type);
  result.byte_count = source->byte_count;
  result.stream = Registry<unishare::IStream>::Get(source->stream);
  return result;
}
} // namespace
extern "C" {
usdk_error USDK_CALL usdk_connect(usdk_handle h, usdk_uuid d,
                                  usdk_completion cb, void *ctx,
                                  uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Service(s, &State::connections)->Connect(UuidOf(d), std::move(done));
  });
}
usdk_error USDK_CALL usdk_confirm_pairing(usdk_handle h, uint64_t request,
                                          uint32_t matches, usdk_completion cb,
                                          void *ctx, uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Require(request && matches <= 1);
    Service(s, &State::connections)
        ->ConfirmPairing(request, matches != 0, std::move(done));
  });
}
usdk_error USDK_CALL usdk_reject_pairing(usdk_handle h, uint64_t request,
                                         usdk_completion cb, void *ctx,
                                         uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Require(request);
    Service(s, &State::connections)->RejectPairing(request, std::move(done));
  });
}
usdk_error USDK_CALL usdk_forget_device(usdk_handle h, usdk_uuid d,
                                        usdk_completion cb, void *ctx,
                                        uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Service(s, &State::connections)->ForgetDevice(UuidOf(d), std::move(done));
  });
}
usdk_error USDK_CALL usdk_send_text(usdk_handle h, usdk_uuid d, usdk_span text,
                                    usdk_completion cb, void *ctx,
                                    uint64_t *out) {
  return Command<TransferId>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    auto value = String(text, 4194304);
    Service(s, &State::transfers)
        ->SendText(UuidOf(d), std::move(value), std::move(done));
  });
}
usdk_error USDK_CALL usdk_send_text_stream(usdk_handle h, usdk_uuid d,
    const usdk_source* source, usdk_completion cb, void* ctx, uint64_t* out) {
  return Command<TransferId>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    auto value = Source(source);
    Service(s, &State::transfers)->SendTextStream(UuidOf(d), std::move(value), std::move(done));
  });
}
usdk_error USDK_CALL usdk_send_image(usdk_handle h, usdk_uuid d,
                                     const usdk_source *source,
                                     usdk_completion cb, void *ctx,
                                     uint64_t *out) {
  return Command<TransferId>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    auto value = Source(source);
    Service(s, &State::transfers)
        ->SendImage(UuidOf(d), std::move(value), std::move(done));
  });
}
usdk_error USDK_CALL usdk_send_file(usdk_handle h, usdk_uuid d,
                                    const usdk_source *source,
                                    usdk_completion cb, void *ctx,
                                    uint64_t *out) {
  return Command<TransferId>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    auto value = Source(source);
    Service(s, &State::transfers)
        ->SendFile(UuidOf(d), std::move(value), std::move(done));
  });
}
usdk_error USDK_CALL usdk_accept(usdk_handle h, uint64_t request,
                                 usdk_stream_handle destination,
                                 usdk_completion cb, void *ctx, uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Require(request);
    auto stream = Registry<unishare::IStream>::Get(destination);
    Service(s, &State::transfers)
        ->Accept(request, std::move(stream), std::move(done));
  });
}
usdk_error USDK_CALL usdk_reject(usdk_handle h, uint64_t request,
                                 usdk_completion cb, void *ctx, uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Require(request);
    Service(s, &State::transfers)->Reject(request, std::move(done));
  });
}
usdk_error USDK_CALL usdk_cancel(usdk_handle h, usdk_uuid transfer,
                                 usdk_completion cb, void *ctx, uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Service(s, &State::transfers)->Cancel(UuidOf(transfer), std::move(done));
  });
}
usdk_error USDK_CALL usdk_clipboard_read(usdk_handle h, usdk_completion cb,
                                         void *ctx, uint64_t *out) {
  return Command<ShareContent>(h, cb, ctx, out, [](auto s, auto, auto done) {
    Service(s, &State::clipboard)->ReadShareableContent(std::move(done));
  });
}
usdk_error USDK_CALL usdk_clipboard_write(usdk_handle h,
                                          const usdk_content *content,
                                          usdk_completion cb, void *ctx,
                                          uint64_t *out) {
  return Command<void>(h, cb, ctx, out, [&](auto s, auto, auto done) {
    Require(content &&
            (content->type == USDK_TEXT || content->type == USDK_IMAGE));
    ShareContent value;
    value.type = static_cast<ContentType>(content->type);
    Validate(content->image, 4194304);
    value.text = String(content->text, 4194304);
    value.mime_type = String(content->mime_type);
    Require(value.text.size() + value.mime_type.size() +
                    content->image.length <=
                4194304,
            USDK_RESOURCE_LIMIT);
    if (content->image.length)
      value.image.assign(content->image.data,
                         content->image.data +
                             static_cast<size_t>(content->image.length));
    Service(s, &State::clipboard)
        ->WriteContent(std::move(value), std::move(done));
  });
}
}
