import SwiftUI

/// The attached app, chosen in the toolbar, seen three ways: its requests (the selected one in an
/// inspector on the right), its rules, its performance. Glass is the system's: toolbar, popover
/// and inspector. Rows and bodies stay solid.
struct ContentView: View {
    @Bindable var viewer: Viewer
    @Environment(\.scenePhase) private var phase

    var body: some View {
        content
            .toolbar {
                ToolbarItem(placement: .navigation) {
                    AppPicker(viewer: viewer)
                }
                ToolbarItem(placement: .principal) {
                    Picker("View", selection: $viewer.mode) {
                        ForEach(Viewer.Mode.allCases) { mode in
                            Label(mode.rawValue, systemImage: mode.symbol).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .accessibilityIdentifier("mode-picker")
                    .help("Requests, rules or performance (⌘1, ⌘2, ⌘3)")
                }
            }
            .toolbar(removing: .title)
            .task { await viewer.scan() }
            .onChange(of: phase) { _, now in
                // Apps come and go while the viewer is in the background: look again on return.
                if now == .active, viewer.session == nil { Task { await viewer.scan() } }
            }
    }

    @ViewBuilder
    private var content: some View {
        if let session = viewer.session {
            if case .closed(let reason) = session.status {
                ContentUnavailableView {
                    Label("Disconnected", systemImage: "bolt.horizontal.circle")
                } description: {
                    Text(reason ?? "The app closed the connection.")
                } actions: {
                    Button("Reconnect") { viewer.reconnect() }
                        .buttonStyle(.glassProminent)
                }
            } else {
                switch viewer.mode {
                case .requests:
                    RequestList(viewer: viewer, session: session)
                        .inspector(isPresented: inspectorShown) {
                            Group {
                                if let id = viewer.selectedTransaction {
                                    DetailView(session: session, id: id).id(id)
                                }
                            }
                            .inspectorColumnWidth(min: 360, ideal: 440, max: 760)
                        }
                case .rules:
                    RulesView(viewer: viewer, session: session)
                case .performance:
                    if let perf = viewer.perf {
                        PerfView(perf: perf)
                    }
                }
            }
        } else {
            ContentUnavailableView {
                Label(viewer.targets.isEmpty ? "No Apps Found" : "No App Selected", systemImage: "network")
            } description: {
                Text(viewer.targets.isEmpty
                     ? "Run a debug build with Hopi on an Android emulator, an iOS simulator or the JVM."
                     : "Choose an app in the toolbar.")
            } actions: {
                if viewer.targets.isEmpty {
                    Button("Look Again") { Task { await viewer.scan() } }
                        .disabled(viewer.scanning)
                }
            }
        }
    }

    /// The inspector follows the selection; closing it clears the selection.
    private var inspectorShown: Binding<Bool> {
        Binding(get: { viewer.selectedTransaction != nil },
                set: { if !$0 { viewer.selectedTransaction = nil } })
    }
}
