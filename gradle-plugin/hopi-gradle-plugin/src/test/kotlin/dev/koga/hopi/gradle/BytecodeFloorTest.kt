package dev.koga.hopi.gradle

import dev.koga.hopi.instrument.HopiRewriter
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * What an app's Gradle loads of the plugin, its own classes and hopi-instrument's, runs on the oldest
 * build the plugin supports, Gradle 8.0 and AGP 8.0: no class file is newer than Java 17's (major
 * 61), and every class and member they name exists where that build supplies it: under `kotlin/`, in
 * the Kotlin standard library Gradle 8.0 runs them with, 1.8.10 (`-Dhopi.stdlibFloor`); under
 * `org/gradle/`, in Gradle 8.0.2's API (`-Dhopi.gradleApiFloor`); under `com/android/`, in AGP
 * 8.0.2's (`-Dhopi.agpApiFloor`), except AGP 8.3's variant API in the two methods that HopiAndroid
 * calls from AGP 8.3 on only ([AGP_8_3_ONLY]). The plugin compiles against Gradle 9.7.1's API and AGP
 * 8.3.2's, at apiVersion 1.8, which refuses what the standard library marks as newer, but not a
 * declaration added in a 1.8 patch or without the mark, nor what the compiler emits on its own: the
 * bytecode shows all of them.
 */
class BytecodeFloorTest {
    private val floor = Library(
        listOf(
            Floor("kotlin/", "Kotlin 1.8.10's standard library", "hopi.stdlibFloor"),
            Floor("org/gradle/", "Gradle 8.0.2's API", "hopi.gradleApiFloor"),
            Floor("com/android/", "AGP 8.0.2's API", "hopi.agpApiFloor"),
        ),
    )

    @AfterTest
    fun closeTheFloor() = floor.close()

    @Test
    fun theClassesGradleLoadsNeedNothingAboveJava17AndTheFloorsLibraries() {
        val sources = listOf(HopiPlugin::class.java, HopiRewriter::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()) }
            .distinct()
        val problems = sortedSetOf<String>()
        val allowed = sortedSetOf<String>()
        var read = 0
        for ((name, bytes) in sources.flatMap(::classFiles)) {
            read++
            val reader = ClassReader(bytes)
            val major = reader.readUnsignedShort(6)
            if (major > JAVA_17) problems += "$name: class file version $major, above $JAVA_17 (Java 17)"
            val references = References { method, reference ->
                val problem = floor.problem(reference)
                if (problem != null) {
                    val site = if (method == null) name else "$name.$method"
                    if (reference.key in AGP_8_3_ONLY[site].orEmpty()) {
                        allowed += "$site: ${reference.key}"
                    } else {
                        problems += "$site: $problem"
                    }
                }
            }
            reader.accept(references, ClassReader.SKIP_FRAMES)
        }
        // Both code sources were read, the plugin's and the rewriter's (a classes directory or a jar each).
        assertTrue(read >= 20 && sources.size == 2, "read $read classes from $sources")
        assertEquals(emptyList(), problems.toList(), "what Gradle 8.0 and AGP 8.0 cannot load")
        // Each exemption is used, from its own method: the check sees AGP 8.3's API there.
        assertEquals(AGP_8_3_ONLY.flatMap { (site, keys) -> keys.map { "$site: $it" } }.toSortedSet(), allowed)
    }

    /** The check itself: what each floor's library lacks is reported, and what it inherits is found. */
    @Test
    fun theCheckReportsWhatTheFloorsLibrariesLack() {
        // Kotlin 1.8.20's Base64, and 1.9's Path.createParentDirectories on a facade 1.8.10 has.
        assertNotNull(floor.problem(Reference("kotlin/io/encoding/Base64")))
        val parentDirectories = "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"
        assertNotNull(floor.problem(Reference("kotlin/io/path/PathsKt", "createParentDirectories", parentDirectories, field = false)))
        // A facade's member, declared by one of its parts; a member a JDK superclass declares; a field.
        assertNull(floor.problem(Reference("kotlin/collections/CollectionsKt", "listOf", "([Ljava/lang/Object;)Ljava/util/List;", field = false)))
        assertNull(floor.problem(Reference("kotlin/collections/AbstractMutableList", "iterator", "()Ljava/util/Iterator;", field = false)))
        assertNull(floor.problem(Reference("kotlin/Unit", "INSTANCE", "Lkotlin/Unit;", field = true)))
        // FlowAction and Provider.filter, which Gradle added after 8.0.2; Provider.zip, which 8.0.2
        // has; NamedDomainObjectContainer.iterator, which java.util.Collection declares.
        assertNotNull(floor.problem(Reference("org/gradle/api/flow/FlowAction")))
        val filter = "(Lorg/gradle/api/specs/Spec;)Lorg/gradle/api/provider/Provider;"
        assertNotNull(floor.problem(Reference("org/gradle/api/provider/Provider", "filter", filter, field = false)))
        val zip = "(Lorg/gradle/api/provider/Provider;Ljava/util/function/BiFunction;)Lorg/gradle/api/provider/Provider;"
        assertNull(floor.problem(Reference("org/gradle/api/provider/Provider", "zip", zip, field = false)))
        assertNull(floor.problem(Reference("org/gradle/api/NamedDomainObjectContainer", "iterator", "()Ljava/util/Iterator;", field = false)))
        assertNotNull(floor.problem(Reference("org/gradle/api/Project", "missing", "()V", field = false)))
        // AGP 8.3's LifecycleTasks and Component.debuggable; Component.instrumentation, which 8.0.2 has.
        assertNotNull(floor.problem(Reference("com/android/build/api/variant/LifecycleTasks")))
        assertNotNull(floor.problem(Reference("com/android/build/api/variant/ApplicationVariant", "getDebuggable", "()Z", field = false)))
        val instrumentation = "()Lcom/android/build/api/variant/Instrumentation;"
        assertNull(floor.problem(Reference("com/android/build/api/variant/ApplicationVariant", "getInstrumentation", instrumentation, field = false)))
        // Only those three prefixes are checked: the rest comes from the JDK, the Kotlin Gradle plugin and ASM.
        assertNull(floor.problem(Reference("org/jetbrains/kotlin/gradle/plugin/Missing")))
        assertNull(floor.problem(Reference("org/objectweb/asm/ClassVisitor", "missing", "()V", field = false)))
    }

    private companion object {
        const val JAVA_17 = 61

        /**
         * AGP 8.3's variant API, which HopiAndroid calls from AGP 8.3 on only (its
         * `variantLifecycleApi`): each reference AGP 8.0.2's API lacks, by the one method allowed to
         * make it (`class.method`). Anything else of AGP 8.3 fails the check, as do these elsewhere.
         */
        val AGP_8_3_ONLY: Map<String, Set<String>> = mapOf(
            "dev/koga/hopi/gradle/HopiAndroid.debuggable" to setOf("com/android/build/api/variant/ApplicationVariant.getDebuggable()Z"),
            "dev/koga/hopi/gradle/HopiAndroid.runBeforePreBuild" to setOf(
                "com/android/build/api/variant/ApplicationVariant.getLifecycleTasks()Lcom/android/build/api/variant/LifecycleTasks;",
                "com/android/build/api/variant/LifecycleTasks",
                "com/android/build/api/variant/LifecycleTasks.registerPreBuild([Ljava/lang/Object;)V",
            ),
        )
    }
}

