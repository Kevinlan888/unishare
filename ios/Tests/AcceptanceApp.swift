import UIKit
import Darwin
import UniShareSDK

func check(_ condition: @autoclosure () -> Bool, _ message: String, file: StaticString = #file, line: UInt = #line) {
    precondition(condition(), message, file: file, line: line)
}
func passed(_ message: String) {
    print("PASS \(ISO8601DateFormatter().string(from: Date())) \(message)"); fflush(stdout)
}
final class Operation {
    private static let registryLock = NSLock()
    private static var registry: [Operation] = []
    private let lock = NSLock()
    private var results: [OperationResult] = []
    var id: UInt64 = 0
    init(_ submit: (@escaping (OperationResult) -> Void) -> UInt64) {
        id = submit { result in self.lock.lock(); self.results.append(result); self.lock.unlock() }
        Operation.registryLock.lock(); Operation.registry.append(self); Operation.registryLock.unlock()
    }
    var count: Int { lock.lock(); defer { lock.unlock() }; return results.count }
    static func verifyAllCompletedOnce() {
        registryLock.lock(); let operations = registry; registryLock.unlock()
        check(operations.allSatisfy { $0.count == 1 }, "all submitted operations complete exactly once")
    }
    func result(_ timeout: TimeInterval = 25) -> OperationResult {
        eventually("operation \(id)", timeout: timeout) { self.count > 0 }
        lock.lock(); defer { lock.unlock() }
        check(results.count == 1 && results[0].operationId == id, "exactly one completion with submission ID")
        return results[0]
    }
}
func eventually(_ label: String, timeout: TimeInterval = 25, _ predicate: () -> Bool) {
    let until = Date().addingTimeInterval(timeout)
    while !predicate() { check(Date() < until, "timeout: \(label)"); Thread.sleep(forTimeInterval: 0.02) }
}
@discardableResult func ok(_ submit: (@escaping (OperationResult) -> Void) -> UInt64) -> OperationResult {
    let result = Operation(submit).result()
    check(result.isSuccess, "unexpected result \(String(describing: result.error))")
    return result
}
func newSdk(_ service: String, _ name: String) -> ShareSdk {
    let sdk = ShareSdk(keychainService: service)
    var config = SdkConfig(deviceName: name)
    config.offlineDebounceSeconds = 1
    config.requestTimeoutSeconds = 20
    ok { sdk.initialize(config, completion: $0) }
    return sdk
}

@main final class AcceptanceApp: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private var started = false
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey:Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        return true
    }
    func applicationDidBecomeActive(_ application: UIApplication) {
        guard !started else { return }; started = true
        DispatchQueue.global().async {
            let mode = CommandLine.arguments.dropFirst().first ?? "network"
            print("ACCEPTANCE_BEGIN mode=\(mode) pid=\(getpid()) os=\(ProcessInfo.processInfo.operatingSystemVersionString)"); fflush(stdout)
            switch mode {
            case "clipboard-write", "clipboard-read": runClipboard(mode)
            case "trust-read": runTrustRead()
            case "denied":
                let sdk = newSdk("com.unishare.acceptance.denied", "denied")
                let events = Events(sdk)
                let result = Operation { sdk.start(completion: $0) }.result()
                if let error = result.error { check(error.category == .permissionDenied, "OS denial category"); print("OS_DENIAL \(error)") }
                else {
                    let event = events.next(after: 0) { if case .discoveryError(let error) = $0 { return error.category == .permissionDenied }; return false }
                    print("OS_DENIAL \(event)")
                    ok { sdk.stop(completion: $0) }
                }
                events.close(); sdk.close(); passed("OS local-network denial surfaces PermissionDenied")
            case "permission":
                let sdk = newSdk("com.unishare.acceptance.permission", "permission")
                check(Operation { sdk.start(completion: $0) }.result().error?.category == .permissionDenied, "missing Bonjour declaration")
                sdk.close(); passed("missing local-network declaration rejected")
            default: runPeers()
            }
            Operation.verifyAllCompletedOnce()
            print("IOS_ACCEPTANCE_RESULT mode=\(mode) failures=0"); fflush(stdout); exit(0)
        }
    }
}
