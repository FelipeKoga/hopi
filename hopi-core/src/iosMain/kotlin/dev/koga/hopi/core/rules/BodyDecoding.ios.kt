package dev.koga.hopi.core.rules

/** None: the records here are URLSession's and the Ktor plugin's, which [heldCodings] never shows encoded. */
internal actual fun decodeBody(coding: String, bytes: ByteArray, maxBytes: Int): ByteArray? = null
