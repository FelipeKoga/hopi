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
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * The provider's start, from `hopi-core`, run through its companion functions with the lines they
 * write collected: the generated stand-in (`generated/HopiGeneratedConfig.kt`) lists two fake
 * tools, and a class defined from bytes lists none (`network { enabled = false }` with perf off),
 * both with `agentBridge` off, so no thread or bridge starts here.
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
    fun aConfigurationWithNoToolStartsAndWritesOnlyTheStartLine() {
        // What the plugin generates with every tool off: the start sequence runs (the rules runtime,
        // the bridge with agentBridge) with no tool, and its line marks the end of it.
        val started = assertNotNull(HopiInitProvider.start(NoToolConfigLoader(), null, lines::add))
        assertEquals(emptyList(), started.map { it.name })
        assertNotNull(HopiStart.config)
        val line = lines.single()
        assertTrue(line.startsWith(notice("android capture started (").removeSuffix("\"}")), line)
        // No tool part, and no "no generated configuration" notice: the configuration was found.
        assertTrue(Regex("""^\{"hopi":1,"notice":"android capture started \(\d+ us; config \d+ us\)"}$""").matches(line), line)
    }

    /**
     * Defines `HopiGeneratedConfig` child-first, from bytes ASM writes: the class the plugin
     * generates with an empty tool list (default caps, `summaryLog` on, `agentBridge` off). Every
     * other class, `HopiConfigValues` included, is the parent's, where the two-tool stand-in of the
     * same name also lives.
     */
    private class NoToolConfigLoader : ClassLoader(GeneratedConfigTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != GeneratedConfig.CLASS_NAME) return super.loadClass(name, resolve)
            // The loader itself: android.jar's ClassLoader, which this compiles against, has no getClassLoadingLock.
            synchronized(this) {
                return findLoadedClass(name) ?: defineClass(name, NO_TOOL_CONFIG, 0, NO_TOOL_CONFIG.size).also { if (resolve) resolveClass(it) }
            }
        }
    }

    @Test
    fun withoutTheGeneratedClassNothingStarts() {
        val empty = object : ClassLoader(null) {}
        assertNull(GeneratedConfig.read(empty))
        assertNull(HopiInitProvider.start(empty, null, lines::add))
        assertEquals(listOf(notice("not started: no generated configuration; apply the io.github.felipekoga.hopi Gradle plugin to the app")), lines)
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

    private companion object {
        /** `public final class HopiGeneratedConfig extends HopiConfigValues { HopiGeneratedConfig() { super(500, 16 MiB, 256 KiB, true, false, false, {}, {}); } }` */
        val NO_TOOL_CONFIG: ByteArray = ClassWriter(ClassWriter.COMPUTE_MAXS).run {
            val name = GeneratedConfig.CLASS_NAME.replace('.', '/')
            val base = "dev/koga/hopi/android/internal/HopiConfigValues"
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, name, null, base, null)
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitLdcInsn(500)
                visitLdcInsn(16L * 1024 * 1024)
                visitLdcInsn(256L * 1024)
                visitInsn(Opcodes.ICONST_1) // summaryLog
                visitInsn(Opcodes.ICONST_0) // agentBridge
                visitInsn(Opcodes.ICONST_0) // redactionDisabled
                for (type in listOf("java/lang/String", "java/lang/String", "dev/koga/hopi/core/start/HopiTool")) {
                    visitInsn(Opcodes.ICONST_0)
                    visitTypeInsn(Opcodes.ANEWARRAY, type)
                }
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL, base, "<init>",
                    "(IJJZZZ[Ljava/lang/String;[Ljava/lang/String;[Ldev/koga/hopi/core/start/HopiTool;)V", false,
                )
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
            toByteArray()
        }
    }
}
