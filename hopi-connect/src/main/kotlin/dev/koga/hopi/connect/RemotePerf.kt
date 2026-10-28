package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a host viewer shows of an app's perf monitors (docs/design/perf-monitors.md, "Desktop
 * viewer"), newest last. [supported] is false while not connected and for an app whose `hello`
 * did not say `"perf":true`; [control] mirrors `hello.perfControl`.
 */
@HopiInternalApi
public class RemotePerfView(
    public val supported: Boolean,
    public val control: Boolean,
    /** The latest `perf.status`; null until the first one. */
    public val status: RemotePerfStatus?,
    /** Closed seconds of frames, at most [RemotePerf.MAX_POINTS]. */
    public val frames: List<RemoteFrameSecond>,
    /** CPU and memory samples, at most [RemotePerf.MAX_POINTS]. */
    public val samples: List<RemotePerfSample>,
    /** Hangs by sequence number, each in its latest state, at most [RemotePerf.MAX_HANGS]. */
    public val hangs: List<RemotePerfEvent>,
    /** Memory events (and the previous ANR), at most [RemotePerf.MAX_MEMORY_EVENTS]. */
    public val memoryEvents: List<RemotePerfEvent>,
    /** Janky frames and iOS stalls seen since the stream opened. */
    public val janks: Long,
    public val stalls: Long,
    /** The per-thread list while [RemotePerf.watchThreads] asks for it; null otherwise or where the platform has none. */
    public val threads: List<RemotePerfThread>?,
    /** True while the perf stream is open. */
    public val streaming: Boolean,
    /** The last command's refusal or the stream's end, for the panel. */
    public val message: String?,
) {
    internal fun copy(
        supported: Boolean = this.supported,
        control: Boolean = this.control,
        status: RemotePerfStatus? = this.status,
        frames: List<RemoteFrameSecond> = this.frames,
        samples: List<RemotePerfSample> = this.samples,
        hangs: List<RemotePerfEvent> = this.hangs,
        memoryEvents: List<RemotePerfEvent> = this.memoryEvents,
        janks: Long = this.janks,
        stalls: Long = this.stalls,
        threads: List<RemotePerfThread>? = this.threads,
        streaming: Boolean = this.streaming,
        message: String? = this.message,
    ): RemotePerfView = RemotePerfView(supported, control, status, frames, samples, hangs, memoryEvents, janks, stalls, threads, streaming, message)

    public companion object {
        public val NONE: RemotePerfView = RemotePerfView(false, false, null, emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, null, false, null)
    }
}

/**
 * A [RemoteSession]'s perf side. Nothing is sent to the app until a viewer [watch]es: then one
 * more bridge connection carries a `perf.tail` (samples, frames and events as they happen; the
 * per-thread list only while [watchThreads]), and one `perf.snapshot` on the request connection
 * fills the charts with what the app already holds. Monitors switched on here are named in that
 * tail's `start`, so the app holds them only while the stream is open: closing the panel or the
 * viewer stops what it started, with no lease to renew. Switching one off is `perf.stop`
 * (explicit control: every owner, code included). Changing the set opens the new stream before
 * closing the old one, so a monitor both name never restarts.
 *
 * Nothing polls: the stream is one thread blocked on its socket, commands run on the session's
 * request thread.
 */
