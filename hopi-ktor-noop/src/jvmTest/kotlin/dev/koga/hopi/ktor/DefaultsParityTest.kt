package dev.koga.hopi.ktor

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ABI dumps that `:checkNoopParity` compares hold signatures, not initializers: they cannot
 * see a default that changed on one side. This test loads the real `hopi-ktor` (and its
 * `hopi-core`) in a classloader of its own and compares what a fresh configuration reads
 * back, the log sink entries in order, and the runtime-visible annotations.
 */
class DefaultsParityTest {
    @Test
    fun theNoopConfigHasTheRealDefaults() {
        realLoader().use { loader ->
            val real = loader.loadClass(HopiConfig::class.java.name)
            assertTrue(real.classLoader === loader, "the real HopiConfig must come from hopi-ktor's jar")
            assertEquals(describe(real), describe(HopiConfig::class.java))
        }
    }

    @Test
    fun theLogSinkHasTheSameEntriesInTheSameOrder() {
        realLoader().use { loader ->
            val real = loader.loadClass(HopiLogSink::class.java.name)
            assertEquals(real.enumConstants.map { (it as Enum<*>).name }, HopiLogSink.entries.map { it.name })
            assertEquals(annotations(real), annotations(HopiLogSink::class.java))
        }
    }

    /** Every public getter of a fresh configuration with the value it returns, and the class's annotations. */
    private fun describe(config: Class<*>): Map<String, String> {
        val instance = config.getConstructor().newInstance()
        val values = config.methods
            .filter { it.declaringClass == config && it.parameterCount == 0 && Modifier.isPublic(it.modifiers) }
            // Kotlin `internal` members are public in the bytecode under a `$module` name: not API.
            .filter { (it.name.startsWith("get") || it.name.startsWith("is")) && '$' !in it.name }
            .associate { method ->
                val value = method.invoke(instance)
                method.name to if (value is Enum<*>) "${value.declaringJavaClass.simpleName}.${value.name}" else value.toString()
            }
        assertTrue(values.keys.containsAll(listOf("getEnabled", "getMaxEntries", "getMaxTotalBytes", "getMaxBodyBytes", "getLogSink", "getAgentBridge")), values.keys.toString())
        return values + ("annotations" to annotations(config).toString())
    }

    private fun annotations(type: Class<*>): List<String> = type.annotations.map { it.annotationClass.java.name }.sorted()

    /**
     * Loads `dev.koga.hopi.*` from the real module's classpath first, everything else (Ktor,
     * the Kotlin runtime) from this test's loader, so both sides see the same library classes.
     */
    private fun realLoader(): URLClassLoader {
        val path = System.getProperty("hopi.realKtorClasspath")
            ?: error("hopi.realKtorClasspath is not set; run this test through Gradle")
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
