import Foundation

public enum ErrorCategory: UInt32 { case permissionDenied = 1, discoveryFailed, versionMismatch, pairingRejected, authenticationFailed, identityChanged, transferRejected, cancelled, timeout, networkLost, storageFailed, integrityFailed, invalidInput, resourceLimit }
public struct ShareError: Error, Equatable { public let category: ErrorCategory; public let platformError: Int64?; public init(_ category: ErrorCategory, platformError: Int64? = nil) { self.category = category; self.platformError = platformError } }
public enum SdkState: UInt32 { case uninitialized, initialized, running, stopped, closed }
public enum DevicePlatform: UInt32 { case windows, android, ios }
public enum DiscoveryState: UInt32 { case starting, browsing, changed, stopped }
public enum ContentType: UInt32 { case text = 1, image, file }
public struct DeviceInfo: Equatable {
    public let id: UUID, name: String, platform: DevicePlatform
    public let protocolMajor: UInt16, protocolMinor: UInt16, port: UInt16
    public let paired: Bool, lastSeenUnixMillis: Int64, addresses: [String]
}
public struct TrustedDevice: Equatable { public let id: UUID; public let publicKeyFingerprint: Data }
public enum ShareContent: Equatable { case text(String), image(mimeType: String, bytes: Data) }
public struct OperationResult: Equatable {
    public let operationId: UInt64, error: ShareError?, transferId: UUID?, content: ShareContent?
    public var isSuccess: Bool { error == nil }
}
public enum ShareEvent: Equatable {
    case online(DeviceInfo), updated(DeviceInfo), offline(DeviceInfo)
    case discoveryError(ShareError), discoveryState(DiscoveryState)
    case connection(deviceId: UUID, connected: Bool, error: ShareError?)
    case pairingRequest(device: DeviceInfo, requestId: UInt64, verificationCode: String)
    case pairingResult(deviceId: UUID, requestId: UInt64, error: ShareError?)
    case identityChanged(deviceId: UUID)
    case incomingOffer(deviceId: UUID, requestId: UInt64, transferId: UUID, type: ContentType, displayName: String, mimeType: String, byteCount: UInt64)
    case progress(transferId: UUID, transferred: UInt64, total: UInt64)
    case transferResult(transferId: UUID, error: ShareError?)
}
public struct SdkConfig {
    public var deviceName: String
    public var maxConnections: UInt32 = 8, maxPendingRequests: UInt32 = 4
    public var maxBufferBytes: UInt64 = 4 * 1024 * 1024, maxTextBytes: UInt64 = 8 * 1024 * 1024
    public var maxImageBytes: UInt64 = 100 * 1024 * 1024, maxFileBytes: UInt64 = 20 * 1024 * 1024 * 1024
    public var controlFrameBytes: UInt32 = 65536, dataChunkBytes: UInt32 = 262144
    public var requestTimeoutSeconds: UInt32 = 60, offlineDebounceSeconds: UInt32 = 5
    public init(deviceName: String) { self.deviceName = deviceName }
}
