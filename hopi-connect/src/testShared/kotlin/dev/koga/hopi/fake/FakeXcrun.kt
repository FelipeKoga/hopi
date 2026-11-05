package dev.koga.hopi.fake

import dev.koga.hopi.connect.ProcessResult
import dev.koga.hopi.connect.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections

/**
 * `/usr/bin/xcrun` for simctl and devicectl, answered from memory; any other program goes to
 * [others]. `simctl list devices booted -j` prints [simulators]; `devicectl list devices
 * --json-output <file>` writes [devicectl] to that file (null fails); `devicectl device copy from`
 * writes what [appFile] returns for (device, bundle id, source) to its `--destination`, and fails
 * with [copyError] when that is null; while [copyWith] is set, a copy runs it on the destination
 * instead and succeeds. Every command is kept in [calls], with what each copy's destination
 * directory held in [copiedInto] so a test can check it is gone afterwards.
 *
 * Compiled into the tests of hopi-connect, the CLI and the desktop viewer.
 */
internal class FakeXcrun(
    @Volatile var simulators: String = NO_SIMULATORS,
    @Volatile var devicectl: String? = null,
    @Volatile var appFile: (device: String, bundleId: String, source: String) -> String? = { _, _, _ -> null },
    @Volatile var copyError: String = "ERROR: fake devicectl: no such file in the app's data container",
    @Volatile var copyWith: ((destination: Path) -> Unit)? = null,
    private val others: ProcessRunner = ProcessRunner { ProcessResult(1, "", "unexpected command") },
) : ProcessRunner {
    val calls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    val copiedInto: MutableList<Path> = Collections.synchronizedList(mutableListOf())

    /** The devicectl argument arrays, the program dropped. */
    val devicectlCalls: List<List<String>> get() = synchronized(calls) { calls.filter { it.getOrNull(1) == "devicectl" }.map { it.drop(1) } }

    override fun run(command: List<String>): ProcessResult {
        if (command.firstOrNull() != XCRUN) return others.run(command)
        calls += command
        val args = command.drop(1)
        return when {
            args == listOf("simctl", "list", "devices", "booted", "-j") -> ProcessResult(0, simulators, "")
            args.take(3) == listOf("devicectl", "list", "devices") -> {
                val json = devicectl ?: return ProcessResult(1, "", "ERROR: fake devicectl: cannot list devices")
                Files.writeString(Paths.get(args.valueOf("--json-output")), json)
                ProcessResult(0, "", "")
            }
            args.take(4) == listOf("devicectl", "device", "copy", "from") -> {
                val destination = Paths.get(args.valueOf("--destination"))
                val write = copyWith
                if (write != null) {
                    copiedInto.add(destination.parent)
                    write(destination)
                    return ProcessResult(0, "Copied 1 file.\n", "")
                }
                val text = appFile(args.valueOf("--device"), args.valueOf("--domain-identifier"), args.valueOf("--source"))
                    ?: return ProcessResult(1, "", copyError)
                // add, not +=: a Path is an Iterable of its names.
                copiedInto.add(destination.parent)
                Files.writeString(destination, text)
                ProcessResult(0, "Copied 1 file.\n", "")
            }
            else -> ProcessResult(1, "", "unexpected: ${args.joinToString(" ")}")
        }
    }

    private fun List<String>.valueOf(flag: String): String = this[indexOf(flag) + 1]

    companion object {
        const val XCRUN: String = "/usr/bin/xcrun"
        const val NO_SIMULATORS: String = """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-2":[]}}"""

        /**
         * `devicectl list devices` JSON (version 5) for one physical device and one simulator, in
         * the shape devicectl 651.13.5 writes for its simulators (no phone was attached to see one).
         */
        fun devicectlList(udid: String, name: String, osVersion: String): String = """
            {"info":{"commandType":"devicectl.list.devices","jsonVersion":5,"outcome":"success"},
             "result":{"devices":[
              {"identifier":"E8064CC7-17C3-4F35-B880-34E31F6E8E9C",
               "properties":{"hardware":{"platform":"iOS","reality":"simulated","udid":"E8064CC7-17C3-4F35-B880-34E31F6E8E9C"},
                             "state":{"name":"A simulator"},"software":{"osVersionNumber":{"stringValue":"18.2"}}}},
              {"identifier":"5B0F1C2D-3E4F-4A5B-8C6D-7E8F9A0B1C2D",
               "properties":{"connection":{"pairingState":"paired","transportType":"wired"},
                             "hardware":{"platform":"iOS","reality":"physical","udid":"$udid"},
                             "state":{"name":"$name"},"software":{"osVersionNumber":{"stringValue":"$osVersion"}}}}
             ]}}
        """.trimIndent()
    }
}
