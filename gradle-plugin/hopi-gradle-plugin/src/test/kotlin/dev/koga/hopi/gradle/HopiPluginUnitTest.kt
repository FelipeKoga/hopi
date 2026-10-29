package dev.koga.hopi.gradle

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class HopiPluginUnitTest {
    @Test
    fun theRuntimeVersionIsTheRepositorysVersion() {
        val properties = Properties().apply { File(System.getProperty("hopi.repositoryProperties")).inputStream().use(::load) }
        assertEquals(properties.getProperty("hopi.version"), HOPI_VERSION)
    }

    private fun generateTask(perf: Boolean): GenerateHopiConfig {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("generate", GenerateHopiConfig::class.java) { task ->
            task.okhttp.set(false)
            task.urlConnection.set(true)
            task.maxEntries.set(42)
            task.maxTotalBytes.set(1024L)
            task.maxBodyBytes.set(512L)
            task.summaryLog.set(true)
            task.agentBridge.set(true)
            task.redactedHeaders.set(listOf("X-Api-Key", "Quote\"d"))
            task.redactedQueryParameters.set(listOf("token"))
            task.redactionDisabled.set(false)
            task.rules.set(false)
            task.perfPresent.set(perf)
            task.perfAutoStart.set(HopiPerfExtension.bits(setOf(HopiMonitor.Hangs, HopiMonitor.Frames)))
            task.perfHangThresholdMs.set(300L)
            task.perfHangHeartbeat.set(true)
            task.perfRemoteControl.set(false)
        }.get()
    }

    @Test
    fun theConfigClassPassesTheBlockAndTheToolListToTheBaseConstructor() {
        val source = generateTask(perf = true).render()
        assertTrue("extends dev.koga.hopi.android.internal.HopiConfigValues" in source, source)
        assertTrue("42, // maxEntries" in source, source)
        assertTrue("1024L, // maxTotalBytes" in source, source)
        assertTrue("512L, // maxBodyBytes" in source, source)
        assertTrue("true, // summaryLog" in source, source)
        assertTrue("true, // agentBridge" in source, source)
        assertTrue("false, // redactionDisabled" in source, source)
        assertTrue("""new String[] { "X-Api-Key", "Quote\"d" }, // redactHeaders""" in source, source)
        assertTrue("""new String[] { "token" }, // redactQueryParameters""" in source, source)
        // Each tool with its own settings.
        assertTrue("new dev.koga.hopi.android.internal.NetworkTool(false, true, false), // okhttp, urlConnection, rules" in source, source)
        // Frames and Hangs: bits 0 and 1, the order PerfEntry decodes; then the heartbeat and remote control.
        assertTrue(
            "new dev.koga.hopi.perf.internal.PerfEntry(3, 300L, true, false) // perf { }: autoStart, hangThresholdMs, hangHeartbeat, remoteControl" in source,
            source,
        )
        assertTrue(source.indexOf("NetworkTool(") < source.indexOf("PerfEntry("), source)
    }

    @Test
    fun withoutThePerfBlockTheToolListNamesTheNetworkToolOnly() {
        val source = generateTask(perf = false).render()
        assertTrue("new dev.koga.hopi.android.internal.NetworkTool(false, true, false) // okhttp, urlConnection, rules\n        );" in source, source)
        assertFalse("PerfEntry" in source, source)
    }

    @Test
    fun perfIsOffByDefault() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("hopi", HopiExtension::class.java)
        extension.applyDefaults()
        assertEquals(false, extension.perf.enabled.get())
        assertEquals(emptySet(), extension.perf.autoStart.get())
        assertEquals(250L, extension.perf.hangThresholdMs.get())
        assertEquals(false, extension.perf.hangHeartbeat.get())
        assertEquals(true, extension.perf.remoteControl.get(), "remote control on unless refused")
        extension.perf { it.autoStart(HopiMonitor.Memory) }
        assertEquals(8, HopiPerfExtension.bits(extension.perf.autoStart.get()))
    }

    @Test
    fun anExcludePatternThatCoversKtorsAndroidEngineIsRecognized() {
        val plugin = HopiPlugin()
        val engine = "io/ktor/client/engine/android/AndroidClientEngine"
        for (pattern in listOf("io.ktor.**", "io/ktor/**", "io.ktor.client.engine.android.*", "io/ktor/client/engine/*/AndroidClientEngine", "**")) {
            assertTrue(plugin.excludes(pattern, engine), pattern)
        }
        for (pattern in listOf("io.ktor.*", "com.vendor.pinned.**", "io/ktor/client/engine/okhttp/**", "io.ktor.client.engine.android.Other")) {
            assertEquals(false, plugin.excludes(pattern, engine), pattern)
        }
    }

    @Test
    fun applyingItOutsideAnAndroidApplicationFails() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java-library")
        project.pluginManager.apply(HopiPlugin::class.java)
        val failure = runCatching { (project as org.gradle.api.internal.project.ProjectInternal).evaluate() }.exceptionOrNull()
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "apply dev.koga.hopi to the Android application module" in it }, messages.toString())
    }
}
