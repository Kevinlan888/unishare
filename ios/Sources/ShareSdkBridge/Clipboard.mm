#include "Common.hpp"
#import <ImageIO/ImageIO.h>
namespace unishare::ios {
namespace {
class Clipboard final : public IPlatformClipboard {
 UIPasteboard* board_;
public:
 explicit Clipboard(UIPasteboard* board):board_(board) {}
 OperationId Read(ResultCallback<ShareContent> callback) override {
  auto id=Next();auto board=board_;
  dispatch_async(dispatch_get_main_queue(),^{
   @try {
    ShareContent content;
    if(board.hasStrings) {
     NSData* utf8=[board.string dataUsingEncoding:NSUTF8StringEncoding allowLossyConversion:NO];
     if(!utf8||utf8.length>8*1024*1024){Deliver(callback,Result<ShareContent>::Failure(Failure(ErrorCode::InvalidInput,"clipboard text size/encoding")));return;}
     content.text.assign(static_cast<const char*>(utf8.bytes),utf8.length);
    } else if(board.hasImages) {
     NSData* image=UIImagePNGRepresentation(board.image);
     if(!image||image.length>100*1024*1024){Deliver(callback,Result<ShareContent>::Failure(Failure(ErrorCode::InvalidInput,"clipboard image")));return;}
     content.type=ContentType::Image;content.mime_type="image/png";content.image=Bytes(image);
    } else {Deliver(callback,Result<ShareContent>::Failure(Failure(ErrorCode::InvalidInput,"clipboard has no text or image")));return;}
    Deliver(callback,Result<ShareContent>::Success(std::move(content)));
   } @catch(NSException*) {Deliver(callback,Result<ShareContent>::Failure(Failure(ErrorCode::PermissionDenied,"pasteboard unavailable")));}
  });return id;
 }
 OperationId Write(ShareContent content,ResultCallback<void> callback) override {
  auto id=Next();auto board=board_;
  dispatch_async(dispatch_get_main_queue(),^{
   @try {
    if(content.type==ContentType::Text&&content.text.size()<=8*1024*1024) {
     NSString* text=[[NSString alloc] initWithBytes:content.text.data() length:content.text.size() encoding:NSUTF8StringEncoding];
     if(!text){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"invalid UTF-8")));return;}
     board.string=text;
    } else if(content.type==ContentType::Image&&content.image.size()<=100*1024*1024&&
       (content.mime_type=="image/png"||content.mime_type=="image/jpeg")) {
     NSData* bytes=Data(content.image);
     CGImageSourceRef source=CGImageSourceCreateWithData((__bridge CFDataRef)bytes,nullptr);
     CFStringRef type=source?CGImageSourceGetType(source):nullptr;
     CFStringRef expected=content.mime_type=="image/png"?CFSTR("public.png"):CFSTR("public.jpeg");
     bool matching=type&&CFEqual(type,expected);
     if(source)CFRelease(source);
     if(!matching){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"image MIME type differs from bytes")));return;}
     UIImage* image=[UIImage imageWithData:bytes];
     if(!image){Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"invalid image")));return;}
     board.image=image;
    } else {Deliver(callback,Result<void>::Failure(Failure(ErrorCode::InvalidInput,"unsupported clipboard content")));return;}
    Deliver(callback,Result<void>::Success());
   } @catch(NSException*) {Deliver(callback,Result<void>::Failure(Failure(ErrorCode::PermissionDenied,"pasteboard unavailable")));}
  });return id;
 }
};
}
std::shared_ptr<IPlatformClipboard> MakeClipboard(UIPasteboard* board) {return std::make_shared<Clipboard>(board);}
}
