#include "clipboard.hpp"
#include "win32_helpers.hpp"
#include "core/serial_scheduler.hpp"
#include <wincodec.h>
#include <wrl/client.h>
#include <atomic>
#include <cstring>
#include <limits>
namespace unishare::windows { namespace {
using Bytes=std::vector<std::uint8_t>;using Microsoft::WRL::ComPtr;
constexpr std::size_t Limit=100*1024*1024;
[[noreturn]] void Invalid(const char* message){throw WinError{{ErrorCode::InvalidInput,message,{}}};}
void Hr(HRESULT hr,const char* message){if(FAILED(hr))throw WinError{{ErrorCode::InvalidInput,message,hr}};}
struct ComScope {HRESULT hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);ComScope(){Hr(hr,"initialize WIC apartment");}~ComScope(){CoUninitialize();}};
ComPtr<IWICImagingFactory> Factory(){ComPtr<IWICImagingFactory> f;Hr(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&f)),"create WIC factory");return f;}
std::size_t PixelSize(UINT w,UINT h){if(!w||!h||w>INT_MAX||h>INT_MAX||std::uint64_t(w)*h>Limit/4)Invalid("image dimensions exceed limit");return std::size_t(w)*h*4;}
struct Pixels {UINT width=0,height=0;Bytes bgra;};
Pixels Decode(const Bytes& bytes,const std::string& mime){
 if(bytes.empty()||bytes.size()>Limit)Invalid("invalid encoded image size");
 auto factory=Factory();ComPtr<IWICStream> stream;Hr(factory->CreateStream(&stream),"create image stream");Hr(stream->InitializeFromMemory(const_cast<BYTE*>(bytes.data()),static_cast<DWORD>(bytes.size())),"open image bytes");
 ComPtr<IWICBitmapDecoder> decoder;Hr(factory->CreateDecoderFromStream(stream.Get(),nullptr,WICDecodeMetadataCacheOnLoad,&decoder),"decode image");GUID format;Hr(decoder->GetContainerFormat(&format),"image container");
 if((mime=="image/png"&&format!=GUID_ContainerFormatPng)||(mime=="image/jpeg"&&format!=GUID_ContainerFormatJpeg)||(mime!="image/png"&&mime!="image/jpeg"))Invalid("image MIME does not match bytes");
 ComPtr<IWICBitmapFrameDecode> frame;Hr(decoder->GetFrame(0,&frame),"decode first frame");Pixels p;Hr(frame->GetSize(&p.width,&p.height),"image dimensions");p.bgra.resize(PixelSize(p.width,p.height));
 ComPtr<IWICFormatConverter> convert;Hr(factory->CreateFormatConverter(&convert),"create image converter");Hr(convert->Initialize(frame.Get(),GUID_WICPixelFormat32bppBGRA,WICBitmapDitherTypeNone,nullptr,0,WICBitmapPaletteTypeCustom),"convert image to BGRA");Hr(convert->CopyPixels(nullptr,p.width*4,static_cast<UINT>(p.bgra.size()),p.bgra.data()),"copy decoded pixels");return p;
}
Bytes EncodePng(const Pixels& p){
 auto factory=Factory();ComPtr<::IStream> stream;Hr(CreateStreamOnHGlobal(nullptr,TRUE,&stream),"create PNG stream");ComPtr<IWICBitmapEncoder> encoder;Hr(factory->CreateEncoder(GUID_ContainerFormatPng,nullptr,&encoder),"create PNG encoder");Hr(encoder->Initialize(stream.Get(),WICBitmapEncoderNoCache),"initialize PNG encoder");ComPtr<IWICBitmapFrameEncode> frame;Hr(encoder->CreateNewFrame(&frame,nullptr),"create PNG frame");Hr(frame->Initialize(nullptr),"initialize PNG frame");Hr(frame->SetSize(p.width,p.height),"set PNG size");WICPixelFormatGUID fmt=GUID_WICPixelFormat32bppBGRA;Hr(frame->SetPixelFormat(&fmt),"set PNG pixels");if(fmt!=GUID_WICPixelFormat32bppBGRA)Invalid("unsupported PNG pixel format");Hr(frame->WritePixels(p.height,p.width*4,static_cast<UINT>(p.bgra.size()),const_cast<BYTE*>(p.bgra.data())),"encode PNG pixels");Hr(frame->Commit(),"commit PNG frame");Hr(encoder->Commit(),"commit PNG");STATSTG stat{};Hr(stream->Stat(&stat,STATFLAG_NONAME),"PNG size");if(stat.cbSize.QuadPart>Limit)Invalid("PNG exceeds limit");Bytes bytes(static_cast<std::size_t>(stat.cbSize.QuadPart));LARGE_INTEGER start{};Hr(stream->Seek(start,STREAM_SEEK_SET,nullptr),"rewind PNG");ULONG read=0;Hr(stream->Read(bytes.data(),static_cast<ULONG>(bytes.size()),&read),"read PNG");if(read!=bytes.size())Invalid("short PNG stream");return bytes;
}
Bytes Dib(const Pixels& p){
 BITMAPV5HEADER header{};header.bV5Size=sizeof header;header.bV5Width=p.width;header.bV5Height=-static_cast<LONG>(p.height);header.bV5Planes=1;header.bV5BitCount=32;header.bV5Compression=BI_BITFIELDS;header.bV5SizeImage=static_cast<DWORD>(p.bgra.size());header.bV5RedMask=0x00ff0000;header.bV5GreenMask=0x0000ff00;header.bV5BlueMask=0x000000ff;header.bV5AlphaMask=0xff000000;header.bV5CSType=LCS_sRGB;header.bV5Intent=LCS_GM_IMAGES;
 Bytes bytes(sizeof header+p.bgra.size());memcpy(bytes.data(),&header,sizeof header);memcpy(bytes.data()+sizeof header,p.bgra.data(),p.bgra.size());return bytes;
}
Pixels DecodeDib(const Bytes& bytes){
 if(bytes.size()<sizeof(BITMAPINFOHEADER))Invalid("truncated DIB");BITMAPV5HEADER h{};memcpy(&h,bytes.data(),sizeof(BITMAPINFOHEADER));
 if(h.bV5Size!=sizeof(BITMAPINFOHEADER)&&h.bV5Size!=sizeof(BITMAPV5HEADER))Invalid("unsupported DIB header");if(bytes.size()<h.bV5Size)Invalid("truncated DIB header");memcpy(&h,bytes.data(),h.bV5Size);
 if(h.bV5Width<=0||!h.bV5Height||h.bV5Height==LONG_MIN||h.bV5Planes!=1||h.bV5ClrUsed||(h.bV5BitCount!=24&&h.bV5BitCount!=32))Invalid("unsupported DIB layout");
 if(h.bV5Compression!=BI_RGB&&h.bV5Compression!=BI_BITFIELDS)Invalid("unsupported DIB compression");
 std::size_t offset=h.bV5Size;
 if(h.bV5Compression==BI_BITFIELDS){if(h.bV5Size==sizeof(BITMAPINFOHEADER)){if(bytes.size()<offset+12)Invalid("truncated DIB masks");memcpy(&h.bV5RedMask,bytes.data()+offset,12);offset+=12;}if(h.bV5BitCount!=32||h.bV5RedMask!=0x00ff0000||h.bV5GreenMask!=0xff00||h.bV5BlueMask!=0xff||(h.bV5AlphaMask&&h.bV5AlphaMask!=0xff000000))Invalid("unsupported DIB masks");}
 if(h.bV5Size==sizeof(BITMAPV5HEADER)&&(h.bV5ProfileData||h.bV5ProfileSize))Invalid("embedded DIB profiles unsupported");
 Pixels p;p.width=h.bV5Width;p.height=h.bV5Height<0?-h.bV5Height:h.bV5Height;p.bgra.resize(PixelSize(p.width,p.height));
 auto stride=((std::size_t(p.width)*h.bV5BitCount+31)/32)*4;if(stride*p.height>bytes.size()-offset)Invalid("truncated DIB pixels");
 for(UINT y=0;y<p.height;y++){auto src=bytes.data()+offset+stride*(h.bV5Height<0?y:p.height-1-y);auto dst=p.bgra.data()+std::size_t(y)*p.width*4;for(UINT x=0;x<p.width;x++){dst[x*4]=src[x*(h.bV5BitCount/8)];dst[x*4+1]=src[x*(h.bV5BitCount/8)+1];dst[x*4+2]=src[x*(h.bV5BitCount/8)+2];dst[x*4+3]=(h.bV5BitCount==32&&h.bV5AlphaMask)?src[x*4+3]:255;}}
 return p;
}
Bytes EncodeText(const std::string& text){
 if(text.size()>Limit||text.find('\0')!=std::string::npos)
  Invalid("invalid clipboard text");
 int n=text.empty()?0:MultiByteToWideChar(
  CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),nullptr,0);
 if(!text.empty()&&!n)Invalid("invalid UTF-8 text");
 // The terminator is part of the same byte limit enforced by DecodeText.
 const auto encodedSize=(static_cast<std::size_t>(n)+1)*sizeof(wchar_t);
 if(encodedSize>Limit)Invalid("UTF-16 clipboard text exceeds limit");
 std::wstring wide(n,L'\0');
 if(n&&!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),
                           static_cast<int>(text.size()),wide.data(),n))
  Invalid("invalid UTF-8 text");
 Bytes bytes(encodedSize);
 memcpy(bytes.data(),wide.data(),n*sizeof(wchar_t));
 return bytes;
}
std::string DecodeText(const Bytes& bytes){
 if(bytes.size()<2||bytes.size()%2||bytes.size()>Limit)
  Invalid("invalid UTF-16 clipboard buffer");
 std::wstring wide(bytes.size()/2,L'\0');
 memcpy(wide.data(),bytes.data(),bytes.size());
 auto end=wide.find(L'\0');
 if(end==std::wstring::npos)Invalid("unterminated UTF-16 text");
 if(end==0)return {};
 int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,wide.data(),
                           static_cast<int>(end),nullptr,0,nullptr,nullptr);
 if(!n)Invalid("invalid UTF-16 text");
 std::string text(n,'\0');
 if(!WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,wide.data(),
                         static_cast<int>(end),text.data(),n,nullptr,nullptr))
  Invalid("invalid UTF-16 text");
 return text;
}
UINT NativeFormat(ClipboardFormat f){switch(f){case ClipboardFormat::UnicodeText:return CF_UNICODETEXT;case ClipboardFormat::DibV5:return CF_DIBV5;case ClipboardFormat::Dib:return CF_DIB;case ClipboardFormat::Png:return RegisterClipboardFormatW(L"PNG");case ClipboardFormat::Jpeg:return RegisterClipboardFormatW(L"JFIF");}return 0;}
[[noreturn]] void ClipboardError(const char* what,DWORD code=GetLastError()){throw WinError{{ErrorCode::PermissionDenied,what,code}};}
struct ClipboardSession {
 HWND window=nullptr;bool open=false;
 ClipboardSession(){window=CreateWindowExW(0,L"STATIC",L"UniShare Clipboard",0,0,0,0,0,HWND_MESSAGE,nullptr,GetModuleHandleW(nullptr),nullptr);if(!window)ClipboardError("create clipboard owner");if(!OpenClipboard(window)){auto code=GetLastError();DestroyWindow(window);window=nullptr;ClipboardError("open clipboard",code);}open=true;}
 ~ClipboardSession(){if(open)CloseClipboard();if(window)DestroyWindow(window);}
};
struct GlobalBlock {HGLOBAL h=nullptr;~GlobalBlock(){if(h)GlobalFree(h);}GlobalBlock()=default;GlobalBlock(const GlobalBlock&)=delete;};
class Win32Clipboard final : public ClipboardBoundary {
public:
 Result<std::vector<ClipboardData>> Read() override {try{
  ClipboardSession session;std::vector<ClipboardData> result;
  for(auto format:{ClipboardFormat::UnicodeText,ClipboardFormat::Png,ClipboardFormat::Jpeg,ClipboardFormat::DibV5,ClipboardFormat::Dib}){
   UINT native=NativeFormat(format);if(!native)ClipboardError("register clipboard format");if(!IsClipboardFormatAvailable(native))continue;
   HANDLE data=GetClipboardData(native);if(!data)ClipboardError("get clipboard data");auto size=GlobalSize(data);if(!size||size>Limit+sizeof(BITMAPV5HEADER))Invalid("clipboard data exceeds limit");auto ptr=GlobalLock(data);if(!ptr)ClipboardError("lock clipboard data");Bytes bytes(size);memcpy(bytes.data(),ptr,size);GlobalUnlock(data);result.push_back({format,std::move(bytes)});
   // One supported representation is sufficient; avoid materializing conversions.
   break;
  }
  return Result<std::vector<ClipboardData>>::Success(std::move(result));
 }catch(const WinError& e){return Result<std::vector<ClipboardData>>::Failure(e.error);}}
 Result<void> Write(std::vector<ClipboardData> data) override {try{
  std::vector<std::unique_ptr<GlobalBlock>> blocks;std::vector<UINT> formats;
  for(auto& item:data){auto block=std::make_unique<GlobalBlock>();block->h=GlobalAlloc(GMEM_MOVEABLE,item.bytes.size());if(!block->h)ClipboardError("allocate clipboard data");auto ptr=GlobalLock(block->h);if(!ptr)ClipboardError("lock clipboard data");memcpy(ptr,item.bytes.data(),item.bytes.size());GlobalUnlock(block->h);auto format=NativeFormat(item.format);if(!format)ClipboardError("register clipboard format");formats.push_back(format);blocks.push_back(std::move(block));}
  ClipboardSession session;if(!EmptyClipboard())ClipboardError("empty clipboard");for(std::size_t i=0;i<blocks.size();i++){if(!SetClipboardData(formats[i],blocks[i]->h))ClipboardError("set clipboard data");blocks[i]->h=nullptr;}return Result<void>::Success();
 }catch(const WinError& e){return Result<void>::Failure(e.error);}}
};
}
struct Clipboard::State {std::shared_ptr<ClipboardBoundary> boundary;std::shared_ptr<core::SerialScheduler> worker=std::make_shared<core::SerialScheduler>();std::atomic<OperationId> next{0};explicit State(std::shared_ptr<ClipboardBoundary> b):boundary(std::move(b)){};};
std::shared_ptr<ClipboardBoundary> CreateWin32ClipboardBoundary(){return std::make_shared<Win32Clipboard>();}
Clipboard::Clipboard(std::shared_ptr<ClipboardBoundary> b):state_(std::make_shared<State>(b?std::move(b):CreateWin32ClipboardBoundary())){}Clipboard::~Clipboard()=default;
OperationId Clipboard::Read(ResultCallback<ShareContent> cb){auto s=state_;auto id=++s->next;s->worker->Post([s,cb=std::move(cb)]{
 auto operation=[&]()->Result<ShareContent>{try{ComScope com;auto read=s->boundary->Read();if(!read.ok())return Result<ShareContent>::Failure(read.error());if(read.value().empty())Invalid("no supported clipboard data");const auto& item=read.value()[0];ShareContent content;if(item.format==ClipboardFormat::UnicodeText)content.text=DecodeText(item.bytes);else{content.type=ContentType::Image;if(item.format==ClipboardFormat::Png||item.format==ClipboardFormat::Jpeg){content.mime_type=item.format==ClipboardFormat::Png?"image/png":"image/jpeg";Decode(item.bytes,content.mime_type);content.image=item.bytes;}else{content.mime_type="image/png";content.image=EncodePng(DecodeDib(item.bytes));}}return Result<ShareContent>::Success(std::move(content));}catch(const WinError& e){return Result<ShareContent>::Failure(e.error);}};
 cb(operation());});return id;}
OperationId Clipboard::Write(ShareContent content,ResultCallback<void> cb){auto s=state_;auto id=++s->next;s->worker->Post([s,content=std::move(content),cb=std::move(cb)]{
 auto operation=[&]()->Result<void>{try{ComScope com;std::vector<ClipboardData> formats;if(content.type==ContentType::Text)formats.push_back({ClipboardFormat::UnicodeText,EncodeText(content.text)});else if(content.type==ContentType::Image){auto pixels=Decode(content.image,content.mime_type);formats.push_back({content.mime_type=="image/png"?ClipboardFormat::Png:ClipboardFormat::Jpeg,content.image});formats.push_back({ClipboardFormat::DibV5,Dib(pixels)});}else Invalid("unsupported clipboard content");return s->boundary->Write(std::move(formats));}catch(const WinError& e){return Result<void>::Failure(e.error);}};
 cb(operation());});return id;}
}
