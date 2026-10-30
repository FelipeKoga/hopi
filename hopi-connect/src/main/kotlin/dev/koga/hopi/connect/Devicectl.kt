package dev.koga.hopi.connect

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A device `devicectl list devices` lists that is not a simulator. [os] is e.g. `iOS 26.0`. */
internal class CoreDevice(val udid: String, val name: String?, val os: String?)

/**
 * Parses `xcrun devicectl list devices --json-output <file>` (JSON version 5, devicectl 651.13.5):
 * `{"result":{"devices":[{"properties":{"hardware":{"udid":…,"reality":"physical"|"simulated",
 * "platform":"iOS"},"state":{"name":…},"software":{"osVersionNumber":{"stringValue":"26.0"}}},
 * "hardwareProperties":{…},"deviceProperties":{…}},…]}}`. The `properties` dictionary is read
 * first and the deprecated `hardwareProperties` and `deviceProperties` after it, for older Xcodes.
 * Simulators (`reality` `simulated`) are left out. Null when the output is not that shape.
 */
internal fun parseDevicectlDevices(output: String): List<CoreDevice>? {
    val root = try {
        Json.parseToJsonElement(output) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    val devices = (root["result"] as? JsonObject)?.get("devices") as? JsonArray ?: return null
    return devices.mapNotNull { element ->
        val device = element as? JsonObject ?: return@mapNotNull null
        val properties = device["properties"] as? JsonObject
        val hardware = properties?.get("hardware") as? JsonObject
        val legacyHardware = device["hardwareProperties"] as? JsonObject
        val legacyDevice = device["deviceProperties"] as? JsonObject
        if ((hardware?.str("reality") ?: legacyHardware?.str("reality")) == "simulated") return@mapNotNull null
        val udid = hardware?.str("udid") ?: legacyHardware?.str("udid") ?: return@mapNotNull null
        val name = ((properties?.get("state") as? JsonObject)?.str("name") ?: legacyDevice?.str("name"))?.let(::visible)
        val platform = hardware?.str("platform") ?: legacyHardware?.str("platform")
        val version = (((properties?.get("software") as? JsonObject)?.get("osVersionNumber") as? JsonObject)?.str("stringValue"))
            ?: legacyDevice?.str("osVersionNumber")
        CoreDevice(udid, name, if (platform != null && version != null) visible("$platform $version") else null)
    }
}

/**
 * `xcrun devicectl` (Xcode 15 or later) at its fixed location; never looked up on `PATH`. Its
 * JSON goes to a file (`--json-output`), and `device copy from` writes one, so each command runs
 * in a fresh owner-only temporary directory deleted when it returns, or by a shutdown hook
 * ([shutdownHooks]) when the JVM ends meanwhile (SIGINT): a copied `bridge.json` holds the bridge
 * token for that long. Neither the deletion nor the reads follow a link devicectl may have made.
 */
internal class Devicectl(private val xcrun: Path, private val processes: ProcessRunner, private val shutdownHooks: ShutdownHooks) {
    /** The physical devices CoreDevice knows, attached or not. */
    fun devices(): List<CoreDevice> = inTemporaryDirectory { directory ->
        val output = directory.resolve("devices.json")
        val result = run("list", "devices", "--json-output", output.toString())
        if (result.exitCode != 0) throw notReachable("devicectl list devices failed: ${reason(result)}")
        val text = readSmall(output) ?: throw notReachable("devicectl list devices wrote no JSON")
        parseDevicectlDevices(text) ?: throw notReachable("devicectl list devices wrote unexpected JSON")
    }

    /**
     * The text of [source] (relative to the container) in the data container of [bundleId] on
     * device [udid]. When devicectl fails, the [CliException] carries [failed] of its reason.
     */
    fun readAppFile(udid: String, bundleId: String, source: String, failed: (String) -> String): String = inTemporaryDirectory { directory ->
        val destination = directory.resolve(source.substringAfterLast('/'))
        val result = run(
            "device", "copy", "from",
            "--device", udid,
            "--source", source,
            "--destination", destination.toString(),
            "--domain-type", "appDataContainer",
            "--domain-identifier", bundleId,
        )
        if (result.exitCode != 0) throw notReachable(failed(reason(result)))
        // A destination taken as a directory would hold the file under its own name.
        val file = if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) destination.resolve(destination.fileName) else destination
        readSmall(file) ?: throw notReachable(failed("devicectl reported success but copied no file"))
    }

    private fun reason(result: ProcessResult): String = visible(firstLine(result.stderr + "\n" + result.stdout))

    private fun run(vararg args: String): ProcessResult = try {
        processes.run(listOf(xcrun.toString(), "devicectl") + args)
    } catch (e: IOException) {
        throw notReachable("cannot run xcrun devicectl: ${e.message}")
    }

    private fun <T> inTemporaryDirectory(block: (Path) -> T): T {
        val directory = try {
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                Files.createTempDirectory("hopi-devicectl", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createTempDirectory("hopi-devicectl")
            }
        } catch (e: IOException) {
            throw notReachable("cannot create a temporary directory for devicectl: ${e.message}")
        }
        // A SIGINT ends the JVM without running this thread's finally; its shutdown hooks do run.
        val hook = Thread({ deleteTree(directory) }, "hopi-devicectl-cleanup")
        try {
            shutdownHooks.add(hook)
            return block(directory)
        } finally {
            deleteTree(directory)
            shutdownHooks.remove(hook)
        }
    }

    /** A regular file (a link is none) of at most [MAX_FILE_BYTES]; null when it is missing, a link, larger, or unreadable. */
    private fun readSmall(file: Path): String? = try {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) null else Files.readString(file)
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
    }
}

/**
 * Deletes [root] and everything under it, best effort. Links are not followed (`walkFileTree`
 * without `FOLLOW_LINKS`): a link is deleted, never what it points to.
 */
private fun deleteTree(root: Path) {
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = deleteEntry(file)

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = deleteEntry(file)

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = deleteEntry(dir)
            },
        )
    } catch (_: IOException) {
        // Gone already: nothing left to delete.
    }
}

private fun deleteEntry(path: Path): FileVisitResult {
    try {
        Files.deleteIfExists(path)
    } catch (_: IOException) {
        // Left behind; the rest is still deleted.
    }
    return FileVisitResult.CONTINUE
}
