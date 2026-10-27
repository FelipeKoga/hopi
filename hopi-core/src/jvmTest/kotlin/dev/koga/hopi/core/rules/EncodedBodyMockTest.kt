package dev.koga.hopi.core.rules

import dev.koga.hopi.core.model.CapturedBody
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.model.TransactionSource
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A mock copied from a record that holds its response body encoded: an OkHttp or
 * `HttpURLConnection` request that named its own `Accept-Encoding` is recorded with the server's
 * bytes, and a mock is delivered decoded (docs/design/rules.md, "Actions").
 */
class EncodedBodyMockTest {
    private val json = """{"users":[{"id":1,"name":"Ada"}]}""".encodeToByteArray()
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Record 42 as the recorder of [source] leaves it for a request that asked for [encoding] itself. */
    private fun record(held: ByteArray, encoding: String, source: String? = TransactionSource.OKHTTP) =
        HttpTransaction.pending(42, 0, "GET", "https://api.example.com/users", listOf("Accept-Encoding" to encoding), null, source = source)
            .completed(
                200,
                listOf("Content-Type" to "application/json", "Content-Encoding" to encoding, "Content-Length" to held.size.toString(), "X-Kept" to "1"),
                CapturedBody(held, held.size.toLong(), truncated = false, "application/json", null),
                5,
                "h2",
            )

    private fun mock(from: HttpTransaction, body: RuleBody? = null) = RuleJson.mockFromTransaction(from, status = null, headers = null, body = body)

    @Test
    fun aGzipBodyIsCopiedDecodedWithoutContentEncoding() {
        for (source in listOf(TransactionSource.OKHTTP, TransactionSource.URL_CONNECTION)) {
            for (encoding in listOf("gzip", "x-gzip", "GZIP", "identity, gzip")) {
                val copied = assertIs<RuleParse.Ok<MockSpec>>(mock(record(gzip(json), encoding, source)), "$source $encoding").value
                assertContentEquals(json, copied.body?.bytes, "$source $encoding")
                // Content-Encoding goes with the encoding; each stack computes Content-Length from the decoded body.
                assertEquals(listOf("Content-Type" to "application/json", "X-Kept" to "1"), copied.headers, "$source $encoding")
            }
        }
        // The bridge's `fromTransaction` (the desktop's "Mock this response", the CLI's `--from`) builds it the same way in the app.
        val context = object : RuleJsonContext {
            override fun blob(id: Long): ByteArray? = null

            override fun transaction(id: Long): HttpTransaction? = record(gzip(json), "gzip").takeIf { id == 42L }
        }
        val parsed = assertIs<RuleParse.Ok<RuleSpec>>(RuleJson.parseRule(mapOf("mock" to mapOf("fromTransaction" to 42L)), context)).value
        assertContentEquals(json, parsed.mock?.body?.bytes)
        assertNull(RuleJson.encodedBodyRefusal(record(gzip(json), "gzip")))
    }

    @Test
    fun aDeflateBodyIsCopiedDecodedWithOrWithoutTheZlibWrapper() {
        for (bare in listOf(false, true)) {
            val copied = assertIs<RuleParse.Ok<MockSpec>>(mock(record(deflate(json, bare), "deflate")), "bare=$bare").value
            assertContentEquals(json, copied.body?.bytes, "bare=$bare")
            assertEquals(listOf("Content-Type" to "application/json", "X-Kept" to "1"), copied.headers)
        }
    }

    @Test
    fun anEncodingThatIsNotDecodedIsRefusedUnlessTheRuleGivesTheBody() {
        for ((encoding, named) in listOf("br" to "br", "zstd" to "zstd", "gzip, br" to "gzip, br", "identity, compress" to "compress")) {
            val from = record(json, encoding)
            val refused = assertIs<RuleParse.Failed>(mock(from), encoding)
            assertEquals(RuleErrorCode.BAD_REQUEST, refused.error.code)
            assertEquals("mock.fromTransaction: response body is encoded (Content-Encoding: $named); give a body", refused.error.message)
            assertEquals("response body is encoded (Content-Encoding: $named)", RuleJson.encodedBodyRefusal(from))
            val given = assertIs<RuleParse.Ok<MockSpec>>(mock(from, body = RuleBody.text("plain")), encoding).value
            assertEquals("plain", given.body?.bytes?.decodeToString())
        }
        // Two Content-Encoding lines are two codings, stacked.
        val twice = record(gzip(gzip(json)), "gzip").let { it.copy(responseHeaders = it.responseHeaders + ("Content-Encoding" to "gzip")) }
        assertEquals("response body is encoded (Content-Encoding: gzip, gzip)", RuleJson.encodedBodyRefusal(twice))
        assertIs<RuleParse.Failed>(mock(twice))
        // Bytes that are not what the header says, or a stream cut short, do not decode.
        val cutGzip = gzip(json).let { it.copyOf(it.size - 4) }
        val cutDeflate = deflate(json, bare = false).let { it.copyOf(it.size / 2) }
        for ((held, encoding) in listOf(json to "gzip", cutGzip to "gzip", cutDeflate to "deflate")) {
            val refused = assertIs<RuleParse.Failed>(mock(record(held, encoding)), "${held.size} B $encoding")
            assertEquals(RuleErrorCode.BAD_REQUEST, refused.error.code)
            assertEquals("mock.fromTransaction: response body does not decode as $encoding; give a body", refused.error.message)
        }
        // A record without a body has nothing to decode: a HEAD, a 304.
        val empty = record(ByteArray(0), "br")
        assertNull(assertIs<RuleParse.Ok<MockSpec>>(mock(empty)).value.body)
        assertNull(RuleJson.encodedBodyRefusal(empty))
    }

