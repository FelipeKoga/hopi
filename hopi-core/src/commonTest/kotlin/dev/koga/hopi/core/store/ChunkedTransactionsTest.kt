package dev.koga.hopi.core.store

import dev.koga.hopi.core.complete
import dev.koga.hopi.core.model.HttpTransaction
import dev.koga.hopi.core.pending
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChunkedTransactionsTest {
    @Test
    fun matchesAPlainListUnderRandomEditsAndEverySnapshotStaysAsItWas() {
        val random = Random(7)
        val chunked = ChunkedTransactions()
        val reference = ArrayList<HttpTransaction>()
        val published = ArrayList<Pair<List<HttpTransaction>, List<HttpTransaction>>>()
        var nextId = 1L
        repeat(20_000) { step ->
            when (val op = random.nextInt(100)) {
                in 0..44 -> pending(nextId++).also { chunked.addLast(it); reference.add(it) }
                in 45..59 -> if (reference.isNotEmpty()) {
                    val index = random.nextInt(reference.size)
                    val replaced = complete(reference[index].id)
                    chunked[index] = replaced
                    reference[index] = replaced
                }
                in 60..69 -> {
                    val index = random.nextInt(reference.size + 1)
                    val inserted = pending(nextId++)
                    chunked.add(index, inserted)
                    reference.add(index, inserted)
                }
                in 70..89 -> if (reference.isNotEmpty()) assertEquals(reference.removeAt(0), chunked.removeFirst())
                in 90..97 -> if (reference.isNotEmpty()) {
                    val index = random.nextInt(reference.size)
                    assertEquals(reference.removeAt(index), chunked.removeAt(index))
                }
                else -> while (reference.isNotEmpty() && op == 99) {
                    reference.removeAt(0)
                    chunked.removeFirst()
                }
            }
            assertEquals(reference.size, chunked.size)
            if (step % 7 == 0) published += chunked.snapshot() to ArrayList(reference)
        }
        for ((snapshot, expected) in published) {
            assertEquals(expected, snapshot, "a published snapshot never changes")
            assertEquals(expected.hashCode(), snapshot.hashCode())
            assertTrue(snapshot == expected && expected == snapshot)
        }
    }

    @Test
    fun snapshotsCompareStructurallyWhetherTheyShareChunksOrNot() {
        val chunked = ChunkedTransactions()
        for (id in 1L..100L) chunked.addLast(pending(id))
        val before = chunked.snapshot()
        assertEquals(before, chunked.snapshot(), "nothing changed")
        chunked[99] = complete(100)
        val after = chunked.snapshot()
        assertNotEquals(before, after)
        chunked[99] = pending(100)
        assertEquals(before, chunked.snapshot(), "equal entries in a copied chunk are equal")
        chunked.removeFirst()
        val shifted = chunked.snapshot()
        assertEquals(before.drop(1), shifted)
        assertFalse(shifted == before)
        assertEquals(emptyList(), ChunkedTransactions().snapshot())
    }

    @Test
    fun anEvictedEntryIsNotReferencedByTheNextSnapshot() {
        val chunked = ChunkedTransactions()
        for (id in 1L..40L) chunked.addLast(pending(id))
        chunked.snapshot()
        val evicted = chunked.removeFirst()
        val next = chunked.snapshot()
        assertTrue(next.none { it === evicted })
        assertEquals((2L..40L).toList(), next.map { it.id })
    }
}
