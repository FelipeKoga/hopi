import Shared
import SwiftUI

@main
struct IOSApp: App {
    init() {
        // One call, first thing, the same in every configuration (the io.github.felipekoga.hopi plugin
        // generates HopiSetup into sample/shared). In a Debug build it starts Hopi with
        // sample/shared's hopi { } block: every URLSession request of the process is recorded from
        // here on, the app's own and those of SDKs it does not own, the bridge is up, and the
        // performance monitors are registered for the bridge (`hopi perf start`, the desktop
        // viewer's Perf panel) and for HopiPerf, the code API, none started (each costs nothing
        // until one is; `-hopiPerf frames,hangs` starts monitors at launch). In a Release build,
        // or one built with -Phopi.enabled=false, it does nothing.
        HopiSetup.shared.start()
        if ProcessInfo.processInfo.arguments.contains("-thirdPartySDK") {
            ThirdPartySDK.run()
        }
        // -verifyCapture, -captureBench, -idleCPU: the end-to-end capture check (CaptureVerification).
        CaptureVerification.runFromLaunchArguments()
    }

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
