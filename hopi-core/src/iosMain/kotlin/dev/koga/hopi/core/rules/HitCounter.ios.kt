package dev.koga.hopi.core.rules

import dev.koga.hopi.core.internal.urlsession.HOPI_US_CLAIM_LAST
import dev.koga.hopi.core.internal.urlsession.HOPI_US_CLAIM_REFUSED
import dev.koga.hopi.core.internal.urlsession.hopi_us_hits_claim
import dev.koga.hopi.core.internal.urlsession.hopi_us_hits_new
import dev.koga.hopi.core.internal.urlsession.hopi_us_hits_release
import dev.koga.hopi.core.internal.urlsession.hopi_us_hits_value
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

internal actual fun newHitCounter(): HitCounter = NativeHitCounter()

/**
 * The iOS counter: an atomic in the URLSession library's memory (docs/design/rules.md, "iOS
 * mirror"), so the library's hooks, which run on app and CFNetwork threads and call no Kotlin, claim
 * through the same compare-and-increment as every Kotlin source. The library's rule mirror retains
 * it too, so a hook still holding an older mirror never reads freed memory.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class NativeHitCounter : HitCounter {
    /** Retained; released by [cleaner] when this counter is collected. */
    val handle: COpaquePointer = checkNotNull(hopi_us_hits_new()) { "no hit counter" }

    @Suppress("unused")
    private val cleaner: Cleaner = createCleaner(handle) { hopi_us_hits_release(it) }

    override val value: Long get() = hopi_us_hits_value(handle)

    override fun claim(times: Int?): RuleHits.Claim = when (hopi_us_hits_claim(handle, times?.toLong() ?: 0L)) {
        HOPI_US_CLAIM_REFUSED -> RuleHits.Claim.REFUSED
        HOPI_US_CLAIM_LAST -> RuleHits.Claim.LAST
        else -> RuleHits.Claim.CLAIMED
    }
}
