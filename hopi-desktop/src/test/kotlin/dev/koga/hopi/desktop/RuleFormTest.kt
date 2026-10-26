package dev.koga.hopi.desktop

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.koga.hopi.core.rules.FailKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleFormTest {
    private val inline = { bytes: ByteArray -> """{"text":"${bytes.decodeToString()}"}""" }

    private fun TextFieldState.set(text: String) = setTextAndPlaceCursorAtEnd(text)

    @Test
    fun aMockFromARecordNamesItsExactRequestAndCopiesTheRestInTheApp() {
        val form = RuleForm.mockOf(completeTx(7, url = "https://API.example.com:8443/users/42?page=2", method = "POST"))
        assertEquals("POST https://api.example.com:8443/users/42", form.match.text.toString())
        assertEquals(
            """{"match":{"methods":["POST"],"scheme":"https","host":"api.example.com","port":8443,"path":"/users/42"},"mock":{"fromTransaction":7}}""",
            form.json(inline),
        )
        form.status.set("503")
        form.beforeResponseMs.set("2000")
        assertEquals(
            """{"match":{"methods":["POST"],"scheme":"https","host":"api.example.com","port":8443,"path":"/users/42"},""" +
                """"delay":{"beforeRequestMs":0,"beforeResponseMs":2000},"mock":{"status":503,"fromTransaction":7}}""",
            form.json(inline),
        )
    }

    @Test
    fun failEditAndOptions() {
        val fail = RuleForm(FormKind.Fail).apply {
            match.set("* *.example.com/**?debug")
            failKind = FailKind.RESET
            times.set("2")
            sources.set("okhttp, ktor")
            disabled = true
        }
        assertEquals(
            """{"match":{"host":"*.example.com","query":[{"name":"debug"}],"sources":["ktor","okhttp"]},"times":2,"enabled":false,"fail":"reset"}""",
            fail.json(inline),
        )
        val edit = RuleForm(FormKind.Edit).apply {
            match.set("/v1/**")
            request.host.set("staging.example.com")
            request.setHeaders.add("X-Env", "staging")
            responseRemoveHeaders.set("ETag, Age")
        }
        assertEquals(
            """{"match":{"path":"/v1/**"},"edit":{"request":{"host":"staging.example.com","setHeaders":[["X-Env","staging"]]},""" +
                """"response":{"removeHeaders":["ETag","Age"]}}}""",
            edit.json(inline),
        )
    }

    @Test
    fun theHostRefusesWhatItCanCheck() {
        assertEquals("match: empty", assertFailsWith<IllegalArgumentException> { RuleForm(FormKind.Fail).json(inline) }.message)
        val mock = RuleForm(FormKind.Mock).apply { match.set("/x") }
        assertEquals("status: a mock needs a status", assertFailsWith<IllegalArgumentException> { mock.json(inline) }.message)
        mock.status.set("42")
        assertEquals("status: a number in 100..599, not '42'", assertFailsWith<IllegalArgumentException> { mock.json(inline) }.message)
        val delay = RuleForm(FormKind.Delay).apply {
            match.set("/x")
            beforeRequestMs.set("200000")
        }
        assertEquals("before request: milliseconds in 0..120000, not '200000'", assertFailsWith<IllegalArgumentException> { delay.json(inline) }.message)
        val sources = RuleForm(FormKind.Fail).apply {
            match.set("/x")
            this.sources.set("curl")
        }
        assertEquals(
            "sources: unknown source 'curl' (ktor, okhttp, urlconnection, urlsession)",
            assertFailsWith<IllegalArgumentException> { sources.json(inline) }.message,
        )
    }
}
