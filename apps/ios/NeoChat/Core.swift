import Foundation
import Security

/// Error from the core: user-facing Russian message, never secrets.
struct CoreError: Error {
    let code: String
    let message: String
}

extension Notification.Name {
    static let coreEvent = Notification.Name("NeoChatCoreEvent")
}

/// Asynchronous access to the Rust core. Calls run on a small concurrent queue so the
/// main thread never waits for storage, crypto or network; results and events arrive
/// on the main queue.
final class Core {
    static let shared = Core()

    private var handle: UnsafeMutableRawPointer?
    private let queue = DispatchQueue(label: "app.neochat.core", qos: .userInitiated, attributes: .concurrent)
    private let limit = DispatchSemaphore(value: 4)

    var isOpen: Bool { handle != nil }

    func open() throws {
        if handle != nil { return }
        let dir = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("core", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // The database lives in a folder excluded from backups and readable after first unlock.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
        let key = try DbKey.loadOrCreate()
        let cfg: [String: Any] = ["data_dir": dir.path, "db_key": key, "foreground": true]
        let json = String(data: try JSONSerialization.data(withJSONObject: cfg), encoding: .utf8)!
        var err: UnsafeMutablePointer<CChar>?
        let h = json.withCString { nc_open($0, coreEventCallback, nil, &err) }
        guard let h else {
            var message = "Не удалось открыть хранилище"
            if let err {
                if let obj = try? JSONSerialization.jsonObject(with: Data(String(cString: err).utf8)) as? [String: Any],
                   let m = obj["message"] as? String { message = m }
                nc_free(err)
            }
            throw CoreError(code: "storage", message: message)
        }
        handle = h
    }

    /// Synchronous call for background work; never use it on the main thread.
    func callBlocking(_ method: String, _ params: [String: Any] = [:]) -> Result<Any?, CoreError> {
        guard let handle else { return .failure(CoreError(code: "closed", message: "Хранилище не открыто")) }
        let p = String(data: (try? JSONSerialization.data(withJSONObject: params)) ?? Data("{}".utf8), encoding: .utf8)!
        guard let out = method.withCString({ m in p.withCString { nc_call(handle, m, $0) } }) else {
            return .failure(CoreError(code: "unknown", message: "Нет ответа"))
        }
        let s = String(cString: out)
        nc_free(out)
        guard let obj = try? JSONSerialization.jsonObject(with: Data(s.utf8), options: [.fragmentsAllowed]) as? [String: Any] else {
            return .failure(CoreError(code: "unknown", message: "Неверный ответ ядра"))
        }
        if let e = obj["error"] as? [String: Any] {
            return .failure(CoreError(code: e["code"] as? String ?? "unknown", message: e["message"] as? String ?? ""))
        }
        let ok = obj["ok"]
        return .success(ok is NSNull ? nil : ok)
    }

    func call(_ method: String, _ params: [String: Any] = [:], _ cb: ((Result<Any?, CoreError>) -> Void)? = nil) {
        queue.async {
            self.limit.wait()
            let r = self.callBlocking(method, params)
            self.limit.signal()
            if let cb { DispatchQueue.main.async { cb(r) } }
        }
    }
}

private func coreEventCallback(_ ctx: UnsafeMutableRawPointer?, _ json: UnsafePointer<CChar>?) {
    guard let json else { return }
    let s = String(cString: json)
    guard let ev = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any] else { return }
    DispatchQueue.main.async {
        NotificationCenter.default.post(name: .coreEvent, object: nil, userInfo: ev)
    }
}

/// Random 32-byte database key kept in the Keychain, available after first unlock, never synced.
enum DbKey {
    private static let service = "app.neochat.db"
    private static let account = "db-key-v1"

    static func loadOrCreate() throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let st = SecItemCopyMatching(query as CFDictionary, &item)
        if st == errSecSuccess, let data = item as? Data, data.count == 32 {
            return data.map { String(format: "%02x", $0) }.joined()
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw CoreError(code: "storage", message: "Не удалось создать ключ хранилища")
        }
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(bytes),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemDelete(query.filter { $0.key != kSecReturnData as String && $0.key != kSecMatchLimit as String } as CFDictionary)
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else {
            throw CoreError(code: "storage", message: "Не удалось сохранить ключ хранилища")
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - JSON helpers

typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String { self[k] as? String ?? "" }
    func bool(_ k: String) -> Bool { self[k] as? Bool ?? false }
    func int(_ k: String) -> Int { (self[k] as? NSNumber)?.intValue ?? 0 }
    func int64(_ k: String) -> Int64 { (self[k] as? NSNumber)?.int64Value ?? 0 }
    func obj(_ k: String) -> JSON? { self[k] as? JSON }
    func arr(_ k: String) -> [JSON] { self[k] as? [JSON] ?? [] }
}
