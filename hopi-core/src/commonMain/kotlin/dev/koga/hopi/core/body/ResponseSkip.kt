package dev.koga.hopi.core.body

import dev.koga.hopi.core.HopiInternalApi

/** Response bodies no recorder captures, by content type; their bytes are counted, never kept. */
@HopiInternalApi
public object ResponseSkip {
    public const val BINARY: String = "binary content-type"
    public const val STREAMING: String = "streaming"

    /**
     * Why a response body with [contentType] is not captured, or null when it is. The iOS
     * URLSession capture library mirrors this list in Objective-C; a test compares the two.
     */
    public fun reason(contentType: String?): String? {
        if (contentType == null) return null
        // The commonest type first, with no scan: `application/json`, alone or before its parameters.
        val json = JSON.length
        if (contentType.regionMatches(0, JSON, 0, json, ignoreCase = true) && (contentType.length == json || contentType[json] == ';')) return null
        // Called on the request thread for every response: the MIME type is matched in place, and
        // a string is made only for an `application/…` type that is not JSON.
        var from = 0
        var to = contentType.indexOf(';').let { if (it < 0) contentType.length else it }
        while (from < to && contentType[from].isWhitespace()) from++
        while (to > from && contentType[to - 1].isWhitespace()) to--
        // Read-only from here, so the local functions below capture no boxed variable.
        val start = from
        val end = to
        val length = end - start
        fun startsWith(prefix: String) = length >= prefix.length && contentType.regionMatches(start, prefix, 0, prefix.length, ignoreCase = true)
        fun isExactly(type: String) = length == type.length && startsWith(type)
        return when {
            startsWith("text/") -> if (isExactly("text/event-stream")) STREAMING else null
            startsWith("application/") -> when {
                isExactly("application/json") -> null
                startsWith("application/grpc+") -> BINARY
                contentType.substring(start, end).lowercase() in binaryApplicationTypes -> BINARY
                else -> null
            }
            isExactly("image/svg+xml") -> null
            startsWith("image/") || startsWith("video/") || startsWith("audio/") || startsWith("font/") -> BINARY
            else -> null
        }
    }

    private const val JSON = "application/json"

    /** The binary `application/…` types, for the mirror test. */
    public val binaryApplicationTypes: Set<String> = setOf(
        "application/octet-stream",
        "application/zip",
        "application/gzip",
        "application/x-gzip",
        "application/x-bzip2",
        "application/x-tar",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/zstd",
        "application/pdf",
        "application/wasm",
        "application/protobuf",
        "application/x-protobuf",
        "application/vnd.google.protobuf",
        "application/grpc",
        "application/msgpack",
        "application/x-msgpack",
        "application/cbor",
        "application/vnd.android.package-archive",
    )
}
