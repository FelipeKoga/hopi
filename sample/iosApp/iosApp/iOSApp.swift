import Shared
import SwiftUI

@main
struct IOSApp: App {
    init() {
        // One call, first thing: every URLSession request of the process is recorded from here
        // on, the app's own and those of SDKs it does not own. In a Release build the framework
        // links hopi-urlsession-noop and this does nothing.
        HopiUrlSession.shared.start { config in
            // The same bridge and caps as the sample's Ktor plugin: Ktor's Darwin requests are
            // recorded here now (the plugin defers to URLSession capture), so the giant-body
            // launch arguments (-maxBodyBytes N, -maxTotalBytes N) must reach this call too.
            config.agentBridge = true
            if let value = launchArgument("-maxBodyBytes").flatMap(Int64.init) { config.maxBodyBytes = value }
            if let value = launchArgument("-maxTotalBytes").flatMap(Int64.init) { config.maxTotalBytes = value }
        }
        // The performance monitors: registered for the bridge (`hopi perf start`), the in-app Perf tab
        // and HUD, none started (each costs nothing until one is). Before the UI, so the launcher sees
        // the controller when it first appears. `-hopiPerf frames,hangs` starts monitors at launch.
        // In a Release build this is hopi-perf-noop and does nothing.
        HopiPerf.shared.install { config in
            config.agentBridge = true
        }
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
