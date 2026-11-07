package dev.koga.hopi.cli

import dev.koga.hopi.connect.long
import dev.koga.hopi.connect.str
import dev.koga.hopi.connect.usageError
import kotlinx.serialization.json.JsonObject

/**
 * `list` and `tail`'s `--url`, `--method` and `--status`, applied to the bridge's summary lines on
 * the host: an agent asks for the failures of one endpoint instead of paging through all of them.
 * Every flag given must match.
 */
internal class TransactionFilter(
    /** A case-insensitive substring of the URL. */
    private val url: String?,
    /** Upper-case methods, any of them. */
    private val methods: Set<String>?,
    /** Any of them: an exact code, a class (`4xx`), `failed` or `pending`. */
    private val statuses: List<String>?,
) {
    fun matches(summary: JsonObject): Boolean {
        if (url != null && !summary.str("url").orEmpty().contains(url, ignoreCase = true)) return false
        if (methods != null && summary.str("method")?.uppercase() !in methods) return false
        if (statuses != null && statuses.none { statusMatches(it, summary) }) return false
        return true
    }

    private fun statusMatches(status: String, summary: JsonObject): Boolean {
        val state = summary.str("state")
        return when (status) {
            "failed", "pending" -> state == status
            else -> {
                val code = summary.long("code")?.toString() ?: return false
                if (status.endsWith("xx")) code.length == 3 && code[0] == status[0] else code == status
            }
        }
    }

    companion object {
        private val STATUS = Regex("[1-5]xx|[1-5][0-9][0-9]|failed|pending")
        private val METHOD = Regex("[A-Za-z]+")

        /** Null when no filter flag was given; a malformed value fails before connecting. */
        fun of(args: CliArgs): TransactionFilter? {
            val url = args.values["--url"]?.also { if (it.isEmpty()) throw usageError("--url needs text to look for") }
            val methods = args.values["--method"]?.let { value ->
                value.split(',').map { it.trim() }.onEach {
                    if (!METHOD.matches(it)) throw usageError("--method takes methods separated by commas (GET,POST), not '$value'")
                }.map { it.uppercase() }.toSet()
            }
            val statuses = args.values["--status"]?.let { value ->
                value.split(',').map { it.trim().lowercase() }.onEach {
                    if (!STATUS.matches(it)) throw usageError("--status takes codes, classes, failed or pending separated by commas (404,5xx,failed), not '$value'")
                }
            }
            if (url == null && methods == null && statuses == null) return null
            return TransactionFilter(url, methods, statuses)
        }
    }
}