@HopiInternalApi
public class RemotePerf internal constructor(
    private val requests: ExecutorService,
    private val readTimeoutMs: Int,
) {
    private val _view = MutableStateFlow(RemotePerfView.NONE)
    public val view: StateFlow<RemotePerfView> = _view.asStateFlow()

    private val lock = Any()

    // Under [lock].
    private var endpoint: BridgeEndpoint? = null
    private var client: PerfClient? = null
    private var lost: ((String) -> Unit)? = null
    private var watchers = 0
    private var wantThreads = false
    private val wanted = LinkedHashSet<String>()
    private var stream: Stream? = null
    private var highestSeq = 0L
    private var lastSecond = 0L
    private var lastSampleAt = 0L
    private var closed = false

    /**
     * A viewer shows the perf data: the stream runs while at least one holds it, across reconnects.
     * Closing the returned handle releases it (and, with the last, the monitors started here).
     */
    public fun watch(): AutoCloseable {
        val open = synchronized(lock) {
            watchers++
            watchers == 1
        }
        if (open) reopenSoon(backfill = true)
        var released = false
        return AutoCloseable {
            val last = synchronized(lock) {
                if (released) return@AutoCloseable
                released = true
                watchers--
                if (watchers == 0) {
                    // What the stream held ends with it; a later viewer starts from the app's state.
                    wanted.clear()
                    wantThreads = false
                }
                watchers == 0
            }
            if (last) closeStream()
        }
    }

    /** Switches [monitor] on (held by this viewer's stream) or off (`perf.stop`, every owner). */
    public fun setMonitor(monitor: String, on: Boolean): CompletableFuture<Unit> {
        if (on) {
            synchronized(lock) { wanted += monitor }
            return reopenSoon(backfill = false)
        }
        synchronized(lock) { wanted -= monitor }
        return command { it.stop(listOf(monitor)) }
    }

    /** `perf.stop` of every monitor, whoever started it. */
    public fun stopAll(): CompletableFuture<Unit> {
        synchronized(lock) { wanted.clear() }
        return command { it.stop() }
    }

    /** Asks the stream for the per-thread list (the app scans threads only while someone does). */
    public fun watchThreads(watching: Boolean): CompletableFuture<Unit> {
        synchronized(lock) {
            if (wantThreads == watching) return CompletableFuture.completedFuture(Unit)
            wantThreads = watching
            if (!watching) _view.value = _view.value.copy(threads = null)
        }
        return reopenSoon(backfill = false)
    }

    // ---- the session's side

    /** A link came up; [client] is null for an app without perf. Request thread or connect thread. */
    internal fun attach(endpoint: BridgeEndpoint, client: PerfClient?, lost: (String) -> Unit, sameProcess: Boolean) {
        val reopen = synchronized(lock) {
            if (closed) return
            this.endpoint = endpoint
            this.client = client
            this.lost = lost
            if (!sameProcess) {
                highestSeq = 0
                lastSecond = 0
                lastSampleAt = 0
                _view.value = RemotePerfView.NONE
            }
            _view.value = _view.value.copy(supported = client != null, control = client?.control == true, message = null)
            client != null && watchers > 0
        }
        if (reopen) reopenSoon(backfill = true)
    }

    /** The link went down: the stream with it. */
    internal fun detach() {
        val previous = synchronized(lock) {
            endpoint = null
            client = null
            lost = null
            _view.value = _view.value.copy(supported = false, control = false, streaming = false)
            stream.also { stream = null }
        }
        previous?.close()
    }

    internal fun close() {
        synchronized(lock) { closed = true }
        detach()
    }

    // ---- stream

    private inner class Stream(val tail: BridgeClient) {
        @Volatile var closed = false

        fun close() {
            closed = true
            tail.close()
        }
    }

    private fun closeStream() {
        val previous = synchronized(lock) {
            _view.value = _view.value.copy(streaming = false, threads = null)
            stream.also { stream = null }
        }
        previous?.close()
    }

    /**
     * Opens a stream with the current wishes on the request thread, then closes the previous one
     * (in that order, so the app never drops a monitor both hold); [backfill] then reads the
     * series the app already holds.
     */
    private fun reopenSoon(backfill: Boolean): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        try {
            requests.execute {
                val (target, perf, since, start, threads) = synchronized(lock) {
                    val e = endpoint
                    val c = client
                    if (watchers == 0 || e == null || c == null) {
                        future.complete(Unit)
                        return@execute
                    }
                    Wish(e, c, resumeSince(), wanted.toList(), wantThreads)
                }
                val tail = try {
                    BridgeClient.connect(target, readTimeoutMs).also { next ->
                        next.handshake(null)
                        PerfClient(next).tail(since = since, samples = true, threads = threads, start = start)
                    }
                } catch (e: CliException) {
                    synchronized(lock) { _view.value = _view.value.copy(message = e.message) }
                    future.completeExceptionally(e)
                    return@execute
                }
                val next = Stream(tail)
                val previous = synchronized(lock) {
                    if (watchers == 0 || client !== perf) {
                        tail.close()
                        future.complete(Unit)
                        return@execute
                    }
                    _view.value = _view.value.copy(streaming = true, message = null)
                    stream.also { stream = next }
                }
                Thread({ readLoop(next) }, "hopi-remote-perf").apply { isDaemon = true }.start()
                previous?.close()
                if (backfill) backfill(perf)
                future.complete(Unit)
            }
        } catch (_: RejectedExecutionException) {
            future.completeExceptionally(notReachable("the session was closed"))
        }
        return future
    }

    /**
     * Under [lock]: where a new stream resumes. Below the lowest hang shown as ongoing, since a hang
     * is updated in place under its seq and its end may come while no stream is open; the events
     * re-sent above it replace their own row or are not counted twice ([apply]).
     */
    private fun resumeSince(): Long {
        val ongoing = _view.value.hangs.filter { it.ongoing }.minOfOrNull { it.seq } ?: return highestSeq
        return minOf(highestSeq, ongoing - 1)
    }

    private data class Wish(val endpoint: BridgeEndpoint, val client: PerfClient, val since: Long, val start: List<String>, val threads: Boolean)

    /** Request thread: the series the app holds, merged under what the stream has brought since it opened. */
    private fun backfill(perf: PerfClient) {
        val lines = try {
            perf.snapshot(series = listOf("frames", "cpu", "memory"), points = MAX_POINTS, events = false)
        } catch (e: CliException) {
            if (e.exitCode == ExitCode.NOT_REACHABLE) synchronized(lock) { lost }?.invoke(e.message.orEmpty())
            return
        }
        val series = lines.mapNotNull { RemotePerfSeries.decode(it.json) }.associateBy { it.name }
        val frames = series["frames"]?.let(::framesOf).orEmpty()
        val samples = samplesOf(series["cpu"], series["memory"])
        val status = lines.firstNotNullOfOrNull { RemotePerfStatus.decode(it.json) }
        synchronized(lock) {
            if (client !== perf) return
            val shown = _view.value
            val olderFrames = frames.filter { f -> shown.frames.none { it.second == f.second } && f.second < (shown.frames.firstOrNull()?.second ?: Long.MAX_VALUE) }
            val olderSamples = samples.filter { s -> s.atMs < (shown.samples.firstOrNull()?.atMs ?: Long.MAX_VALUE) }
            _view.value = shown.copy(
                frames = (olderFrames + shown.frames).takeLast(MAX_POINTS),
                samples = (olderSamples + shown.samples).takeLast(MAX_POINTS),
                status = shown.status ?: status,
            )
            frames.lastOrNull()?.let { if (it.second > lastSecond) lastSecond = it.second }
            samples.lastOrNull()?.let { if (it.atMs > lastSampleAt) lastSampleAt = it.atMs }
        }
    }

    private fun readLoop(current: Stream) {
        while (true) {
            val reply = try {
                current.tail.reply()
            } catch (e: SlowClientException) {
                if (current.closed) return
                // Resumed from the highest event seen: samples and seconds in between are lost, events are not.
                reopenSoon(backfill = false)
                return
            } catch (e: CliException) {
                if (current.closed) return
                synchronized(lock) {
                    if (stream === current) {
                        stream = null
                        _view.value = _view.value.copy(streaming = false, message = "perf stream ended: ${e.message}")
                    }
                }
                return
            }
            synchronized(lock) {
                if (stream !== current) return
                apply(reply)
            }
        }
    }

    /** Under [lock]. */
    private fun apply(reply: Reply) {
        val shown = _view.value
        when (reply.type) {
            "perf.status" -> RemotePerfStatus.decode(reply.json)?.let { _view.value = shown.copy(status = it) }
            "perf.event" -> RemotePerfEvent.decode(reply.json)?.let { event ->
                // A resumed stream re-sends what lies above its `since`: counted once.
                val seen = event.seq <= highestSeq
                if (event.seq > highestSeq) highestSeq = event.seq
                _view.value = when (event.kind) {
                    "hang" -> shown.copy(hangs = upsert(shown.hangs, event, MAX_HANGS))
                    "memory", "anr" -> shown.copy(memoryEvents = upsert(shown.memoryEvents, event, MAX_MEMORY_EVENTS))
                    "jank" -> if (seen) shown else shown.copy(janks = shown.janks + 1)
                    "stall" -> if (seen) shown else shown.copy(stalls = shown.stalls + 1)
                    else -> shown
                }
            }
            "perf.frames" -> RemoteFrameSecond.decode(reply.json)?.takeIf { !it.provisional && it.second > lastSecond }?.let {
                lastSecond = it.second
                _view.value = shown.copy(frames = (shown.frames + it).takeLast(MAX_POINTS))
            }
            "perf.sample" -> RemotePerfSample.decode(reply.json)?.takeIf { it.atMs > lastSampleAt }?.let {
                lastSampleAt = it.atMs
                _view.value = shown.copy(samples = (shown.samples + it).takeLast(MAX_POINTS))
            }
            "perf.threads" -> if (wantThreads) _view.value = shown.copy(threads = RemotePerfThread.decodeAll(reply.json))
        }
    }

    /** A command on the request connection; its `perf.status` reply updates the view. */
    private fun command(block: (PerfClient) -> Reply): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        try {
            requests.execute {
                val (perf, onLost) = synchronized(lock) { client to lost }
                if (perf == null) {
                    future.completeExceptionally(CliException(ExitCode.UNSUPPORTED, PerfClient.PERF_UNSUPPORTED))
                    return@execute
                }
                try {
                    val status = RemotePerfStatus.decode(block(perf).json)
                    synchronized(lock) { if (client === perf && status != null) _view.value = _view.value.copy(status = status, message = null) }
                    future.complete(Unit)
                } catch (e: CliException) {
                    if (e.exitCode == ExitCode.NOT_REACHABLE) onLost?.invoke(e.message.orEmpty())
                    synchronized(lock) { _view.value = _view.value.copy(message = e.message) }
                    future.completeExceptionally(e)
                }
            }
        } catch (_: RejectedExecutionException) {
            future.completeExceptionally(notReachable("the session was closed"))
        }
        return future
    }

    public companion object {
        /** Points the charts keep per series: 20 minutes at 1 Hz, as the app does. */
        public const val MAX_POINTS: Int = 1_200
        public const val MAX_HANGS: Int = 100
        public const val MAX_MEMORY_EVENTS: Int = 200

        private fun upsert(events: List<RemotePerfEvent>, event: RemotePerfEvent, max: Int): List<RemotePerfEvent> {
            val index = events.indexOfFirst { it.seq == event.seq }
            if (index >= 0) return events.toMutableList().also { it[index] = event }
            return (events + event).sortedBy { it.seq }.takeLast(max)
        }

        /** The seconds of a `frames` series, as a stream brings them. */
        public fun framesOf(series: RemotePerfSeries): List<RemoteFrameSecond> {
            val frames = series.column("frames")
            val slow = series.column("slow")
            val janky = series.column("janky")
            val frozen = series.column("frozen")
            val worst = series.column("worstMs")
            return List(series.size) { i ->
                RemoteFrameSecond(series.times[i] / 1000, count(frames[i]), count(slow[i]), count(janky[i]), count(frozen[i]), worst[i].takeIf { !it.isNaN() } ?: 0.0, false)
            }
        }

        /** The sampler writes CPU and memory at the same instant, so their points pair by time. */
        public fun samplesOf(cpu: RemotePerfSeries?, memory: RemotePerfSeries?): List<RemotePerfSample> {
            val times = sortedSetOf<Long>().apply {
                cpu?.times?.forEach { add(it) }
                memory?.times?.forEach { add(it) }
            }
            val process = cpu?.column("process")
            val hopi = cpu?.column("hopi")
            val resident = memory?.column("residentKb")
            val javaHeap = memory?.column("javaHeapKb")
            val nativeHeap = memory?.column("nativeHeapKb")
            val available = memory?.column("availableKb")
            val cpuAt = cpu?.times?.withIndex()?.associate { it.value to it.index }.orEmpty()
            val memoryAt = memory?.times?.withIndex()?.associate { it.value to it.index }.orEmpty()
            return times.map { t ->
                val c = cpuAt[t]
                val m = memoryAt[t]
                RemotePerfSample(
                    atMs = t,
                    cpu = c?.let { process?.get(it) }?.takeIf { !it.isNaN() },
                    hopiCpu = c?.let { hopi?.get(it) }?.takeIf { !it.isNaN() },
                    residentBytes = m?.let { kib(resident?.get(it)) },
                    javaHeapBytes = m?.let { kib(javaHeap?.get(it)) },
                    nativeHeapBytes = m?.let { kib(nativeHeap?.get(it)) },
                    availableBytes = m?.let { kib(available?.get(it)) },
                )
            }
        }

        private fun count(value: Double): Long = if (value.isNaN()) 0 else value.toLong()

        private fun kib(value: Double?): Long? = value?.takeIf { !it.isNaN() }?.let { (it * 1024).toLong() }
    }
}
