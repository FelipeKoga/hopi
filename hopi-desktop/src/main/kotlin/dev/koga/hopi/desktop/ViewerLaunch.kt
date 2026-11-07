package dev.koga.hopi.desktop

import dev.koga.hopi.connect.Platform
import dev.koga.hopi.connect.TargetIds

/** What `hopi viewer` asked the window to open on; each part is optional. */
internal data class ViewerLaunch(val app: String?, val platform: Platform?, val device: String?) {
    /** The one ready device this launch can only mean, or null when none or several fit. */
    fun pick(devices: List<Device>): Device? = devices
        .filter { it.ready && it !is JvmBridge }
        .filter { platform == null || it.platform == platform }
        .filter { device == null || it.id == device }
        .singleOrNull()

    companion object {
        /** A value that is missing or malformed is dropped: the window opens anyway, and its picker still works. */
        fun parse(args: List<String>): ViewerLaunch? {
            fun value(flag: String): String? = args.getOrNull(args.indexOf(flag) + 1)?.takeIf { flag in args }
            val app = value("--app")?.takeIf(TargetIds.APP::matches)
            val platform = Platform.entries.firstOrNull { it.id == value("--platform") }
            val device = value("--device")?.takeIf(TargetIds.DEVICE::matches)
            return if (app == null && platform == null && device == null) null else ViewerLaunch(app, platform, device)
        }
    }
}
