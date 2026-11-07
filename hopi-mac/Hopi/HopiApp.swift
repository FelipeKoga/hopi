import SwiftUI

@main
struct HopiApp: App {
    @State private var viewer = Viewer()

    var body: some Scene {
        WindowGroup("Hopi") {
            ContentView(viewer: viewer)
                .frame(minWidth: 960, minHeight: 560)
        }
        .defaultSize(width: 1280, height: 800)
        .commands {
            CommandGroup(after: .newItem) {
                Button("Find Apps") { Task { await viewer.scan() } }
                    .keyboardShortcut("r", modifiers: [.command, .shift])
                Button("Reconnect") { viewer.reconnect() }
                    .keyboardShortcut("r")
                    .disabled(viewer.session == nil)
                Divider()
                Button("Clear Requests") { Task { await viewer.session?.clear() } }
                    .keyboardShortcut("k")
                    .disabled(viewer.session == nil)
            }
        }
    }
}
