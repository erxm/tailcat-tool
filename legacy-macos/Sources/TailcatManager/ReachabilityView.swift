import SwiftUI
import TailcatCore

struct ReachabilityView: View {
    @ObservedObject var manager: SessionManager
    @ObservedObject var checker: ReachabilityChecker
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("全部设备连通测试").font(.title2.bold())
                Spacer()
                Button("完成") { dismiss() }.keyboardShortcut(.cancelAction)
            }
            Text("测试全部设备的 Tailcat ping，每次探测最长 8 秒，同时测试 3 台；并显示已有 ADB 状态。测试不会建立或断开 ADB 连接。")
                .font(.callout).foregroundStyle(.secondary)
            HStack {
                if checker.isRunning {
                    ProgressView().controlSize(.small)
                    Text(checker.isCancelling ? "正在取消…" : "已完成 \(checker.completedCount) / \(checker.results.count)")
                    Spacer()
                    Button("取消测试") { checker.cancel() }.disabled(checker.isCancelling)
                } else {
                    Text(checker.results.isEmpty ? "点击开始测试" : "Tailcat ping：\(checker.reachableCount) / \(checker.results.count) 响应 · 已有 ADB：\(checker.adbOnlineCount) 台在线")
                    Spacer()
                    Button(checker.results.isEmpty ? "开始测试" : "重新测试") { manager.testAllDevices() }
                        .buttonStyle(.borderedProminent).disabled(manager.allProfiles.isEmpty)
                }
            }
            Table(checker.results) {
                TableColumn("设备") { result in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(result.name)
                        if result.temporary { Text("临时设备").font(.caption).foregroundStyle(.secondary) }
                    }
                }.width(min: 130, ideal: 160)
                TableColumn("Tailcat ping") { result in
                    Label(result.state.label, systemImage: symbol(result.state))
                        .foregroundStyle(color(result.state))
                }.width(min: 125, ideal: 140)
                TableColumn("已有 ADB") { result in
                    Text(result.adbState.label).foregroundStyle(result.adbState == .online ? .green : .secondary)
                }.width(105)
                TableColumn("延迟", value: \.latency).width(70)
                TableColumn("路径 / 详情") { result in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(result.route == "—" ? result.detail : result.route).lineLimit(2).help(result.detail)
                        if let time = result.checkedAt { Text(time, style: .time).font(.caption).foregroundStyle(.secondary) }
                    }.textSelection(.enabled)
                }
            }
            Text("收到 pong 可确认 Tailcat 可达；未收到 pong 也可能是服务器版本不支持或新链路建连超时，不代表已有 ADB 离线。ADB 列显示当前会话状态；未连接的 ADB 不自动建立连接。关闭窗口后测试继续，退出应用会取消测试。")
                .font(.caption).foregroundStyle(.secondary)
        }.padding(24).frame(width: 900, height: 510)
    }
    private func color(_ state: ReachabilityState) -> Color {
        switch state {
        case .reachable: return .green
        case .unreachable, .failed: return .orange
        default: return .secondary
        }
    }
    private func symbol(_ state: ReachabilityState) -> String {
        switch state {
        case .reachable: return "checkmark.circle.fill"
        case .unreachable, .failed: return "exclamationmark.circle"
        case .cancelled: return "xmark.circle"
        default: return "clock"
        }
    }
}
