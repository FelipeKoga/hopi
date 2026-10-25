package dev.koga.hopi.instrument

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads what an APK's dex files define and call, with no SDK tool: the classes they define, every
 * type and method they reference, and for each defined class the methods its code invokes (from
 * the instructions themselves). The sample's release and debug checks and the plugin's TestKit
 * tests use it to prove what was, and was not, instrumented.
 */
public class DexReader private constructor(private val dexFiles: List<Dex>) {
    /** A method reference, by internal owner name, name and JVM-style descriptor. */
    public data class MethodRef(val owner: String, val name: String, val descriptor: String) {
        override fun toString(): String = "$owner.$name$descriptor"
    }

    /** Internal names (`a/b/C`) of the classes the dex files define. */
    public val definedClasses: Set<String> by lazy { dexFiles.flatMapTo(sortedSetOf()) { it.definedClasses() } }

    /** Internal names of every class type the dex files reference, defined or not. */
    public val referencedTypes: Set<String> by lazy { dexFiles.flatMapTo(sortedSetOf()) { it.referencedTypes() } }

    /** Every method the dex files reference (method ids), defined or not. */
    public val referencedMethods: Set<MethodRef> by lazy { dexFiles.flatMapTo(mutableSetOf()) { it.methodRefs() } }

    /** For each defined class, the methods its code invokes (`invoke-*` instructions). */
    public val invokesByClass: Map<String, Set<MethodRef>> by lazy {
        val result = sortedMapOf<String, MutableSet<MethodRef>>()
        for (dex in dexFiles) dex.invokes(result)
        result
    }

    /** The defined classes whose code invokes [method]. */
    public fun callersOf(method: MethodRef): Set<String> = invokesByClass.filterValues { method in it }.keys

