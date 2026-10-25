package dev.koga.hopi.core.log

import dev.koga.hopi.core.HopiInternalApi
import dev.koga.hopi.core.internal.oslog.hopi_os_log_bytes
import dev.koga.hopi.core.internal.oslog.hopi_os_log_line
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
@HopiInternalApi
public actual fun emitPlatformLogLine(line: String) {
    hopi_os_log_line(line)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun emitPlatformLogLine(line: StringBuilder) {
    // The runtime's encoder is native; a Kotlin loop into a reused buffer measured slower here.
    val bytes = line.toString().encodeToByteArray()
    if (bytes.isEmpty()) return
    bytes.usePinned { hopi_os_log_bytes(it.addressOf(0), bytes.size) }
}

/**
 * Measured on an iOS 18.2 simulator (see ARCHITECTURE.md): lines up to 1015 UTF-8 bytes arrive
 * intact, longer ones are cut to 1015 bytes plus `<…>`. 1000 keeps a margin for other versions.
 */
@HopiInternalApi
public actual val platformLogLineMaxBytes: Int = 1000
