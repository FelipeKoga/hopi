package dev.koga.hopi.ktor

import dev.koga.hopi.core.rules.FailKind
import kotlinx.io.IOException

internal actual fun platformFailure(kind: FailKind, host: String): Throwable = when (kind) {
    FailKind.UNKNOWN_HOST -> IOException("Could not resolve host: $host")
    else -> IOException("Connection reset by peer")
}
