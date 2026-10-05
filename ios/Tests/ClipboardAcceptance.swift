import UIKit
import UniShareSDK

// Four opaque, distinct pixels. Compare decoded pixels, not encoded container bytes.
func fixtureImage(_ pixels: [UInt8] = [255,0,0,255, 0,255,0,255, 0,0,255,255, 255,255,255,255]) -> UIImage {
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
    if mode == "clipboard-write" {
        // New opaque pixel values for every writer run prevent stale-image success.
        let nonce = UUID().uuid
        var pixels = withUnsafeBytes(of: nonce) { Array($0) }
        for alpha in [3, 7, 11, 15] { pixels[alpha] = 255 }
        let png = fixtureImage(pixels).pngData()!
        UserDefaults.standard.set(decodedPixels(png), forKey: "clipboardExpectedPixels")
        ok { sdk.clipboard.writeContent(.text("你好 👋\0line\n é"), completion: $0) }
        check(Operation { sdk.clipboard.readShareableContent(completion: $0) }.result().content == .text("你好 👋\0line\n é"), "manual text clipboard UTF-8/NUL")
        ok { sdk.clipboard.writeContent(.image(mimeType: "image/png", bytes: png), completion: $0) }
        // The runner waits for this process to exit before launching the reader.
        passed("manual UTF-8/NUL and PNG clipboard write; writer now exits")
    } else {
        guard let expected = UserDefaults.standard.data(forKey: "clipboardExpectedPixels") else {
            preconditionFailure("run a fresh clipboard writer before reader")
        }
        let result = Operation { sdk.clipboard.readShareableContent(completion: $0) }.result()
        guard case .image(let mime, let bytes) = result.content else {
            preconditionFailure("separate-process clipboard image missing: \(String(describing: result.error))")
        }
        check(mime == "image/png", "clipboard image MIME")
        check(decodedPixels(bytes) == expected, "separate-process clipboard pixels match this writer run")
        UserDefaults.standard.removeObject(forKey: "clipboardExpectedPixels")
        passed("separate-process clipboard PNG read/decode/pixels after writer exit")
    }
}