/** A class the bytecode names, or a member of it: a field or a method, by [name] and [descriptor]. */
private data class Reference(val owner: String, val name: String? = null, val descriptor: String? = null, val field: Boolean = false) {
    /** `owner`, `owner.name(descriptor)` for a method, `owner.name:descriptor` for a field. */
    val key: String get() = if (name == null) owner else "$owner.${memberKey(name, descriptor!!, this.field)}"
}

private fun memberKey(name: String, descriptor: String, field: Boolean) = if (field) "$name:$descriptor" else "$name$descriptor"

/**
 * Each class and member a class file names: its supertypes, the types of its members, every type and
 * member its instructions use (constants and bootstrap methods included), and its annotations; with
 * the method that names it (its descriptor, annotations or code), or null outside a method.
 */
private class References(private val found: (method: String?, reference: Reference) -> Unit) : ClassVisitor(Opcodes.ASM9) {
    /** The method being read: ClassReader visits each method whole, after the class's annotations and fields. */
    private var method: String? = null

    private fun report(reference: Reference) = found(method, reference)

    private fun ofType(type: Type) {
        when (type.sort) {
            Type.ARRAY -> ofType(type.elementType)
            Type.OBJECT -> report(Reference(type.internalName))
            Type.METHOD -> {
                type.argumentTypes.forEach(::ofType)
                ofType(type.returnType)
            }
        }
    }

    private fun ofDescriptor(descriptor: String) = ofType(Type.getType(descriptor))

    private fun ofInternalName(name: String) = ofType(Type.getObjectType(name))

    private fun ofMember(owner: String, name: String, descriptor: String, field: Boolean) {
        ofInternalName(owner)
        ofDescriptor(descriptor)
        // An array's members (clone) are the JVM's own.
        if (!owner.startsWith("[")) report(Reference(owner, name, descriptor, field))
    }

    private fun ofConstant(value: Any?) {
        when (value) {
            is Type -> ofType(value)
            is Handle -> ofMember(value.owner, value.name, value.desc, field = value.tag <= Opcodes.H_PUTSTATIC)
            is ConstantDynamic -> {
                ofDescriptor(value.descriptor)
                ofConstant(value.bootstrapMethod)
                for (i in 0 until value.bootstrapMethodArgumentCount) ofConstant(value.getBootstrapMethodArgument(i))
            }
        }
    }

