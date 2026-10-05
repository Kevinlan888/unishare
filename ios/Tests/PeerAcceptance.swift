import UIKit
import CryptoKit
import UniShareSDK

final class Events {
    private let lock = NSLock()
    private var storage: [ShareEvent] = []
    private var token: EventObservation?
    init(_ sdk: ShareSdk) {
        token = sdk.observe { event in
            self.lock.lock(); self.storage.append(event); self.lock.unlock()
            switch event {
            case .online(let peer): print("EVENT online id=\(peer.id) name=\(peer.name) addresses=\(peer.addresses) port=\(peer.port) version=\(peer.protocolMajor).\(peer.protocolMinor)")
            case .discoveryError(let error): print("EVENT discoveryError \(error)")
            case .transferResult(let id, let error): print("EVENT terminal id=\(id) error=\(String(describing: error))")
            default: break
            }
            fflush(stdout)
        }
    }
    func close() { token?.close(); token = nil }
    var all: [ShareEvent] { lock.lock(); defer { lock.unlock() }; return storage }
    func count(_ predicate: (ShareEvent) -> Bool) -> Int { all.filter(predicate).count }
    func next(after start: Int, _ predicate: (ShareEvent) -> Bool) -> ShareEvent {
        var found: ShareEvent?
        eventually("event after \(start)") { found = self.all.dropFirst(start).first(where: predicate); return found != nil }
        return found!
    }
    func terminal(_ id: UUID) -> ShareError? {
        let result = next(after: 0) { if case .transferResult(let value, _) = $0 { return value == id }; return false }
        guard case .transferResult(_, let error) = result else { preconditionFailure() }
        return error
    }
    func terminalCount(_ id: UUID) -> Int {
        count { if case .transferResult(let value, _) = $0 { return value == id }; return false }
    }
    func verifyTerminals(_ expected: Int) {
        let ids: [UUID] = all.compactMap { if case .transferResult(let id, _) = $0 { return id }; return nil }
        check(ids.count == expected && Set(ids).count == expected, "one terminal event per transfer including failures")
    }
}
struct Offer {
    let request: UInt64, id: UUID, type: ContentType, name: String, mime: String, size: UInt64
    init(_ events: Events, after: Int) {
        let event = events.next(after: after) { if case .incomingOffer = $0 { return true }; return false }
        guard case .incomingOffer(_, let request, let id, let type, let name, let mime, let size) = event else { preconditionFailure() }
        self.request = request; self.id = id; self.type = type; self.name = name; self.mime = mime; self.size = size
    }
}
func discover(_ sdk: ShareSdk, name: String) -> DeviceInfo {
    var peer: DeviceInfo?
    eventually("Bonjour peer \(name)") { peer = try! sdk.discovery.getDevices().first { $0.name == name }; return peer != nil }
    check(peer!.platform == .ios && peer!.protocolMajor == 1 && peer!.protocolMinor == 0, "platform/version metadata")
    check(peer!.port != 0 && !peer!.addresses.isEmpty && peer!.lastSeenUnixMillis > 0, "resolved port/address/timestamp")
    return peer!
}
func pair(_ a: ShareSdk, _ ae: Events, _ b: ShareSdk, _ be: Events, to: UUID, fresh: Bool) {
    let ac = ae.all.count, bc = be.all.count
    let connection = Operation { a.connections.connect(to, completion: $0) }
    let incoming = be.next(after: bc) { if case .pairingRequest = $0 { return true }; return false }
    guard case .pairingRequest(_, let br, let bcode) = incoming else { preconditionFailure() }
    check(bcode.count == 6 && bcode.allSatisfy { $0 >= "0" && $0 <= "9" }, "six digit SAS")
    if fresh {
        let outgoing = ae.next(after: ac) { if case .pairingRequest = $0 { return true }; return false }
        guard case .pairingRequest(_, let ar, let acode) = outgoing else { preconditionFailure() }
        check(acode == bcode, "matching SAS across TLS peers")
        check((try! a.connections.getTrustedDevices()).isEmpty && (try! b.connections.getTrustedDevices()).isEmpty, "no trust before explicit confirmations")
        ok { a.connections.confirmPairing(ar, codeMatches: true, completion: $0) }
        Thread.sleep(forTimeInterval: 0.15)
        check(connection.count == 0, "one side approval does not connect")
    }
    ok { b.connections.confirmPairing(br, codeMatches: true, completion: $0) }
    check(connection.result().isSuccess, "TLS connect after explicit consent")
    _ = be.next(after: bc) { if case .connection(_, true, nil) = $0 { return true }; return false }
    check(connection.count == 1, "connect completes once")
    passed(fresh ? "matching six digit SAS, bilateral confirmation, real TLS connection" : "trusted reconnect still requires receiver confirmation")
}

