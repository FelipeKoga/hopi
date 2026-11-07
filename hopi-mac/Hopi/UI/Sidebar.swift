import SwiftUI

struct Sidebar: View {
    @Bindable var viewer: Viewer

    var body: some View {
        List(selection: $viewer.selectedTarget) {
            section("Android", kind: .android)
            section("iOS Simulators", kind: .simulator)
            section("JVM", kind: .jvm)
        }
        .overlay {
            if viewer.targets.isEmpty {
                if viewer.scanning {
                    ProgressView("Looking for apps…").controlSize(.small)
                } else {
                    ContentUnavailableView {
                        Label("No Apps", systemImage: "app.dashed")
                    } description: {
                        Text("Run a debug build with Hopi on an emulator, a simulator or the JVM.")
                    } actions: {
                        Button("Look Again") { Task { await viewer.scan() } }
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem {
                Button {
                    Task { await viewer.scan() }
                } label: {
                    Label("Find Apps", systemImage: "arrow.clockwise")
                }
                .help("Find apps with Hopi (⇧⌘R)")
                .disabled(viewer.scanning)
            }
        }
    }

    @ViewBuilder
    private func section(_ title: String, kind: Target.Kind) -> some View {
        let targets = viewer.targets.filter { $0.kind == kind }
        if !targets.isEmpty {
            Section(title) {
                ForEach(targets) { target in
                    TargetRow(target: target, status: viewer.session?.target == target ? viewer.session?.status : nil)
                        .tag(target.id)
                }
            }
        }
    }
}

private struct TargetRow: View {
    let target: Target
    let status: Session.Status?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .frame(width: 22)
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(target.app)
                    .lineLimit(1).truncationMode(.middle)
                Text(target.device)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            if let status {
                Circle()
                    .fill(status == .live ? Color.green : status == .connecting ? Color.yellow : Color.secondary)
                    .frame(width: 7, height: 7)
                    .help(status == .live ? "Connected" : status == .connecting ? "Connecting" : "Disconnected")
            }
        }
        .padding(.vertical, 3)
    }

    private var symbol: String {
        switch target.kind {
        case .android: "smartphone"
        case .simulator: "iphone"
        case .jvm: "desktopcomputer"
        }
    }
}
