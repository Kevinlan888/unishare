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
    private var cancelID: UUID?, cancellationStarted=false
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
    private func pixels(_ data: Data) throws -> String {
        guard let image=UIImage(data:data)?.cgImage else{throw HostFailure(message:"clipboard decode")}
        try require(image.width==2 && image.height==2,"clipboard dimensions")
        var rgba=Data(count:16)
        try rgba.withUnsafeMutableBytes{buffer in
            guard let context=CGContext(data:buffer.baseAddress,width:2,height:2,bitsPerComponent:8,bytesPerRow:8,space:CGColorSpace(name:CGColorSpace.sRGB)!,bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue) else{throw HostFailure(message:"RGBA context")}
            context.draw(image,in:CGRect(x:0,y:0,width:2,height:2))
        };return hash(rgba)
    }
    func execute() throws {
        #if targetEnvironment(simulator)
        let hostPlatform="ios-simulator"
        #else
        let hostPlatform="ios"
        #endif
        log("begin", ["platform":hostPlatform, "sdkPlatform":"ios", "scenario":"extended", "expectedPeer":expected, "name":"interop-\(run)-\(role)", "os":ProcessInfo.processInfo.operatingSystemVersionString])
        let sdk=ShareSdk(keychainService:"com.unishare.interop.\(run).\(role)")
        defer { sdk.close() }
        var config=SdkConfig(deviceName:"interop-\(run)-\(role)"); config.requestTimeoutSeconds=30
        try ok("initialize") { sdk.initialize(config,completion:$0) }
        let observer: (ShareEvent)->Void = { event in
            switch event {
            case .pairingRequest(let peer,let request,let sas):
                DispatchQueue.global().async {
                    do {
                        let discovered=try self.next { if case .online(let p)=$0 { return p.name==self.expected };return false }
                        guard case .online(let expectedPeer)=discovered else {return}
                        let match=peer.id==expectedPeer.id
                        self.log("pairing", ["id":peer.id.uuidString.lowercased(),"sas":sas,"decision":match ? "confirm":"reject"])
                        self.submit("confirm") { sdk.connections.confirmPairing(request,codeMatches:match,completion:$0) }
                    }catch{self.log("failure",["message":"pairing discovery timeout"])}
                }
            case .online(let peer): self.log("discovery",["id":peer.id.uuidString.lowercased(),"name":peer.name,"port":peer.port,"paired":peer.paired])
            case .updated(let peer): self.log("updated",["id":peer.id.uuidString.lowercased(),"name":peer.name,"port":peer.port,"paired":peer.paired])
            case .offline(let peer): self.log("offline",["id":peer.id.uuidString.lowercased(),"name":peer.name])
            case .discoveryError(let error): self.log("discovery_error",["error":String(describing:error)])
            case .incomingOffer(_,let request,let id,let kind,_,_,let size): self.log("offer",["id":id.uuidString.lowercased(),"requestId":request,"kind":kind.rawValue,"size":size])
            case .progress(let id,let count,let size):
                self.log("progress",["id":id.uuidString.lowercased(),"bytes":count,"size":size])
                self.eventLock.lock();let cancel=id==self.cancelID && count>0 && count<size && !self.cancellationStarted;if cancel{self.cancellationStarted=true};self.eventLock.unlock()
                if cancel{self.log("cancel",["id":id.uuidString.lowercased(),"bytes":count,"size":size]);self.submit("cancel",transfer:id.uuidString.lowercased()){sdk.transfers.cancel(id,completion:$0)}}
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
        let names=["text.txt","image.png","image.jpg","empty.bin","large.bin","clipboard.txt","text-large.txt","clipboard.png","isolation.txt"]
        if role == "sender" {
            try ok("connect") { sdk.connections.connect(peer.id,completion:$0) }
            for (index,name) in names.prefix(8).enumerated() {
                var data=try bytes(name), url=Bundle.main.bundleURL.appendingPathComponent("fixtures/"+name)
                if index == 5 {
                    let text=String(decoding:data,as:UTF8.self)
                    try ok("clipboard_write") { sdk.clipboard.writeContent(.text(text),completion:$0) }
                    let value=try ok("clipboard_read") { sdk.clipboard.readShareableContent(completion:$0) }
                    try require(value.content == .text(text), "clipboard bytes")
                    log("clipboard",["decision":"explicit_read_send"])
                }
                if index==7 {
                    try ok("clipboard_write"){sdk.clipboard.writeContent(.image(mimeType:"image/png",bytes:data),completion:$0)}
                    let value=try ok("clipboard_read"){sdk.clipboard.readShareableContent(completion:$0)}
                    guard case .image(let mime,let actual)=value.content else{throw HostFailure(message:"clipboard image content")}
                    try require(mime=="image/png" && pixels(actual)==pixels(data),"clipboard image pixels")
                    data=actual;url=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);try data.write(to:url)
                    log("clipboard",["decision":"explicit_read_send"])
                }
                let result: OperationResult
                if index == 0 || index == 5 || index==6 { result=try ok("send") { sdk.transfers.sendText(String(decoding:data,as:UTF8.self),to:peer.id,completion:$0) } }
                else if index < 3 || index==7 { result=try ok("send") { sdk.transfers.sendImage(url,to:peer.id,mimeType:index == 2 ? "image/jpeg":"image/png",byteCount:UInt64(data.count),displayName:name,completion:$0) } }
                else { result=try ok("send") { sdk.transfers.sendFile(url,to:peer.id,displayName:name,byteCount:UInt64(data.count),completion:$0) } }
                try require(result.transferId != nil,"send transfer ID")
                log("sent",["id":result.transferId!.uuidString.lowercased(),"name":name,"size":data.count,"sha256":hash(data),"pixel_sha256":index==7 ? try pixels(data):""])
                if index==7{try FileManager.default.removeItem(at:url)}
            }
            let rejected=try submit("send_reject") { sdk.transfers.sendText("reject",to:peer.id,completion:$0) }.wait(success:false)
            try require(rejected.error?.category == .transferRejected,"reject outcome")
            let cancelled=try submit("send_cancel"){sdk.transfers.sendFile(Bundle.main.bundleURL.appendingPathComponent("fixtures/cancel.bin"),to:peer.id,displayName:"cancel.bin",byteCount:8388608,completion:$0)}.wait(success:false)
            try require(cancelled.error?.category == .cancelled || cancelled.error?.category == .networkLost,"cancel outcome")
        } else {
            var cursor=0
            for index in 0...9 {
                let event=try next({ if case .incomingOffer=$0 { return true }; return false },after:cursor)
                guard case .incomingOffer(let remote,let request,let id,let type,_,_,let count)=event else { throw HostFailure(message:"offer") }
                try require(remote.id == peer.id,"unexpected peer")
                let sink=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try require(!FileManager.default.fileExists(atPath:sink.path),"fresh staging")
                if index == 8 { log("consent",["id":id.uuidString.lowercased(),"requestId":request,"decision":"reject"]); try ok("reject",request:request,transfer:id.uuidString.lowercased()) { sdk.transfers.reject(request,completion:$0) } }
                else {
                    let expectedBytes=try bytes(index==9 ? "cancel.bin":names[index])
                    try require(index==7 ? count>0:count == expectedBytes.count,"offer size")
                    try require(type == (index == 0 || index == 5 || index==6 ? .text : index < 3 || index==7 ? .image : .file),"offer type")
                    if index==9{eventLock.lock();cancelID=id;eventLock.unlock()}
                    try require(FileManager.default.createFile(atPath:sink.path,contents:Data()),"staging create")
                    log("consent",["id":id.uuidString.lowercased(),"requestId":request,"decision":"accept"])
                    try ok("accept",request:request,transfer:id.uuidString.lowercased()) { sdk.transfers.accept(request,stagingURL:sink,completion:$0) }
                }
                let terminal=try next { if case .transferResult(let t,_)=$0 { return t == id }; return false }
                guard case .transferResult(_,let error)=terminal else { throw HostFailure(message:"terminal") }
                if index == 8 { try require(error?.category == .transferRejected && !FileManager.default.fileExists(atPath:sink.path),"rejection cleanup") }
                else if index==9{try require(error?.category == .cancelled && !FileManager.default.fileExists(atPath:sink.path),"cancel cleanup");log("cleanup",["id":id.uuidString.lowercased(),"deleted":true])}
                else {
                    try require(error == nil,"receive outcome")
                    let data=try Data(contentsOf:sink); try require(index==7 ? pixels(data)==pixels(bytes(names[index])):data == bytes(names[index]),"received bytes")
                    log("received",["id":id.uuidString.lowercased(),"name":names[index],"size":data.count,"sha256":hash(data),"pixel_sha256":index==7 ? try pixels(data):""])
                    try FileManager.default.removeItem(at:sink)
                }
                // Move past this offer, not the snapshot end: the next offer may already be queued.
                cursor=(snapshot().firstIndex(of:event) ?? cursor)+1
            }
        }
        let trust=try sdk.connections.getTrustedDevices();try require(trust.count==1 && trust[0].id==peer.id,"trust before restart")
        try ok("restart_stop"){sdk.stop(completion:$0)};try ok("restart_start"){sdk.start(completion:$0)}
        try require(sdk.connections.getTrustedDevices()==trust,"restart trust");log("restart",["trustStable":true])
        func recordFresh() throws { guard let fresh=try sdk.discovery.getDevices().first(where:{$0.id==peer.id && $0.port != peer.port}) else {throw HostFailure(message:"fresh discovery snapshot")};log("rediscovered",["id":fresh.id.uuidString.lowercased(),"port":fresh.port,"paired":fresh.paired]) }
        if role=="sender"{
            let end=Date().addingTimeInterval(60)
            while try !sdk.discovery.getDevices().contains(where:{$0.id==peer.id && $0.port != peer.port}){try require(Date()<end,"restart discovery");Thread.sleep(forTimeInterval:0.02)}
            try recordFresh()
            try ok("reconnect"){sdk.connections.connect(peer.id,completion:$0)}
            let data=try bytes("isolation.txt");let result=try ok("send"){sdk.transfers.sendText(String(decoding:data,as:UTF8.self),to:peer.id,completion:$0)}
            try require(result.transferId != nil,"isolation ID");log("sent",["id":result.transferId!.uuidString.lowercased(),"name":"isolation.txt","size":data.count,"sha256":hash(data)])
        }else{
            let rediscover=Date().addingTimeInterval(60)
            while try !sdk.discovery.getDevices().contains(where:{$0.id==peer.id && $0.port != peer.port}){try require(Date()<rediscover,"receiver restart discovery");Thread.sleep(forTimeInterval:0.02)}
            try recordFresh()
            let end=Date().addingTimeInterval(60)
            func allOffers()->[ShareEvent]{snapshot().filter{if case .incomingOffer=$0{return true};return false}}
            while allOffers().count<11{try require(Date()<end,"isolation offer timeout");Thread.sleep(forTimeInterval:0.02)}
            let isolation=allOffers()[10]
            guard case .incomingOffer(let remote,let request,let id,let kind,_,_,let count)=isolation else {throw HostFailure(message:"isolation offer")}
            try require(remote.id==peer.id && kind == .text && count==14,"isolation metadata")
            let sink=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);try require(FileManager.default.createFile(atPath:sink.path,contents:Data()),"isolation sink")
            log("consent",["id":id.uuidString.lowercased(),"requestId":request,"decision":"accept"]);try ok("accept",request:request,transfer:id.uuidString.lowercased()){sdk.transfers.accept(request,stagingURL:sink,completion:$0)}
            let result=try next{if case .transferResult(let t,_)=$0{return t==id};return false};guard case .transferResult(_,let error)=result else{throw HostFailure(message:"isolation terminal")};try require(error==nil,"isolation result")
            let data=try Data(contentsOf:sink);try require(data==bytes("isolation.txt"),"isolation bytes");log("received",["id":id.uuidString.lowercased(),"name":"isolation.txt","size":data.count,"sha256":hash(data)]);try FileManager.default.removeItem(at:sink)
            _=try next{if case .offline(let p)=$0{return p.id==peer.id};return false}
        }
        try ok("stop") { sdk.stop(completion:$0) }
        let terminals=snapshot().compactMap { event -> UUID? in if case .transferResult(let id,_) = event { return id }; return nil }
        try require(terminals.count == 11 && Set(terminals).count == 11,"exactly one terminal")
        operationLock.lock(); let all=operations; operationLock.unlock()
        try require(all.allSatisfy { $0.count == 1 },"exactly one operation completion")
        log("complete",["terminals":11])
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