func runPeers() {
    let run = UUID().uuidString
    let serviceA = "com.unishare.acceptance.a." + run, serviceB = "com.unishare.acceptance.b." + run
    let nameA = "acceptance A " + run, nameB = "acceptance B " + run
    var a = newSdk(serviceA, nameA), b = newSdk(serviceB, nameB)
    var ae = Events(a), be = Events(b)
    defer { ae.close(); be.close(); a.close(); b.close() }
    ok { a.start(completion: $0) }; ok { b.start(completion: $0) }
    let peerB = discover(a, name: nameB), peerA = discover(b, name: nameA)
    check(peerA.id != peerB.id, "independent identities")
    Thread.sleep(forTimeInterval: 1)
    for (sdk, events, id) in [(a, ae, peerB.id), (b, be, peerA.id)] {
        check((try! sdk.discovery.getDevices()).filter { $0.id == id }.count == 1, "snapshot deduplicates DeviceId")
        check(events.count { if case .online(let peer) = $0 { return peer.id == id }; return false } == 1, "Online once per DeviceId")
    }
    passed("two system Bonjour peers: unique Online, snapshots, address/port/version metadata")
    pair(a, ae, b, be, to: peerB.id, fresh: true)
    let trustA = try! a.connections.getTrustedDevices(), trustB = try! b.connections.getTrustedDevices()
    check(trustA.count == 1 && trustA[0].id == peerB.id && trustA[0].publicKeyFingerprint.count == 32, "A trusted identity")
    check(trustB.count == 1 && trustB[0].id == peerA.id && trustB[0].publicKeyFingerprint.count == 32, "B trusted identity")

    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("acceptance-" + run)
    try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    var sends: [Operation] = [], received: [UUID] = [], sent: [UUID] = []
    // All cases use explicit empty host-owned staging files, never peer filenames.
    func transfer(_ bytes: Data, type: ContentType, mime: String, name: String, reverse: Bool = false) {
        let sender = reverse ? b : a, receiver = reverse ? a : b
        let receiverEvents = reverse ? ae : be, senderEvents = reverse ? be : ae
        let target = reverse ? peerA.id : peerB.id
        let start = receiverEvents.all.count
        let source = directory.appendingPathComponent(UUID().uuidString)
        try! bytes.write(to: source)
        let send = Operation { callback in
            switch type {
            case .text: return sender.transfers.sendText(String(data: bytes, encoding: .utf8)!, to: target, completion: callback)
            case .image: return sender.transfers.sendImage(source, to: target, mimeType: mime, byteCount: UInt64(bytes.count), displayName: name, completion: callback)
            case .file: return sender.transfers.sendFile(source, to: target, displayName: name, byteCount: UInt64(bytes.count), mimeType: mime, completion: callback)
            @unknown default: preconditionFailure("unsupported future content type")
            }
        }
        sends.append(send)
        let offer = Offer(receiverEvents, after: start)
        check(offer.type == type && offer.size == UInt64(bytes.count), "offer type/size")
        if type != .text { check(offer.name == name && offer.mime == mime, "offer metadata") }
        let staging = directory.appendingPathComponent(UUID().uuidString)
        try! Data().write(to: staging)
        Thread.sleep(forTimeInterval: 0.1)
        check(send.count == 0 && (try! Data(contentsOf: staging)).isEmpty, "no data before incoming offer approval")
        ok { receiver.transfers.accept(offer.request, stagingURL: staging, completion: $0) }
        let result = send.result()
        check(result.isSuccess && result.transferId != nil, "send success")
        check(receiverEvents.terminal(offer.id) == nil && senderEvents.terminal(result.transferId!) == nil, "both transfer terminal success")
        let actual = try! Data(contentsOf: staging)
        check(actual == bytes && SHA256.hash(data: actual) == SHA256.hash(data: bytes), "received bytes/SHA256")
        check(receiverEvents.terminalCount(offer.id) == 1 && senderEvents.terminalCount(result.transferId!) == 1 && send.count == 1, "one terminal event/completion")
        check((try! Data(contentsOf: source)) == bytes, "source preserved")
        if !reverse { received.append(offer.id); sent.append(result.transferId!) }
        passed("\(reverse ? "B-to-A" : "A-to-B") \(name): consent, bytes/SHA256, one terminal result (\(bytes.count) bytes)")
    }
    transfer(Data("你好 👋\0line\n é".utf8), type: .text, mime: "text/plain", name: "UTF-8/NUL text")
    transfer(fixtureImage().pngData()!, type: .image, mime: "image/png", name: "fixture.png")
    transfer(fixtureImage().jpegData(compressionQuality: 0.9)!, type: .image, mime: "image/jpeg", name: "fixture.jpeg")
    transfer(Data(), type: .file, mime: "application/octet-stream", name: "empty.bin")
    transfer(Data((0..<700_001).map { UInt8($0 % 251) }), type: .file, mime: "application/octet-stream", name: "chunks.bin")
    transfer(Data("回传\0reply".utf8), type: .text, mime: "text/plain", name: "reverse text", reverse: true)

    let rejectStart = be.all.count
    let rejected = Operation { a.transfers.sendText("decline", to: peerB.id, completion: $0) }
    sends.append(rejected)
    let rejectedOffer = Offer(be, after: rejectStart)
    ok { b.transfers.reject(rejectedOffer.request, completion: $0) }
    check(rejected.result().error?.category == .transferRejected, "sender rejection category")
    check(be.terminal(rejectedOffer.id)?.category == .transferRejected && be.terminalCount(rejectedOffer.id) == 1, "receiver rejection terminal once")
    passed("incoming offer rejected explicitly; sender/receiver TransferRejected once")

    // A large regular file gives cancellation a real receiving stream to clean up.
    let large = directory.appendingPathComponent("large.bin")
    try! Data(repeating: 0xa7, count: 32 * 1024 * 1024).write(to: large)
    let cancelStart = be.all.count
    let cancelled = Operation { a.transfers.sendFile(large, to: peerB.id, displayName: "cancel.bin", byteCount: 32 * 1024 * 1024, completion: $0) }
    sends.append(cancelled)
    let cancelledOffer = Offer(be, after: cancelStart)
    let partial = directory.appendingPathComponent("partial")
    try! Data().write(to: partial)
    let cancelLock = NSLock()
    var cancelOperation: Operation?
    let cancelObservation = b.transfers.observe { event in
        if case .progress(let id, let bytes, _) = event, id == cancelledOffer.id, bytes > 0 {
            cancelLock.lock(); defer { cancelLock.unlock() }
            if cancelOperation == nil { cancelOperation = Operation { b.transfers.cancel(id, completion: $0) } }
        }
    }
    ok { b.transfers.accept(cancelledOffer.request, stagingURL: partial, completion: $0) }
    check(be.terminal(cancelledOffer.id)?.category == .cancelled, "receiver cancellation category")
    cancelLock.lock(); let cancellation = cancelOperation; cancelLock.unlock()
    check(cancellation != nil && cancellation!.result().isSuccess, "cancel accepted after positive progress")
    check(!cancelled.result().isSuccess, "sender interrupted")
    eventually("partial staging removed") { !FileManager.default.fileExists(atPath: partial.path) }
    cancelObservation.close()
    check(be.terminalCount(cancelledOffer.id) == 1 && cancelled.count == 1, "cancel terminal once")
    passed("cancel after received bytes: sender interrupted, receiver Cancelled once, partial staging deleted")

    let offlineStart = ae.all.count
    ok { b.stop(completion: $0) }
    _ = ae.next(after: offlineStart) { if case .offline(let peer) = $0 { return peer.id == peerB.id }; return false }
    check(!(try! a.discovery.getDevices()).contains { $0.id == peerB.id }, "Offline removes snapshot")
    ok { b.start(completion: $0) }
    check(discover(a, name: nameB).id == peerB.id, "Stop/restart preserves identity")
    passed("Bonjour Offline after Stop, restart rediscovers stable DeviceId")

    ok { a.stop(completion: $0) }; ok { b.stop(completion: $0) }
    Thread.sleep(forTimeInterval: 0.2)
    check(sends.allSatisfy { $0.count == 1 }, "late callbacks do not duplicate send completions")
    check(received.allSatisfy { be.terminalCount($0) == 1 } && sent.allSatisfy { ae.terminalCount($0) == 1 }, "late terminal events not duplicated")
    ae.verifyTerminals(8); be.verifyTerminals(8)
    ae.close(); be.close(); a.close(); b.close()
    a = newSdk(serviceA, nameA); b = newSdk(serviceB, nameB)
    ae = Events(a); be = Events(b)
    check((try! a.connections.getTrustedDevices()) == trustA && (try! b.connections.getTrustedDevices()) == trustB, "Keychain trust survives SDK recreation")
    ok { a.start(completion: $0) }; ok { b.start(completion: $0) }
    check(discover(a, name: nameB).id == peerB.id && discover(b, name: nameA).id == peerA.id, "Keychain identities survive SDK recreation")
    passed("Keychain identities/fingerprints/trust survive SDK recreation")
    pair(a, ae, b, be, to: peerB.id, fresh: false)
    transfer(Data("after recreation".utf8), type: .text, mime: "text/plain", name: "trusted text")

    let interruptionStart = be.all.count
    let interrupted = Operation { a.transfers.sendText("background pending offer", to: peerB.id, completion: $0) }
    let pending = Offer(be, after: interruptionStart)
    DispatchQueue.main.sync { NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil) }
    eventually("background stops both SDKs") { a.state == .stopped && b.state == .stopped }
    check(!interrupted.result().isSuccess && be.terminal(pending.id) != nil, "background interrupts pending transfer")
    check(Operation { a.start(completion: $0) }.result().error?.category == .networkLost, "background Start denied")
    DispatchQueue.main.sync { NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil) }
    ok { a.start(completion: $0) }; ok { b.start(completion: $0) }
    _ = discover(a, name: nameB)
    ok { a.stop(completion: $0) }; ok { b.stop(completion: $0) }
    check(interrupted.count == 1 && be.terminalCount(pending.id) == 1, "interruption terminal once")
    ae.verifyTerminals(2); be.verifyTerminals(2)
    // Persist only public expected values for a later process; never identity key material.
    UserDefaults.standard.set(["serviceA": serviceA, "serviceB": serviceB, "nameA": nameA, "nameB": nameB,
                               "idA": peerA.id.uuidString, "idB": peerB.id.uuidString,
                               "fingerprintA": trustB[0].publicKeyFingerprint,
                               "fingerprintB": trustA[0].publicKeyFingerprint], forKey: "acceptanceTrust")
    passed("synthetic UIKit background interrupts pending work; foreground needs explicit Start")
}

