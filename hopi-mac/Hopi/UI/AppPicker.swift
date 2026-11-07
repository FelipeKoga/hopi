import SwiftUI

/// The attached app in the toolbar; a click lists every app found, grouped by platform.
struct AppPicker: View {
    @Bindable var viewer: Viewer
    @State private var open = false

    private var current: Target? {
        viewer.targets.first { $0.id == viewer.selectedTarget }
    }

    var body: some View {
        Button {
            open.toggle()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: current.map { Self.symbol($0.kind) } ?? "app.dashed")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 0) {
                    Text(current?.app ?? (viewer.scanning ? "Looking for apps…" : "No App"))
                        .font(.system(size: 12, weight: .semibold))
                        .lineLimit(1).truncationMode(.middle)
                    Text(detail)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: 240, alignment: .leading)
                Image(systemName: "chevron.down")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 6)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("app-picker")
        .help("Choose the app to inspect")
        .popover(isPresented: $open, arrowEdge: .bottom) {
            AppList(viewer: viewer) { open = false }
        }
    }

    private var detail: String {
        guard let current else { return viewer.targets.isEmpty ? "Run a debug build with Hopi" : "Pick an app" }
        let state: String
        switch viewer.session?.status {
        case .live: state = "Connected"
        case .connecting: state = "Connecting…"
        case .closed: state = "Disconnected"
        case nil: state = ""
        }
        return [current.device, state].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    static func symbol(_ kind: Target.Kind) -> String {
        switch kind {
        case .android: "smartphone"
        case .iphone: "iphone.gen3"
        case .simulator: "iphone"
        case .jvm: "desktopcomputer"
        }
    }
}

private struct AppList: View {
    @Bindable var viewer: Viewer
    let dismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if viewer.targets.isEmpty {
                ContentUnavailableView {
                    Label("No Apps", systemImage: "app.dashed")
                } description: {
                    Text("Run a debug build with Hopi on Android, an iPhone, an iOS simulator or the JVM.")
                }
                .frame(height: 170)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        group("Android", .android)
                        group("iPhones", .iphone)
                        group("iOS Simulators", .simulator)
                        group("JVM", .jvm)
                    }
                    .padding(10)
                }
                .frame(maxHeight: 360)
                .fixedSize(horizontal: false, vertical: true)
            }
            Divider()
            Button {
                Task { await viewer.scan() }
            } label: {
                HStack {
                    Label("Find Apps", systemImage: "arrow.clockwise")
                    Spacer()
                    if viewer.scanning { ProgressView().controlSize(.mini) } else { Text("⇧⌘R").foregroundStyle(.tertiary) }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(viewer.scanning)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .frame(width: 330)
    }

    @ViewBuilder
    private func group(_ title: String, _ kind: Target.Kind) -> some View {
        let targets = viewer.targets.filter { $0.kind == kind }
        if !targets.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 2)
                ForEach(targets) { target in
                    Row(target: target, selected: target.id == viewer.selectedTarget) {
                        viewer.selectedTarget = target.id
                        dismiss()
                    }
                }
            }
        }
    }
}

private struct Row: View {
    let target: Target
    let selected: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: AppPicker.symbol(target.kind))
                    .frame(width: 18)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 1) {
                    Text(target.app).lineLimit(1).truncationMode(.middle)
                    Text(target.device).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 6)
                if selected {
                    Image(systemName: "checkmark").font(.caption.weight(.bold)).foregroundStyle(.tint)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(hover ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear), in: .rect(cornerRadius: 7))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .accessibilityIdentifier("target:\(target.app)")
    }
}
