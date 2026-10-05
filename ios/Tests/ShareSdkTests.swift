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
    var createFailure: [String: Any]?
    var holdCreate = false
    var createCount = 0
    var heldCreate: (([String: Any]) -> Void)?
    let createSubmitted = DispatchSemaphore(value:0)
    func create(_ config: SdkConfig, completion: @escaping ([String: Any]) -> Void) {
        createCount += 1
        if holdCreate { heldCreate = completion; createSubmitted.signal(); return }
        if let failure = createFailure { createFailure = nil; completion(failure); return }
        completion([:]); completion([:])
    }
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

internal func runInitializeRetryTests() {
    let synchronous = FakeBackend()
    synchronous.createFailure = ["error":11]
    let synchronousSdk = ShareSdk(backend:synchronous, callbackQueue:nil)
    let done = DispatchSemaphore(value:0)
    synchronousSdk.initialize(SdkConfig(deviceName:"sync failure")) { result in
        precondition(result.error?.category == .storageFailed); done.signal()
    }
    precondition(done.wait(timeout:.now()+5) == .success)
    synchronousSdk.initialize(SdkConfig(deviceName:"sync retry")) { result in
        precondition(result.isSuccess); done.signal()
    }
    precondition(done.wait(timeout:.now()+5) == .success, "same Swift wrapper retries after synchronous failure")
    synchronousSdk.initialize(SdkConfig(deviceName:"after success")) { result in
        precondition(result.error?.category == .invalidInput); done.signal()
    }
    precondition(done.wait(timeout:.now()+5) == .success && synchronous.createCount == 2,
                 "Swift wrapper rejects initialization after success")
    synchronousSdk.close()

    let asynchronous = FakeBackend()
    asynchronous.holdCreate = true
    let asynchronousSdk = ShareSdk(backend:asynchronous, callbackQueue:nil)
    asynchronousSdk.initialize(SdkConfig(deviceName:"async failure")) { result in
        precondition(result.error?.category == .storageFailed); done.signal()
    }
    precondition(asynchronous.createSubmitted.wait(timeout:.now()+5) == .success, "asynchronous create was not submitted")
    asynchronousSdk.initialize(SdkConfig(deviceName:"concurrent")) { result in
        precondition(result.error?.category == .invalidInput); done.signal()
    }
    precondition(done.wait(timeout:.now()+5) == .success)
    asynchronousSdk.initialize(SdkConfig(deviceName:"still concurrent")) { result in
        precondition(result.error?.category == .invalidInput); done.signal()
    }
    precondition(done.wait(timeout:.now()+5) == .success && asynchronous.createCount == 1,
                 "rejected callback must not clear active initialize state")
    asynchronous.heldCreate?(["error":11])
    precondition(done.wait(timeout:.now()+5) == .success)
    asynchronous.holdCreate = false
    asynchronousSdk.initialize(SdkConfig(deviceName:"async retry")) { result in
        precondition(result.isSuccess); done.signal()
    }
    precondition(done.wait(timeout:.now()+5) == .success, "same Swift wrapper retries after asynchronous failure")
    asynchronousSdk.close()
    print("PASS Swift initialization retry after synchronous/asynchronous failure and concurrent ordering")
}


internal func runMappingTests() {
    let id = UUID(), transfer = UUID()
    let device: [String: Any] = ["id":id,"name":"汉字\0é","platform":2,"major":1,"minor":7,"port":4567,"paired":true,"lastSeen":-12,"addresses":["fe80::1%en0","127.0.0.1"]]
    let idOnlyDevice: [String: Any] = ["id":id,"name":"","platform":0,"major":0,"minor":0,"port":0,"paired":false,"lastSeen":0,"addresses":[]]
    let mapped = Packets.device(device)
    precondition(mapped.id == id && mapped.name == "汉字\0é" && mapped.addresses[0] == "fe80::1%en0")
    precondition(mapped.protocolMajor == 1 && mapped.protocolMinor == 7 && mapped.port == 4567 && mapped.paired && mapped.lastSeenUnixMillis == -12)
    for kind in 1...12 {
        let eventDevice = [6,8,9,10].contains(kind) ? idOnlyDevice : device
        let packet: [String: Any] = ["kind":kind,"device":eventDevice,"error":kind == 9 ? 0 : 6,"platformError":-34018,"request":99,"code":"000007","transfer":transfer,"contentType":2,"name":"图片.png","mime":"image/png","size":1234,"transferred":12]
        guard let event = Packets.event(packet) else { preconditionFailure("missing event") }
        if kind == 7 { precondition(event == .pairingRequest(device:mapped,requestId:99,verificationCode:"000007")) }
        if kind == 6 { precondition(event == .connection(deviceId:id,connected:true,error:ShareError(.identityChanged,platformError:-34018))) }
        if kind == 8 { precondition(event == .pairingResult(deviceId:id,requestId:99,error:ShareError(.identityChanged,platformError:-34018))) }
        if kind == 9 { precondition(event == .identityChanged(deviceId:id)) }
        if kind == 10 { precondition(event == .incomingOffer(deviceId:id,requestId:99,transferId:transfer,type:.image,displayName:"图片.png",mimeType:"image/png",byteCount:1234)) }
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
internal func runTextCapacityTests() {
    let backend = FakeBackend()
    let owner = ShareSdk(backend: backend, callbackQueue: nil)
    let done = DispatchSemaphore(value: 0)
    var textConfig = SdkConfig(deviceName: "text"); textConfig.maxTextBytes = 16777216
    owner.initialize(textConfig) { _ in done.signal() }
    precondition(done.wait(timeout: .now()+5) == .success)
    let before = backend.count
    owner.transfers.sendText(String(repeating:"x",count:8388609),to:UUID()) { result in
        precondition(result.error?.category == .resourceLimit); done.signal()
    }
    precondition(done.wait(timeout:.now()+10) == .success && backend.count == before)
    backend.delay = true
    for ending in 0..<3 {
        owner.transfers.sendText(String(repeating: "x", count: 8*1024*1024), to: UUID()) { _ in done.signal() }
        precondition(backend.submitted.wait(timeout: .now()+10) == .success)
        precondition(backend.lastArguments["text"] == nil, "large text must not cross native bridge as a direct value")
        guard let url = backend.lastArguments["url"] as? URL else { preconditionFailure("streamed text URL required") }
        precondition((try! Data(contentsOf: url)).count == 8388608)
        if ending == 0 { backend.saved?(["error": 7]); precondition(done.wait(timeout: .now()+5) == .success) }
        else if ending == 1 { owner.stop { _ in done.signal() }; precondition(done.wait(timeout: .now()+5) == .success); precondition(done.wait(timeout: .now()+5) == .success) }
        else { owner.close() }
        precondition(!FileManager.default.fileExists(atPath:url.path), "staged text must be removed at terminal/Stop/close")
    }
    print("PASS Swift 8MiB streamed text and terminal/Stop/close staging cleanup")
}
