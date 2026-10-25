package dev.koga.hopi.sample

import androidx.compose.ui.window.ComposeUIViewController
import dev.koga.hopi.rules.HopiSource
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/**
 * The shared sample UI for the SwiftUI host (`sample/iosApp`). Two launch arguments serve the
 * giant-body measurements: `-maxBodyBytes N` sets Hopi's capture cap (`-maxTotalBytes N` its
 * store byte cap, which must be at least twice the capture cap), and `-giant <spec>`
 * runs [runGiant] at launch (see [GiantSpec]), e.g.
 * `xcrun simctl launch --console-pty booted dev.koga.hopi.sample -giant 'mb=1,5;runs=5'`.
 * `-ktorVerify` runs [ktorVerifyAction] at launch instead (the end-to-end capture check),
 * `-verifyRules` the rules self-test through Ktor Darwin (the URLSession shapes run in Swift,
 * `CaptureVerification`, at the same time), and `-rulesTargets` the requests the host's own rules
 * act on. Both rules actions are also buttons in the diagnostics tab.
 */
public fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    fun argument(name: String): String? = arguments.indexOf(name).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    argument("-maxBodyBytes")?.toLongOrNull()?.let { SampleHttp.maxBodyBytes = it }
    argument("-maxTotalBytes")?.toLongOrNull()?.let { SampleHttp.maxTotalBytes = it }
    // Created before the first frame, so the agent bridge is up before the first request.
    SampleHttp.start()
    // Ktor's Darwin engine is recorded and ruled by URLSession capture, never by the Ktor plugin.
    val targets = listOf(ktorRulesTarget("ktor-darwin", HopiSource.UrlSession))
    val rulesVerify = PlatformAction("Rules self-test") { runRulesVerify(targets) }
    val rulesTargets = PlatformAction("Rules targets (for host rules)") { runRulesTargets(targets) }
    val launchAction = argument("-giant")?.let(::giantJsonAction)
        ?: ktorVerifyAction().takeIf { "-ktorVerify" in arguments }
        ?: rulesVerify.takeIf { "-verifyRules" in arguments }
        ?: rulesTargets.takeIf { "-rulesTargets" in arguments }
    return ComposeUIViewController { SampleApp(platformActions = listOf(rulesVerify, rulesTargets), launchAction = launchAction) }
}
