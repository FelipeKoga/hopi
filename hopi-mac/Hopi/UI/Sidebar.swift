import SwiftUI

struct Sidebar: View {
    @Bindable var viewer: Viewer

    var body: some View {
        List(selection: $viewer.selectedTarget) {
            section("Simulators", kind: .simulator)
            section("JVM", kind: .jvm)
        }
        .overlay {
            if viewer.targets.isEmpty && !viewer.scanning {
                ContentUnavailableView {
                    Label("No Apps", systemImage: "app.dashed")
                } description: {
                    Text("Run a debug build with Hopi on a simulator or the JVM.")
                } actions: {
                    Button("Look Again") { Task { await viewer.scan() } }
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
                    TargetRow(target: target, live: viewer.session?.target == target && viewer.session?.status == .live)
                        .tag(target.id)
                }
            }
        }
    }
}

private struct TargetRow: View {
    let target: Target
    let live: Bool

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 1) {
                Text(target.app).lineLimit(1).truncationMode(.middle)
                Text(target.device).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        } icon: {
            Image(systemName: target.kind == .simulator ? "iphone" : "cup.and.saucer")
                .overlay(alignment: .bottomTrailing) {
                    if live {
                        Circle().fill(.green).frame(width: 6, height: 6).offset(x: 2, y: 2)
                    }
                }
        }
    }
}
