package dev.koga.hopi.core.rules

import dev.koga.hopi.api.HopiInternalApi
import dev.koga.hopi.core.internal.hits.HOPI_HITS_CLAIM_LAST
import dev.koga.hopi.core.internal.hits.HOPI_HITS_CLAIM_REFUSED
import dev.koga.hopi.core.internal.hits.hopi_hits_claim
import dev.koga.hopi.core.internal.hits.hopi_hits_new
import dev.koga.hopi.core.internal.hits.hopi_hits_release
import dev.koga.hopi.core.internal.hits.hopi_hits_value
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

internal actual fun newHitCounter(): HitCounter = NativeHitCounter()

/**
 * The counter's native handle, a `hopi_hits` of core's library, which `hopi-network`'s URLSession
 * rule mirror hands to its own library with each rule: that library retains it and claims through
 * `hopi_hits_*` only.
 */
@OptIn(ExperimentalForeignApi::class)
@HopiInternalApi
public val RuleHits.nativeHandle: COpaquePointer get() = (counter as NativeHitCounter).handle

/**
 * The iOS counter: an atomic in the memory of core's `hopi_hits` library (docs/design/rules.md,
 * "iOS mirror"), so `hopi-network`'s URLSession hooks, which run on app and CFNetwork threads and
 * call no Kotlin, claim through the same compare-and-increment as every Kotlin source. That
 * module's rule mirror retains it too (an ARC strong reference to core's `HopiHits` object) and
 * reads and claims it through `hopi_hits_value` and `hopi_hits_claim` only, so a hook still
 * holding an older mirror never reads freed memory.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class NativeHitCounter : HitCounter {
    /** Retained; released by [cleaner] when this counter is collected. */
    val handle: COpaquePointer = checkNotNull(hopi_hits_new()) { "no hit counter" }

    @Suppress("unused")
    private val cleaner: Cleaner = createCleaner(handle) { hopi_hits_release(it) }

    override val value: Long get() = hopi_hits_value(handle)

    override fun claim(times: Int?): RuleHits.Claim = when (hopi_hits_claim(handle, times?.toLong() ?: 0L)) {
        HOPI_HITS_CLAIM_REFUSED -> RuleHits.Claim.REFUSED
        HOPI_HITS_CLAIM_LAST -> RuleHits.Claim.LAST
        else -> RuleHits.Claim.CLAIMED
    }
}
