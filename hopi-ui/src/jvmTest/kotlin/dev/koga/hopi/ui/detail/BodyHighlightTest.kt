package dev.koga.hopi.ui.detail

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class BodyHighlightTest {
    private val palette = JsonPalette(
        key = Color.Blue, string = Color.Green, number = Color.Yellow, literal = Color.Magenta,
        punctuation = Color.Gray, match = Color.Cyan, currentMatch = Color.Red, onCurrentMatch = Color.White,
    )

    /** Each colored run as `text=color`, in order. */
    private fun runs(piece: String, state: Int = 0): List<String> =
        highlightPiece(piece, state, palette).spanStyles.sortedBy { it.start }.map { piece.substring(it.start, it.end) + "=" + it.item.color }

    @Test
    fun keysValuesAndLiteralsTakeTheirColors() {
        val g = Color.Gray
        assertEquals(
            listOf(
                "{=$g", "\"id\"=${Color.Blue}", ":=$g", "-1.5e3=${Color.Yellow}", ",=$g",
                "\"a \\\" b\"=${Color.Blue}", ":=$g", "\"x:y\"=${Color.Green}", ",=$g",
                "\"n\"=${Color.Blue}", ":=$g", "null=${Color.Magenta}", "}=$g",
            ),
            runs("""{"id": -1.5e3, "a \" b" : "x:y", "n":null}"""),
        )
    }

    @Test
    fun aStringCutAcrossPiecesKeepsItsColor() {
        val pieces = listOf("{\"long\": \"abc\\\"", "def\\\\", "ghi\", \"k\": 1}")
        val states = jsonPieceStates(pieces)
        assertEquals(listOf(0, 1, 1), states.map { it.toInt() })
        // The second piece is all string; the third ends the string, then a key and a number.
        assertEquals(listOf("def\\\\=${Color.Green}"), runs(pieces[1], states[1].toInt()))
        val third = runs(pieces[2], states[2].toInt())
        assertEquals("ghi\"=${Color.Green}", third[0])
        assertEquals("\"k\"=${Color.Blue}", third[2])
    }

    @Test
    fun searchFindsEveryOccurrenceIgnoringCase() {
        val found = findInBody(listOf("Needle needle", "hay", "NEEDLE"), "needle")
        assertEquals(listOf(0 to 0..5, 0 to 7..12, 2 to 0..5), found.map { it.piece to it.range })
        assertEquals(2, findInBody(listOf("aaaa"), "a", limit = 2).size)
        assertEquals(emptyList(), findInBody(listOf("abc"), "").map { it.piece })
    }
}
