package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi

/** Process exit codes; part of the CLI's contract with scripts and agents. */
@HopiInternalApi
public object ExitCode {
    public const val OK: Int = 0
    public const val USAGE: Int = 1
    public const val NOT_REACHABLE: Int = 2
    public const val NOT_FOUND: Int = 3

    /** The app cannot do it: rules off or absent in its Hopi, no perf monitors, a monitor its platform lacks. */
    public const val UNSUPPORTED: Int = 4
}

/** Ends the command with [exitCode]; [message] is the one line printed to stderr. */
@HopiInternalApi
public open class CliException(public val exitCode: Int, message: String) : Exception(message)

/**
 * The bridge disconnected a `tail` that fell behind (`SLOW_CLIENT`): tailing again with [since]
 * (null when the bridge did not say) loses nothing the client was owed.
 */
@HopiInternalApi
public class SlowClientException(public val since: Long?, message: String) : CliException(ExitCode.NOT_REACHABLE, message)

@HopiInternalApi
public fun usageError(message: String): CliException = CliException(ExitCode.USAGE, message)

@HopiInternalApi
public fun notReachable(message: String): CliException = CliException(ExitCode.NOT_REACHABLE, message)

/** Where an app's bridge runs; [id] is what `hello` reports as `platform`. */
@HopiInternalApi
public enum class Platform(public val id: String) {
    Android("android"),
    Ios("ios"),
    Jvm("jvm"),
}

/**
 * [text] safe to write to a terminal: C0 controls, DEL and C1 controls become `\uXXXX`, so bytes a
 * server put in a body, header or error cannot move the cursor, rewrite lines or reach OSC
 * sequences (clipboard writes, titles). [multiline] keeps `\n` and `\t` (bodies); a single-line
 * field escapes them too. Applied to every bridge value human output prints; `--json` prints the
 * bridge's own escaped JSON instead.
 */
@HopiInternalApi
public fun visible(text: String, multiline: Boolean = false): String {
    if (text.none { needsEscape(it, multiline) }) return text
    return buildString(text.length + 16) {
        for (c in text) {
            if (needsEscape(c, multiline)) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
}

private fun needsEscape(c: Char, multiline: Boolean): Boolean = when {
    c == '\n' || c == '\t' -> !multiline
    c < ' ' -> true
    else -> c in '\u007f'..'\u009f'
}
