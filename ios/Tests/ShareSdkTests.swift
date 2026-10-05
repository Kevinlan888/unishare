import Foundation
internal final class FakeBackend: Backend {
    var event: (([String: Any]) -> Void)?
    var count = 0
    var lastArguments: [String: Any] = [:]
    var delay = false
    var saved: (([String: Any]) -> Void)?
    var late: (([String: Any]) -> Void)?
    let submitted = DispatchSemaphore(value:0)
    var closed = false
    func create(_ config: SdkConfig, completion: @escaping ([String: Any]) -> Void) { completion([:]); completion([:]) }
    func submit(_ command: Int, arguments: [String: Any], completion: @escaping ([String: Any]) -> Void) {
        count += 1
        lastArguments = arguments
        if command == 2 { let callback = saved; saved = nil; callback?(["error":8]); completion([:]); return }
        if delay { saved = completion; late = completion; submitted.signal() } else { completion(["error": 13, "platformError": -50]); completion([:]) }
    }
    func snapshot(_ trusted: Bool) throws -> [[String: Any]] { [] }
    func state() throws -> UInt32 { 1 }
    func close() { closed = true; saved?([:]) }
}
internal func runContractTests() {
    let backend = FakeBackend()
    let queue = DispatchQueue(label: "consumer.callbacks")
    let sdk = ShareSdk(backend: backend, callbackQueue: queue)
    let done = DispatchSemaphore(value: 0)
    var results = [OperationResult]()
    let caller = DispatchSpecificKey<Bool>()
    let submitting = DispatchQueue(label: "consumer.submit")
    submitting.setSpecific(key: caller, value: true)
    _ = submitting.sync {
        sdk.initialize(SdkConfig(deviceName: "汉字\0é")) { result in
            precondition(DispatchQueue.getSpecific(key: caller) == nil, "callback inline")
            results.append(result); done.signal()
        }
    }
    precondition(done.wait(timeout: .now() + 5) == .success, "initialize timeout")
    queue.sync {}
    precondition(results.count == 1 && results[0].isSuccess, "completion must be exactly once")
    sdk.start { result in
        precondition(result.error == ShareError(.invalidInput, platformError: -50), "error mapping")
        sdk.close(); done.signal()
    }
    precondition(done.wait(timeout: .now() + 5) == .success, "close reentrancy deadlock")
    precondition(sdk.state == .closed)
    let blocked = DispatchSemaphore(value: 0)
    queue.async { blocked.wait() }
    let late = FakeBackend()
    let other = ShareSdk(backend: late, callbackQueue: queue)
    var called = false
    other.initialize(SdkConfig(deviceName: "late")) { _ in called = true }
    other.close(); blocked.signal(); queue.sync {}
    precondition(!called, "callback after close")
    print("PASS Swift exact-once, inline backend, configured delayed queue, error mapping, close reentrancy")
}


internal func runMappingTests() {
    let id = UUID(), transfer = UUID()
    let device: [String: Any] = ["id":id,"name":"汉字\0é","platform":2,"major":1,"minor":7,"port":4567,"paired":true,"lastSeen":-12,"addresses":["fe80::1%en0","127.0.0.1"]]
    let mapped = Packets.device(device)
    precondition(mapped.id == id && mapped.name == "汉字\0é" && mapped.addresses[0] == "fe80::1%en0")
    precondition(mapped.protocolMajor == 1 && mapped.protocolMinor == 7 && mapped.port == 4567 && mapped.paired && mapped.lastSeenUnixMillis == -12)
    for kind in 1...12 {
        let packet: [String: Any] = ["kind":kind,"device":device,"error":6,"platformError":-34018,"request":99,"code":"000007","transfer":transfer,"contentType":2,"name":"图片.png","mime":"image/png","size":1234,"transferred":12]
        guard let event = Packets.event(packet) else { preconditionFailure("missing event") }
        if kind == 7 { precondition(event == .pairingRequest(device:mapped,requestId:99,verificationCode:"000007")) }
        if kind == 10 { precondition(event == .incomingOffer(device:mapped,requestId:99,transferId:transfer,type:.image,displayName:"图片.png",mimeType:"image/png",byteCount:1234)) }
        if kind == 12 { precondition(event == .transferResult(transferId:transfer,error:ShareError(.identityChanged,platformError:-34018))) }
    }
    for category in 1...14 { precondition((Packets.error(["error":category])?.category.rawValue ?? 0) == UInt32(category)) }
    let image = Data([0,1,255])
    precondition(Packets.result(3,["contentType":2,"mime":"image/png","bytes":image]).content == .image(mimeType:"image/png",bytes:image))
    print("PASS Swift all event/error categories, SAS zeros, scoped IPv6, version, UTF-8/NUL, image values")
}

internal func runCancellationTests() {
    let backend = FakeBackend()
    let queue = DispatchQueue(label:"consumer.cancel")
    let key = DispatchSpecificKey<Bool>(); queue.setSpecific(key:key,value:true)
    let sdk = ShareSdk(backend:backend,callbackQueue:queue)
    let done = DispatchSemaphore(value:0)
    sdk.initialize(SdkConfig(deviceName:"cancel")) { _ in done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    backend.delay = true
    var count = 0
    sdk.connections.connect(UUID()) { result in
        precondition(DispatchQueue.getSpecific(key:key) == true, "configured queue")
        precondition(result.error?.category == .cancelled)
        count += 1; done.signal()
    }
    precondition(backend.submitted.wait(timeout:.now()+5) == .success)
    sdk.stop { result in precondition(result.isSuccess); done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    precondition(done.wait(timeout:.now()+5) == .success)
    backend.late?([:])
    let eventDone = DispatchSemaphore(value:0)
    let first = sdk.observe { _ in sdk.close(); eventDone.signal() }
    var secondCalled = false
    let second = sdk.observe { _ in secondCalled = true }
    backend.event?(["kind":5,"state":3])
    precondition(eventDone.wait(timeout:.now()+5) == .success)
    queue.sync {}
    precondition(count == 1 && !secondCalled)
    withExtendedLifetime([first,second]) {}
    print("PASS Swift Stop cancellation, late duplicate suppression, configured queue, reentrant observer close")
}
internal func runServiceMetadataTests() {
    let backend = FakeBackend(), done = DispatchSemaphore(value:0)
    let sdk = ShareSdk(backend:backend,callbackQueue:nil)
    sdk.initialize(SdkConfig(deviceName:"metadata")) { _ in done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    let source = URL(fileURLWithPath:"/tmp/source")
    sdk.transfers.sendImage(source,to:UUID(),mimeType:"image/png",byteCount:10) { _ in done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    precondition(backend.lastArguments["name"] as? String == "image", "default image display name")
    sdk.transfers.sendFile(source,to:UUID(),displayName:"document",byteCount:10) { _ in done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    precondition(backend.lastArguments["mime"] as? String == "application/octet-stream", "default file MIME")
    sdk.transfers.sendImage(source,to:UUID(),mimeType:"image/jpeg",byteCount:10,displayName:"photo.jpg") { _ in done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    precondition(backend.lastArguments["name"] as? String == "photo.jpg")
    precondition(backend.lastArguments["mime"] as? String == "image/jpeg")
    sdk.transfers.sendFile(source,to:UUID(),displayName:"report.pdf",byteCount:10,mimeType:"application/pdf") { _ in done.signal() }
    precondition(done.wait(timeout:.now()+5) == .success)
    precondition(backend.lastArguments["mime"] as? String == "application/pdf")
    sdk.close()
    print("PASS Swift source metadata defaults and explicit overrides match native services")
}