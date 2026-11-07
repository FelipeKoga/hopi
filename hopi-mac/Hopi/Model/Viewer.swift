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
