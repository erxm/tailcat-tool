import Foundation
import Security

/// Use the same installed v1 helper for every GUI build. Its caller check pins
/// the GUI identifier and signing certificate; stdin/stdout carry no shell/argv secrets.
public final class HelperCredentialStore: CredentialStore {
    private let helper: URL
    public init(bundledHelper: URL, directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("TailcatManager/CredentialHelper/v1")) throws {
        let file = directory.appendingPathComponent("TailcatCredentialHelper")
        helper = file
        for path in [directory.deletingLastPathComponent().deletingLastPathComponent(), directory.deletingLastPathComponent(), directory, file] {
            if (try? FileManager.default.attributesOfItem(atPath: path.path)[.type] as? FileAttributeType) == .typeSymbolicLink {
                throw ManagerError.message("拒绝使用符号链接中的凭据助手")
            }
        }
        var source: SecStaticCode?, requirement: SecRequirement?
        try check(SecStaticCodeCreateWithPath(bundledHelper as CFURL, [], &source))
        guard let source else { throw ManagerError.message("凭据助手缺失") }
        try check(SecStaticCodeCheckValidity(source, SecCSFlags(rawValue: kSecCSStrictValidate), nil))
        try check(SecCodeCopyDesignatedRequirement(source, [], &requirement))
        guard let requirement else { throw ManagerError.message("凭据助手没有签名身份") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        if !FileManager.default.fileExists(atPath: file.path) {
            let staging = directory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: staging) }
            try FileManager.default.copyItem(at: bundledHelper, to: staging)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: staging.path)
            try FileManager.default.moveItem(at: staging, to: file)
        }
        var installed: SecStaticCode?
        try check(SecStaticCodeCreateWithPath(file as CFURL, [], &installed))
        guard let installed else { throw ManagerError.message("无法检查本机凭据助手") }
        try check(SecStaticCodeCheckValidity(installed, SecCSFlags(rawValue: kSecCSStrictValidate), requirement))
    }
    public func read(id: UUID) throws -> String? { try request("read", id: id, allowUI: true)["address"] as? String }
    public func readForObservation(id: UUID) throws -> String? { try request("read", id: id, allowUI: false)["address"] as? String }
    public func write(_ address: String, id: UUID) throws { _ = try request("write", id: id, address: address, allowUI: true) }
    public func delete(id: UUID) throws { _ = try request("delete", id: id, allowUI: true) }
    private func request(_ operation: String, id: UUID, address: String? = nil, allowUI: Bool) throws -> [String: Any] {
        var input: [String: Any] = ["operation": operation, "id": id.uuidString, "allowUI": allowUI]
        if let address { input["address"] = address }
        let process = Process(), stdin = Pipe(), stdout = Pipe()
        process.executableURL = helper
        process.standardInput = stdin; process.standardOutput = stdout; process.standardError = FileHandle.nullDevice
        try process.run()
        do { try stdin.fileHandleForWriting.write(contentsOf: JSONSerialization.data(withJSONObject: input)) }
        catch { try? stdin.fileHandleForWriting.close(); process.terminate(); process.waitUntilExit(); throw error }
        try stdin.fileHandleForWriting.close()
        let data = stdout.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0, data.count <= 16384,
              let reply = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], reply["version"] as? Int == 1,
              let status = reply["status"] as? Int32 else { throw ManagerError.message("凭据助手未返回有效结果") }
        try check(status)
        return reply
    }
    private func check(_ status: OSStatus) throws {
        guard status == errSecSuccess else {
            let detail = SecCopyErrorMessageString(status, nil) as String? ?? "错误码 \(status)"
            throw ManagerError.message("Keychain：\(detail)")
        }
    }
}
