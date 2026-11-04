package dev.koga.hopi.sample

import androidx.compose.ui.window.ComposeUIViewController
import dev.koga.hopi.rules.HopiSource
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/**
 * The shared sample UI for the SwiftUI host (`sample/iosApp`). The giant-body measurements set
 * Hopi's capture cap at build time (`-Psample.maxBodyBytes=N`, and `-Psample.maxTotalBytes=N` its
 * store byte cap, which must be at least twice the capture cap; the launch arguments
 * `-maxBodyBytes N`/`-maxTotalBytes N` set only the Ktor plugin's, which installs nothing on iOS),
 * and `-giant <spec>` runs [runGiant] at launch (see [GiantSpec]), e.g.
 * `xcrun simctl launch --console-pty booted dev.koga.hopi.sample -giant 'mb=1,5;runs=5'`.
 * `-ktorVerify` runs [ktorVerifyAction] at launch instead (the end-to-end capture check),
 * `-verifyRules` the rules self-test through Ktor Darwin (the URLSession shapes run in Swift,
 * `CaptureVerification`, at the same time), and `-rulesTargets` the requests the host's own rules
 * act on. Both rules actions are also buttons in the diagnostics tab. `-verifyPerf [steps]` opens the
 * perf lab and runs its injections once ([PerfLabRun]; `all`, or a comma list of
 * `jank,hang,dialog,cpu,alloc,memory`), and `-perfBench <spec>` runs [runPerfBench], the perf
 * monitors' overhead workload (benchmark/perf/).
 */
public fun MainViewController(): UIViewController {
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    fun argument(name: String): String? = arguments.indexOf(name).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    argument("-maxBodyBytes")?.toLongOrNull()?.let { SampleHttp.maxBodyBytes = it }
    argument("-maxTotalBytes")?.toLongOrNull()?.let { SampleHttp.maxTotalBytes = it }
    // The client, created before the first frame so the first request does not pay for it. The
    // agent bridge no longer depends on this: HopiSetup.start (iOSApp.swift) starts it first thing.
    SampleHttp.start()
    // Ktor's Darwin engine is recorded and ruled by URLSession capture, never by the Ktor plugin.
    val targets = listOf(ktorRulesTarget("ktor-darwin", HopiSource.UrlSession))
    val rulesVerify = PlatformAction("Rules self-test") { runRulesVerify(targets) }
    val rulesTargets = PlatformAction("Rules targets (for host rules)") { runRulesTargets(targets) }
    val launchAction = argument("-giant")?.let(::giantJsonAction)
        ?: argument("-perfBench")?.let { spec -> PlatformAction("Perf bench") { runPerfBench(spec) } }
        ?: ktorVerifyAction().takeIf { "-ktorVerify" in arguments }
        ?: rulesVerify.takeIf { "-verifyRules" in arguments }
        ?: rulesTargets.takeIf { "-rulesTargets" in arguments }
    // `-verifyPerf` alone (or followed by another flag) runs every step.
    val perfVerify = if ("-verifyPerf" in arguments) argument("-verifyPerf")?.takeUnless { it.startsWith("-") } ?: "all" else null
    return ComposeUIViewController {
        SampleApp(platformActions = listOf(rulesVerify, rulesTargets), launchAction = launchAction, perfVerify = perfVerify)
    }
}
