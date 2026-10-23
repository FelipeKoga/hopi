package dev.koga.hopi.core.bridge

import dev.koga.hopi.core.HopiInternalApi

/** One request line read by [BridgeLineReader]. */
@HopiInternalApi
public sealed interface BridgeLine {
    public class Text(public val text: String) : BridgeLine

    /** The line exceeded the cap. Its bytes were discarded, never held, through the next newline. */
    public data object TooLong : BridgeLine

    public data object End : BridgeLine
}

/**
 * Splits [connection]'s input into `\n`-terminated lines (a trailing `\r` is dropped). A line is
 * kept only up to [maxChars] UTF-16 chars, so a peer that never sends a newline costs a bounded
 * buffer, never an unbounded one; the cap is enforced here, before anything parses the line.
 */
@HopiInternalApi
public class BridgeLineReader(
    private val connection: BridgeConnection,
    private val maxChars: Int = BridgeCommand.MAX_LINE_LENGTH,
) {
    private val input = ByteArray(READ_CHUNK)
    private var inputPos = 0
    private var inputEnd = 0

    // A UTF-8 code point is at most 4 bytes and 2 chars, so maxChars chars fit in 4 * maxChars bytes.
    private val line = ByteArray(maxChars * 4)

    public fun readLine(): BridgeLine {
        var length = 0
        var chars = 0
        var overflow = false
        while (true) {
            if (inputPos == inputEnd) {
                val read = try {
                    connection.read(input)
                } catch (_: Exception) {
                    -1
                }
                if (read <= 0) return BridgeLine.End
                inputPos = 0
                inputEnd = read
            }
            val b = input[inputPos++]
            if (b == NEWLINE) {
                if (overflow) return BridgeLine.TooLong
                if (length > 0 && line[length - 1] == CARRIAGE_RETURN) length--
                return BridgeLine.Text(line.decodeToString(0, length))
            }
            if (overflow) continue
            if (b == CARRIAGE_RETURN && chars == maxChars) {
                // Possibly the `\r` of a `\r\n` ending a line that is exactly at the cap.
                if (length == line.size) {
                    overflow = true
                } else {
                    line[length++] = b
                    chars++
                }
                continue
            }
            val unit = b.toInt() and 0xFF
            // Continuation bytes add no char; a 4-byte sequence's lead byte adds a surrogate pair.
            chars += when {
                unit and 0xC0 == 0x80 -> 0
                unit >= 0xF0 -> 2
                else -> 1
            }
            if (chars > maxChars) {
                overflow = true
            } else {
                line[length++] = b
            }
        }
    }

    private companion object {
        const val READ_CHUNK = 4096
        const val NEWLINE = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}
