package dev.koga.hopi.desktop

import dev.koga.hopi.connect.AdbDevice
import dev.koga.hopi.connect.CliException
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.connect.Simulator
import dev.koga.hopi.connect.TargetIds
import dev.koga.hopi.connect.Targets
import dev.koga.hopi.core.bridge.JvmAgentBridge
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence

/** Something the viewer can reach an app on: an adb device, a booted simulator, a USB iPhone, a JVM process. */
internal sealed interface Device {
    val platform: Platform

    /** What [Targets.open] takes: the adb serial, the simulator or iPhone UDID, the JVM bridge file. */
    val id: String
    val label: String
    val ready: Boolean
}

/** [bridges] are the process names with a Hopi bridge listening right now (see [Targets.androidBridges]). */
internal data class AndroidDevice(val serial: String, val state: String, val model: String?, val bridges: List<String>) : Device {
    override val platform: Platform get() = Platform.Android
    override val id: String get() = serial
    override val label: String get() = if (model != null) "$model ($serial)" else serial
    override val ready: Boolean get() = state == "device"
}

internal data class SimulatorDevice(val udid: String, val name: String, val os: String?) : Device {
    override val platform: Platform get() = Platform.Ios
    override val id: String get() = udid
    override val label: String get() = if (os != null) "$name, $os" else name
    override val ready: Boolean get() = true
}

/** An iPhone attached over USB (usbmuxd); [name] and [os] are devicectl's, when it knows the device. */
internal data class UsbIphoneDevice(val udid: String, val name: String?, val os: String?) : Device {
    override val platform: Platform get() = Platform.Ios
    override val id: String get() = udid
    override val label: String get() = "${name ?: udid} (USB)" + (os?.let { ", $it" } ?: "")
    override val ready: Boolean get() = true
}

/** A JVM process's bridge, by its `bridge.json`; the app id is optional there. */
internal data class JvmBridge(val file: Path) : Device {
    override val platform: Platform get() = Platform.Jvm
    override val id: String get() = file.toString()
    override val label: String get() = "JVM " + (file.parent?.fileName?.toString() ?: file.toString())
    override val ready: Boolean get() = true
}

/** One app on one [device]. [app] is required except on a JVM bridge, where null accepts any. */
internal data class Target(val device: Device, val app: String?) {
    val label: String get() = if (app != null) "$app on ${device.label}" else device.label

    /**
     * The same app on the same device, whatever else adb or the bridge list said about the device
     * since (its model, its state, its bridges change while it stays the one being viewed).
     */
    fun sameAs(other: Target?): Boolean =
        other != null && app == other.app && device.platform == other.device.platform && device.id == other.device.id
}

/** Why [app] cannot be connected to on [device], or null when it can (it reaches subprocess argument arrays). */
internal fun targetProblem(device: Device, app: String?): String? = when {
    app == null && device.platform == Platform.Jvm -> null
    app.isNullOrEmpty() -> "enter the app's id (applicationId, bundle id or process name)"
    !TargetIds.APP.matches(app) -> "'$app' is not an application id, bundle id or process name"
    else -> null
}

/** What [discover] found; [notes] say why a platform is missing (no adb, no Xcode). */
internal class Discovery(val devices: List<Device>, val notes: List<String>) {
    companion object {
        val Empty: Discovery = Discovery(emptyList(), emptyList())
    }
}

/** Where JVM bridges write by default: `<java.io.tmpdir>/hopi/<pid>/bridge.json`. */
internal fun defaultJvmBridgeRoot(): Path = Paths.get(System.getProperty("java.io.tmpdir"), "hopi")

/**
 * Lists every target; blocking (adb, simctl and devicectl subprocesses, usbmuxd), so off the UI
 * thread. A platform whose tool fails is left out with a note instead of failing the others.
 */
internal fun discover(targets: Targets, jvmRoot: Path?, androids: List<AdbDevice>? = null): Discovery {
    val notes = mutableListOf<String>()
    val android = try {
        (androids ?: targets.androidDevices())?.map { device ->
            val bridges = if (device.isReady) androidBridges(targets, device.serial) else emptyList()
            AndroidDevice(device.serial, device.state, device.model, bridges)
        } ?: run {
            notes += "adb not found: set ANDROID_HOME, or put adb on PATH"
            emptyList()
        }
    } catch (e: CliException) {
        notes += e.message.orEmpty()
        emptyList()
    }
    val simulators = try {
        targets.simulators()?.map(::simulatorDevice).orEmpty()
    } catch (e: CliException) {
        notes += e.message.orEmpty()
        emptyList()
    }
    val iphones = try {
        targets.usbIphones()?.map { UsbIphoneDevice(it.udid, it.name, it.os) }.orEmpty()
    } catch (e: CliException) {
        notes += e.message.orEmpty()
        emptyList()
    }
    return Discovery(android + simulators + iphones + jvmBridges(jvmRoot), notes)
}

private fun androidBridges(targets: Targets, serial: String): List<String> = try {
    if (TargetIds.DEVICE.matches(serial)) targets.androidBridges(serial) else emptyList()
} catch (_: CliException) {
    emptyList()
}

private fun simulatorDevice(simulator: Simulator) = SimulatorDevice(simulator.udid, simulator.name, simulator.os)

/** `bridge.json` files one level below [root], newest first. */
internal fun jvmBridges(root: Path?): List<JvmBridge> {
    if (root == null || !Files.isDirectory(root)) return emptyList()
    return try {
        Files.list(root).use { children ->
            children.asSequence()
                .map { it.resolve(JvmAgentBridge.BRIDGE_FILE_NAME) }
                .filter { Files.isRegularFile(it) }
                .sortedByDescending { lastModified(it) }
                .map(::JvmBridge)
                .toList()
        }
    } catch (_: IOException) {
        emptyList()
    }
}

private fun lastModified(file: Path): Long = try {
    Files.getLastModifiedTime(file).toMillis()
} catch (_: IOException) {
    0
}
