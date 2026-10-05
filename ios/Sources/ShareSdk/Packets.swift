import Foundation
internal enum Packets {
    static let zero = UUID(uuid: (0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0))
    static func number(_ p: [String: Any], _ key: String) -> UInt64 { (p[key] as? NSNumber)?.uint64Value ?? 0 }
    static func error(_ p: [String: Any]) -> ShareError? {
        guard let category = ErrorCategory(rawValue: UInt32(number(p, "error"))) else { return nil }
        return ShareError(category, platformError: (p["platformError"] as? NSNumber)?.int64Value)
    }
    static func device(_ p: [String: Any]) -> DeviceInfo {
        DeviceInfo(id: p["id"] as? UUID ?? zero, name: p["name"] as? String ?? "", platform: DevicePlatform(rawValue: UInt32(number(p,"platform"))) ?? .ios,
                   protocolMajor: UInt16(number(p,"major")), protocolMinor: UInt16(number(p,"minor")), port: UInt16(number(p,"port")), paired: number(p,"paired") != 0,
                   lastSeenUnixMillis: (p["lastSeen"] as? NSNumber)?.int64Value ?? 0, addresses: p["addresses"] as? [String] ?? [])
    }
    static func result(_ id: UInt64, _ p: [String: Any]) -> OperationResult {
        var content: ShareContent?
        if number(p,"contentType") == 1 { content = .text(p["text"] as? String ?? "") }
        if number(p,"contentType") == 2 { content = .image(mimeType: p["mime"] as? String ?? "", bytes: p["bytes"] as? Data ?? Data()) }
        let transfer = p["transfer"] as? UUID
        return OperationResult(operationId: id, error: error(p), transferId: transfer == zero ? nil : transfer, content: content)
    }
    static func event(_ p: [String: Any]) -> ShareEvent? {
        let d = device(p["device"] as? [String: Any] ?? [:]), e = error(p), request = number(p,"request"), transfer = p["transfer"] as? UUID ?? zero
        switch number(p,"kind") {
        case 1: return .online(d)
        case 2: return .updated(d)
        case 3: return .offline(d)
        case 4: return .discoveryError(e ?? ShareError(.discoveryFailed))
        case 5: return .discoveryState(DiscoveryState(rawValue: UInt32(number(p,"state"))) ?? .stopped)
        case 6: return .connection(device: d, connected: number(p,"state") == 0, error: e)
        case 7: return .pairingRequest(device: d, requestId: request, verificationCode: p["code"] as? String ?? "")
        case 8: return .pairingResult(device: d, requestId: request, error: e)
        case 9: return .identityChanged(d,e)
        case 10: return .incomingOffer(device: d, requestId: request, transferId: transfer, type: ContentType(rawValue: UInt32(number(p,"contentType"))) ?? .file, displayName: p["name"] as? String ?? "", mimeType: p["mime"] as? String ?? "", byteCount: number(p,"size"))
        case 11: return .progress(transferId: transfer, transferred: number(p,"transferred"), total: number(p,"size"))
        case 12: return .transferResult(transferId: transfer, error: e)
        default: return nil
        }
    }
}
