package compat.kmp

import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.rules.HopiRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/** A Ktor client with Hopi's plugin, as an app installs it: it records only where a runtime registered. */
fun hopiClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(Hopi) {
        maxBodyBytes = 64L * 1024
    }
}

/** A mock rule through hopi-api: its id, or -1 in a build without Hopi's runtime. */
fun mockPing(): Long = HopiRules.mock("/ping") {
    status = 200
    header("Content-Type", "text/plain")
}

/** The rules the runtime holds: none in a build without it. */
fun ruleCount(): Int = HopiRules.list().size
