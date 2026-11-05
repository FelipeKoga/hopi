@file:JvmName("HopiUrlHooks")

package dev.koga.hopi.android.internal

import dev.koga.hopi.api.HopiInternalApi
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

// Called by every rewritten `java.net.URL` call site, with the URL first. Each does what `URL`
// itself does, through the real method (Hopi's own classes are never rewritten), and wraps an
// HTTP(S) connection in a recording wrapper once capture runs. This class names no okhttp3 type.

@HopiInternalApi
@JvmName("openConnection")
public fun openConnection(url: URL): URLConnection = wrap(url.openConnection())

@HopiInternalApi
@JvmName("openConnection")
public fun openConnection(url: URL, proxy: Proxy): URLConnection = wrap(url.openConnection(proxy), proxy)

/** `URL.openStream()` is `openConnection().getInputStream()`. */
@HopiInternalApi
@JvmName("openStream")
public fun openStream(url: URL): InputStream = openConnection(url).getInputStream()

/** `URL.getContent()` is `openConnection().getContent()`. */
@HopiInternalApi
@JvmName("getContent")
public fun getContent(url: URL): Any? = openConnection(url).content

@HopiInternalApi
@JvmName("getContent")
public fun getContent(url: URL, classes: Array<Class<*>>): Any? = openConnection(url).getContent(classes)

/**
 * The recording wrapper for an HTTP(S) connection, or [connection] itself: before capture starts,
 * for other schemes, and for a connection OkHttp implements (`okhttp-urlconnection` behind a
 * `URLStreamHandlerFactory`), which the OkHttp interceptor records already.
 */
private fun wrap(connection: URLConnection, proxy: Proxy? = null): URLConnection {
    if (!AndroidCapture.started || connection !is HttpURLConnection) return connection
    if (connection.javaClass.name.startsWith("okhttp3.")) return connection
    // The proxy the app chose: what a rule's rebuilt connection opens with again.
    return if (connection is HttpsURLConnection) HopiHttpsURLConnection(connection, proxy) else HopiHttpURLConnection(connection, proxy)
}
