import Foundation

@MainActor public protocol CLISessionScanning: AnyObject {
    func scan(root: URL, profiles: [DeviceProfile], adbPath: String,
              addressForProfile: @escaping @MainActor (UUID) throws -> String?) async throws -> [UUID: [ExternalCLISession]]
}

/// Observes CLI bridges. It never owns a process handle or runs adb connect/disconnect.
@MainActor public final class CLISessionScanner: CLISessionScanning {
    private let runner: any CommandExecuting
    public init(runner: any CommandExecuting) { self.runner = runner }

    public func scan(root: URL, profiles: [DeviceProfile], adbPath: String,
                     addressForProfile: @escaping @MainActor (UUID) throws -> String?) async throws -> [UUID: [ExternalCLISession]] {
        let records = Self.records(root)
        guard !records.isEmpty else { return [:] }
        let pids = Array(Set(records.map(\.pid))).sorted().map(String.init).joined(separator: ",")
        let processes = try await runner.run(Command(URL(fileURLWithPath: "/bin/ps"), ["-ww", "-p", pids, "-o", "pid=,command="], timeout: 4), onOutput: { _ in })
        try Task.checkCancellation()
        // ps exits 1 when all recorded processes have disappeared.
        guard !processes.timedOut, !processes.cancelled else { try processes.requireSuccess(); return [:] }
        let commands = Self.processCommands(processes.output)
        let listeners = try await runner.run(Command(URL(fileURLWithPath: "/usr/sbin/lsof"), ["-nP", "-a", "-p", pids, "-iTCP", "-sTCP:LISTEN", "-Fpn"], timeout: 4), onOutput: { _ in })
        try Task.checkCancellation()
        guard !listeners.timedOut, !listeners.cancelled else { try listeners.requireSuccess(); return [:] }
        let endpoints = Self.listenerEndpoints(listeners.output)
        var candidates: [(Record, String, Int)] = []
        for record in records {
            guard let command = commands[record.pid],
                  endpoints[record.pid]?.contains("127.0.0.1:\(record.port)") == true,
                  let target = Self.bridgeTarget(command, port: record.port) else { continue }
            candidates.append((record, target.address, target.remotePort))
        }
        guard !candidates.isEmpty else { return [:] }
        let book = (try? LegacyAddressBook.read(root: root).entries) ?? []
        var resolved: [UUID: String] = [:]
        var attempted: Set<UUID> = []
        func address(_ profile: DeviceProfile) throws -> String? {
            if attempted.insert(profile.id).inserted, let value = try addressForProfile(profile.id) { resolved[profile.id] = value }
            return resolved[profile.id]
        }
        var matched: [(UUID, Record, Int)] = []
        for (record, target, remotePort) in candidates {
            // Imported usernames narrow Keychain reads; the current GUI address must still agree.
            let users = Set(book.filter { $0.address == target }.map(\.username))
            let relevant = users.isEmpty ? profiles : profiles.filter { $0.legacyUsername.map(users.contains) ?? true }
            for profile in relevant {
                if try address(profile) == target { matched.append((profile.id, record, remotePort)) }
            }
        }
        guard !matched.isEmpty else { return [:] }
        var devices: [String: (ConnectionState, String, String)] = [:]
        if FileManager.default.isExecutableFile(atPath: adbPath) {
            let result = try await runner.run(Command(URL(fileURLWithPath: adbPath), ["devices", "-l"], timeout: 4), onOutput: { _ in })
            try Task.checkCancellation()
            try result.requireSuccess()
            devices = Self.adbDevices(result.output)
        }
        var result: [UUID: [ExternalCLISession]] = [:]
        for (id, record, remotePort) in matched {
            let device = devices["127.0.0.1:\(record.port)"]
            result[id, default: []].append(ExternalCLISession(pid: record.pid, port: record.port, remotePort: remotePort,
                state: device?.0 ?? .offline, model: device?.1 ?? "—", product: device?.2 ?? "—"))
        }
        return result.mapValues { $0.sorted { $0.port < $1.port } }
    }

