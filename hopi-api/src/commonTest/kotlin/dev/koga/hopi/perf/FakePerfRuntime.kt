package dev.koga.hopi.perf

/** A perf runtime that records every call it got, and answers [running] with [runningNow]. */
internal class FakePerfRuntime : HopiPerfRuntime {
    class Start(val monitors: Set<HopiMonitor>, val hangThresholdMs: Long?, val hangHeartbeat: Boolean?, val sampleIntervalMs: Long?)

    val starts = ArrayList<Start>()
    val stops = ArrayList<Set<HopiMonitor>>()
    val screens = ArrayList<String?>()
    var runningNow: Set<HopiMonitor> = emptySet()

    override fun start(monitors: Set<HopiMonitor>, hangThresholdMs: Long?, hangHeartbeat: Boolean?, sampleIntervalMs: Long?) {
        starts += Start(monitors, hangThresholdMs, hangHeartbeat, sampleIntervalMs)
    }

    override fun stop(monitors: Set<HopiMonitor>) {
        stops += monitors
    }

    override val running: Set<HopiMonitor> get() = runningNow

    override fun screen(name: String?) {
        screens += name
    }
}
