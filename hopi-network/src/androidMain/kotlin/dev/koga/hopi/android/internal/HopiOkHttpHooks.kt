@file:JvmName("HopiOkHttpHooks")

package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Called by OkHttp's rewritten `RealCall` in place of `client.interceptors()`, for the list it
 * builds its own chain from (no app code can reach that list). While capture is off it is the
 * client's own list; once on, a read-only view of it with Hopi's interceptor last, so it runs after
 * every interceptor the app or an SDK added and before OkHttp's retry, bridge, cache and network
 * interceptors. Nothing the app can read changes: not the client's lists, not a builder's, not
 * a `newBuilder()` copy's. This class names okhttp3 types, so only an APK with OkHttp loads it.
 */
@HopiInternalApi
@JvmName("interceptors")
public fun interceptors(client: OkHttpClient): List<Interceptor> {
    val own = client.interceptors
    // Not merely started: an OkHttp the runtime does not link against (before 3.14) is rewritten
    // too, and its chain stays its own.
    if (!AndroidCapture.okHttpInstrumented) return own
    // An app sends its calls through a few clients (an API client, an image or CDN one built with
    // `newBuilder()`): each one's list (an immutable field, the same object on every call) gets the
    // same view back, with no scan and no allocation, also while their calls interleave.
    // A client whose authenticator answers a 401 adds a credential below Hopi, to whatever host the
    // request went: its rules refuse an edit to another origin (OkHttpRules). Part of the key: two
    // clients can share one list object (OkHttp 5 gives every client without interceptors the same
    // empty list) and differ in their authenticator.
    val hopi = if (client.authenticator === Authenticator.NONE) HopiOkHttpInterceptor else HopiOkHttpInterceptor.WithAuthenticator
    val seen = lastSeen
    if (seen != null) {
        if (seen.own0 === own && seen.hopi0 === hopi) return seen.list0
        if (seen.own1 === own && seen.hopi1 === hopi) return seen.list1!!
        if (seen.own2 === own && seen.hopi2 === hopi) return seen.list2!!
        if (seen.own3 === own && seen.hopi3 === hopi) return seen.list3!!
    }
    var hasHopi = false
    for (i in own.indices) if (own[i] === HopiOkHttpInterceptor || own[i] === HopiOkHttpInterceptor.WithAuthenticator) hasHopi = true
    val list = if (hasHopi) own else WithHopi(own, hopi)
    // Newest first; the oldest of four is dropped. A lost race between two misses drops a slot, never a pair.
    lastSeen = if (seen == null) {
        LastSeen(own, hopi, list, null, null, null, null, null, null, null, null, null)
    } else {
        LastSeen(own, hopi, list, seen.own0, seen.hopi0, seen.list0, seen.own1, seen.hopi1, seen.list1, seen.own2, seen.hopi2, seen.list2)
    }
    return list
}

/**
 * The last [CACHED_CLIENTS] client lists [interceptors] saw, each with the Hopi interceptor its
 * client needs, and what it returned for each, newest first: one immutable object, so a reader sees
 * matching triples. Fields, not arrays, so a hit on the newest costs one volatile read and two
 * reference compares.
 */
private class LastSeen(
    val own0: List<Interceptor>,
    val hopi0: Interceptor,
    val list0: List<Interceptor>,
    val own1: List<Interceptor>?,
    val hopi1: Interceptor?,
    val list1: List<Interceptor>?,
    val own2: List<Interceptor>?,
    val hopi2: Interceptor?,
    val list2: List<Interceptor>?,
    val own3: List<Interceptor>?,
    val hopi3: Interceptor?,
    val list3: List<Interceptor>?,
)

/** How many clients' lists [interceptors] keeps (tests). */
internal const val CACHED_CLIENTS = 4

@Volatile
private var lastSeen: LastSeen? = null

/** The client's immutable list plus Hopi's interceptor, not copied (RealCall copies it itself). */
private class WithHopi(private val own: List<Interceptor>, private val hopi: Interceptor) : AbstractList<Interceptor>(), RandomAccess {
    override val size: Int get() = own.size + 1

    override fun get(index: Int): Interceptor = if (index == own.size) hopi else own[index]
}
