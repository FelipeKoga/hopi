package compat.jvm

import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.rules.HopiRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import dev.koga.hopi.start.Hopi as HopiStart

/**
 * Starts Hopi, then sends one request through Hopi's Ktor plugin to a mock engine, with a mock rule
 * that answers it: the rule answers only if the start registered the rules runtime and the plugin
 * installed the recorder, so a status other than the rule's fails the run. Exits explicitly, since
 * the start leaves Hopi's threads running.
 */
fun main() {
    HopiStart.start { }
    check(HopiRules.list().isEmpty()) { "rules before any was added: ${HopiRules.list()}" }
    // Without a runtime, mock returns -1.
    val rule = HopiRules.mock("GET /floor") { status = 418 }
    check(rule >= 0) { "HopiRules.mock returned $rule after Hopi.start" }

    val engine = MockEngine { respond("from the engine", HttpStatusCode.OK) }
    val client = HttpClient(engine) {
        install(Hopi)
    }
    val status = runBlocking { client.get("http://compat.test/floor").status.value }
    client.close()
    check(status == 418) { "the request was answered $status, not by Hopi's rule (418)" }

    println("compat-jvm: Hopi started, ${HopiRules.list().size} rule, the request through Hopi's Ktor plugin answered $status by the rule")
    exitProcess(0)
}
