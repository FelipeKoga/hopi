package dev.koga.hopi.android

import dev.koga.hopi.android.internal.GeneratedConfig
import dev.koga.hopi.android.internal.HopiInitProvider
import dev.koga.hopi.android.internal.PerfConfig
import dev.koga.hopi.core.perf.PerfBootstrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeneratedConfigTest {
    @Test
    fun theGeneratedClassIsReadThroughItsConstructor() {
        val config = GeneratedConfig.read(javaClass.classLoader)
        assertEquals(false, config.okhttp)
        assertEquals(true, config.urlConnection)
        assertEquals(42, config.settings.maxEntries)
        assertEquals(4096L, config.settings.maxTotalBytes)
        assertEquals(1024L, config.settings.maxBodyBytes)
        assertEquals(listOf("X-Api-Key"), config.settings.redactedHeaders)
        assertEquals(listOf("token"), config.settings.redactedQueryParameters)
        assertEquals(true, config.settings.agentBridge)
        assertEquals(false, config.settings.rules)
        val perf = assertNotNull(config.perf)
        assertEquals(6, perf.autoStart)
        assertEquals(300L, perf.hangThresholdMs)
        assertEquals(false, perf.hangHeartbeat)
    }

    @Test
    fun withoutTheGeneratedClassTheDefaultsApply() {
        val empty = object : ClassLoader(null) {}
        val config = GeneratedConfig.read(empty)
        assertEquals(true, config.okhttp)
        // Without the plugin nothing was rewritten: Ktor's Android engine stays with the Ktor plugin.
        assertEquals(false, config.urlConnection)
        assertEquals(500, config.settings.maxEntries)
        assertEquals(256L * 1024, config.settings.maxBodyBytes)
        assertNull(config.perf, "no hopi-perf without the plugin's perf block")
    }

    /** A loader that records every class it is asked for and has none of Hopi's perf classes. */
    private class RecordingLoader : ClassLoader(GeneratedConfigTest::class.java.classLoader) {
        val asked = ArrayList<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            asked += name
            if (name.startsWith("dev.koga.hopi.perf.")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun withoutThePerfBlockTheProviderLooksNothingUp() {
        val loader = RecordingLoader()
        assertFalse(HopiInitProvider.bootPerf(null, loader, null))
        assertEquals(emptyList(), loader.asked, "no Class.forName at all: a miss on ART costs a stack trace")
    }

    @Test
    fun withThePerfBlockTheProviderLoadsTheEntryByNameOnce() {
        val loader = RecordingLoader()
        // hopi-perf is not on this module's test classpath: the lookup misses, and says so.
        assertFalse(HopiInitProvider.bootPerf(PerfConfig(0, 250, false), loader, null))
        assertEquals(listOf(PerfBootstrap.CLASS_NAME), loader.asked)
    }
}
