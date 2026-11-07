import SwiftUI

/// The app's requests across the window, the selected one in an inspector on the right, and the
/// attached app chosen from the toolbar. Glass is the system's: toolbar, popover and inspector.
/// Rows and bodies stay solid.
struct ContentView: View {
    @Bindable var viewer: Viewer
    @Environment(\.scenePhase) private var phase

    var body: some View {
        RequestList(viewer: viewer)
            .inspector(isPresented: inspectorShown) {
                Group {
                    if let session = viewer.session, let id = viewer.selectedTransaction {
                        DetailView(session: session, id: id).id(id)
                    }
                }
                .inspectorColumnWidth(min: 360, ideal: 440, max: 760)
            }
            .toolbar {
                ToolbarItem(placement: .navigation) {
                    AppPicker(viewer: viewer)
                }
            }
            .toolbar(removing: .title)
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
