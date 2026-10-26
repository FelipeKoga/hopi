package dev.koga.hopi.perf.internal

import dev.koga.hopi.perf.internal.native.HOPI_PERF_ASYNC_FP_BIT
import dev.koga.hopi.perf.internal.native.HOPI_PERF_PAC_MASK
import dev.koga.hopi.perf.internal.native.hopi_perf_walk
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame-pointer walk over synthetic chains (no thread is suspended): it follows a sound chain,
 * and ends cleanly on a frame pointer out of the stack's bounds, misaligned, or not strictly above
 * the previous one (a cycle); it clears the Swift async-frame flag and strips signed return
 * addresses.
 */
class StackWalkTest {
    /** A fake stack of [frames] 16-byte frame records 32 bytes apart; [link] says where each one's saved fp points. */
    private fun walk(frames: Int, link: (index: Int, base: ULong) -> ULong, returnAddress: (Int) -> ULong = { 0x1000uL + it.toULong() }, max: Int = 64): List<ULong> = memScoped {
        // 4 ulongs per frame, plus room to align the base to 16.
        val words = allocArray<ULongVar>(frames * 4 + 2)
        val raw = words.toLong().toULong()
        val base = (raw + 15uL) and 15uL.inv()
        val firstWord = ((base - raw) / 8uL).toInt()
        for (i in 0 until frames) {
            words[firstWord + i * 4] = link(i, base)
            words[firstWord + i * 4 + 1] = returnAddress(i)
        }
        val lo = base
        val hi = base + (frames * 32).toULong()
        val out = allocArray<ULongVar>(max)
        val count = hopi_perf_walk(base, lo, hi, out, 0, max)
        List(count) { out[it] }
    }

    private fun frameAt(base: ULong, index: Int): ULong = base + (index * 32).toULong()

    @Test
    fun followsASoundChain() {
        val stack = walk(5, { i, base -> if (i == 4) 0uL else frameAt(base, i + 1) })
        assertEquals(List(5) { 0x1000uL + it.toULong() }, stack)
    }

    @Test
    fun stopsAtMax() {
        val stack = walk(5, { i, base -> if (i == 4) 0uL else frameAt(base, i + 1) }, max = 3)
        assertEquals(3, stack.size)
    }

    @Test
    fun endsOnAFramePointerOutOfBounds() {
        val stack = walk(3, { i, base -> if (i == 1) base + 0x10000uL else frameAt(base, i + 1) })
        assertEquals(listOf(0x1000uL, 0x1001uL), stack)
    }

    @Test
    fun endsOnAMisalignedFramePointer() {
        val stack = walk(3, { i, base -> if (i == 0) frameAt(base, 1) + 8uL else frameAt(base, i + 1) })
        assertEquals(listOf(0x1000uL), stack)
    }

    @Test
    fun endsOnACycle() {
        val stack = walk(3, { i, base -> if (i == 1) frameAt(base, 0) else frameAt(base, i + 1) })
        assertEquals(listOf(0x1000uL, 0x1001uL), stack)
    }

    @Test
    fun endsOnASelfLink() {
        val stack = walk(2, { i, base -> frameAt(base, i) })
        assertEquals(listOf(0x1000uL), stack)
    }

    @Test
    fun clearsTheSwiftAsyncFlag() {
        val stack = walk(3, { i, base -> if (i == 2) 0uL else frameAt(base, i + 1) or HOPI_PERF_ASYNC_FP_BIT })
        assertEquals(listOf(0x1000uL, 0x1001uL, 0x1002uL), stack)
    }

    @Test
    fun stripsSignedReturnAddresses() {
        val signed = 0xA5B4_0001_2345_6789uL
        val stack = walk(2, { i, base -> if (i == 1) 0uL else frameAt(base, 1) }, returnAddress = { signed })
        assertEquals(listOf(signed and HOPI_PERF_PAC_MASK, signed and HOPI_PERF_PAC_MASK), stack)
        assertEquals(0x1_2345_6789uL, signed and HOPI_PERF_PAC_MASK)
    }

    @Test
    fun endsOnAZeroReturnAddress() {
        val stack = walk(3, { i, base -> if (i == 2) 0uL else frameAt(base, i + 1) }, returnAddress = { if (it == 1) 0uL else 0x2000uL })
        assertEquals(listOf(0x2000uL), stack)
    }
}
