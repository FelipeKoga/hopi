import SwiftUI

struct RequestList: View {
    @Bindable var viewer: Viewer

    var body: some View {
        Group {
            if let session = viewer.session {
                switch session.status {
                case .closed(let reason):
                    ContentUnavailableView {
                        Label("Disconnected", systemImage: "bolt.horizontal.circle")
                    } description: {
                        Text(reason ?? "The app closed the connection.")
                    } actions: {
                        Button("Reconnect") { viewer.reconnect() }
                    }
                default:
                    table(session)
                }
            } else {
                ContentUnavailableView("No App", systemImage: "network",
                                       description: Text("Pick an app on the left."))
            }
        }
        .navigationTitle(viewer.session?.target.app ?? "Hopi")
        .navigationSubtitle(subtitle)
        .searchable(text: $viewer.search, placement: .toolbar, prompt: "URL, method or status")
        .toolbar {
            ToolbarItem {
                Picker("Status", selection: $viewer.statusFilter) {
                    ForEach(Viewer.StatusFilter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.menu)
                .help("Show only these responses")
            }
            ToolbarItem {
                Button {
                    Task { await viewer.session?.clear() }
                } label: {
                    Label("Clear", systemImage: "trash")
                }
                .help("Clear the app's requests (⌘K)")
                .disabled(viewer.session?.transactions.isEmpty ?? true)
            }
        }
    }

    private var subtitle: String {
        guard let session = viewer.session else { return "" }
        switch session.status {
        case .connecting: return "Connecting…"
        case .closed: return "Disconnected"
        case .live:
            let total = session.transactions.count
            let shown = viewer.visibleTransactions.count
            return shown == total ? "\(total) requests" : "\(shown) of \(total) requests"
        }
    }

    private func table(_ session: Session) -> some View {
        Table(viewer.visibleTransactions, selection: $viewer.selectedTransaction) {
            TableColumn("Status") { StatusText(summary: $0) }
                .width(min: 44, ideal: 52, max: 64)
            TableColumn("Method") { Text($0.method).font(.body.monospaced()) }
                .width(min: 50, ideal: 64, max: 80)
            TableColumn("Host") { Text(Format.host($0.url)).foregroundStyle(.secondary).lineLimit(1) }
                .width(min: 80, ideal: 150)
            TableColumn("Path") { Text(Format.path($0.url)).lineLimit(1).truncationMode(.middle) }
            TableColumn("Time") { summary in
                Text(summary.ms.map(Format.duration) ?? "…")
                    .monospacedDigit().foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 52, ideal: 64, max: 80)
            TableColumn("Size") { summary in
                Text(Format.bytes(summary.resBytes))
                    .monospacedDigit().foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 52, ideal: 68, max: 90)
        }
        .overlay {
            if viewer.visibleTransactions.isEmpty {
                if session.transactions.isEmpty {
                    ContentUnavailableView("Waiting for Requests", systemImage: "antenna.radiowaves.left.and.right",
                                           description: Text("Requests the app makes show up here as they start."))
                } else {
                    ContentUnavailableView.search(text: viewer.search)
                }
            }
        }
    }
}

/// The status code, the only colored text in the list.
struct StatusText: View {
    let summary: Summary

    var body: some View {
        Text(label)
            .font(.body.monospacedDigit().weight(.medium))
            .foregroundStyle(color)
    }

    private var label: String {
        switch summary.state {
        case .pending: "…"
        case .failed: "ERR"
        case .complete: summary.code.map(String.init) ?? "—"
        }
    }

    private var color: Color { Self.color(code: summary.code, state: summary.state) }

    static func color(code: Int?, state: TransactionState) -> Color {
        if state == .failed { return .red }
        switch code ?? 0 {
        case 200..<300: return .green
        case 300..<400: return .blue
        case 400..<500: return .orange
        case 500..<600: return .red
        default: return .secondary
        }
    }
}
