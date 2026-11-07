import SwiftUI

/// Apps on the left, the app's requests across the window, the selected one in an inspector on
/// the right. Glass is the system's: sidebar, toolbar and inspector. Rows and bodies stay solid.
struct ContentView: View {
    @Bindable var viewer: Viewer
    @Environment(\.scenePhase) private var phase

    var body: some View {
        NavigationSplitView {
            Sidebar(viewer: viewer)
                .navigationSplitViewColumnWidth(min: 210, ideal: 250, max: 340)
        } detail: {
            RequestList(viewer: viewer)
                .inspector(isPresented: inspectorShown) {
                    Group {
                        if let session = viewer.session, let id = viewer.selectedTransaction {
                            DetailView(session: session, id: id).id(id)
                        }
                    }
                    .inspectorColumnWidth(min: 360, ideal: 460, max: 760)
                }
        }
        .task { await viewer.scan() }
        .onChange(of: phase) { _, now in
            // Apps come and go while the viewer is in the background: look again on return.
            if now == .active, viewer.session == nil { Task { await viewer.scan() } }
        }
    }

    /// The inspector follows the selection; closing it clears the selection.
    private var inspectorShown: Binding<Bool> {
        Binding(get: { viewer.selectedTransaction != nil },
                set: { if !$0 { viewer.selectedTransaction = nil } })
    }
}
