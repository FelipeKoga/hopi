package dev.koga.hopi.rules

import kotlin.time.Duration

/** Accepted and ignored, as in `hopi-rules`. */
public enum class HopiFailure {
    Timeout,
    Reset,
    UnknownHost,
    Cancel,
}

/** Accepted and ignored, as in `hopi-rules`. */
public enum class HopiSource {
    Ktor,
    OkHttp,
    UrlConnection,
    UrlSession,
}

/** Every setting of `hopi-rules` is accepted, kept so that reading it back returns what was set, and otherwise ignored. */
public open class HopiRuleOptions internal constructor() {
    public var label: String? = null
    public var priority: Int = 0
    public var times: Int? = null
    public var enabled: Boolean = true

    public fun sources(vararg sources: HopiSource) {}

    public fun query(name: String, value: String) {}

    public fun hasQuery(name: String) {}

    public fun noQuery(name: String) {}

    public fun matchHeader(name: String, value: String) {}

    public fun hasHeader(name: String) {}

    public fun noHeader(name: String) {}

    public fun delay(beforeRequest: Duration = Duration.ZERO, beforeResponse: Duration = Duration.ZERO) {}
}

public class HopiMock internal constructor() : HopiRuleOptions() {
    public var status: Int? = null

    public fun header(name: String, value: String) {}

    public fun body(text: String) {}

    public fun body(bytes: ByteArray) {}

    public fun from(transactionId: Long) {}
}

public class HopiDelay internal constructor() : HopiRuleOptions() {
    public var beforeRequest: Duration = Duration.ZERO
    public var beforeResponse: Duration = Duration.ZERO
}

public class HopiEdit internal constructor() : HopiRuleOptions() {
    /** Runs [block] on a request edit that is then dropped. */
    public fun request(block: HopiRequestEdit.() -> Unit) {
        HopiRequestEdit().apply(block)
    }

    /** Runs [block] on a response edit that is then dropped. */
    public fun response(block: HopiResponseEdit.() -> Unit) {
        HopiResponseEdit().apply(block)
    }
}

public class HopiRequestEdit internal constructor() {
    public var method: String? = null
    public var host: String? = null
    public var port: Int? = null
    public var path: String? = null

    public fun setQuery(name: String, value: String) {}

    public fun removeQuery(name: String) {}

    public fun setHeader(name: String, value: String) {}

    public fun addHeader(name: String, value: String) {}

    public fun removeHeader(name: String) {}

    public fun body(text: String) {}

    public fun body(bytes: ByteArray) {}
}

public class HopiResponseEdit internal constructor() {
    public var status: Int? = null

    public fun setHeader(name: String, value: String) {}

    public fun addHeader(name: String, value: String) {}

    public fun removeHeader(name: String) {}

    public fun body(text: String) {}

    public fun body(bytes: ByteArray) {}
}

/** Never created by the noop: [HopiRules.list] is always empty. */
public class HopiRuleInfo internal constructor(
    public val id: Long,
    public val label: String?,
    public val enabled: Boolean,
    public val priority: Int,
    public val times: Int?,
    public val hits: Long,
    public val exhausted: Boolean,
    public val match: String,
    public val action: String,
    public val unsupported: List<String>,
) {
    override fun toString(): String = "#$id $match  $action"
}
