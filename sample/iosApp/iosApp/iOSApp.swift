import Shared
import SwiftUI

@main
struct IOSApp: App {
    var body: some Scene {
        WindowGroup {
            SampleView().ignoresSafeArea()
        }
    }
}

/// Hosts the shared Compose UI (`sample/shared`), which handles the safe area itself.
struct SampleView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
