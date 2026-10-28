package dev.koga.hopi.core.log

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.json.JsonWriter
import dev.koga.hopi.core.json.TransactionJson

/**
 * `{"hopi":1,"notice":"…"}`: a notice line of the summary log (no `id`, so agents skip it). A file
 * of its own, so the start sequence on Android's main thread loads no other class for it.
 */
@HopiInternalApi
public fun noticeLine(text: String): String = JsonWriter().beginObject()
    .field("hopi", TransactionJson.SUMMARY_VERSION)
    .field("notice", text)
    .endObject()
    .toString()
