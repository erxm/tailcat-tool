import Foundation
import Combine

public enum ToolComponent: String, CaseIterable, Identifiable {
    case tailcat, adb, scrcpy
    public var id: String { rawValue }
    public var label: String { self == .adb ? "ADB" : self == .tailcat ? "Tailcat" : "scrcpy" }
    public var executableName: String { rawValue }
    public var installArguments: [String] {
        self == .adb ? ["install", "--cask", "android-platform-tools"] : ["install", rawValue]
    }
    public var installDescription: String { "brew " + installArguments.joined(separator: " ") }
}

public extension ToolSettings {
    subscript(component: ToolComponent) -> String {
        get { switch component { case .tailcat: return tailcat; case .adb: return adb; case .scrcpy: return scrcpy } }
        set { switch component { case .tailcat: tailcat = newValue; case .adb: adb = newValue; case .scrcpy: scrcpy = newValue } }
    }
}

@MainActor public final class ComponentInstaller: ObservableObject {
    @Published public private(set) var installing: ToolComponent?
    @Published public private(set) var output = ""
    @Published public private(set) var message: String?
    @Published public private(set) var failed = false
    private let runner: any CommandExecuting
    private let locate: @MainActor (String) -> String?
    private var job: Task<Void, Never>?
    public var homebrewPath: String? { locate("brew") }
    public var isBusy: Bool { installing != nil }

    public init(runner: (any CommandExecuting)? = nil, locate: @escaping @MainActor (String) -> String? = { ToolLocator.find($0) }) {
        self.runner = runner ?? ProcessRunner(); self.locate = locate
    }

    @discardableResult public func install(_ component: ToolComponent, configure: @escaping @MainActor (String) throws -> Void) -> Task<Void, Never>? {
        install([component]) { _, path in try configure(path) }
    }

    @discardableResult public func install(_ components: [ToolComponent], configure: @escaping @MainActor (ToolComponent, String) throws -> Void) -> Task<Void, Never>? {
        guard !isBusy, let first = components.first else { return nil }
        installing = first; output = ""; message = nil; failed = false
        let task = Task { [weak self] in
            guard let self else { return }
            defer { self.installing = nil; self.job = nil }
            do {
                for component in components {
                    try Task.checkCancellation()
                    self.installing = component
                    // A broken/missing configured path may simply need discovery, not package installation.
                    if let existing = self.locate(component.executableName) {
                        try configure(component, existing)
                        self.output = String((self.output + "\(component.label) 已检测并配置\n").suffix(32768)); continue
                    }
                    guard let brew = self.homebrewPath else {
                        throw ManagerError.message("未检测到 Homebrew，请先从 brew.sh 安装 Homebrew，然后重试")
                    }
                    self.output = String((self.output + component.installDescription + "\n").suffix(32768))
                    let redactor = OutputRedactor()
                    let result = try await self.runner.run(Command(URL(fileURLWithPath: brew), component.installArguments, timeout: 1800,
                        environment: ["HOMEBREW_NO_AUTO_UPDATE": "1", "HOMEBREW_NO_INSTALL_UPGRADE": "1",
                                      "HOMEBREW_NO_INSTALL_CLEANUP": "1", "HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK": "1",
                                      "HOMEBREW_NO_ENV_HINTS": "1", "HOMEBREW_NO_ASK": "1", "NONINTERACTIVE": "1"]),
                        onOutput: { [weak self] chunk in
                            let safe = redactor.append(chunk)
                            if !safe.isEmpty, let self { self.output = String((self.output + safe).suffix(32768)) }
                        })
                    let trailing = redactor.flush()
                    if !trailing.isEmpty { self.output = String((self.output + trailing).suffix(32768)) }
                    try Task.checkCancellation(); try result.requireSuccess()
                    guard let path = self.locate(component.executableName) else {
                        throw ManagerError.message("Homebrew 已完成，但未找到 \(component.label) 可执行文件，请检查安装日志和链接状态")
                    }
                    try configure(component, path)
                }
                self.message = components.count == 1 ? "\(first.label) 已配置完成" : "\(components.count) 个组件已配置完成"
            } catch is CancellationError {
                self.failed = true; self.message = "安装已取消，可重新检测或重试"
            } catch {
                self.failed = true; self.message = Credentials.redact(error.localizedDescription)
            }
        }
        job = task; return task
    }
    public func cancel() { job?.cancel() }
    public func shutdown() async { job?.cancel(); await job?.value }
}