    @Test
    fun aBodyThatDecodesPastTheMockBodyLimitIsRefused() {
        val atLimit = ByteArray(RuleLimits.MAX_BODY_BYTES) { 'a'.code.toByte() }
        assertEquals(RuleLimits.MAX_BODY_BYTES, assertIs<RuleParse.Ok<MockSpec>>(mock(record(gzip(atLimit), "gzip"))).value.body?.size)
        // One byte past the limit, and two bombs: 16 MiB of zeros in a few KB, of which decoding reads one byte past the limit.
        val pastLimit = ByteArray(RuleLimits.MAX_BODY_BYTES + 1) { 'a'.code.toByte() }
        val bombs = listOf(bomb(::GZIPOutputStream) to "gzip", bomb(::DeflaterOutputStream) to "deflate")
        for ((held, encoding) in listOf(gzip(pastLimit) to "gzip", deflate(pastLimit, bare = false) to "deflate") + bombs) {
            val refused = assertIs<RuleParse.Failed>(mock(record(held, encoding)), "${held.size} B $encoding")
            assertEquals(RuleErrorCode.LIMIT, refused.error.code)
            assertEquals("mock.fromTransaction: decoded response body larger than ${RuleLimits.MAX_BODY_BYTES} bytes", refused.error.message)
            assertEquals(RuleLimits.MAX_BODY_BYTES + 1, decodeBody(encoding, held, RuleLimits.MAX_BODY_BYTES)?.size, "${held.size} B $encoding")
        }
    }

    @Test
    fun decodingABombAllocatesAboutTwiceTheLimit() {
        for ((bomb, encoding) in listOf(bomb(::GZIPOutputStream) to "gzip", bomb(::DeflaterOutputStream) to "deflate")) {
            decodeBody(encoding, bomb, RuleLimits.MAX_BODY_BYTES) // warm-up
            val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
            val decoded = decodeBody(encoding, bomb, RuleLimits.MAX_BODY_BYTES)
            val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
            assertEquals(RuleLimits.MAX_BODY_BYTES + 1, decoded?.size, encoding)
            // One array, doubling from 8 KB with its last doubling straight to the limit and a byte:
            // twice the limit in all. A ByteArrayOutputStream doubled to twice the limit for that
            // byte and was copied out (5x); doubling to the limit, then once more for the byte, is 3x.
            assertTrue(allocated < 2L * RuleLimits.MAX_BODY_BYTES + 512 * 1024, "$encoding: decoding allocated ${allocated.toDouble() / RuleLimits.MAX_BODY_BYTES}x the limit")
        }
    }

    @Test
    fun ktorAndUrlSessionRecordsAreCopiedAsHeld() {
        // Their recorders keep what the app's decoder handed it, under the raw Content-Encoding.
        for (source in listOf(TransactionSource.KTOR, TransactionSource.URL_SESSION, null)) {
            val copied = assertIs<RuleParse.Ok<MockSpec>>(mock(record(json, "gzip", source)), "$source").value
            assertContentEquals(json, copied.body?.bytes, "$source")
            assertEquals(listOf("Content-Type" to "application/json", "X-Kept" to "1"), copied.headers, "$source")
            assertNull(RuleJson.encodedBodyRefusal(record(json, "br", source)), "$source")
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** 16 MiB of zeros written through [encoder] (gzip, or deflate's zlib stream): a few KB. */
    private fun bomb(encoder: (OutputStream) -> OutputStream): ByteArray {
        val zeros = ByteArray(64 * 1024)
        return ByteArrayOutputStream().also { out -> encoder(out).use { stream -> repeat(256) { stream.write(zeros) } } }.toByteArray()
    }

    /** [bytes] as a zlib stream, or as the bare deflate data some servers send for `deflate`. */
    private fun deflate(bytes: ByteArray, bare: Boolean): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, bare)
        return try {
            ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, deflater).use { it.write(bytes) } }.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
