package dev.koga.hopi.instrument

/**
 * The one table of what the rewrite replaces and with what (docs/design/android-capture.md,
 * "Interception points"). The rewriter reads it, and hopi-android's tests check every hook here
 * against the hook classes it compiles, so the two cannot drift apart.
 */
public object HookDescriptors {
    /** Internal names of OkHttp's call class: 4.x and 5.x, then 3.x. */
    public val REAL_CALL_CLASSES: Set<String> = setOf("okhttp3/internal/connection/RealCall", "okhttp3/RealCall")

    public const val OKHTTP_CLIENT: String = "okhttp3/OkHttpClient"
    public const val INTERCEPTORS: String = "interceptors"
    public const val INTERCEPTORS_DESCRIPTOR: String = "()Ljava/util/List;"

    /** The class of the OkHttp hook; it names okhttp3 types, so it is never the URL hooks' class. */
    public const val OKHTTP_HOOKS: String = "dev/koga/hopi/android/internal/HopiOkHttpHooks"
    public const val OKHTTP_HOOK_DESCRIPTOR: String = "(Lokhttp3/OkHttpClient;)Ljava/util/List;"

    /** The class annotation a rewritten RealCall carries, so the runtime knows OkHttp is covered. */
    public const val INSTRUMENTED_ANNOTATION: String = "Ldev/koga/hopi/android/internal/HopiInstrumented;"

    public const val URL: String = "java/net/URL"
    public const val URL_HOOKS: String = "dev/koga/hopi/android/internal/HopiUrlHooks"

    /** Hopi's own runtime: never rewritten, because its hooks call the original methods. */
    public const val RUNTIME_PACKAGE: String = "dev/koga/hopi/android/"

    /** One `java.net.URL` instance method and the static hook that takes its receiver first. */
    public class UrlHook(public val name: String, public val descriptor: String) {
        /** The hook's descriptor: the original's with the `URL` receiver as the first parameter. */
        public val hookDescriptor: String = "(L$URL;" + descriptor.removePrefix("(")

        override fun toString(): String = "URL.$name$descriptor"
    }

    /** Every `URL` method that opens a connection, with its hook's name (the same name). */
    public val URL_HOOK_METHODS: List<UrlHook> = listOf(
        UrlHook("openConnection", "()Ljava/net/URLConnection;"),
        UrlHook("openConnection", "(Ljava/net/Proxy;)Ljava/net/URLConnection;"),
        UrlHook("openStream", "()Ljava/io/InputStream;"),
        UrlHook("getContent", "()Ljava/lang/Object;"),
        UrlHook("getContent", "([Ljava/lang/Class;)Ljava/lang/Object;"),
    )

    /** The hook for `URL.<name><descriptor>`, or null when that method is not rewritten. */
    public fun urlHook(name: String, descriptor: String): UrlHook? =
        URL_HOOK_METHODS.firstOrNull { it.name == name && it.descriptor == descriptor }
}
