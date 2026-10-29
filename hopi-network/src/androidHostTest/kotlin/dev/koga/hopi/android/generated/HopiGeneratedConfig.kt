package dev.koga.hopi.android.generated

import dev.koga.hopi.android.internal.HopiConfigValues
import dev.koga.hopi.core.start.HopiTool
import dev.koga.hopi.core.start.HopiToolContext

/**
 * What the plugin generates into an app (GenerateHopiConfig), with test values. Fake tools take
 * the tool list's places: the real network tool starts capture once per process, and hopi-perf is
 * not on this test classpath.
 */
class HopiGeneratedConfig : HopiConfigValues(
    42, 4096L, 1024L, true, false, false, arrayOf("X-Api-Key"), arrayOf("token"),
    FakeTool("network", "okhttp instrumented"), FakeTool("perf", null),
)

/** A tool that starts nothing and returns [note]. */
class FakeTool(override val name: String, private val note: String?) : HopiTool {
    override fun start(context: HopiToolContext): String? = note
}