    public companion object {
        private val DEX_ENTRY = Regex("classes\\d*\\.dex")

        /** Reads every `classes*.dex` of [apk]. */
        public fun ofApk(apk: File): DexReader = ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }.toList()
            require(entries.isNotEmpty()) { "${apk.name} has no classes*.dex" }
            DexReader(entries.map { entry -> Dex(zip.getInputStream(entry).use { it.readAllBytes() }, entry.name) })
        }

        /** Reads dex files given as bytes. */
        public fun of(vararg dexBytes: ByteArray): DexReader = DexReader(dexBytes.mapIndexed { i, bytes -> Dex(bytes, "dex$i") })
    }

    private class Dex(private val bytes: ByteArray, name: String) {
        private val dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        init {
            require(bytes.size >= 0x70 && String(bytes, 0, 4, Charsets.US_ASCII) == "dex\n") { "$name is not a dex file" }
        }

        private val stringIdsOff = dex.getInt(0x3C)
        private val typeIdsSize = dex.getInt(0x40)
        private val typeIdsOff = dex.getInt(0x44)
        private val protoIdsOff = dex.getInt(0x4C)
        private val methodIdsSize = dex.getInt(0x58)
        private val methodIdsOff = dex.getInt(0x5C)
        private val classDefsSize = dex.getInt(0x60)
        private val classDefsOff = dex.getInt(0x64)

        private val strings = HashMap<Int, String>()
        private val methods = arrayOfNulls<MethodRef>(methodIdsSize)

        fun string(index: Int): String = strings.getOrPut(index) {
            var offset = dex.getInt(stringIdsOff + index * 4)
            // string_data_item: the length in UTF-16 units as uleb128, then MUTF-8 bytes up to a NUL.
            while (bytes[offset].toInt() and 0x80 != 0) offset++
            offset++
            var end = offset
            while (bytes[end].toInt() != 0) end++
            String(bytes, offset, end - offset, Charsets.UTF_8)
        }

        fun typeDescriptor(index: Int): String = string(dex.getInt(typeIdsOff + index * 4))

        private fun internalName(descriptor: String): String = descriptor.removePrefix("L").removeSuffix(";")

        fun definedClasses(): List<String> = (0 until classDefsSize).map { internalName(typeDescriptor(dex.getInt(classDefsOff + it * 32))) }

        fun referencedTypes(): List<String> = (0 until typeIdsSize).map { typeDescriptor(it) }
            .map { it.trimStart('[') }
            .filter { it.startsWith("L") }
            .map(::internalName)

        fun methodRefs(): List<MethodRef> = (0 until methodIdsSize).map(::method)

        fun method(index: Int): MethodRef = methods[index] ?: run {
            val offset = methodIdsOff + index * 8
            val owner = typeDescriptor(dex.getShort(offset).toInt() and 0xFFFF)
            val protoOffset = protoIdsOff + (dex.getShort(offset + 2).toInt() and 0xFFFF) * 12
            val name = string(dex.getInt(offset + 4))
            val returnType = typeDescriptor(dex.getInt(protoOffset + 4))
            val parametersOff = dex.getInt(protoOffset + 8)
            val parameters = StringBuilder()
            if (parametersOff != 0) {
                val count = dex.getInt(parametersOff)
                for (i in 0 until count) parameters.append(typeDescriptor(dex.getShort(parametersOff + 4 + i * 2).toInt() and 0xFFFF))
            }
            MethodRef(internalName(owner), name, "($parameters)$returnType").also { methods[index] = it }
        }

        fun invokes(into: MutableMap<String, MutableSet<MethodRef>>) {
            for (i in 0 until classDefsSize) {
                val def = classDefsOff + i * 32
                val className = internalName(typeDescriptor(dex.getInt(def)))
                val classDataOff = dex.getInt(def + 24)
                if (classDataOff == 0) continue
                val calls = into.getOrPut(className) { mutableSetOf() }
                val cursor = intArrayOf(classDataOff)
                val staticFields = uleb(cursor)
                val instanceFields = uleb(cursor)
                val directMethods = uleb(cursor)
                val virtualMethods = uleb(cursor)
                repeat((staticFields + instanceFields) * 2) { uleb(cursor) }
                repeat(directMethods + virtualMethods) {
                    uleb(cursor) // method_idx_diff
                    uleb(cursor) // access_flags
                    val codeOff = uleb(cursor)
                    if (codeOff != 0) invokesInCode(codeOff, calls)
                }
            }
        }

        private fun uleb(cursor: IntArray): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = bytes[cursor[0]++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Walks a code_item's instructions, by each opcode's format width, and keeps the invoked methods. */
        private fun invokesInCode(codeOff: Int, calls: MutableSet<MethodRef>) {
            val insnsSize = dex.getInt(codeOff + 12)
            val insns = codeOff + 16
            var pc = 0
            while (pc < insnsSize) {
                val unit = dex.getShort(insns + pc * 2).toInt() and 0xFFFF
                val opcode = unit and 0xFF
                if (opcode == 0 && unit != 0) {
                    // Payload pseudo-instructions: packed-switch, sparse-switch, fill-array-data.
                    val size = dex.getShort(insns + pc * 2 + 2).toInt() and 0xFFFF
                    pc += when (unit) {
                        0x0100 -> size * 2 + 4
                        0x0200 -> size * 4 + 2
                        0x0300 -> {
                            val elementCount = dex.getInt(insns + pc * 2 + 4).toLong()
                            ((elementCount * size + 1) / 2 + 4).toInt()
                        }
                        else -> 1
                    }
                    continue
                }
                if (opcode in 0x6E..0x72 || opcode in 0x74..0x78 || opcode == 0xFA || opcode == 0xFB) {
                    calls += method(dex.getShort(insns + pc * 2 + 2).toInt() and 0xFFFF)
                }
                pc += WIDTH[opcode]
            }
        }
    }
}

/** Code units per Dalvik instruction, by opcode (the format table of the dex specification). */
private val WIDTH = IntArray(256) { 1 }.also { w ->
    fun set(range: IntRange, width: Int) = range.forEach { w[it] = width }
    set(0x02..0x02, 2); set(0x03..0x03, 3); set(0x05..0x05, 2); set(0x06..0x06, 3); set(0x08..0x08, 2); set(0x09..0x09, 3)
    set(0x13..0x13, 2); set(0x14..0x14, 3); set(0x15..0x16, 2); set(0x17..0x17, 3); set(0x18..0x18, 5); set(0x19..0x1A, 2)
    set(0x1B..0x1B, 3); set(0x1C..0x1C, 2); set(0x1F..0x20, 2); set(0x22..0x23, 2); set(0x24..0x26, 3); set(0x29..0x29, 2)
    set(0x2A..0x2C, 3); set(0x2D..0x3D, 2); set(0x44..0x6D, 2); set(0x6E..0x72, 3); set(0x74..0x78, 3); set(0x90..0xAF, 2)
    set(0xD0..0xE2, 2); set(0xFA..0xFB, 4); set(0xFC..0xFD, 3); set(0xFE..0xFF, 2)
}
