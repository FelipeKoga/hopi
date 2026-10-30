import Shared
import SwiftUI

@main
struct IOSApp: App {
    init() {
        // One call, first thing: every URLSession request of the process is recorded from here
        // on, the app's own and those of SDKs it does not own, and the performance monitors are
        // registered for the bridge (`hopi perf start`, the desktop viewer's Perf panel) and for
        // HopiPerf, the code API, none started (each costs nothing until one is;
        // `-hopiPerf frames,hangs` starts monitors at launch). HOPI is set only where the
        // framework has Hopi: the Debug configuration, unless built with HOPI_ENABLED=false
        // (project.yml). A Release framework has no Hopi to call.
        #if HOPI
        Hopi.shared.start { config in
            // The bridge and the caps: Ktor's Darwin requests are recorded by URLSession capture
            // (the plugin installs nothing on iOS), so the giant-body launch arguments
            // (-maxBodyBytes N, -maxTotalBytes N) must reach this call.
            config.agentBridge = true
            if let value = launchArgument("-maxBodyBytes").flatMap(Int64.init) { config.maxBodyBytes = value }
            if let value = launchArgument("-maxTotalBytes").flatMap(Int64.init) { config.maxTotalBytes = value }
        }
        #endif
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

private func launchArgument(_ name: String) -> String? {
    let arguments = ProcessInfo.processInfo.arguments
    guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

/// Hosts the shared Compose UI (`sample/shared`), which handles the safe area itself.
struct SampleView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
