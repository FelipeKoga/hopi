import SwiftUI

/// Apps on the left, their requests in the middle, the selected one on the right. Glass is left to
/// the system's own chrome (sidebar, toolbar); the request list and bodies stay solid to read.
struct ContentView: View {
    @Bindable var viewer: Viewer
    @Environment(\.scenePhase) private var phase

    var body: some View {
        NavigationSplitView {
            Sidebar(viewer: viewer)
                .navigationSplitViewColumnWidth(min: 200, ideal: 240, max: 320)
        } content: {
            RequestList(viewer: viewer)
                .navigationSplitViewColumnWidth(min: 420, ideal: 620)
        } detail: {
            if let session = viewer.session, let id = viewer.selectedTransaction {
                DetailView(session: session, id: id)
                    .id(id)
            } else {
                ContentUnavailableView("No Request Selected", systemImage: "arrow.left.arrow.right",
                                       description: Text("Pick a request to see its headers and bodies."))
            }
        }
        .task { await viewer.scan() }
        .onChange(of: phase) { _, now in
            // Apps come and go while the viewer is in the background: look again on return.
            if now == .active, viewer.session == nil { Task { await viewer.scan() } }
        }
    }
}
