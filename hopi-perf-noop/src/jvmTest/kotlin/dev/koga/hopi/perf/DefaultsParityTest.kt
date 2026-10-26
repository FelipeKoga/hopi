package dev.koga.hopi.perf

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ABI dumps that `:checkNoopParity` compares hold signatures, not initializers: they cannot see
 * a default that changed on one side. This test loads the real `hopi-perf` (and its `hopi-core`) in
 * a classloader of its own and compares what a fresh configuration reads back and the monitors'
 * entries in order.
 */
class DefaultsParityTest {
    @Test
    fun theNoopConfigHasTheRealDefaults() {
        realLoader().use { loader ->
            val real = loader.loadClass(HopiPerfConfig::class.java.name)
            assertTrue(real.classLoader === loader, "the real HopiPerfConfig must come from hopi-perf's jar")
            assertEquals(describe(real), describe(HopiPerfConfig::class.java))
        }
    }

    @Test
    fun theMonitorsHaveTheSameEntriesInTheSameOrder() {
        realLoader().use { loader ->
            val real = loader.loadClass(HopiMonitor::class.java.name)
            assertEquals(real.enumConstants.map { (it as Enum<*>).name }, HopiMonitor.entries.map { it.name })
        }
    }

    private fun describe(config: Class<*>): Map<String, String> {
        val constructor = config.getDeclaredConstructor().apply { isAccessible = true }
        val instance = constructor.newInstance()
        val values = config.methods
            .filter { it.declaringClass == config && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
            .filter { (it.name.startsWith("get") || it.name.startsWith("is")) && '$' !in it.name }
            .associate { method -> method.name to method.invoke(instance).toString() }
        assertTrue(values.keys.containsAll(listOf("getFrames", "getHangs", "getHangThresholdMs", "getSampleIntervalMs", "getRemoteControl", "getSummaryLog")), values.keys.toString())
        return values + ("annotations" to config.annotations.map { it.annotationClass.java.name }.sorted().toString())
    }

    /** Loads `dev.koga.hopi.*` from the real module's classpath first, the Kotlin runtime from this test's loader. */
    private fun realLoader(): URLClassLoader {
        val path = System.getProperty("hopi.realPerfClasspath")
            ?: error("hopi.realPerfClasspath is not set; run this test through Gradle")
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