    private struct Record { let pid: Int32; let port: Int }
    private static func records(_ root: URL) -> [Record] {
        guard safeDirectory(root) else { return [] }
        let bridges = root.appendingPathComponent("bridges")
        var result: [Record] = []
        if safeDirectory(bridges), let files = try? FileManager.default.contentsOfDirectory(at: bridges, includingPropertiesForKeys: nil) {
            for file in files.prefix(1024) where file.pathExtension == "pid" {
                guard let port = Int(file.deletingPathExtension().lastPathComponent), (1...65535).contains(port),
                      let text = smallFile(file), let pid = Int32(text), pid > 1 else { continue }
                result.append(Record(pid: pid, port: port))
            }
        }
        // Observe old single-bridge state without invoking the CLI's mutating migration.
        if let text = smallFile(root.appendingPathComponent("bridge.pid")), let pid = Int32(text), pid > 1,
           let portText = smallFile(root.appendingPathComponent("local-port")), let port = Int(portText), (1...65535).contains(port),
           !result.contains(where: { $0.pid == pid && $0.port == port }) { result.append(Record(pid: pid, port: port)) }
        return result
    }
    private static func safeDirectory(_ url: URL) -> Bool {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.type] as? FileAttributeType) == .typeDirectory
    }
    private static func smallFile(_ url: URL) -> String? {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              attrs[.type] as? FileAttributeType == .typeRegular,
              (attrs[.size] as? NSNumber)?.intValue ?? 257 <= 256,
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private static func processCommands(_ output: String) -> [Int32: String] {
        var result: [Int32: String] = [:]
        for line in output.split(separator: "\n") {
            let parts = line.split(maxSplits: 1, whereSeparator: { $0.isWhitespace })
            if parts.count == 2, let pid = Int32(parts[0]) { result[pid] = String(parts[1]) }
        }
        return result
    }
    private static func listenerEndpoints(_ output: String) -> [Int32: Set<String>] {
        var result: [Int32: Set<String>] = [:]; var current: Int32?
        for line in output.split(separator: "\n") {
            if line.first == "p" { current = Int32(line.dropFirst()) }
            else if line.first == "n", let current { result[current, default: []].insert(String(line.dropFirst())) }
        }
        return result
    }
    private static func bridgeTarget(_ command: String, port: Int) -> (address: String, remotePort: Int)? {
        let parts = command.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard parts.count == 5, URL(fileURLWithPath: parts[0]).lastPathComponent == "socat",
              parts[1].split(separator: ",").first == "TCP-LISTEN:\(port)",
              parts[1].split(separator: ",").contains("bind=127.0.0.1"),
              parts[2].hasPrefix("EXEC:"), URL(fileURLWithPath: String(parts[2].dropFirst(5))).lastPathComponent == "tailcat",
              Credentials.isPlausible(parts[3]), let remotePort = Int(parts[4]), (1...65535).contains(remotePort) else { return nil }
        return (parts[3], remotePort)
    }
    private static func adbDevices(_ output: String) -> [String: (ConnectionState, String, String)] {
        var result: [String: (ConnectionState, String, String)] = [:]
        for line in output.split(separator: "\n") {
            let parts = line.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            guard parts.count >= 2, parts[0].hasPrefix("127.0.0.1:") else { continue }
            let state: ConnectionState = parts[1] == "device" ? .online : parts[1] == "unauthorized" ? .unauthorized : .offline
            let model = parts.first { $0.hasPrefix("model:") }.map { String($0.dropFirst(6)) } ?? "—"
            let product = parts.first { $0.hasPrefix("product:") }.map { String($0.dropFirst(8)) } ?? "—"
            result[parts[0]] = (state, model, product)
        }
        return result
    }
}
