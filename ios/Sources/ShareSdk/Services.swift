import Foundation
public struct DeviceDiscovery {
    private let sdk: ShareSdk
    internal init(_ sdk: ShareSdk) { self.sdk = sdk }
    public func getDevices() throws -> [DeviceInfo] { try sdk.snapshot(false).map(Packets.device) }
    public func observe(_ listener: @escaping (ShareEvent) -> Void) -> EventObservation { sdk.observe { event in
        switch event { case .online, .updated, .offline, .discoveryError, .discoveryState: listener(event); default: break }
    } }
}
public struct ConnectionManager {
    private let sdk: ShareSdk
    internal init(_ sdk: ShareSdk) { self.sdk = sdk }
    @discardableResult public func connect(_ device: UUID, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(3,["id": device],completion: completion) }
    @discardableResult public func confirmPairing(_ request: UInt64, codeMatches: Bool, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(4,["request":request,"matches":codeMatches],completion: completion) }
    @discardableResult public func rejectPairing(_ request: UInt64, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(5,["request":request],completion: completion) }
    @discardableResult public func forgetDevice(_ device: UUID, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(6,["id":device],completion: completion) }
    public func getTrustedDevices() throws -> [TrustedDevice] { try sdk.snapshot(true).map { TrustedDevice(id:$0["id"] as? UUID ?? Packets.zero,publicKeyFingerprint:$0["fingerprint"] as? Data ?? Data()) } }
    public func observe(_ listener: @escaping (ShareEvent) -> Void) -> EventObservation { sdk.observe { event in
        switch event { case .connection, .pairingRequest, .pairingResult, .identityChanged: listener(event); default: break }
    } }
}
public struct TransferService {
    private let sdk: ShareSdk
    internal init(_ sdk: ShareSdk) { self.sdk = sdk }
    @discardableResult public func sendText(_ text: String, to device: UUID, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(7,["id":device,"text":text],completion:completion) }
    @discardableResult public func sendImage(_ url: URL, to device: UUID, mimeType: String, byteCount: UInt64, displayName: String = "image", completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(8,["id":device,"url":url,"name":displayName,"mime":mimeType,"size":byteCount],completion:completion) }
    @discardableResult public func sendFile(_ url: URL, to device: UUID, displayName: String, byteCount: UInt64, mimeType: String = "application/octet-stream", completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(9,["id":device,"url":url,"name":displayName,"mime":mimeType,"size":byteCount],completion:completion) }
    @discardableResult public func accept(_ request: UInt64, stagingURL: URL, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(10,["request":request,"url":stagingURL],completion:completion) }
    @discardableResult public func reject(_ request: UInt64, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(11,["request":request],completion:completion) }
    @discardableResult public func cancel(_ transfer: UUID, completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(12,["id":transfer],completion:completion) }
    public func observe(_ listener: @escaping (ShareEvent) -> Void) -> EventObservation { sdk.observe { event in
        switch event { case .incomingOffer, .progress, .transferResult: listener(event); default: break }
    } }
}
public struct ClipboardService {
    private let sdk: ShareSdk
    internal init(_ sdk: ShareSdk) { self.sdk = sdk }
    @discardableResult public func readShareableContent(completion: @escaping (OperationResult) -> Void) -> UInt64 { sdk.command(13,completion:completion) }
    @discardableResult public func writeContent(_ content: ShareContent, completion: @escaping (OperationResult) -> Void) -> UInt64 {
        switch content {
        case .text(let value): return sdk.command(14,["contentType":1,"text":value],completion:completion)
        case .image(let mime,let bytes): return sdk.command(14,["contentType":2,"mime":mime,"bytes":bytes],completion:completion)
        }
    }
}
