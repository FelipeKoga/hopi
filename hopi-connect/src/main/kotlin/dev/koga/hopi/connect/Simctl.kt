package dev.koga.hopi.connect

import dev.koga.hopi.core.HopiInternalApi
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A booted iOS simulator from `simctl list devices booted -j`. [os] is e.g. `iOS 18.2`. */
@HopiInternalApi
public class Simulator(public val udid: String, public val name: String, public val state: String, public val os: String?)

/**
 * Parses `xcrun simctl list devices booted -j`:
 * `{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-2":[{"udid":…,"name":…,"state":"Booted"},…]}}`.
 * Returns only devices whose state is `Booted`; null when the output is not that shape.
 */
internal fun parseBootedSimulators(output: String): List<Simulator>? {
    val root = try {
        Json.parseToJsonElement(output) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    val runtimes = root["devices"] as? JsonObject ?: return null
    return runtimes.flatMap { (runtime, devices) ->
        (devices as? JsonArray).orEmpty().mapNotNull { element ->
            val device = element as? JsonObject ?: return@mapNotNull null
            val udid = device.str("udid") ?: return@mapNotNull null
            val state = device.str("state") ?: return@mapNotNull null
            if (state != "Booted") return@mapNotNull null
            Simulator(udid, device.str("name") ?: udid, state, runtimeName(runtime))
        }
    }
}

/** `com.apple.CoreSimulator.SimRuntime.iOS-18-2` → `iOS 18.2`. */
internal fun runtimeName(identifier: String): String? {
    val last = identifier.substringAfterLast('.')
    val platform = last.substringBefore('-', missingDelimiterValue = "")
    val version = last.substringAfter('-', missingDelimiterValue = "")
    if (platform.isEmpty() || version.isEmpty()) return null
    return "$platform ${version.replace('-', '.')}"
}

/** `xcrun simctl` at its fixed location; never looked up on `PATH`. */
internal class Simctl(private val xcrun: Path, private val processes: ProcessRunner) {
    fun booted(): List<Simulator> {
        val result = run("list", "devices", "booted", "-j")
        if (result.exitCode != 0) throw notReachable("simctl list failed: ${firstLine(result.stderr)}")
        return parseBootedSimulators(result.stdout) ?: throw notReachable("simctl list printed unexpected output")
    }

    /** The app's data container on [simulator]. */
    fun dataContainer(simulator: Simulator, bundleId: String): Path {
        val result = run("get_app_container", simulator.udid, bundleId, "data")
        val path = result.stdout.trim()
        if (result.exitCode != 0 || path.isEmpty() || !path.startsWith("/")) {
            throw notReachable("$bundleId is not installed on simulator ${simulator.name} (${simulator.udid})")
        }
        return Paths.get(path)
    }

    private fun run(vararg args: String): ProcessResult = try {
        processes.run(listOf(xcrun.toString(), "simctl") + args)
    } catch (e: IOException) {
        throw notReachable("cannot run xcrun simctl: ${e.message}")
    }

    companion object {
        val XCRUN: Path = Paths.get("/usr/bin/xcrun")
    }
}
