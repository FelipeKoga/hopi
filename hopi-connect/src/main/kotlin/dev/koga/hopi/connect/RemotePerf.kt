package dev.koga.hopi.connect

import dev.koga.hopi.api.HopiInternalApi

/** A perf snapshot's series as rows: what `hopi perf snapshot` prints. */
@HopiInternalApi
public object RemotePerf {
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