func runTrustRead() {
    guard let values = UserDefaults.standard.dictionary(forKey: "acceptanceTrust"),
          let serviceA = values["serviceA"] as? String, let serviceB = values["serviceB"] as? String,
          let nameA = values["nameA"] as? String, let nameB = values["nameB"] as? String,
          let idA = values["idA"] as? String, let idB = values["idB"] as? String,
          let fingerprintA = values["fingerprintA"] as? Data, let fingerprintB = values["fingerprintB"] as? Data else {
        preconditionFailure("run network acceptance before trust-read")
    }
    let a = newSdk(serviceA, nameA), b = newSdk(serviceB, nameB)
    defer { a.close(); b.close() }
    let trustA = try! a.connections.getTrustedDevices(), trustB = try! b.connections.getTrustedDevices()
    check(trustA.count == 1 && trustA[0].id.uuidString == idB && trustA[0].publicKeyFingerprint == fingerprintB, "A trust after process restart")
    check(trustB.count == 1 && trustB[0].id.uuidString == idA && trustB[0].publicKeyFingerprint == fingerprintA, "B trust after process restart")
    ok { a.start(completion: $0) }; ok { b.start(completion: $0) }
    check(discover(a, name: nameB).id.uuidString == idB && discover(b, name: nameA).id.uuidString == idA, "identity after process restart")
    ok { a.stop(completion: $0) }; ok { b.stop(completion: $0) }
    passed("separate-process Keychain identity and trust fingerprints preserved")
}
