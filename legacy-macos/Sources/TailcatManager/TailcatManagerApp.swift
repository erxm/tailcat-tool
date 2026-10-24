import SwiftUI
import AppKit
import TailcatCore

@main
struct TailcatManagerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = ApplicationModel()

    var body: some Scene {
        Window("Tailcat Manager", id: "manager") {
            Group {
                if let error = model.startupError {
                    VStack(spacing: 20) {
                        Image(systemName: "exclamationmark.triangle").font(.largeTitle)
                        Text("配置读取失败").font(.title2)
                        Text(error).textSelection(.enabled)
                        Text("配置文件已保留，请修复后重新启动应用。")
                        Button("显示配置目录") { NSWorkspace.shared.open(model.configurationDirectory) }
                    }.padding(40).frame(minWidth: 620, minHeight: 400)
                } else {
                    ManagerView(manager: model.manager, demo: model.demo)
                }
            }
            .onAppear {
                delegate.manager = model.manager
                NSApp.setActivationPolicy(.regular)
            }
        }
        .defaultSize(width: 1050, height: 730)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
        Settings { ToolSettingsView(manager: model.manager) }
        MenuBarExtra("Tailcat", systemImage: "network") {
            ManagerMenu(manager: model.manager)
        }
    }
}

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate {
    weak var manager: SessionManager?
    private var terminating = false
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationDidFinishLaunching(_ notification: Notification) {
        guard let bundleID = Bundle.main.bundleIdentifier else { return }
        if let other = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            .first(where: { $0.processIdentifier != ProcessInfo.processInfo.processIdentifier }) {
            other.activate(options: [.activateAllWindows, .activateIgnoringOtherApps])
            NSApp.terminate(nil)
        }
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !terminating, let manager else { return .terminateNow }
        terminating = true
        Task {
            await manager.shutdown()
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}

@MainActor final class ApplicationModel: ObservableObject {
    let manager: SessionManager
    let demo: Bool
    let startupError: String?
    let configurationDirectory: URL
    init() {
        demo = CommandLine.arguments.contains("--demo")
        let store = ConfigurationStore(); configurationDirectory = store.directory
        if demo {
            manager = DemoEnvironment.makeManager(); startupError = nil
        } else {
            do {
                let configuration = try store.load()
                let runner = ProcessRunner()
                let credentials: any CredentialStore
                if let helper = Bundle.main.url(forAuxiliaryExecutable: "TailcatCredentialHelper") {
                    credentials = try HelperCredentialStore(bundledHelper: helper)
                } else if Bundle.main.bundleIdentifier != nil {
                    throw ManagerError.message("应用包缺少凭据助手，请重新打包")
                } else { credentials = KeychainCredentialStore() }
                manager = SessionManager(configuration: configuration, configurationStore: store,
                                         credentials: credentials, runner: runner,
                                         cliScanner: CLISessionScanner(runner: runner))
                startupError = nil
                // Compatibility is a read-only import, never a transfer of CLI PID/log ownership.
                if configuration.profiles.isEmpty,
                   FileManager.default.fileExists(atPath: URL(fileURLWithPath: configuration.tools.addressBookDirectory).appendingPathComponent("users").path) {
                    do { try manager.importLegacy() } catch { manager.notice = error.localizedDescription }
                }
                manager.startCLIObservation()
            } catch {
                startupError = error.localizedDescription
                manager = SessionManager(credentials: MemoryCredentialStore(), runner: ProcessRunner())
            }
        }
    }
}

private struct ManagerMenu: View {
    @ObservedObject var manager: SessionManager
    @Environment(\.openWindow) private var openWindow
    var body: some View {
        Button("打开管理窗口") {
            openWindow(id: "manager")
            NSApp.activate(ignoringOtherApps: true)
        }
        Divider()
        ForEach(manager.allProfiles) { profile in
            let session = manager.session(profile.id)
            Menu("\(profile.name) · \(manager.isTemporary(profile.id) ? "临时 · " : "")\(session.isExternalCLI ? "CLI 外部连接 · " : "")\(session.state.label)") {
                if session.isExternalCLI { Text("由 CLI 管理通道，可在主窗口执行 ADB 操作") }
                Button(session.serial == nil ? "连接" : "重新连接") { manager.connect(profile.id) }
                    .disabled(session.state.isBusy || session.isExternalCLI)
                Button("断开") { manager.disconnect(profile.id) }
                    .disabled(session.isExternalCLI || (session.serial == nil && !session.state.isBusy))
                if manager.isTemporary(profile.id) {
                    Button("关闭临时设备") {
                        Task { do { try await manager.removeDevice(profile.id) } catch { manager.notice = error.localizedDescription } }
                    }
                }
            }
        }
        Divider()
        Button("断开应用管理的全部连接") { Task { await manager.disconnectAll() } }
            .disabled(manager.ownedActiveCount == 0)
        Button("退出 Tailcat Manager") { NSApp.terminate(nil) }.keyboardShortcut("q")
    }
}
