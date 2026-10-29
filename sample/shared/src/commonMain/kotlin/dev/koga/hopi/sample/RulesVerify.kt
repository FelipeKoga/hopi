package dev.koga.hopi.sample

import dev.koga.hopi.rules.HopiFailure
import dev.koga.hopi.rules.HopiRules
import dev.koga.hopi.rules.HopiSource
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One request a rules scenario sends: the method, the URL, extra headers, and a text body for a POST. */
public class RulesCall(
    public val method: String,
    public val url: String,
    public val headers: List<Pair<String, String>>,
    public val body: String?,
)

/**
 * What the app saw: the status and body it read and one response header ([RulesCall]'s caller names
 * it), or the exception's class and message when the request failed.
 */
public class RulesOutcome(
    public val status: Int,
    public val body: String,
    public val header: String?,
    public val error: String?,
) {
    public companion object {
        public fun failed(error: Throwable): RulesOutcome =
            RulesOutcome(-1, "", null, listOfNotNull(error::class.simpleName, error.message).joinToString(": "))
    }
}

/**
 * One way the sample sends a request, named after the Hopi source that records and rules it:
 * [send] makes [RulesCall] and reports what the app read, including the response header
 * [RULES_HEADER].
 */
public class RulesTarget(
    public val name: String,
    public val source: HopiSource,
    public val send: suspend (RulesCall) -> RulesOutcome,
)

/** The response header the edit-response scenario sets, which every [RulesTarget] reads back. */
public const val RULES_HEADER: String = "X-Hopi-Edited"

/**
 * Ktor through the sample's client. On Android its OkHttp engine is recorded (and ruled) by the
 * instrumented OkHttp interceptor, unless the request names its own `Accept-Encoding`: Ktor then
 * decodes the body itself and its plugin keeps the request, so [passThrough] targets the `ktor`
 * source. On iOS the Darwin engine is recorded by URLSession capture, so the source is `urlsession`
 * either way, and no request of an app with URLSession capture is ruled by the Ktor plugin.
 */
public fun ktorRulesTarget(name: String, source: HopiSource, passThrough: Boolean = false): RulesTarget =
    RulesTarget(name, source) { call -> ktorSend(SampleHttp.client, call, passThrough) }

private suspend fun ktorSend(client: HttpClient, call: RulesCall, passThrough: Boolean): RulesOutcome = try {
    val response = client.request(call.url) {
        method = HttpMethod.parse(call.method)
        call.headers.forEach { (name, value) -> header(name, value) }
        if (passThrough) header(HttpHeaders.AcceptEncoding, "identity")
        call.body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
    }
    RulesOutcome(response.status.value, response.bodyAsText(), response.headers[RULES_HEADER], null)
} catch (e: CancellationException) {
    // A cancel rule ends the call the way Ktor cancels one; only the caller's own cancellation
    // goes on up.
    currentCoroutineContext().ensureActive()
    RulesOutcome.failed(e)
} catch (e: Exception) {
    RulesOutcome.failed(e)
}

/**
 * The in-process rules self-test (docs/design/rules.md, "Device verification plan"): for each
 * target, every action through [HopiRules] (mock, each fail, a before-request delay, a delayed
 * mock, a request edit of path, header and body, a response edit, `times`), each rule limited to
 * the target's source and to its own `rv` query key, so the rules of one scenario never touch
 * another's requests or a rule the host added. One `{"rules":…}` line per scenario under
 * `HopiSample`, with `ok`, what the app saw, and the time; the dev server's log is the other half
 * of the oracle (a mocked or failed request never reaches it, an edited one arrives edited).
 * The rules it adds are removed again, whatever happens. With no rules runtime registered (a
 * release build, or one built without the flag) every rule id is `-1` and nothing applies, so
 * every line says `"ok":false`: the control.
 */
