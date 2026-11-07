import Foundation
import Observation

/// The window's state: the bridges found, the one attached, the filter.
@Observable
final class Viewer {
    enum StatusFilter: String, CaseIterable, Identifiable {
        case all = "All", success = "2xx", redirect = "3xx", client = "4xx", server = "5xx", failed = "Failed"
        var id: String { rawValue }

        func matches(_ s: Summary) -> Bool {
            switch self {
            case .all: true
            case .failed: s.state == .failed
            case .success: (200..<300).contains(s.code ?? 0)
            case .redirect: (300..<400).contains(s.code ?? 0)
            case .client: (400..<500).contains(s.code ?? 0)
            case .server: (500..<600).contains(s.code ?? 0)
            }
        }
    }

    enum Mode: String, CaseIterable, Identifiable {
        case requests = "Requests", rules = "Rules", performance = "Performance"
        var id: String { rawValue }

        var symbol: String {
            switch self {
            case .requests: "list.bullet"
            case .rules: "slider.horizontal.3"
            case .performance: "gauge.with.dots.needle.33percent"
            }
        }
    }

    var mode: Mode = .requests {
        didSet { if mode != oldValue { followMode() } }
    }
    /// Open while the performance view shows; its tail holds what this viewer started.
    private(set) var perf: PerfSession?
    /// A mock to make from a request: set by "Mock This Response", read by the rules view.
    var draftMock: Summary?

    /// What `hopi viewer --app … --platform … --device …` asked for: attached as soon as a scan
    /// finds it, then forgotten, so later scans keep whatever is selected.
    @ObservationIgnored private var launch = LaunchTarget(arguments: ProcessInfo.processInfo.arguments)

    private(set) var targets: [Target] = []
    private(set) var scanning = false
    private(set) var session: Session?
    var selectedTarget: Target.ID? {
        didSet { if selectedTarget != oldValue { attach() } }
    }
    var selectedTransaction: Summary.ID?
    var search = ""
    /// Bumped by ⌘F; the list focuses its search field.
    var findRequests = 0
    var statusFilter: StatusFilter = .all

    var visibleTransactions: [Summary] {
        guard let session else { return [] }
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        // Arrival order: new requests land at the bottom, so rows being read never move.
        return session.transactions.filter { summary in
            statusFilter.matches(summary) && (query.isEmpty
                || summary.url.lowercased().contains(query)
                || summary.method.lowercased() == query
                || summary.code.map(String.init) == query)
        }
    }

    func scan() async {
        guard !scanning else { return }
        scanning = true
        targets = await Discovery.scan()
        scanning = false
        if !launch.isEmpty {
            // The named app only: another one attached instead would read as the one asked for.
            guard let match = targets.first(where: launch.matches) else { return }
            launch = LaunchTarget(arguments: [])
            selectedTarget = match.id
            return
        }
        if selectedTarget == nil || !targets.contains(where: { $0.id == selectedTarget }) {
            selectedTarget = targets.first?.id
        }
    }

    func reconnect() {
        attach()
    }

    private func attach() {
        session?.stop()
        session = nil
        perf?.stop()
        perf = nil
        selectedTransaction = nil
        guard let target = targets.first(where: { $0.id == selectedTarget }) else { return }
        let session = Session(target: target)
        self.session = session
        session.start()
        followMode()
    }

    /// Opens the perf tail when the performance view shows, and closes it otherwise.
    private func followMode() {
        guard mode == .performance, let session else {
            perf?.stop()
            perf = nil
            return
        }
        guard perf == nil else { return }
        let perf = PerfSession(target: session.target)
        self.perf = perf
        perf.start()
    }
}

/// The target named on the command line, as `hopi viewer` passes it through `open --args`:
/// `--app <id>`, `--platform android|ios|jvm`, `--device <serial or UDID>` (also `--flag=value`).
nonisolated struct LaunchTarget: Sendable, Equatable {
    var app: String?
    var platform: String?
    var device: String?

    init(arguments: [String]) {
        var values: [String: String] = [:]
        var index = 0
        while index < arguments.count {
            let argument = arguments[index]
            index += 1
            guard argument.hasPrefix("--") else { continue }
            if let equals = argument.firstIndex(of: "=") {
                values[String(argument[..<equals])] = String(argument[argument.index(after: equals)...])
            } else if index < arguments.count, !arguments[index].hasPrefix("--") {
                values[argument] = arguments[index]
                index += 1
            }
        }
        app = values["--app"]
        platform = values["--platform"]
        device = values["--device"]
    }

    var isEmpty: Bool { app == nil && platform == nil && device == nil }

    func matches(_ target: Target) -> Bool {
        if let app, target.app != app { return false }
        if let platform {
            let kinds: Set<Target.Kind> = switch platform {
            case "android": [.android]
            case "ios": [.iphone, .simulator]
            case "jvm": [.jvm]
            default: []
            }
            if !kinds.contains(target.kind) { return false }
        }
        // Android ids are `android:<serial>:<app>`, USB iPhones `usb:<udid>:<app>`, a simulator's
        // its bridge.json path under `Devices/<udid>/`.
        if let device, !target.id.contains(":\(device):"), !target.id.contains("/\(device)/") { return false }
        return true
    }
}
