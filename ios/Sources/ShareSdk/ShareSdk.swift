import Foundation

public final class EventObservation {
    private let cancel: () -> Void
    internal init(_ cancel: @escaping () -> Void) { self.cancel = cancel }
    public func close() { cancel() }
    deinit { cancel() }
}
/// Owns one SDK installation identity and its foreground services.
public final class ShareSdk {
    private let backend: Backend
    private let gate = NSRecursiveLock()
    private let worker = DispatchQueue(label: "com.unishare.submit")
    private let delivery: DispatchQueue
    private var closed = false, initializing = false, ready = false
    private var maxTextBytes: UInt64 = 8388608
    private var textGeneration: UInt64 = 0
    private var initializeId: UInt64?
    private var stagedText: [UUID: URL] = [:]
    private var next: UInt64 = 1
    private var pending: [UInt64: (OperationResult) -> Void] = [:]
    private var observers: [UInt64: (ShareEvent) -> Void] = [:]
    public var discovery: DeviceDiscovery { DeviceDiscovery(self) }
    public var connections: ConnectionManager { ConnectionManager(self) }
    public var transfers: TransferService { TransferService(self) }
    public var clipboard: ClipboardService { ClipboardService(self) }
    #if canImport(UIKit)
    public convenience init(keychainService: String = "com.unishare.identity", callbackQueue: DispatchQueue? = nil) {
        self.init(backend: NativeBackend(service: keychainService), callbackQueue: callbackQueue)
    }
    #endif
    internal init(backend: Backend, callbackQueue: DispatchQueue?) {
        self.backend = backend
        delivery = DispatchQueue(label: "com.unishare.delivery", target: callbackQueue)
        backend.event = { [weak self] packet in
            guard let self = self, let event = Packets.event(packet) else { return }
            self.delivery.async { [weak self] in
                guard let self = self else { return }
                self.gate.lock(); defer { self.gate.unlock() }
                guard !self.closed else { return }
                for id in self.observers.keys.sorted() {
                    if self.closed { break }
                    self.observers[id]?(event)
                }
            }
        }
    }
    public var state: SdkState {
        gate.lock(); defer { gate.unlock() }
        return closed ? .closed : SdkState(rawValue: (try? backend.state()) ?? 0) ?? .uninitialized
    }
    @discardableResult public func initialize(_ config: SdkConfig, completion: @escaping (OperationResult) -> Void) -> UInt64 {
        gate.lock(); defer { gate.unlock() }
        let allowed = !initializing
        if allowed {
            initializing = true
            maxTextBytes = min(config.maxTextBytes, 8388608)
        }
        return submit(requireReady: false, initialization: allowed, completion: completion) { callback in
            if allowed { self.backend.create(config, completion: callback) }
            else { callback(["error": 13]) }
        }
    }
    @discardableResult public func start(completion: @escaping (OperationResult) -> Void) -> UInt64 { command(1, completion: completion) }
    @discardableResult public func stop(completion: @escaping (OperationResult) -> Void) -> UInt64 {
        gate.lock(); textGeneration += 1; gate.unlock()
        return command(2, completion: completion)
    }
    /// Waits for an executing host callback, except the callback calling close itself.
    /// Pending/queued callbacks are retired; native cleanup runs on its worker.
    public func close() {
        gate.lock(); defer { gate.unlock() }
        guard !closed else { return }
        closed = true; textGeneration += 1; pending.removeAll(); observers.removeAll()
        for url in stagedText.values { try? FileManager.default.removeItem(at: url) }
        stagedText.removeAll()
        let backend = self.backend
        worker.async { backend.close() }
    }
    deinit { for url in stagedText.values { try? FileManager.default.removeItem(at:url) }; backend.close() }
    public func observe(_ listener: @escaping (ShareEvent) -> Void) -> EventObservation {
        gate.lock(); defer { gate.unlock() }
        let id = next; next += 1
        if !closed { observers[id] = listener }
        return EventObservation { [weak self] in
            guard let self = self else { return }
            self.gate.lock(); self.observers.removeValue(forKey: id); self.gate.unlock()
        }
    }
    internal func snapshot(_ trusted: Bool) throws -> [[String: Any]] {
        gate.lock(); defer { gate.unlock() }
        guard !closed && ready else { throw ShareError(.invalidInput) }
        return try backend.snapshot(trusted)
    }
    @discardableResult internal func command(_ command: Int, _ arguments: [String: Any] = [:], completion: @escaping (OperationResult) -> Void) -> UInt64 {
        submit(completion: completion) { self.backend.submit(command, arguments: arguments, completion: $0) }
    }
    // Encoding and disk I/O run on the submission worker. Only chunk buffers cross
    // into the native source; the caller's String never becomes a native span.
    @discardableResult internal func sendText(_ text: String, to device: UUID, completion: @escaping (OperationResult) -> Void) -> UInt64 {
        gate.lock(); let generation = textGeneration, limit = maxTextBytes; gate.unlock()
        return submit(completion: completion) { finish in
            let count = UInt64(text.utf8.count)
            guard count <= limit else { finish(["error":14]); return }
            let key = UUID(), url = FileManager.default.temporaryDirectory.appendingPathComponent("unishare-text-" + UUID().uuidString)
            do {
                guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw ShareError(.storageFailed) }
                let file = try FileHandle(forWritingTo: url)
                do {
                    var chunk = Data(); chunk.reserveCapacity(65536)
                    for byte in text.utf8 {
                        chunk.append(byte)
                        if chunk.count == 65536 { try file.write(contentsOf: chunk); chunk.removeAll(keepingCapacity: true) }
                    }
                    if !chunk.isEmpty { try file.write(contentsOf: chunk) }
                    try file.close()
                } catch { try? file.close(); throw error }
                self.gate.lock()
                guard !self.closed && generation == self.textGeneration else {
                    self.gate.unlock(); try? FileManager.default.removeItem(at:url); finish(["error":8]); return
                }
                self.stagedText[key] = url
                self.backend.submit(15, arguments:["id":device,"url":url,"size":count], completion: { [weak self] packet in
                    if let self = self {
                        self.gate.lock(); self.stagedText.removeValue(forKey:key); self.gate.unlock()
                    }
                    try? FileManager.default.removeItem(at:url)
                    finish(packet)
                })
                self.gate.unlock()
            } catch {
                try? FileManager.default.removeItem(at:url)
                finish(["error":11])
            }
        }
    }
    private func submit(requireReady: Bool = true, initialization: Bool = false,
                        completion: @escaping (OperationResult) -> Void,
                        work: @escaping (@escaping ([String: Any]) -> Void) -> Void) -> UInt64 {
        gate.lock(); defer { gate.unlock() }
        let id = next; next += 1
        precondition(!closed, "ShareSdk is closed")
        pending[id] = completion
        if initialization { initializeId = id }
        worker.async { [weak self] in
            guard let self = self else { return }
            self.gate.lock(); let allowed = !self.closed && (!requireReady || self.ready); self.gate.unlock()
            let finish: ([String: Any]) -> Void = { [weak self] packet in
                guard let self = self else { return }
                let result = Packets.result(id, packet)
                self.delivery.async { [weak self] in
                    guard let self = self else { return }
                    self.gate.lock(); defer { self.gate.unlock() }
                    guard !self.closed, let callback = self.pending.removeValue(forKey: id) else { return }
                    if self.initializeId == id {
                        self.initializeId = nil
                        if result.isSuccess { self.ready = true }
                        else { self.initializing = false }
                    }
                    callback(result)
                }
            }
            if allowed { work(finish) } else { finish(["error": 13]) }
        }
        return id
    }
}
