#if canImport(UIKit)
import Foundation
@_implementationOnly import ShareSdkBridge
internal final class NativeBackend: Backend {
    private let queue = DispatchQueue(label: "com.unishare.native")
    private let acquisition = DispatchQueue(label: "com.unishare.file.prepare")
    private var generation: UInt64 = 0, nextPreparation: UInt64 = 0
    private var preparing: [UInt64: ([String: Any]) -> Void] = [:]
    private let service: String
    private var bridge: USBridge?
    private var closed = false
    private let eventLock = NSLock()
    private var handler: (([String: Any]) -> Void)?
    var event: (([String: Any]) -> Void)? {
        get { eventLock.lock(); defer { eventLock.unlock() }; return handler }
        set { eventLock.lock(); handler = newValue; eventLock.unlock() }
    }
    init(service: String) { self.service = service }
    func create(_ config: SdkConfig, completion: @escaping ([String: Any]) -> Void) {
        // UIKit setup never blocks a worker waiting on the main queue.
        DispatchQueue.main.async {
            let native = USBridge(keychainService: self.service)
            native.event = { [weak self] packet in self?.event?(packet) }
            self.queue.async {
                guard !self.closed else { native.close(); return }
                self.bridge = native
                native.create([
                    "deviceName":config.deviceName,"max_connections":config.maxConnections,
                    "max_pending_requests":config.maxPendingRequests,"max_buffer_bytes":config.maxBufferBytes,
                    "max_text_bytes":config.maxTextBytes,"max_image_bytes":config.maxImageBytes,
                    "max_file_bytes":config.maxFileBytes,"control_frame_bytes":config.controlFrameBytes,
                    "data_chunk_bytes":config.dataChunkBytes,"request_timeout_seconds":config.requestTimeoutSeconds,
                    "offline_debounce_seconds":config.offlineDebounceSeconds
                ], completion:completion)
            }
        }
    }
    func submit(_ command: Int, arguments: [String: Any], completion: @escaping ([String: Any]) -> Void) {
        queue.async {
            guard !self.closed, let bridge = self.bridge else { completion(["error":13]); return }
            if command == 2 { self.retirePreparation() }
            guard command == 8 || command == 9 || command == 10 || command == 15 else {
                bridge.submit(command, arguments:arguments, completion:completion); return
            }
            guard let url = arguments["url"] as? URL else { completion(["error":13]); return }
            self.nextPreparation += 1
            let key = self.nextPreparation, generation = self.generation
            self.preparing[key] = completion
            self.acquisition.async {
                let result: Result<USFileStream, Error> = Result { try USFileStream.open(url, destination:command == 10) }
                self.queue.async {
                    guard let callback = self.preparing.removeValue(forKey:key), !self.closed, generation == self.generation else {
                        if case .success(let stream) = result { self.acquisition.async { stream.close() } }
                        return
                    }
                    switch result {
                    case .failure(let failure):
                        let error = failure as NSError
                        callback(["error":error.code].merging(error.userInfo) { _, rhs in rhs })
                    case .success(let stream):
                        var prepared = arguments; prepared["stream"] = stream
                        bridge.submit(command, arguments:prepared, completion:callback)
                    }
                }
            }
        }
    }
    func snapshot(_ trusted: Bool) throws -> [[String: Any]] {
        try queue.sync {
            guard !closed, let bridge = bridge else { throw ShareError(.invalidInput) }
            do { return try bridge.snapshot(trusted) }
            catch let error as NSError { throw ShareError(ErrorCategory(rawValue:UInt32(error.code)) ?? .invalidInput, platformError:(error.userInfo["platformError"] as? NSNumber)?.int64Value) }
        }
    }
    func state() throws -> UInt32 { queue.sync { bridge.map { UInt32($0.state()) } ?? 0 } }
    private func retirePreparation() {
        generation += 1
        let callbacks = Array(preparing.values); preparing.removeAll()
        for callback in callbacks { callback(["error":8]) }
    }
    func close() {
        queue.async {
            guard !self.closed else { return }
            self.closed = true
            self.retirePreparation()
            self.bridge?.close(); self.bridge = nil
            self.event = nil
        }
    }
}
#endif
