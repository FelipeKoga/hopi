package dev.koga.hopi.urlsession

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HopiUrlSessionNoopTest {
    @Test
    fun everySetterIsAcceptedAndNothingStarts() {
        var configured = false
        HopiUrlSession.start()
        HopiUrlSession.start {
            enabled = true
            maxEntries = 10
            maxTotalBytes = 1024
            maxBodyBytes = 16
            redactHeaders("X-Api-Key")
            redactQueryParameters("token")
            unsafeDisableRedaction()
            summaryLog = false
            agentBridge = true
            configured = true
        }
        assertTrue(configured, "the configure block runs, as with the real module")
        assertFalse(HopiUrlSession.isStarted)
    }

    @Test
    fun theConfigKeepsWhatWasSet() {
        val config = HopiUrlSessionConfig()
        config.maxEntries = 7
        config.agentBridge = true
        assertEquals(7, config.maxEntries)
        assertEquals(true, config.agentBridge)
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.store.HopiStore") }
        val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it }, "hopi-core on the classpath")
    }

    /** The ABI dumps hold signatures, not initializers: compare a fresh configuration's getters with the real one's. */
    @Test
    fun theNoopConfigHasTheRealDefaults() {
        realLoader().use { loader ->
            val real = loader.loadClass(HopiUrlSessionConfig::class.java.name)
            assertTrue(real.classLoader === loader, "the real HopiUrlSessionConfig must come from hopi-urlsession's jar")
            assertEquals(describe(real), describe(HopiUrlSessionConfig::class.java))
        }
    }

    private fun describe(config: Class<*>): Map<String, String> {
        val instance = config.getConstructor().newInstance()
        val values = config.methods
            .filter { it.declaringClass == config && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
            .filter { (it.name.startsWith("get") || it.name.startsWith("is")) && '$' !in it.name }
            .associate { method -> method.name to method.invoke(instance).toString() }
        assertTrue(values.keys.containsAll(listOf("getEnabled", "getMaxEntries", "getMaxTotalBytes", "getMaxBodyBytes", "getSummaryLog", "getAgentBridge")), values.keys.toString())
        return values + ("annotations" to config.annotations.map { it.annotationClass.java.name }.sorted().toString())
    }

    private fun realLoader(): URLClassLoader {
        val path = System.getProperty("hopi.realUrlSessionClasspath")
            ?: error("hopi.realUrlSessionClasspath is not set; run this test through Gradle")
        val urls = path.split(File.pathSeparatorChar).filter { it.isNotEmpty() }.map { File(it).toURI().toURL() }
        return object : URLClassLoader(urls.toTypedArray(), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("dev.koga.hopi.")) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }
        }
    }
}
