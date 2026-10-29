package dev.koga.hopi.instrument

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicVerifier

class HopiRewriterTest {
    @Test
    fun everyUrlCallSiteAndMethodReferenceGoesToItsHook() {
        val classes = compile(FIXTURE_SOURCES)
        val original = classes.getValue("fixture/UrlCalls")
        val rewrites = mutableListOf<String>()
        val rewritten = HopiRewriter.rewrite(original, listener = { _, what -> rewrites += what })

        // Five direct calls, one in a lambda body, one in a constructor, one in a finally block
        // (javac copies it per exit path), and one method reference.
        assertEquals(
            setOf(
                "call URL.openConnection()Ljava/net/URLConnection;",
                "call URL.openConnection(Ljava/net/Proxy;)Ljava/net/URLConnection;",
                "call URL.openStream()Ljava/io/InputStream;",
                "call URL.getContent()Ljava/lang/Object;",
                "call URL.getContent([Ljava/lang/Class;)Ljava/lang/Object;",
                "handle URL.openConnection()Ljava/net/URLConnection;",
            ),
            rewrites.toSet(),
        )
        val before = node(original)
        val after = node(rewritten)
        assertNoUrlCalls(after)
        for ((old, new) in before.methods.zip(after.methods)) {
            assertEquals(old.maxStack, new.maxStack, old.name)
            assertEquals(old.maxLocals, new.maxLocals, old.name)
            assertSameExceptHooks(old, new)
            verify(after.name, new)
        }
    }

    @Test
    fun rewrittenCodeRunsThroughTheHooksWithTheSameResult() {
        val classes = compile(FIXTURE_SOURCES)
        val loader = DefinedClassLoader(classes.mapValues { (_, bytes) -> HopiRewriter.rewrite(bytes) })
        val fixture = loader.loadClass("fixture.UrlCalls")
        val file = Files.createTempFile("hopi-rewriter", ".txt").toFile().apply { writeText("hello") }
        try {
            val result = fixture.getMethod("run", String::class.java).invoke(null, file.toURI().toString()) as String
            assertEquals("hello|hello|ok", result)
            val hooks = loader.loadClass("dev.koga.hopi.android.internal.HopiUrlHooks")
            @Suppress("UNCHECKED_CAST")
            val calls = hooks.getField("CALLS").get(null) as List<String>
            // openStream, openConnection through the method reference, openConnection in the constructor.
            assertEquals(listOf("openStream", "openConnection", "openConnection"), calls)
        } finally {
            file.delete()
        }
    }

    @Test
    fun hopisOwnRuntimeIsNeverRewritten() {
        val classes = compile(FIXTURE_SOURCES)
        val hooks = classes.getValue("dev/koga/hopi/android/internal/HopiUrlHooks")
        assertSame(hooks, HopiRewriter.rewrite(hooks))
        assertFalse(HopiRewriter.isInstrumentable("dev.koga.hopi.android.internal.HopiUrlHooks", HopiRewriter.Options()))
        assertFalse(HopiRewriter.isInstrumentable("dev.koga.hopi.android.Anything", HopiRewriter.Options()))
        assertFalse(HopiRewriter.isInstrumentable("dev.koga.hopi.android.generated.HopiGeneratedConfig", HopiRewriter.Options()))
        assertTrue(HopiRewriter.isInstrumentable("dev.koga.hopi.core.Anything", HopiRewriter.Options()))
        assertTrue(HopiRewriter.isInstrumentable("okhttp3.internal.connection.RealCall", HopiRewriter.Options(urlConnection = false)))
        assertFalse(HopiRewriter.isInstrumentable("okhttp3.OkHttpClient", HopiRewriter.Options(urlConnection = false)))
        assertFalse(HopiRewriter.isInstrumentable("okhttp3.internal.connection.RealCall", HopiRewriter.Options(okhttp = false, urlConnection = false)))
    }

