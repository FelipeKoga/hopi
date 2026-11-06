package dev.koga.hopi.core.bridge

import dev.koga.hopi.api.HopiInternalApi

/**
 * The per-process secret a token transport's clients send as their first line. Never logged,
 * printed or included in [toString]; it leaves the process only through `bridge.json`.
 */
@HopiInternalApi
public class BridgeToken private constructor(private val value: String) {
    /** Compares in time that depends only on the lengths, never on where [candidate] differs. */
    public fun matches(candidate: String): Boolean {
        var difference = value.length xor candidate.length
        for (i in value.indices) {
            val other = if (i < candidate.length) candidate[i].code else 0
            difference = difference or (value[i].code xor other)
        }
        return difference == 0
    }

    /** Writes the secret as a JSON string value; the only way to read it. */
    internal fun writeTo(out: StringBuilder) {
        out.append(value)
    }

    override fun toString(): String = "BridgeToken(<redacted>)"

    public companion object {
        /** 256 bits, so the 128-bit floor holds with margin. */
        public const val BYTES: Int = 32
        private const val HEX = "0123456789abcdef"

        /** A fresh token from the OS CSPRNG, as lowercase hex. */
        public fun generate(): BridgeToken = fromBytes(secureRandomBytes(BYTES))

        /** The token [hex] (64 lowercase hex characters), as a client read it (tests). */
        public fun of(hex: String): BridgeToken {
            require(hex.length == BYTES * 2 && hex.all { it in HEX }) { "a bridge token is ${BYTES * 2} lowercase hex characters" }
            return BridgeToken(hex)
        }

        internal fun fromBytes(bytes: ByteArray): BridgeToken {
            require(bytes.size >= 16) { "a bridge token needs at least 128 bits" }
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            }
            bytes.fill(0)
            return BridgeToken(out.toString())
        }
    }
}

/** [size] bytes from the OS CSPRNG (`SecRandomCopyBytes` on iOS, `SecureRandom` on the JVM). Throws if it fails. */
internal expect fun secureRandomBytes(size: Int): ByteArray
