import AppKit
import SwiftUI

/// The attached app's requests, oldest first, filtered by the toolbar's search and status menu.
struct RequestList: View {
    @Bindable var viewer: Viewer
    let session: Session
    @FocusState private var searchFocused: Bool

    var body: some View {
        table(session)
            .searchable(text: $viewer.search, placement: .toolbar, prompt: "Filter by URL, method or status")
            .searchFocused($searchFocused)
            .onChange(of: viewer.findRequests) { searchFocused = true }
            .toolbar {
                ToolbarItem {
                    Text(count)
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .fixedSize()
                }
                .sharedBackgroundVisibility(.hidden)
                ToolbarItem {
                    Menu {
                        Picker("Status", selection: $viewer.statusFilter) {
                            ForEach(Viewer.StatusFilter.allCases) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.inline)
                    } label: {
                        Label("Status", systemImage: viewer.statusFilter == .all
                              ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
                    }
                    .accessibilityIdentifier("status-filter")
                    .help("Show only these responses")
                }
                ToolbarItem {
                    Button {
                        Task { await session.clear() }
                    } label: {
                        Label("Clear", systemImage: "trash")
                    }
                    .help("Clear the app's requests (⌘K)")
                    .disabled(session.transactions.isEmpty)
                }
            }
    }

    private var count: String {
        let total = session.transactions.count
        let shown = viewer.visibleTransactions.count
        let noun = total == 1 ? "request" : "requests"
        return shown == total ? "\(total) \(noun)" : "\(shown) of \(total) \(noun)"
    }

    private func table(_ session: Session) -> some View {
        ScrollViewReader { proxy in
            rows(session)
                // Follow the newest request, unless one is open.
                .onChange(of: viewer.visibleTransactions.last?.id) { _, last in
                    guard let last, viewer.selectedTransaction == nil else { return }
                    proxy.scrollTo(last, anchor: .bottom)
                }
        }
    }

    private func rows(_ session: Session) -> some View {
        Table(viewer.visibleTransactions, selection: $viewer.selectedTransaction) {
            TableColumn("Status") { StatusBadge(code: $0.code, state: $0.state) }
                .width(58)
            TableColumn("Method") { summary in
                Text(summary.method)
                    .font(.system(.callout, design: .monospaced).weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .width(min: 52, ideal: 60, max: 80)
            TableColumn("Path") { summary in
                HStack(spacing: 6) {
                    PathText(url: summary.url)
                    if let applied = summary.applied {
                        RuleTag(text: applied.replacingOccurrences(of: ",", with: " + "))
                    }
                }
            }
            .width(min: 140, ideal: 340)
            TableColumn("Host") { summary in
                Text(Format.host(summary.url))
                    .foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
            }
            .width(min: 70, ideal: 140, max: 240)
            TableColumn("Time") { summary in
                Text(summary.ms.map(Format.duration) ?? "…")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle((summary.ms ?? 0) >= 1000 ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 56, ideal: 64, max: 84)
            TableColumn("Size") { summary in
                Text(Format.bytes(summary.resBytes))
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 52, ideal: 60, max: 90)
        }
        .contextMenu(forSelectionType: Summary.ID.self) { ids in
            if let id = ids.first, let summary = session.transactions.last(where: { $0.id == id }) {
                Button("Mock This Response…") {
                    viewer.draftMock = summary
                    viewer.mode = .rules
                }
                .disabled(summary.state != .complete)
                Divider()
                Button("Copy URL") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(summary.url, forType: .string)
                }
            }
        }
        .tableStyle(.inset)
        .alternatingRowBackgrounds(.disabled)
        .overlay {
            if viewer.visibleTransactions.isEmpty {
                if session.transactions.isEmpty {
                    ContentUnavailableView("Waiting for Requests", systemImage: "antenna.radiowaves.left.and.right",
                                           description: Text("Requests show up here as the app makes them."))
                } else {
                    ContentUnavailableView.search(text: viewer.search)
                }
            }
        }
    }
}

/// What a rule did to a request: `mock`, `fail`, `delay + edit-response`.
struct RuleTag: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.purple)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(.purple.opacity(0.14), in: .capsule)
            .fixedSize()
            .help("Changed by a rule")
    }
}

/// The path in full color, its query dimmed.
private struct PathText: View {
    let url: String

    var body: some View {
        let path = Format.path(url)
        let split = path.firstIndex(of: "?")
        let base = split.map { String(path[..<$0]) } ?? path
        let query = split.map { String(path[$0...]) } ?? ""
        Text("\(base)\(Text(query).foregroundStyle(.tertiary))")
            .font(.system(.body, design: .monospaced))
            .lineLimit(1)
            .truncationMode(.middle)
    }
}

/// The status code as a small tinted pill: the only color in a row.
struct StatusBadge: View {
    let code: Int?
    let state: TransactionState

    var body: some View {
        Group {
            if state == .pending {
                ProgressView().controlSize(.mini)
            } else {
                Text(label)
                    .font(.system(.caption, design: .monospaced).weight(.semibold))
                    .foregroundStyle(color)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(color.opacity(0.15), in: .capsule)
            }
        }
        .frame(minWidth: 44, alignment: .leading)
    }

    private var label: String {
        state == .failed ? "ERR" : code.map(String.init) ?? "—"
    }

    private var color: Color { Self.color(code: code, state: state) }

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