    @Test
    fun okHttp4RealCallGetsExactlyOneRewriteAndTheAnnotation() = checkRealCall(System.getProperty("hopi.okhttp4"))

    @Test
    fun okHttp5RealCallGetsExactlyOneRewriteAndTheAnnotation() = checkRealCall(System.getProperty("hopi.okhttp5"))

    private fun checkRealCall(jarPath: String) {
        val rewrites = mutableListOf<Pair<String, String>>()
        ZipFile(jarPath).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                val rewritten = HopiRewriter.rewrite(bytes, listener = { name, what -> rewrites += name to what })
                if (rewritten === bytes) continue
                val before = node(bytes)
                val after = node(rewritten)
                assertEquals(listOf(HookDescriptors.INSTRUMENTED_ANNOTATION), after.visibleAnnotations.orEmpty().map { it.desc } - before.visibleAnnotations.orEmpty().map { it.desc }.toSet())
                for ((old, new) in before.methods.zip(after.methods)) {
                    assertEquals(old.maxStack, new.maxStack)
                    assertSameExceptHooks(old, new)
                    verify(after.name, new)
                }
                // Rewriting a rewritten class changes nothing more and adds no second annotation.
                assertEquals(1, node(HopiRewriter.rewrite(rewritten)).visibleAnnotations.count { it.desc == HookDescriptors.INSTRUMENTED_ANNOTATION })
            }
        }
        assertEquals(
            listOf("okhttp3/internal/connection/RealCall" to "OkHttpClient.interceptors in getResponseWithInterceptorChain\$okhttp"),
            rewrites,
            "in $jarPath",
        )
    }

    private fun assertNoUrlCalls(node: ClassNode) {
        for (method in node.methods) {
            for (instruction in method.instructions) {
                if (instruction is MethodInsnNode) assertFalse(instruction.owner == "java/net/URL" && instruction.name != "<init>" && instruction.name != "toURI", "${method.name}: ${instruction.name}")
                if (instruction is InvokeDynamicInsnNode) {
                    assertFalse(instruction.bsmArgs.any { it is Handle && it.owner == "java/net/URL" }, method.name)
                }
            }
        }
    }

    /** The two methods' instructions are identical, except that a URL or OkHttp call became its hook. */
    private fun assertSameExceptHooks(old: MethodNode, new: MethodNode) {
        val a = old.instructions.toArray().filter { it.opcode >= 0 }
        val b = new.instructions.toArray().filter { it.opcode >= 0 }
        assertEquals(a.size, b.size, old.name)
        for ((x, y) in a.zip(b)) {
            if (x is MethodInsnNode && y is MethodInsnNode && x.owner != y.owner) {
                assertEquals(Opcodes.INVOKEVIRTUAL, x.opcode)
                assertEquals(Opcodes.INVOKESTATIC, y.opcode)
                assertTrue(y.owner == HookDescriptors.URL_HOOKS || y.owner == HookDescriptors.OKHTTP_HOOKS, y.owner)
                assertEquals(x.name, y.name)
                assertEquals("(L${x.owner};" + x.desc.removePrefix("("), y.desc)
                continue
            }
            if (x is InvokeDynamicInsnNode && y is InvokeDynamicInsnNode) {
                assertEquals(x.desc, y.desc)
                assertEquals(x.bsm, y.bsm)
                assertEquals(x.bsmArgs.size, y.bsmArgs.size)
                continue
            }
            assertEquals(describe(x), describe(y), old.name)
        }
    }

    private fun describe(instruction: AbstractInsnNode): String = when (instruction) {
        is MethodInsnNode -> "${instruction.opcode} ${instruction.owner}.${instruction.name}${instruction.desc}"
        else -> "${instruction.opcode} ${instruction.javaClass.simpleName}"
    }

    private fun verify(owner: String, method: MethodNode) {
        if (method.instructions.size() == 0) return
        Analyzer(BasicVerifier()).analyze(owner, method)
    }

    private fun node(bytes: ByteArray): ClassNode = ClassNode().also { ClassReader(bytes).accept(it, 0) }

    private class DefinedClassLoader(private val classes: Map<String, ByteArray>) : ClassLoader(HopiRewriterTest::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name) ?: if (classes.containsKey(name.replace('.', '/'))) findClass(name) else super.loadClass(name, resolve)
        }
    }

    private companion object {
        val FIXTURE_SOURCES = mapOf(
            "fixture/UrlCalls.java" to """
                package fixture;

                import java.io.InputStream;
                import java.net.Proxy;
                import java.net.URL;
                import java.net.URLConnection;

                public class UrlCalls {
                    interface Opener { URLConnection open(URL url) throws Exception; }

                    private final URLConnection opened;

                    public UrlCalls(URL url) throws Exception {
                        opened = url.openConnection();
                    }

                    public static Object all(URL url) throws Exception {
                        url.openConnection();
                        url.openConnection(Proxy.NO_PROXY);
                        url.getContent();
                        url.getContent(new Class<?>[] { InputStream.class });
                        try (InputStream in = url.openStream()) {
                            return in.read();
                        }
                    }

                    public static Runnable inLambda(URL url) {
                        return () -> {
                            try { url.openConnection(); } catch (Exception e) { throw new RuntimeException(e); }
                        };
                    }

                    public static void inFinally(URL url) throws Exception {
                        try {
                            System.nanoTime();
                        } finally {
                            url.openConnection();
                        }
                    }

                    public static String run(String spec) throws Exception {
                        URL url = new URL(spec);
                        String first;
                        try (InputStream in = url.openStream()) { first = new String(in.readAllBytes()); }
                        Opener open = URL::openConnection;
                        String second;
                        try (InputStream in = open.open(url).getInputStream()) { second = new String(in.readAllBytes()); }
                        return first + "|" + second + "|" + (new UrlCalls(url).opened != null ? "ok" : "none");
                    }
                }
            """.trimIndent(),
            // A recording stand-in for hopi-network's hooks, with their exact descriptors.
            "dev/koga/hopi/android/internal/HopiUrlHooks.java" to """
                package dev.koga.hopi.android.internal;

                import java.io.IOException;
                import java.io.InputStream;
                import java.net.Proxy;
                import java.net.URL;
                import java.net.URLConnection;
                import java.util.ArrayList;
                import java.util.List;

                public final class HopiUrlHooks {
                    public static final List<String> CALLS = new ArrayList<>();
                    public static URLConnection openConnection(URL url) throws IOException { CALLS.add("openConnection"); return url.openConnection(); }
                    public static URLConnection openConnection(URL url, Proxy proxy) throws IOException { CALLS.add("openConnectionProxy"); return url.openConnection(proxy); }
                    public static InputStream openStream(URL url) throws IOException { CALLS.add("openStream"); return url.openStream(); }
                    public static Object getContent(URL url) throws IOException { CALLS.add("getContent"); return url.getContent(); }
                    public static Object getContent(URL url, Class<?>[] types) throws IOException { CALLS.add("getContentTypes"); return url.getContent(types); }
                }
            """.trimIndent(),
        )

        /** Compiles Java sources with the JDK's compiler; returns class files by internal name. */
        fun compile(sources: Map<String, String>): Map<String, ByteArray> {
            val root = Files.createTempDirectory("hopi-fixtures").toFile()
            try {
                val sourceDir = File(root, "src")
                val out = File(root, "out").apply { mkdirs() }
                val files = sources.map { (path, text) -> File(sourceDir, path).apply { parentFile.mkdirs(); writeText(text) } }
                val compiler = ToolProvider.getSystemJavaCompiler()
                val args = listOf("-d", out.path, "--release", "11", "-g") + files.map { it.path }
                assertEquals(0, compiler.run(null, null, null, *args.toTypedArray()), "javac failed")
                return out.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                    .associate { it.relativeTo(out).invariantSeparatorsPath.removeSuffix(".class") to it.readBytes() }
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
