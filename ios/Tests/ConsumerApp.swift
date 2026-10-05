import UIKit
import Darwin
import UniShareSDK

private func awaitResult(_ submit: (@escaping (OperationResult) -> Void) -> UInt64) -> OperationResult {
    let done = DispatchSemaphore(value: 0)
    var value: OperationResult?
    let id = submit { result in value = result; done.signal() }
    precondition(done.wait(timeout: .now() + 20) == .success, "operation timeout")
    precondition(value!.operationId == id, "operation ID")
    return value!
}
private func success(_ result: OperationResult, file: StaticString = #file, line: UInt = #line) { precondition(result.isSuccess, "unexpected error: \(String(describing:result.error))", file:file, line:line) }
private func runConsumer() {
    // Construct on a worker to exercise asynchronous UIKit setup.
    let sdk = ShareSdk(keychainService: "com.unishare.swift.tests." + UUID().uuidString)
    success(awaitResult { sdk.initialize(SdkConfig(deviceName: "Swift 汉字 é"),completion:$0) })
    precondition(sdk.state == .initialized)
    if CommandLine.arguments.contains("--permission") {
        let result = awaitResult { sdk.start(completion:$0) }
        precondition(result.error?.category == .permissionDenied)
        sdk.close(); print("PASS Swift missing local-network declarations"); return
    }
    success(awaitResult { sdk.start(completion:$0) })
    precondition(sdk.state == .running)
    precondition((try! sdk.discovery.getDevices()).isEmpty)
    precondition((try! sdk.connections.getTrustedDevices()).isEmpty)
    let text = "你好 👋\0line\n é"
    success(awaitResult { sdk.clipboard.writeContent(.text(text),completion:$0) })
    precondition(awaitResult { sdk.clipboard.readShareableContent(completion:$0) }.content == .text(text))
    let png = Data(base64Encoded:"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")!
    success(awaitResult { sdk.clipboard.writeContent(.image(mimeType:"image/png",bytes:png),completion:$0) })
    let image = awaitResult { sdk.clipboard.readShareableContent(completion:$0) }
    if case .image(let mime,let data) = image.content { precondition(mime == "image/png" && !data.isEmpty) } else { preconditionFailure("image read") }
    DispatchQueue.main.sync { UIPasteboard.general.items = [["com.unishare.unsupported": Data([1,2,3])]] }
    precondition(awaitResult { sdk.clipboard.readShareableContent(completion:$0) }.error?.category == .invalidInput)
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try! FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
    defer { try? FileManager.default.removeItem(at:directory) }
    let source = directory.appendingPathComponent("source.png")
    try! png.write(to:source)
    let peer = UUID()
    precondition(!(awaitResult { sdk.transfers.sendImage(source,to:peer,mimeType:"image/png",byteCount:UInt64(png.count),completion:$0) }).isSuccess)
    precondition(!(awaitResult { sdk.transfers.sendFile(source,to:peer,displayName:"source",byteCount:UInt64(png.count),completion:$0) }).isSuccess)
    precondition(FileManager.default.fileExists(atPath:source.path), "source must survive rejection")
    let staging = directory.appendingPathComponent("empty")
    try! Data().write(to:staging)
    precondition(!(awaitResult { sdk.transfers.accept(999,stagingURL:staging,completion:$0) }).isSuccess)
    // Failed Accept must dispose of the registered empty destination.
    for _ in 0..<100 { if !FileManager.default.fileExists(atPath:staging.path) { break }; Thread.sleep(forTimeInterval:0.01) }
    precondition(!FileManager.default.fileExists(atPath:staging.path), "failed Accept cleanup")
    DispatchQueue.main.sync { NotificationCenter.default.post(name:UIApplication.didEnterBackgroundNotification,object:nil) }
    for _ in 0..<200 { if sdk.state == .stopped { break }; Thread.sleep(forTimeInterval:0.01) }
    precondition(sdk.state == .stopped, "background interruption")
    precondition(awaitResult { sdk.start(completion:$0) }.error?.category == .networkLost)
    DispatchQueue.main.sync { NotificationCenter.default.post(name:UIApplication.didBecomeActiveNotification,object:nil) }
    success(awaitResult { sdk.start(completion:$0) })
    // FIFO open cannot finish without a writer. Stop must retire preparation
    // even when it has not entered open yet, and must not wait for the provider.
    let fifo = directory.appendingPathComponent("blocked-provider")
    precondition(mkfifo(fifo.path, 0o600) == 0)
    let cancelled = DispatchSemaphore(value:0)
    var fileCompletions = 0
    sdk.transfers.sendFile(fifo,to:peer,displayName:"blocked",byteCount:0) { result in
        precondition(result.error?.category == .cancelled)
        fileCompletions += 1; cancelled.signal()
    }
    success(awaitResult { sdk.stop(completion:$0) })
    precondition(cancelled.wait(timeout:.now()+5) == .success)
    let writer = open(fifo.path,O_RDWR | O_NONBLOCK)
    precondition(writer >= 0)
    success(awaitResult { sdk.start(completion:$0) })
    // This second acquisition drains behind the FIFO; late FIFO failure must
    // not deliver another result for the cancelled operation.
    precondition(!(awaitResult { sdk.transfers.sendFile(source,to:peer,displayName:"drain",byteCount:UInt64(png.count),completion:$0) }).isSuccess)
    Darwin.close(writer)
    precondition(fileCompletions == 1)
    success(awaitResult { sdk.stop(completion:$0) })
    precondition(sdk.state == .stopped)
    sdk.close(); precondition(sdk.state == .closed)
    print("PASS Swift lifecycle, clipboard UTF-8/image/unsupported, file URL source, failed Accept cleanup, foreground, blocked preparation Stop/late completion")
}
@main final class ConsumerApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var started = false
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey:Any]?) -> Bool {
        window = UIWindow(frame:UIScreen.main.bounds)
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        return true
    }
    func applicationDidBecomeActive(_ application: UIApplication) {
        guard !started else { return }; started = true
        precondition(application.applicationState == .active)
        DispatchQueue.global().async {
            runConsumer()
            print("SWIFT_CONSUMER_RESULT failures=0"); fflush(stdout); exit(0)
        }
    }
}