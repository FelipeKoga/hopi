package dev.koga.hopi.instrument

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * The build-time rewrite of docs/design/android-capture.md ("Interception points"). Every change
 * replaces one instruction with one of the same stack effect, or one bootstrap handle with another,
 * or adds one class annotation: no local, no branch, no stack growth, so frames copied from the
 * input stay valid and no class hierarchy is loaded.
 *
 * - In OkHttp's `RealCall`: `invokevirtual OkHttpClient.interceptors()` becomes
 *   `invokestatic HopiOkHttpHooks.interceptors(OkHttpClient)`, and the class gains
 *   `@HopiInstrumented` when that happened.
 * - Everywhere: `invokevirtual URL.openConnection()`, `openConnection(Proxy)`, `openStream()`,
 *   `getContent()` and `getContent(Class[])`, and the same methods as `H_INVOKEVIRTUAL` handles of a
 *   `LambdaMetafactory` bootstrap (a method reference), go to the static hook of the same name in
 *   `HopiUrlHooks`, which takes the `URL` first.
 *
 * Classes of Hopi's own runtime (`dev.koga.hopi.android.`) are never rewritten.
 */
public object HopiRewriter {
    /** What a rewrite should touch. */
    public data class Options(val okhttp: Boolean = true, val urlConnection: Boolean = true)

    /** Called once per rewritten instruction or handle, with the class's internal name. */
    public fun interface Listener {
        public fun rewrote(className: String, what: String)
    }

    /** Whether AGP must hand the class named [className] (dotted, as `ClassData` gives it) to [visitor]. */
    public fun isInstrumentable(className: String, options: Options): Boolean {
        val internal = className.replace('.', '/')
        if (internal.startsWith(HookDescriptors.RUNTIME_PACKAGE)) return false
        if (options.okhttp && internal in HookDescriptors.REAL_CALL_CLASSES) return true
        // URL call sites cannot be seen from the class's name, supertypes or annotations.
        return options.urlConnection
    }

    /**
     * The visitor that rewrites the class named [className] (internal or dotted name) into [next].
     * [api] is the ASM API level (AGP's `instrumentationContext.apiVersion`).
     */
    public fun visitor(api: Int, next: ClassVisitor, className: String, options: Options, listener: Listener? = null): ClassVisitor {
        val internal = className.replace('.', '/')
        if (internal.startsWith(HookDescriptors.RUNTIME_PACKAGE)) return next
        val urlStage = if (options.urlConnection) UrlCallSiteVisitor(api, next, listener) else next
        return if (options.okhttp && internal in HookDescriptors.REAL_CALL_CLASSES) RealCallVisitor(api, urlStage, listener) else urlStage
    }

    /** Rewrites one class file, for tests and tools; returns the input itself when nothing changed. */
    public fun rewrite(bytes: ByteArray, options: Options = Options(), listener: Listener? = null): ByteArray {
        val reader = ClassReader(bytes)
        if (!isInstrumentable(reader.className, options)) return bytes
        var changed = false
        val counting = Listener { name, what ->
            changed = true
            listener?.rewrote(name, what)
        }
        // COPY_FRAMES, as AGP's default: the reader's frames are written back unchanged.
        val writer = ClassWriter(reader, 0)
        reader.accept(visitor(Opcodes.ASM9, writer, reader.className, options, counting), 0)
        return if (changed) writer.toByteArray() else bytes
    }

    /**
     * Buffers OkHttp's `RealCall` (one class) so the annotation can be added in its place, before
     * the members, and only once the rewrite is known to have happened.
     */
    private class RealCallVisitor(api: Int, private val next: ClassVisitor, private val listener: Listener?) : ClassNode(api) {
        override fun visitEnd() {
            super.visitEnd()
            var rewritten = 0
            for (method in methods) {
                val instructions = method.instructions ?: continue
                for (instruction in instructions.toArray()) {
                    if (instruction !is MethodInsnNode) continue
                    if (instruction.opcode != Opcodes.INVOKEVIRTUAL || instruction.owner != HookDescriptors.OKHTTP_CLIENT ||
                        instruction.name != HookDescriptors.INTERCEPTORS || instruction.desc != HookDescriptors.INTERCEPTORS_DESCRIPTOR
                    ) {
                        continue
                    }
                    instructions.set(
                        instruction,
                        MethodInsnNode(Opcodes.INVOKESTATIC, HookDescriptors.OKHTTP_HOOKS, HookDescriptors.INTERCEPTORS, HookDescriptors.OKHTTP_HOOK_DESCRIPTOR, false),
                    )
                    rewritten++
                    listener?.rewrote(name, "OkHttpClient.interceptors in ${method.name}")
                }
            }
            val annotated = visibleAnnotations?.any { it.desc == HookDescriptors.INSTRUMENTED_ANNOTATION } == true
            if (rewritten > 0 && !annotated) {
                val list = visibleAnnotations ?: ArrayList<AnnotationNode>().also { visibleAnnotations = it }
                list += AnnotationNode(api, HookDescriptors.INSTRUMENTED_ANNOTATION)
            }
            accept(next)
        }
    }

    private class UrlCallSiteVisitor(api: Int, next: ClassVisitor, private val listener: Listener?) : ClassVisitor(api, next) {
        private var className = ""

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            className = name
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val method = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return UrlMethodVisitor(api, method)
        }

        private inner class UrlMethodVisitor(api: Int, next: MethodVisitor) : MethodVisitor(api, next) {
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                val hook = if (opcode == Opcodes.INVOKEVIRTUAL && owner == HookDescriptors.URL) HookDescriptors.urlHook(name, descriptor) else null
                if (hook == null) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    return
                }
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HookDescriptors.URL_HOOKS, hook.name, hook.hookDescriptor, false)
                listener?.rewrote(className, "call $hook")
            }

            override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bootstrap: Handle, vararg arguments: Any?) {
                if (bootstrap.owner != LAMBDA_METAFACTORY) {
                    super.visitInvokeDynamicInsn(name, descriptor, bootstrap, *arguments)
                    return
                }
                val rewritten = arguments.map { argument ->
                    val handle = argument as? Handle ?: return@map argument
                    val hook = if (handle.tag == Opcodes.H_INVOKEVIRTUAL && handle.owner == HookDescriptors.URL) HookDescriptors.urlHook(handle.name, handle.desc) else null
                    if (hook == null) return@map argument
                    listener?.rewrote(className, "handle $hook")
                    Handle(Opcodes.H_INVOKESTATIC, HookDescriptors.URL_HOOKS, hook.name, hook.hookDescriptor, false)
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrap, *rewritten.toTypedArray())
            }
        }
    }

    private const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"
}