    private val annotationValues = object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) = ofConstant(value)

        override fun visitEnum(name: String?, descriptor: String, value: String) = ofDescriptor(descriptor)

        override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor = ofAnnotation(descriptor)

        override fun visitArray(name: String?): AnnotationVisitor = this
    }

    private fun ofAnnotation(descriptor: String): AnnotationVisitor {
        ofDescriptor(descriptor)
        return annotationValues
    }

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        superName?.let(::ofInternalName)
        interfaces?.forEach(::ofInternalName)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = ofAnnotation(descriptor)

    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
        ofDescriptor(descriptor)
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = ofAnnotation(descriptor)
        }
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        method = name
        ofDescriptor(descriptor)
        exceptions?.forEach(::ofInternalName)
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = ofAnnotation(descriptor)

            override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor = ofAnnotation(descriptor)

            override fun visitAnnotationDefault(): AnnotationVisitor = annotationValues

            override fun visitTypeInsn(opcode: Int, type: String) = ofInternalName(type)

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) = ofMember(owner, name, descriptor, field = true)

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) =
                ofMember(owner, name, descriptor, field = false)

            override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any?) {
                ofDescriptor(descriptor)
                ofConstant(bootstrapMethodHandle)
                bootstrapMethodArguments.forEach(::ofConstant)
            }

            override fun visitLdcInsn(value: Any?) = ofConstant(value)

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) = ofDescriptor(descriptor)

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                type?.let(::ofInternalName)
            }
        }
    }
}

/** The library an app's oldest build supplies the classes under [prefix] from: [label], the jar the system property [property] names. */
private class Floor(val prefix: String, val label: String, property: String) {
    val file = File(System.getProperty(property) ?: error("no -D$property"))
}

/**
 * The [floors]' libraries, each for the classes under its prefix, with the JDK for every other class
 * (the supertypes of theirs): what each class declares, for references under those prefixes only.
 * The jars are read one class at a time (Gradle's API is 158 MB).
 */
private class Library(private val floors: List<Floor>) : AutoCloseable {
    private val jars: Map<Floor, ZipFile> = floors.associateWith { ZipFile(it.file) }
    private val read = HashMap<String, Declarations>()

    private class Declarations(val supertypes: List<String>, val members: Set<String>)

    /** Why [reference] does not resolve here, or null when it does or names no class under a floor's prefix. */
    fun problem(reference: Reference): String? {
        val floor = floorOf(reference.owner) ?: return null
        if (declarations(reference.owner) == null) return "${reference.owner} is not in ${floor.label}"
        val name = reference.name ?: return null
        val member = memberKey(name, reference.descriptor!!, reference.field)
        return if (declares(reference.owner, member)) null else "${reference.owner}.$member is not in ${floor.label}"
    }

    override fun close() = jars.values.forEach(ZipFile::close)

    private fun floorOf(name: String): Floor? = floors.firstOrNull { name.startsWith(it.prefix) }

    /** Whether [owner] or one of its supertypes declares [member], as the JVM resolves it (in any order). */
    private fun declares(owner: String, member: String): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(owner))
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!seen.add(type)) continue
            val declared = declarations(type) ?: continue
            if (member in declared.members) return true
            queue += declared.supertypes
        }
        return false
    }

    /**
     * A class under a floor's prefix from that floor's jar only (this test's own classpath holds newer
     * ones: the catalog's standard library, Gradle 9.7.1's API, AGP's), any other from the JDK.
     */
    private fun declarations(name: String): Declarations? {
        read[name]?.let { return it }
        val floor = floorOf(name)
        val bytes = if (floor != null) {
            val jar = jars.getValue(floor)
            jar.getEntry("$name.class")?.let { entry -> jar.getInputStream(entry).use { it.readBytes() } }
        } else {
            ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() }
        }
        return bytes?.let(::parse)?.also { read[name] = it }
    }

    private fun parse(bytes: ByteArray): Declarations {
        var supertypes = emptyList<String>()
        val members = HashSet<String>()
        val reader = object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                supertypes = listOfNotNull(superName) + interfaces.orEmpty()
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                members += memberKey(name, descriptor, field = true)
                return null
            }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                members += memberKey(name, descriptor, field = false)
                return null
            }
        }
        ClassReader(bytes).accept(reader, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return Declarations(supertypes, members)
    }
}

/** The class files of a classes directory or a jar, by internal name; module and versioned entries left out. */
private fun classFiles(source: File): List<Pair<String, ByteArray>> =
    if (source.isDirectory) {
        source.walkTopDown()
            .filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
            .map { it.relativeTo(source).invariantSeparatorsPath.removeSuffix(".class") to it.readBytes() }
            .toList()
    } else {
        ZipFile(source).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && !it.name.endsWith("module-info.class") }
                .map { entry -> entry.name.removeSuffix(".class") to zip.getInputStream(entry).use { it.readBytes() } }
                .toList()
        }
    }