public suspend fun runRulesVerify(targets: List<RulesTarget>): String {
    var failed = 0
    for (target in targets) {
        for (scenario in SCENARIOS) {
            val key = "${target.name}-${scenario.name}"
            val started = TimeSource.Monotonic.markNow()
            val ids = ArrayList<Long>()
            val (ok, outcome, detail) = try {
                scenario.run(target, key) { ids += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A rule the engine refused (IllegalArgumentException, IllegalStateException).
                Triple(false, RulesOutcome.failed(e), "rule refused")
            } finally {
                ids.forEach(HopiRules::remove)
            }
            if (!ok) failed++
            sampleLog(
                buildJsonObject {
                    put("rules", JsonPrimitive(scenario.name))
                    put("target", JsonPrimitive(target.name))
                    put("source", JsonPrimitive(target.source.name))
                    put("ok", JsonPrimitive(ok))
                    put("status", JsonPrimitive(outcome.status))
                    put("body", JsonPrimitive(outcome.body.take(120)))
                    outcome.header?.let { put("header", JsonPrimitive(it)) }
                    outcome.error?.let { put("error", JsonPrimitive(it)) }
                    detail?.let { put("detail", JsonPrimitive(it)) }
                    put("ms", JsonPrimitive(started.elapsedNow().inWholeMilliseconds))
                }.toString(),
            )
        }
    }
    val done = "{\"rules\":\"done\",\"real\":${SampleBuild.realHopi},\"failed\":$failed,\"scenarios\":${targets.size * SCENARIOS.size}}"
    sampleLog(done)
    return done
}

/**
 * Requests for rules the host adds (`hopi mock add --match 'GET /json?rv=host-okhttp'`), one of each
 * kind per target, every one carrying its target's `rv=host-<name>` key: a GET of `/json`, a GET
 * of `/missing` (a 404, or an edit's `/echo`), and a POST of `/echo`. One `{"rulesTarget":…}` line
 * each under `HopiSample`.
 */
public suspend fun runRulesTargets(targets: List<RulesTarget>): String {
    val lines = ArrayList<String>()
    for (target in targets) {
        val key = "host-${target.name}"
        for ((label, call) in listOf(
            "get-json" to RulesCall("GET", "$DEV_SERVER/json?rv=$key", tag(key), null),
            "get-missing" to RulesCall("GET", "$DEV_SERVER/missing?rv=$key", tag(key), null),
            "post-echo" to RulesCall("POST", "$DEV_SERVER/echo?rv=$key", tag(key), """{"from":"$key"}"""),
        )) {
            val started = TimeSource.Monotonic.markNow()
            val outcome = target.send(call)
            val line = buildJsonObject {
                put("rulesTarget", JsonPrimitive(label))
                put("target", JsonPrimitive(target.name))
                put("status", JsonPrimitive(outcome.status))
                put("body", JsonPrimitive(outcome.body.take(120)))
                outcome.header?.let { put("header", JsonPrimitive(it)) }
                outcome.error?.let { put("error", JsonPrimitive(it)) }
                put("ms", JsonPrimitive(started.elapsedNow().inWholeMilliseconds))
            }.toString()
            sampleLog(line)
            lines += line
        }
    }
    return lines.joinToString("\n")
}

private fun tag(key: String): List<Pair<String, String>> = listOf("X-Hopi-Verify" to "rules-$key")

private fun get(path: String, key: String): RulesCall = RulesCall("GET", "$DEV_SERVER$path?rv=$key", tag(key), null)

/** A scenario: adds its rules (reporting each id to `added`), sends, and judges what the app saw. */
private class Scenario(
    val name: String,
    val run: suspend (target: RulesTarget, key: String, added: (Long) -> Unit) -> Triple<Boolean, RulesOutcome, String?>,
)

private const val MOCK_BODY = """{"mocked":"by hopi"}"""
private const val DELAY_MS = 1500L

private fun failScenario(failure: HopiFailure) = Scenario("fail-${failure.name.lowercase()}") { target, key, added ->
    added(HopiRules.fail("GET */json?rv=$key", failure) { sources(target.source) })
    // The error's class and message are the platform's own; the line carries them for the host.
    val outcome = target.send(get("/json", key))
    Triple(outcome.error != null && outcome.status == -1, outcome, null)
}

private val SCENARIOS: List<Scenario> = listOf(
    Scenario("mock") { target, key, added ->
        added(
            HopiRules.mock("GET */json?rv=$key") {
                status = 418
                header("Content-Type", "application/json")
                header(RULES_HEADER, "mock")
                body(MOCK_BODY)
                sources(target.source)
            },
        )
        val outcome = target.send(get("/json", key))
        Triple(outcome.status == 418 && outcome.body == MOCK_BODY && outcome.header == "mock", outcome, null)
    },
    failScenario(HopiFailure.Timeout),
    failScenario(HopiFailure.Reset),
    failScenario(HopiFailure.UnknownHost),
    failScenario(HopiFailure.Cancel),
    Scenario("delay-before-request") { target, key, added ->
        added(
            HopiRules.delay("GET */json?rv=$key") {
                beforeRequest = DELAY_MS.milliseconds
                sources(target.source)
            },
        )
        val started = TimeSource.Monotonic.markNow()
        val outcome = target.send(get("/json", key))
        val ms = started.elapsedNow().inWholeMilliseconds
        Triple(outcome.status == 200 && ms >= DELAY_MS, outcome, "took $ms ms, delay $DELAY_MS ms")
    },
    Scenario("delay-mock") { target, key, added ->
        added(
            HopiRules.mock("GET */json?rv=$key") {
                status = 503
                body("""{"error":"delayed mock"}""")
                delay(beforeResponse = DELAY_MS.milliseconds)
                sources(target.source)
            },
        )
        val started = TimeSource.Monotonic.markNow()
        val outcome = target.send(get("/json", key))
        val ms = started.elapsedNow().inWholeMilliseconds
        Triple(outcome.status == 503 && ms >= DELAY_MS, outcome, "took $ms ms, delay $DELAY_MS ms")
    },
    Scenario("edit-request") { target, key, added ->
        // /missing is a 404; the edit sends it to /echo, with a header the echo shows.
        added(
            HopiRules.edit("GET */missing?rv=$key") {
                request {
                    path = "/echo"
                    setHeader("X-Hopi-Rule", "edited")
                }
                sources(target.source)
            },
        )
        val outcome = target.send(get("/missing", key))
        Triple(outcome.status == 200 && "\"/echo?rv=$key\"" in outcome.body && outcome.body.contains("x-hopi-rule", ignoreCase = true), outcome, null)
    },
    Scenario("edit-request-body") { target, key, added ->
        added(
            HopiRules.edit("POST */echo?rv=$key") {
                request { body("""{"edited":"by hopi"}""") }
                sources(target.source)
            },
        )
        val outcome = target.send(RulesCall("POST", "$DEV_SERVER/echo?rv=$key", tag(key), """{"original":true}"""))
        Triple(outcome.status == 200 && "edited" in outcome.body && "original" !in outcome.body, outcome, null)
    },
    Scenario("edit-response") { target, key, added ->
        val id = HopiRules.edit("GET */json?rv=$key") {
            response {
                status = 203
                setHeader(RULES_HEADER, "response")
                body("""{"edited":"response"}""")
            }
            sources(target.source)
        }
        added(id)
        val unsupported = HopiRules.list().firstOrNull { it.id == id }?.unsupported.orEmpty()
        val outcome = target.send(get("/json", key))
        if (target.source == HopiSource.UrlSession) {
            // Unsupported on URLSession (it would need Hopi to re-issue the request): the rule
            // says so, never matches there, and the app reads the real response.
            Triple(outcome.status == 200 && outcome.header == null && unsupported.isNotEmpty(), outcome, "unsupported: $unsupported")
        } else {
            Triple(outcome.status == 203 && outcome.header == "response" && outcome.body == """{"edited":"response"}""", outcome, null)
        }
    },
    Scenario("times") { target, key, added ->
        added(
            HopiRules.mock("GET */json?rv=$key") {
                status = 418
                body(MOCK_BODY)
                times = 1
                sources(target.source)
            },
        )
        val first = target.send(get("/json", key))
        val second = target.send(get("/json", key))
        Triple(first.status == 418 && second.status == 200, second, "first ${first.status}, then ${second.status}")
    },
)
