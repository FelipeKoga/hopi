package dev.koga.hopi.android

import android.content.pm.ApplicationInfo
import dev.koga.hopi.android.internal.GeneratedConfig
import dev.koga.hopi.android.internal.HopiInitProvider
import dev.koga.hopi.core.start.HopiStart
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The provider's start, from `hopi-core`, run through its companion functions with the lines they
 * write collected: the generated stand-in (`generated/HopiGeneratedConfig.kt`) lists two fake
 * tools, with `agentBridge` off, so no thread or bridge starts here.
 */
class GeneratedConfigTest {
    private val lines = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        HopiStart.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        HopiStart.resetForTests()
    }

    private fun notice(text: String) = """{"hopi":1,"notice":"$text"}"""

    /** A loader that records every class it is asked for. */
    private class RecordingLoader(parent: ClassLoader? = GeneratedConfigTest::class.java.classLoader) : ClassLoader(parent) {
        val asked = ArrayList<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            asked += name
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun theGeneratedClassIsReadThroughItsConstructor() {
        val values = assertNotNull(GeneratedConfig.read(javaClass.classLoader))
        val config = values.config
        assertEquals(42, config.maxEntries)
        assertEquals(4096L, config.maxTotalBytes)
        assertEquals(1024L, config.maxBodyBytes)
        assertEquals(true, config.summaryLog)
        assertEquals(false, config.agentBridge)
        assertEquals(false, config.redactionDisabled)
        assertEquals(listOf("X-Api-Key"), config.redactedHeaders)
        assertEquals(listOf("token"), config.redactedQueryParameters)
        // The tool list, in the generated order; each tool carries its own settings.
        assertEquals(listOf("network", "perf"), values.tools.map { it.name })
    }

    @Test
    fun theProviderStartsExactlyTheListedToolsAndLoadsNoClassButTheGeneratedOne() {
        val loader = RecordingLoader()
        val started = assertNotNull(HopiInitProvider.start(loader, null, lines::add))
        assertEquals(listOf("network", "perf"), started.map { it.name })
        assertEquals(listOf(GeneratedConfig.CLASS_NAME), loader.asked, "no lookup but the generated class: a miss on ART costs a stack trace")
        assertNotNull(HopiStart.config)
        // One line, with the prefix device runs read, the tools' notes, then each part.
        val line = lines.single()
        assertTrue(line.startsWith(notice("android capture started (okhttp instrumented, ").removeSuffix("\"}")), line)
        assertTrue(Regex(""" us; config \d+ us, network \d+ us, perf \d+ us\)"}$""").containsMatchIn(line), line)
    }

    @Test
    fun withoutTheGeneratedClassNothingStarts() {
        val empty = object : ClassLoader(null) {}
        assertNull(GeneratedConfig.read(empty))
        assertNull(HopiInitProvider.start(empty, null, lines::add))
        assertEquals(listOf(notice("not started: no generated configuration; apply the dev.koga.hopi Gradle plugin to the app")), lines)
        assertNull(HopiStart.config)
    }

    @Test
    fun aNonDebuggableAppStartsNothingAndWritesOneNotice() {
        // The decision: only FLAG_DEBUGGABLE counts, and no flags at all (no ApplicationInfo) fail closed.
        for (flags in listOf(0, ApplicationInfo.FLAG_DEBUGGABLE.inv(), null)) assertFalse(HopiInitProvider.debuggable(flags), "flags $flags")
        assertTrue(HopiInitProvider.debuggable(ApplicationInfo.FLAG_DEBUGGABLE))
        // The one line such an app gets; without a package name it says "the app".
        val expected = notice(
            "not started: com.example.app is not debuggable (FLAG_DEBUGGABLE unset); " +
                "a Hopi runtime reached a non-debuggable build, check the app's dependencies",
        )
        assertEquals(expected, HopiInitProvider.notDebuggableNotice("com.example.app"))
        assertTrue("not started: the app is not debuggable" in HopiInitProvider.notDebuggableNotice(null))
        // onCreate itself: a host test's provider has no Context, so no ApplicationInfo. Its first
        // statement fails closed, so the generated stand-in on this classpath is never read and
        // its tools never start.
        assertTrue(HopiInitProvider().onCreate())
        assertNull(HopiStart.config)
    }
}
