package dev.koga.hopi.perf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HopiPerfNoopTest {
    @Test
    fun everyCallIsInert() {
        var ran = false
        HopiPerf.start {
            frames = true
            hangs = true
            cpu = true
            memory = true
            ran = true
        }
        assertTrue(ran, "the block runs, as with the real module")
        HopiPerf.install()
        HopiPerf.screen("Checkout")
        assertEquals(emptySet(), HopiPerf.running)
        HopiPerf.stop(HopiMonitor.Frames)
        HopiPerf.stop()
        assertEquals(emptyList(), Thread.getAllStackTraces().keys.filter { it.name.startsWith("hopi-perf") })
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.perf.PerfHook") }
        val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it || "hopi-perf/" in it }, "hopi-core or hopi-perf on the classpath")
    }
}
