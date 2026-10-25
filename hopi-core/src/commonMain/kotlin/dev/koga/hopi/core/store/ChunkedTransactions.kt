package dev.koga.hopi.core.store

import dev.koga.hopi.core.model.HttpTransaction

/**
 * The store's entries, kept by its consumer in fixed-size chunks that published snapshots share.
 * Publishing ([snapshot]) copies only the chunk index (one reference per [CHUNK] entries) and marks
 * every chunk shared; a later write to a shared chunk copies that chunk first (copy on write). The
 * common updates touch the last chunk (a request starts or ends) and the first (eviction), so one
 * update copies about two chunks plus the index, about 80 references for 500 entries, instead of
 * the whole list. Snapshots compare in time proportional to the chunks that differ ([Snapshot.equals]),
 * which is what `StateFlow` does on every update. Consumer thread only; snapshots are immutable.
 */
internal class ChunkedTransactions {
    private var chunks: Array<Array<HttpTransaction?>?> = arrayOfNulls(4)
    private var chunkCount = 0

    // Chunks created or copied since the last snapshot: writable in place.
    private var owned = BooleanArray(4)

    /** Index of element 0 in the first chunk. */
    private var start = 0

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun isNotEmpty(): Boolean = size != 0

    operator fun get(index: Int): HttpTransaction {
        val position = start + index
        return chunks[position shr SHIFT]!![position and MASK]!!
    }

    operator fun set(index: Int, transaction: HttpTransaction) {
        val position = start + index
        writable(position shr SHIFT)[position and MASK] = transaction
    }

    fun first(): HttpTransaction = get(0)

    fun addLast(transaction: HttpTransaction) {
        val position = start + size
        val chunk = position shr SHIFT
        if (chunk == chunkCount) appendChunk()
        writable(chunk)[position and MASK] = transaction
        size++
    }

    /** Inserts at [index], shifting the later entries up by one (ids arrive slightly out of order: rare, near the end). */
    fun add(index: Int, transaction: HttpTransaction) {
        if (index == size) {
            addLast(transaction)
            return
        }
        addLast(get(size - 1))
        var i = size - 2
        while (i > index) {
            set(i, get(i - 1))
            i--
        }
        set(index, transaction)
    }

    fun removeFirst(): HttpTransaction {
        val removed = get(0)
        writable(0)[start] = null
        start++
        size--
        if (start == CHUNK || size == 0) dropFirstChunk()
        return removed
    }

    /** Removes the entry at [index], shifting the later ones down by one. */
    fun removeAt(index: Int): HttpTransaction {
        if (index == 0) return removeFirst()
        val removed = get(index)
        for (i in index until size - 1) set(i, get(i + 1))
        val last = start + size - 1
        writable(last shr SHIFT)[last and MASK] = null
        size--
        if (((start + size) and MASK) == 0 && (start + size) shr SHIFT == chunkCount - 1) {
            chunks[--chunkCount] = null
        }
        return removed
    }

    /** The entries as an immutable list; the chunks become shared, so later writes copy them first. */
    fun snapshot(): List<HttpTransaction> {
        if (size == 0) return emptyList()
        owned.fill(false, 0, chunkCount)
        @Suppress("UNCHECKED_CAST")
        return Snapshot(chunks.copyOf(chunkCount) as Array<Array<HttpTransaction?>>, start, size)
    }

    private fun writable(chunk: Int): Array<HttpTransaction?> {
        val current = chunks[chunk]!!
        if (owned[chunk]) return current
        val copy = current.copyOf()
        chunks[chunk] = copy
        owned[chunk] = true
        return copy
    }

    private fun appendChunk() {
        if (chunkCount == chunks.size) {
            chunks = chunks.copyOf(chunkCount * 2)
            owned = owned.copyOf(chunkCount * 2)
        }
        chunks[chunkCount] = arrayOfNulls(CHUNK)
        owned[chunkCount] = true
        chunkCount++
    }

    private fun dropFirstChunk() {
        if (chunkCount == 0) return
        chunks.copyInto(chunks, 0, 1, chunkCount)
        owned.copyInto(owned, 0, 1, chunkCount)
        chunks[--chunkCount] = null
        start = 0
        if (size == 0) {
            while (chunkCount > 0) chunks[--chunkCount] = null
        }
    }

    /** One published state of the entries: immutable, sharing its chunks with the snapshots around it. */
    private class Snapshot(
        private val chunks: Array<Array<HttpTransaction?>>,
        private val start: Int,
        override val size: Int,
    ) : AbstractList<HttpTransaction>(), RandomAccess {
        override fun get(index: Int): HttpTransaction {
            if (index !in 0 until size) throw IndexOutOfBoundsException("index $index, size $size")
            val position = start + index
            return chunks[position shr SHIFT][position and MASK]!!
        }

        /**
         * Structural equality, as [List] requires, in time proportional to the chunks that differ:
         * two snapshots with the same offset hold the same entries at the same index in a chunk
         * they share, so only the chunks that were copied are compared entry by entry.
         */
        override fun equals(other: Any?): Boolean {
            if (other === this) return true
            if (other !is Snapshot || other.start != start) return super.equals(other)
            if (other.size != size) return false
            val end = start + size
            for (chunk in chunks.indices) {
                val mine = chunks[chunk]
                val theirs = other.chunks[chunk]
                if (mine === theirs) continue
                val from = maxOf(start, chunk shl SHIFT)
                val to = minOf(end, (chunk + 1) shl SHIFT)
                for (position in from until to) {
                    if (mine[position and MASK] != theirs[position and MASK]) return false
                }
            }
            return true
        }

        override fun hashCode(): Int = super.hashCode()
    }

    private companion object {
        const val SHIFT = 5
        const val CHUNK = 1 shl SHIFT
        const val MASK = CHUNK - 1
    }
}
