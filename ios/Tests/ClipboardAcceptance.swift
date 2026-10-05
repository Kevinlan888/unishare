import UIKit
import UniShareSDK

// Four opaque, distinct pixels. Compare decoded pixels, not encoded container bytes.
func fixtureImage() -> UIImage {
    let pixels: [UInt8] = [255,0,0,255, 0,255,0,255, 0,0,255,255, 255,255,255,255]
    let provider = CGDataProvider(data: Data(pixels) as CFData)!
    let image = CGImage(width: 2, height: 2, bitsPerComponent: 8, bitsPerPixel: 32,
                        bytesPerRow: 8, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                        provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
    return UIImage(cgImage: image)
}
func decodedPixels(_ data: Data) -> Data {
    guard let image = UIImage(data: data)?.cgImage else { preconditionFailure("clipboard image decodes") }
    check(image.width == 2 && image.height == 2, "clipboard image dimensions")
    var pixels = [UInt8](repeating: 0, count: 16)
    pixels.withUnsafeMutableBytes { bytes in
        let context = CGContext(data: bytes.baseAddress, width: 2, height: 2, bitsPerComponent: 8,
                                bytesPerRow: 8, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.draw(image, in: CGRect(x: 0, y: 0, width: 2, height: 2))
    }
    return Data(pixels)
}
func runClipboard(_ mode: String) {
    let sdk = newSdk("com.unishare.acceptance.clipboard", "clipboard")
    defer { sdk.close() }
    let png = fixtureImage().pngData()!
    if mode == "clipboard-write" {
        ok { sdk.clipboard.writeContent(.text("你好 👋\0line\n é"), completion: $0) }
        check(Operation { sdk.clipboard.readShareableContent(completion: $0) }.result().content == .text("你好 👋\0line\n é"), "manual text clipboard UTF-8/NUL")
        ok { sdk.clipboard.writeContent(.image(mimeType: "image/png", bytes: png), completion: $0) }
        // The runner waits for this process to exit before launching the reader.
        passed("manual UTF-8/NUL and PNG clipboard write; writer now exits")
    } else {
        let result = Operation { sdk.clipboard.readShareableContent(completion: $0) }.result()
        guard case .image(let mime, let bytes) = result.content else {
            preconditionFailure("separate-process clipboard image missing: \(String(describing: result.error))")
        }
        check(mime == "image/png", "clipboard image MIME")
        check(decodedPixels(bytes) == decodedPixels(png), "separate-process clipboard pixels preserved")
        passed("separate-process clipboard PNG read/decode/pixels after writer exit")
    }
}
