package dev.koga.hopi.bench

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * Timing and memory probes for the giant-body benchmarks (JDK 17: no total-allocation counter, so
 * allocation is the sum over the threads alive before and after a sample; a thread that dies in
 * between drops out, which dispatcher and engine pools, alive for the whole run, do not do).
 */
internal object Measure {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }

    init {
        if (threads.isThreadAllocatedMemorySupported) threads.isThreadAllocatedMemoryEnabled = true
    }

    /** Bytes allocated so far by every live thread. */
    fun allocatedBytes(): Long {
        val ids = threads.allThreadIds
        return threads.getThreadAllocatedBytes(ids).sumOf { if (it < 0) 0L else it }
    }

    /** Bytes allocated so far by the calling thread alone (exact, unlike [allocatedBytes]). */
    fun threadAllocatedBytes(): Long = threads.getThreadAllocatedBytes(Thread.currentThread().id)

    /** Heap in use after a full collection: what is still reachable. */
    fun retainedHeap(): Long {
        val runtime = Runtime.getRuntime()
        var last = Long.MAX_VALUE
        // A few rounds: one System.gc() can leave finalizable or soft garbage behind.
        repeat(4) {
            System.gc()
            val used = runtime.totalMemory() - runtime.freeMemory()
            if (used >= last) return used
            last = used
        }
        return last
    }

    fun resetPeak() {
        for (pool in heapPools) pool.resetPeakUsage()
    }

    /** Sum of every heap pool's peak since [resetPeak]: an upper bound of the peak heap. */
    fun peakHeap(): Long = heapPools.sumOf { it.peakUsage.used }

    val maxHeap: Long get() = Runtime.getRuntime().maxMemory()
}

/** Samples of one measured operation. */
internal class Samples(capacity: Int) {
    private val nanos = LongArray(capacity)
    private val allocated = LongArray(capacity)
    var count: Int = 0
        private set

    fun add(elapsedNanos: Long, allocatedBytes: Long) {
        nanos[count] = elapsedNanos
        allocated[count] = allocatedBytes
        count++
    }

    val medianNanos: Long get() = percentile(nanos, 50)
    val p95Nanos: Long get() = percentile(nanos, 95)
    val medianAllocated: Long get() = percentile(allocated, 50)

    private fun percentile(values: LongArray, p: Int): Long {
        if (count == 0) return 0
        val sorted = values.copyOf(count).also { it.sort() }
        return sorted[minOf(count - 1, (count * p) / 100)]
    }
}

/**
 * Runs [block] [warmup] times, then [runs] times measured: wall time and bytes allocated by every
 * live thread (or by the calling thread only, when [threadOnly]).
 */
internal inline fun measure(runs: Int, warmup: Int = 2, threadOnly: Boolean = false, block: () -> Unit): Samples {
    repeat(warmup) { block() }
    val samples = Samples(runs)
    repeat(runs) {
        val before = if (threadOnly) Measure.threadAllocatedBytes() else Measure.allocatedBytes()
        val start = System.nanoTime()
        block()
        val elapsed = System.nanoTime() - start
        val after = if (threadOnly) Measure.threadAllocatedBytes() else Measure.allocatedBytes()
        samples.add(elapsed, after - before)
    }
    return samples
}

/** Iterations so that every size moves about the same number of bytes, within [min]..[max]. */
internal fun iterationsFor(bytes: Int, budgetBytes: Long = 400L * GiantBodies.MB, min: Int = 5, max: Int = 200): Int =
    (budgetBytes / bytes).toInt().coerceIn(min, max)

internal fun ms(nanos: Long): String = String.format("%.2f", nanos / 1_000_000.0)

internal fun us(nanos: Long): String = String.format("%.1f", nanos / 1_000.0)

internal fun mb(bytes: Long): String = String.format("%.1f", bytes / (1024.0 * 1024.0))

/** Allocation as a multiple of [body] bytes, e.g. `2.31x`. */
internal fun ratio(bytes: Long, body: Long): String = if (body <= 0) "-" else String.format("%.2fx", bytes.toDouble() / body)

/** A Markdown table collected row by row, printed to stderr and saved under `hopi.bench.out`. */
internal class Report(private val name: String, private val title: String, private vararg val columns: String) {
    private val rows = ArrayList<List<String>>()
    private val notes = ArrayList<String>()

    fun row(vararg cells: String) {
        require(cells.size == columns.size) { "${cells.size} cells for ${columns.size} columns" }
        rows += cells.toList()
        System.err.println("[$name] " + cells.joinToString(" | "))
    }

    fun note(text: String) {
        notes += text
        System.err.println("[$name] note: $text")
    }

    fun render(): String = buildString {
        appendLine("### $title")
        appendLine()
        appendLine(columns.joinToString(" | ", "| ", " |"))
        appendLine(columns.joinToString(" | ", "| ", " |") { "---" })
        for (row in rows) appendLine(row.joinToString(" | ", "| ", " |"))
        if (notes.isNotEmpty()) {
            appendLine()
            for (note in notes) appendLine("- $note")
        }
    }

    fun save() {
        val text = render()
        System.err.println(text)
        val dir = System.getProperty("hopi.bench.out") ?: return
        File(dir).mkdirs()
        File(dir, "$name.md").writeText(text)
    }
}
