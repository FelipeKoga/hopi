package dev.koga.hopi.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/**
 * The shared sample UI for the SwiftUI host (`sample/iosApp`). Two launch arguments serve the
 * giant-body measurements: `-maxBodyBytes N` sets Hopi's capture cap (`-maxTotalBytes N` its
 * store byte cap, which must be at least twice the capture cap), and `-giant <spec>`
 * runs [runGiant] at launch (see [GiantSpec]), e.g.
 * `xcrun simctl launch --console-pty booted dev.koga.hopi.sample -giant 'mb=1,5;runs=5'`.
 */
public fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    fun argument(name: String): String? = arguments.indexOf(name).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    argument("-maxBodyBytes")?.toLongOrNull()?.let { SampleHttp.maxBodyBytes = it }
    argument("-maxTotalBytes")?.toLongOrNull()?.let { SampleHttp.maxTotalBytes = it }
    // Created before the first frame, so the agent bridge is up before the first request.
    SampleHttp.start()
    val launchAction = argument("-giant")?.let(::giantJsonAction)
    return ComposeUIViewController { SampleApp(launchAction = launchAction) }
}
