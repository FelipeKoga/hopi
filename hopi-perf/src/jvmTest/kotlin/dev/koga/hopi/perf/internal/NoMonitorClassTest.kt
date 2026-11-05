package dev.koga.hopi.perf.internal

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "No monitor class is loaded until a monitor starts" (docs/design/perf-monitors.md), on the JVM:
 * Hopi's classes are loaded in a child-first classloader of their own, perf's JVM tool is started
 * there by reflection, and the loader says which of them it loaded. HotSpot evidence only: HotSpot
 * loads a class a method names when that code first runs, while a debug build on Android runs from
 * its APK, and ART loads every class a method names when it verifies the class (the runtime-split
 * plan's stage 7). The Android backend is split for that, and an emulator probe checks it.
 */
class NoMonitorClassTest {
    @Test
    fun theToolsStartLoadsNoMonitorClassAndStartsNoThread() {
        IsolatedLoader().use { loader ->
            val config = loader.loadClass("dev.koga.hopi.core.start.StartConfig").getDeclaredConstructor().newInstance()
            val contextClass = loader.loadClass("dev.koga.hopi.core.start.HopiToolContext")
            val context = contextClass.getConstructor(config.javaClass, Any::class.java).newInstance(config, null)
            val entryClass = loader.loadClass("dev.koga.hopi.perf.internal.PerfEntry")
            assertTrue(entryClass.classLoader === loader, "perf's tool comes from the loader of its own")
            val entry = entryClass.getConstructor(Boolean::class.javaPrimitiveType).newInstance(true)
            entryClass.getMethod("start", contextClass).invoke(entry, context)
            assertTrue(loader.loaded("dev.koga.hopi.perf.internal.JvmBackend"), "the tool created the backend (the loader sees its loads)")
            assertTrue(loader.loaded("dev.koga.hopi.perf.internal.ApiPerf"), "and registered HopiPerf's runtime")
            assertFalse(loader.loaded("dev.koga.hopi.perf.internal.JvmBackend\$Sampler"), "the sampler, the JVM's only monitor class")
            assertFalse(loader.loaded("dev.koga.hopi.perf.internal.ApiPerfCalls"), "HopiPerf's calls, before the first one")
        }
        assertEquals(emptyList(), Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("hopi-perf") }.map { it.name })
    }

    /** Loads `dev.koga.hopi.*` from this test's classpath first, everything else (Kotlin's runtime) from the test's loader. */
    private class IsolatedLoader : URLClassLoader(classpath(), NoMonitorClassTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("dev.koga.hopi.")) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }

        fun loaded(name: String): Boolean = findLoadedClass(name) != null

        private companion object {
            fun classpath() = System.getProperty("java.class.path").split(File.pathSeparatorChar)
                .filter { it.isNotEmpty() }
                .map { File(it).toURI().toURL() }
                .toTypedArray()
        }
    }
}
