package dev.koga.hopi.gradle

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class HopiPluginUnitTest {
    @Test
    fun theRuntimeVersionIsTheRepositorysVersion() {
        val properties = Properties().apply { File(System.getProperty("hopi.repositoryProperties")).inputStream().use(::load) }
        assertEquals(properties.getProperty("hopi.version"), HOPI_VERSION)
    }

    @Test
    fun theConfigClassPassesTheBlockToTheRuntimesConstructor() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("generate", GenerateHopiConfig::class.java) { task ->
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
        }.get()
        val source = task.render()
        assertTrue("extends dev.koga.hopi.android.internal.HopiConfigValues" in source, source)
        assertTrue("false, // okhttp" in source, source)
        assertTrue("true, // urlConnection" in source, source)
        assertTrue("42, // maxEntries" in source, source)
        assertTrue("1024L, // maxTotalBytes" in source, source)
        assertTrue("512L, // maxBodyBytes" in source, source)
        assertTrue("true, // agentBridge" in source, source)
        assertTrue("""new String[] { "X-Api-Key", "Quote\"d" }, // redactHeaders""" in source, source)
        assertTrue("""new String[] { "token" }, // redactQueryParameters""" in source, source)
        assertTrue("false // rules" in source, source)
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
