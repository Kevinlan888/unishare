import UIKit
import CryptoKit
import UniShareSDK

private struct HostFailure: Error { let message: String }
private func require(_ value: Bool, _ message: String) throws { if !value { throw HostFailure(message: message) } }
private final class Pending {
    private let lock = NSLock()
    private var values: [OperationResult] = []
    let semaphore = DispatchSemaphore(value: 0)
    func receive(_ value: OperationResult) { lock.lock(); values.append(value); lock.unlock(); semaphore.signal() }
    var count: Int { lock.lock(); defer { lock.unlock() }; return values.count }
    func wait(success: Bool = true) throws -> OperationResult {
        try require(semaphore.wait(timeout: .now()+35) == .success, "operation timeout")
        lock.lock(); let result = values[0]; let count = values.count; lock.unlock()
        try require(count == 1, "duplicate callback")
        if success { try require(result.isSuccess, "operation failed: \(String(describing: result.error))") }
        return result
    }
}
private final class Host {
    let role: String, run: String, expected: String
    private let logLock = NSLock(), eventLock = NSLock(), operationLock = NSLock()
    private var events: [ShareEvent] = [], operations: [Pending] = []
    init(_ args: [String]) throws {
        try require(args.count == 4 && args[3] == "--test-consent", "role run peer --test-consent")
        role=args[0]; run=args[1]; expected=args[2]
        try require(role == "sender" || role == "receiver", "role")
        try require(run.range(of: "^[A-Za-z0-9-]+$", options: .regularExpression) != nil, "run")
    }
    func log(_ event: String, _ fields: [String: Any] = [:]) {
        logLock.lock(); defer { logLock.unlock() }
        var row=fields; row["event"]=event; row["role"]=role; row["run"]=run
        if let data=try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]), let line=String(data:data,encoding:.utf8) { print(line); fflush(stdout) }
    }
    @discardableResult private func submit(_ name: String, request: UInt64 = 0, transfer: String = "", _ body: (@escaping (OperationResult)->Void)->UInt64) -> Pending {
        let pending=Pending(); operationLock.lock(); operations.append(pending); operationLock.unlock()
        _ = body { result in
            self.log("operation", ["operation":name, "operationId":result.operationId, "requestId":request, "id":transfer, "error": result.error.map { String($0.category.rawValue) } ?? ""])
            pending.receive(result)
        }; return pending
    }
    @discardableResult private func ok(_ name: String, request: UInt64 = 0, transfer: String = "", _ body: (@escaping (OperationResult)->Void)->UInt64) throws -> OperationResult { try submit(name,request:request,transfer:transfer,body).wait() }
    private func snapshot() -> [ShareEvent] { eventLock.lock(); defer { eventLock.unlock() }; return events }
    private func next(_ predicate: (ShareEvent)->Bool, after: Int = 0, timeout: TimeInterval = 60) throws -> ShareEvent {
        let end=Date().addingTimeInterval(timeout)
        while Date() < end { if let event=snapshot().dropFirst(after).first(where:predicate) { return event }; Thread.sleep(forTimeInterval:0.02) }
        throw HostFailure(message:"event timeout")
    }
    private func bytes(_ name: String) throws -> Data { try Data(contentsOf: Bundle.main.bundleURL.appendingPathComponent("fixtures/"+name)) }
    private func hash(_ data: Data) -> String { SHA256.hash(data:data).map { String(format:"%02x", $0) }.joined() }
    func execute() throws {
        log("begin", ["platform":"ios-simulator", "sdkPlatform":"ios", "expectedPeer":expected, "name":"interop-\(run)-\(role)", "os":ProcessInfo.processInfo.operatingSystemVersionString])
        let sdk=ShareSdk(keychainService:"com.unishare.interop.\(run).\(role)")
        defer { sdk.close() }
        var config=SdkConfig(deviceName:"interop-\(run)-\(role)"); config.requestTimeoutSeconds=30
        try ok("initialize") { sdk.initialize(config,completion:$0) }
        let observer: (ShareEvent)->Void = { event in
            switch event {
            case .pairingRequest(let peer,let request,let sas):
                let match=peer.name == self.expected
                self.log("pairing", ["id":peer.id.uuidString.lowercased(),"sas":sas,"decision":match ? "confirm":"reject"])
                self.submit("confirm") { sdk.connections.confirmPairing(request,codeMatches:match,completion:$0) }
            case .online(let peer): self.log("discovery",["id":peer.id.uuidString.lowercased(),"name":peer.name])
            case .discoveryError(let error): self.log("discovery_error",["error":String(describing:error)])
            case .incomingOffer(_,let request,let id,let kind,_,_,let size): self.log("offer",["id":id.uuidString.lowercased(),"requestId":request,"kind":kind.rawValue,"size":size])
            case .progress(let id,let count,let size): self.log("progress",["id":id.uuidString.lowercased(),"bytes":count,"size":size])
            case .transferResult(let id,let error): self.log("terminal",["id":id.uuidString.lowercased(),"error":error.map { String($0.category.rawValue) } ?? ""])
            default: break
            }
            // Publish only after evidence is written: the worker may act immediately.
            self.eventLock.lock(); self.events.append(event); self.eventLock.unlock()
        }
        let observations=[sdk.discovery.observe(observer),sdk.connections.observe(observer),sdk.transfers.observe(observer)]
        defer { observations.forEach { $0.close() } }
        try ok("start") { sdk.start(completion:$0) }
        let discovered=try next { if case .online(let p)=$0 { return p.name == self.expected }; return false }
        guard case .online(let peer)=discovered else { throw HostFailure(message:"discovery") }
        log("discovered",["id":peer.id.uuidString.lowercased(),"name":peer.name,"platform":peer.platform.rawValue,"protocol":"\(peer.protocolMajor).\(peer.protocolMinor)","addresses":peer.addresses.joined(separator:","),"port":peer.port])
        let names=["text.txt","image.png","image.jpg","empty.bin","large.bin","clipboard.txt"]
        if role == "sender" {
            try ok("connect") { sdk.connections.connect(peer.id,completion:$0) }
            for (index,name) in names.enumerated() {
                let data=try bytes(name), url=Bundle.main.bundleURL.appendingPathComponent("fixtures/"+name)
                if index == 5 {
                    let text=String(decoding:data,as:UTF8.self)
                    try ok("clipboard_write") { sdk.clipboard.writeContent(.text(text),completion:$0) }
                    let value=try ok("clipboard_read") { sdk.clipboard.readShareableContent(completion:$0) }
                    try require(value.content == .text(text), "clipboard bytes")
                    log("clipboard",["decision":"explicit_read_send"])
                }
                let result: OperationResult
                if index == 0 || index == 5 { result=try ok("send") { sdk.transfers.sendText(String(decoding:data,as:UTF8.self),to:peer.id,completion:$0) } }
                else if index < 3 { result=try ok("send") { sdk.transfers.sendImage(url,to:peer.id,mimeType:index == 1 ? "image/png":"image/jpeg",byteCount:UInt64(data.count),displayName:name,completion:$0) } }
                else { result=try ok("send") { sdk.transfers.sendFile(url,to:peer.id,displayName:name,byteCount:UInt64(data.count),completion:$0) } }
                try require(result.transferId != nil,"send transfer ID")
                log("sent",["id":result.transferId!.uuidString.lowercased(),"name":name,"size":data.count,"sha256":hash(data)])
            }
            let rejected=try submit("send_reject") { sdk.transfers.sendText("reject",to:peer.id,completion:$0) }.wait(success:false)
            try require(rejected.error?.category == .transferRejected,"reject outcome")
        } else {
            var cursor=0
            for index in 0...6 {
                let event=try next({ if case .incomingOffer=$0 { return true }; return false },after:cursor)
                guard case .incomingOffer(let remote,let request,let id,let type,_,_,let count)=event else { throw HostFailure(message:"offer") }
                try require(remote.id == peer.id,"unexpected peer")
                let sink=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try require(!FileManager.default.fileExists(atPath:sink.path),"fresh staging")
                if index == 6 { log("consent",["id":id.uuidString.lowercased(),"requestId":request,"decision":"reject"]); try ok("reject",request:request,transfer:id.uuidString.lowercased()) { sdk.transfers.reject(request,completion:$0) } }
                else {
                    let expectedBytes=try bytes(names[index])
                    try require(count == expectedBytes.count,"offer size")
                    try require(type == (index == 0 || index == 5 ? .text : index < 3 ? .image : .file),"offer type")
                    try require(FileManager.default.createFile(atPath:sink.path,contents:Data()),"staging create")
                    log("consent",["id":id.uuidString.lowercased(),"requestId":request,"decision":"accept"])
                    try ok("accept",request:request,transfer:id.uuidString.lowercased()) { sdk.transfers.accept(request,stagingURL:sink,completion:$0) }
                }
                let terminal=try next { if case .transferResult(let t,_)=$0 { return t == id }; return false }
                guard case .transferResult(_,let error)=terminal else { throw HostFailure(message:"terminal") }
                if index == 6 { try require(error?.category == .transferRejected && !FileManager.default.fileExists(atPath:sink.path),"rejection cleanup") }
                else {
                    try require(error == nil,"receive outcome")
                    let data=try Data(contentsOf:sink); try require(data == bytes(names[index]),"received bytes")
                    log("received",["id":id.uuidString.lowercased(),"name":names[index],"size":data.count,"sha256":hash(data)])
                    try FileManager.default.removeItem(at:sink)
                }
                // Move past this offer, not the snapshot end: the next offer may already be queued.
                cursor=(snapshot().firstIndex(of:event) ?? cursor)+1
            }
        }
        try ok("stop") { sdk.stop(completion:$0) }
        let terminals=snapshot().compactMap { event -> UUID? in if case .transferResult(let id,_) = event { return id }; return nil }
        try require(terminals.count == 7 && Set(terminals).count == 7,"exactly one terminal")
        operationLock.lock(); let all=operations; operationLock.unlock()
        try require(all.allSatisfy { $0.count == 1 },"exactly one operation completion")
        log("complete",["terminals":7])
    }
}

@main final class InteropApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?; private var started=false
    func application(_ application:UIApplication,didFinishLaunchingWithOptions options:[UIApplication.LaunchOptionsKey:Any]?) -> Bool {
        window=UIWindow(frame:UIScreen.main.bounds); window?.rootViewController=UIViewController(); window?.makeKeyAndVisible(); return true
    }
    func applicationDidBecomeActive(_ application:UIApplication) {
        guard !started else { return }; started=true
        DispatchQueue.global().async {
            do { let host=try Host(Array(CommandLine.arguments.dropFirst())); do { try host.execute(); exit(0) } catch { host.log("failure",["message":String(describing:error)]); exit(1) } }
            catch { print("invalid interop arguments: \(error)"); fflush(stdout); exit(2) }
        }
    }
}
