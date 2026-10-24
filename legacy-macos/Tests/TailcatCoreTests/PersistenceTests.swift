import Foundation
import TailcatCore

final class PersistenceTests {
    func testLegacyImportSkipsSymlinksAndInvalidEntriesWithoutChangingSource() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let users = root.appendingPathComponent("users")
        try FileManager.default.createDirectory(at: users, withIntermediateDirectories: true)
        let address = "tc" + String(repeating: "B", count: 30)
        let original = Data((address + "\n").utf8)
        let user = users.appendingPathComponent("carA")
        try original.write(to: user)
        try Data("invalid".utf8).write(to: users.appendingPathComponent("carB"))
        try FileManager.default.createSymbolicLink(at: users.appendingPathComponent("carLink"), withDestinationURL: user)
        let result = try LegacyAddressBook.read(root: root)
        try expectEqual(result.entries, [LegacyEntry(username: "carA", address: address)])
        try expectEqual(result.skipped, 2)
        try expectEqual(try Data(contentsOf: user), original)
        try expectEqual(try FileManager.default.contentsOfDirectory(atPath: users.path).count, 3)
    }
    func testConfigurationHasNoCredentialsAndUsesPrivatePermissions() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = ConfigurationStore(directory: root)
        let config = AppConfiguration(profiles: [DeviceProfile(name: "Test")])
        try store.save(config)
        let file = root.appendingPathComponent("configuration.json")
        let text = try String(contentsOf: file)
        try expectFalse(text.contains("address\""))
        try expectFalse(text.contains("privateKey"))
        try expectEqual(try store.load().profiles, config.profiles)
        let mode = try FileManager.default.attributesOfItem(atPath: file.path)[.posixPermissions] as? Int
        try expectEqual(mode, 0o600)
    }
    func testRedactionAndNativeReadinessParsing() throws {
        let address = "tc" + String(repeating: "Z", count: 40)
        try expectFalse(Credentials.redact("error: \(address)").contains(address))
        let streaming = OutputRedactor()
        try expectEqual(streaming.append(String(address.prefix(24))), "")
        let safe = streaming.append(String(address.dropFirst(24)) + "\n")
        try expectFalse(safe.contains(String(address.dropFirst(24))))
        try expectEqual(safe, "[连接凭据已隐藏]\n")
        try expectEqual(ForwardOutput.listeningPort(in: "noise\n# forwarding 127.0.0.1:5558 -> remote localhost:5555\n", remotePort: 5555), 5558)
        try expectNil(ForwardOutput.listeningPort(in: "# forwarding 0.0.0.0:5558 -> remote localhost:5555\n", remotePort: 5555))
    }

    func testIsolatedKeychainRoundTrip() throws {
        let store = KeychainCredentialStore(), id = UUID()
        defer { try? store.delete(id: id) }
        let original = "tc" + String(repeating: "T", count: 30)
        let updated = "tc" + String(repeating: "U", count: 30)
        try expectNil(try store.read(id: id))
        try store.write(original, id: id)
        try expectEqual(try store.read(id: id), original)
        try store.write(updated, id: id)
        try expectEqual(try store.read(id: id), updated)
        try store.delete(id: id)
        try expectNil(try store.read(id: id))
    }
}
