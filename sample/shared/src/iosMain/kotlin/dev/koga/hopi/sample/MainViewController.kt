package dev.koga.hopi.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** The shared sample UI for the SwiftUI host (`sample/iosApp`). */
public fun MainViewController(): UIViewController {
    // Created before the first frame, so the agent bridge is up before the first request.
    SampleHttp.start()
    return ComposeUIViewController { SampleApp() }
}
