import Foundation
// Foundation values at this boundary own their storage; no borrowed ABI spans escape.
internal protocol Backend: AnyObject {
    var event: (([String: Any]) -> Void)? { get set }
    func create(_ config: SdkConfig, completion: @escaping ([String: Any]) -> Void)
    func submit(_ command: Int, arguments: [String: Any], completion: @escaping ([String: Any]) -> Void)
    func snapshot(_ trusted: Bool) throws -> [[String: Any]]
    func state() throws -> UInt32
    func close()
}
