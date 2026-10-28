# Design: automatic OkHttp and `HttpURLConnection` capture on Android

Status: built (2026-10-24); what changed while building it, and the device measurements, are in
"As built" at the end. This is the Android part of the owner requirement
"capture every HTTP request the app makes, including third-party SDKs, with no per-client
configuration". The iOS part (URLSession) is built, see `docs/design/urlsession-capture.md`. This
design reuses its `source` field, its store semantics (`CaptureInstallation`) and its Ktor dedupe
rule (`PlatformCapture`). Records go into the existing `HopiStore`, so the NDJSON log line, the
bridge/CLI and the desktop viewer (and the in-app screen, until the runtime-split plan's stage 5
removed it) show them with no change: `TransactionSource.OKHTTP`
(`"okhttp"`) and `TransactionSource.URL_CONNECTION` (`"urlconnection"`) already exist in core, on
the wire, in `BridgeDecoder` and in the UI's `sourceLabel` (`OkHttp`, `HttpURLConnection`).

## Goals and non-goals

Goals:

- Setup is one line in the app module's `build.gradle.kts`: `plugins { id("dev.koga.hopi") }`. No
  code, no per-client, per-SDK or per-session setup, and no `-Phopi.enabled` for Android apps that
  use the plugin. An optional `hopi { … }` block holds the same settings as `HopiUrlSessionConfig`.
- Every OkHttp call (OkHttp 3.14, 4.x and 5.x; 3.12 and 3.13 are rewritten but left unrecorded,
  see "Dedupe with the Ktor plugin") and every `HttpURLConnection` /
  `HttpsURLConnection` opened through `java.net.URL` in the APK's own code is recorded once: app
  code, Retrofit, image loaders, analytics, ads and crash SDKs, Kotlin's `URL.readText()`, the
  Ktor OkHttp engine. Clients built before capture starts, `OkHttpClient` subclasses, clients built
  by reflection and `newBuilder()` copies are all covered.
- Observe only. No request is re-issued and nothing about it changes: bytes, timing, callback
  order, threads, cookies, caching, redirects, authentication, HTTP/2, connection reuse, event
  listeners. What the app can read stays the same too: `client.interceptors()`,
  `client.networkInterceptors()` and the builder's lists.
- Bounded: a request body and a response body each keep at most `maxBodyBytes`, and past the cap
  they are only counted; the records queued for a store consumer that fell behind keep at most
  `maxTotalBytes` of captures between them. No `peekBody` or read-ahead. Hopi does no work of its
  own on the main thread. The only main-thread cost is the auto-start, measured.
- Release (non-debuggable) variants are not instrumented and get the noop twin. They contain no
  capture class and no reference to one, and an automated check proves it.

Non-goals (v1):

- WebSocket frames (the handshake is recorded) and gRPC streams.
- Cronet's native API, WebView, `DownloadManager`, and native (C/C++) HTTP stacks.
- Code that is not in the APK when it is built: Play services dynamite modules, and code loaded at
  run time (`DexClassLoader`).
- Shaded OkHttp copies relocated to another package.
- Processes other than the app's default one. The provider runs only there.
- `URLConnection`s obtained by reflection or through a `MethodHandle` lookup.

## What the existing tools do, and what we take

Read from the sources and jars on 2026-10-24: OkHttp 4.12.0 and 5.3.2 (`okhttp-android`) through
`javap`, Android Studio's network inspector (`platform/tools/base`,
`app-inspection/inspectors/network`), and Sentry's Android Gradle plugin
(`instrumentation/okhttp`).

| Tool | Mechanism | Verdict |
| --- | --- | --- |
| Android Studio Network Inspector (`NetworkInspector.kt`) | JVMTI exit hooks through ArtTooling, no build step. `okhttp3.OkHttpClient.networkInterceptors()` returns a **new** `ArrayList` with `OkHttp3Interceptor` first. OkHttp 2's `networkInterceptors()` gets an `OkHttp2Interceptor` inserted in place unless one is present. `URL.openConnection()` returns `wrapURLConnection(...)` (`HttpURLConnectionWrapper`, `HttpsURLConnectionWrapper`, `TrackedHttpURLConnection`). | Idea taken: hook OkHttp's interceptor list and wrap `URL.openConnection`. Not taken: (a) the public getter, because the app and `OkHttpClient.Builder(OkHttpClient)` also read it (`Builder` calls `interceptors()`/`networkInterceptors()` at offsets 623/631 in 5.3.2), so the hook's interceptor leaks into `newBuilder()` copies and into what the app sees; (b) the network-interceptor position, see "Which interceptor position"; (c) its request body capture, `body.writeTo(nullSink)`, which runs the app's `RequestBody` a second time (a file is read twice, a progress-reporting body reports twice), and it skips one-shot and duplex bodies; (d) its response getters, which call `connect()` themselves ("as a side effect, will cause the request to get sent"); (e) `errorStream` is not tracked. JVMTI needs a debugger attached, so it cannot run without Studio. |
| Sentry Android Gradle plugin (`OkHttp.kt`, `ResponseWithInterceptorChainMethodVisitor`) | AGP instrumentation of `okhttp3.internal.connection.RealCall` / `okhttp3.RealCall`, method `getResponseWithInterceptorChain*`. After the `POP` that follows `OkHttpClient.interceptors()`, it loads local 1 (the chain's list) and adds `SentryOkHttpInterceptor` unless an `instanceof` scan finds one. | **Adopted, in a safer form.** Patching inside `RealCall` covers every client and leaves every list the app can see untouched. Sentry depends on the local slot and the `POP` shape. Hopi rewrites only the `invokevirtual OkHttpClient.interceptors()` instruction, which has the same stack effect and depends on nothing around it. |
| DoKit, okhttp-hunter and similar | ASM into `OkHttpClient$Builder.<init>()`, which appends global interceptors to the builder's mutable lists | Rejected. The app's builder visibly changes, `newBuilder()` copies the interceptor, and an interceptor the app adds after it runs later than Hopi's. |
| Firebase Performance, New Relic plugins | Call-site rewrites: `URL.openConnection()`/`openStream()`/`getContent()` to a static that wraps the connection; OkHttp `Call.execute()`/`enqueue()` call sites to statics | `URL` call sites are **adopted**. `java.net.URL` is a boot-class-path class that AGP never feeds to a transform, so the call sites are the only place to hook it. OkHttp call-site rewriting is rejected, because it misses calls made through `Call.Factory` interfaces (Retrofit) and through reflection. |
| Chucker, Flipper, Pluto | A manual `addInterceptor(...)` per client, so no third-party coverage | Rejected for the requirement. Their body handling (`peekBody`, or `writeTo` into a buffer) is rejected too, see "Bodies". |

Facts checked in the jars:

- `okhttp3.OkHttpClient.interceptors()` is `public final ()Ljava/util/List;`, `aload_0; getfield
  interceptors; areturn`, in both 4.12.0 and 5.3.2. `-deprecated_interceptors()` is the same. The
  class is `open` (subclassable), and the getter cannot be overridden.
- `RealCall.getResponseWithInterceptorChain$okhttp()` (`okhttp3.internal.connection.RealCall`)
  calls `invokevirtual okhttp3/OkHttpClient.interceptors:()Ljava/util/List;` at offset 19 and
  `networkInterceptors()` at offset 126, in both versions. It is the only call of `interceptors()`
  in `RealCall`.
- `BridgeInterceptor` adds `Accept-Encoding: gzip`, and when it did, it wraps the body in
  `okio.GzipSource` and removes `Content-Encoding` and `Content-Length`. It runs after the
  application interceptors and before the network interceptors.
- Ktor 3.5.2's OkHttp engine calls `followRedirects(false)`/`followSslRedirects(false)` in
  `OkHttpConfig`, so Ktor follows redirects itself, one OkHttp call per hop. It sends channel
  bodies as `io.ktor.client.engine.okhttp.StreamRequestBody`.
- AGP 9.4.0 has `Component.getDebuggable()`, `Component.getInstrumentation()` with
  `transformClassesWith(factory, InstrumentationScope.ALL, params)`,
  `setAsmFramesComputationMode` and `excludes`, `AsmClassVisitorFactory.isInstrumentable(ClassData)`,
  and `Sources.getRes()` for generated resources. Debuggable is a build-type property:
  `VariantBuilder` does not expose it.

## Interception points

All rewrites are done at build time by the Gradle plugin, for debuggable variants only, with
`InstrumentationScope.ALL` (the app's classes and every dependency jar or AAR, external or project).

| # | Where | Rewrite | Stack effect |
| --- | --- | --- | --- |
| 1 | `okhttp3/internal/connection/RealCall` (4.x, 5.x) and `okhttp3/RealCall` (3.x): every `INVOKEVIRTUAL okhttp3/OkHttpClient.interceptors ()Ljava/util/List;` | → `INVOKESTATIC dev/koga/hopi/android/internal/HopiOkHttpHooks.interceptors (Lokhttp3/OkHttpClient;)Ljava/util/List;` | identical (`client` → `List`) |
| 2 | the same `RealCall` classes | adds a class annotation `@dev.koga.hopi.android.internal.HopiInstrumented` (runtime retention), only when rewrite 1 happened in that class | none |
| 3 | every class, every `INVOKEVIRTUAL java/net/URL.openConnection ()Ljava/net/URLConnection;` | → `INVOKESTATIC …/HopiUrlHooks.openConnection (Ljava/net/URL;)Ljava/net/URLConnection;` | identical |
| 4 | `URL.openConnection(Ljava/net/Proxy;)` | → `HopiUrlHooks.openConnection (Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;` | identical |
| 5 | `URL.openStream()Ljava/io/InputStream;` | → `HopiUrlHooks.openStream (Ljava/net/URL;)Ljava/io/InputStream;` (`openConnection(url).getInputStream()`, which is what `URL.openStream` does) | identical |
| 6 | `URL.getContent()` and `getContent([Ljava/lang/Class;)` | → `HopiUrlHooks.getContent(URL)` / `getContent(URL, Class[])` (`openConnection(url).getContent(…)`) | identical |
| 7 | the same targets as 3 to 6 used as a `Handle` in an `INVOKEDYNAMIC` bootstrap argument (`H_INVOKEVIRTUAL java/net/URL.openConnection`, from Java's `URL::openConnection` or an indy Kotlin callable reference) | → `H_INVOKESTATIC` to the same static. `LambdaMetafactory` accepts a static implementation that takes the receiver as its first argument, and the instantiated method type is unchanged. | identical |

Never rewritten: classes in `dev.koga.hopi.android.*` (the hooks call the real `URL` methods; this
is enforced in `isInstrumentable`, and a test proves the hooks do not recurse), and methods whose
owner is not exactly `java/net/URL` (a `URL` subclass cannot exist, the class is final).

The hooks are internal top-level functions (`@file:JvmName("HopiOkHttpHooks")` and
`@file:JvmName("HopiUrlHooks")`). Kotlin compiles them to public static methods with unmangled
names, but they are not part of the ABI dump, so the noop needs no copy of them and the release
check can look for their types. The OkHttp hook and the URL hooks are in two classes, so an app
without OkHttp never loads a class whose signatures name `okhttp3` types (no soft verification
failure on ART). One shared constant table (`HookDescriptors`, in the `hopi-instrument` module
below) defines owner, name and descriptor. A runtime test loads both hook classes and checks every
descriptor against it, so the rewrite and the hooks cannot drift apart.

**Frames.** Every rewrite replaces one instruction with another of the same stack effect, or
changes only a handle or an annotation. No local is added, max stack is unchanged, and no branch
is added, so `FramesComputationMode.COPY_FRAMES` (AGP's default) stays correct and no class
hierarchy is loaded. The `instrument` tests run ASM's `CheckClassAdapter.verify` on every class
they rewrite.

### Why `RealCall`, not `OkHttpClient`

The constructor or getter of `OkHttpClient` would also cover everything, but the app can see both:
`client.interceptors()`, `builder.interceptors()`, and the copy that `newBuilder()` makes. A
`newBuilder().addInterceptor(auth)` would then run `auth` after Hopi, so Hopi would record the
request without the header `auth` adds. Rewrite 1 changes only the list `RealCall` builds for its
own chain, which no app code can reach. The cost is one static call per call. When capture is off
it returns the client's list unchanged. When it is on, it returns a read-only view: an
`AbstractList` of size n + 1 over the client's immutable list, plus Hopi's singleton interceptor,
allocated once per call and not copied (`RealCall` copies it into its own `ArrayList` anyway). The
view has no Hopi interceptor when the client's list already holds one (identity check), which
cannot happen through Hopi itself.

The risk is that `RealCall` is internal to OkHttp. A future OkHttp could rename it or read the
field directly. Then rewrite 1 does not happen, annotation 2 is missing, and the auto-start writes
a notice line (`{"hopi":1,"notice":"okhttp: not instrumented (unknown OkHttp version), Ktor's
OkHttp engine stays with the plugin"}`). The Ktor dedupe is not enabled, so nothing is lost: the
Ktor plugin keeps recording, and OkHttp calls outside Ktor are not recorded. The plugin also warns
at build time when a jar contains `okhttp3/OkHttpClient` but no `RealCall` was rewritten. It
tracks this with a per-transform flag in the visitor parameters, written to the instrumentation
log.

### Which interceptor position

Hopi's interceptor goes **last among the application interceptors**: after every interceptor the
app or an SDK added, before `RetryAndFollowUpInterceptor`. The `List` view above puts it there.

| | Application position (chosen) | Network position (Studio's) |
| --- | --- | --- |
| DNS failure, connection refused, TLS failure, timeout to connect | Recorded as `Failed` | **Not recorded.** Network interceptors run after `ConnectInterceptor`. The sample's `.invalid` host request would be missing. |
| Cache hits (`Cache`, `CacheControl.FORCE_CACHE`) | Recorded (`protocol` from `response.protocol`) | Not recorded |
| Request as the app built it, after its own interceptors (auth headers added by an interceptor) | Yes | Yes, plus what `BridgeInterceptor` adds |
| Headers OkHttp adds (`Host`, `User-Agent`, `Accept-Encoding`, `Cookie`, `Content-Length`) | Not seen, the same limitation as URLSession | Seen |
| Response body | Decoded, as the app receives it (`BridgeInterceptor`'s `GzipSource` sits below it). This is the existing contract for the Ktor plugin and URLSession. | gzip bytes. A truncated prefix would need inflating off the thread, and `totalBytes` would count compressed bytes. |
| Redirects OkHttp follows (`followRedirects(true)`, the default) | One record per call: the final status, headers and body, plus `finalUrl`. This is URLSession's native-redirect rule. | One record per hop |
| Retries by `RetryAndFollowUpInterceptor` | One record | One per attempt |
| An app interceptor that calls `proceed` twice (its own retry) | Two records, because two requests were sent | Two |
| An app interceptor that answers without `proceed` (a mock) | Not recorded, because nothing was sent | Not recorded |
| Ktor OkHttp engine | One record per hop (Ktor disables OkHttp's redirects), the same as the plugin | Same |
| WebSocket handshake (`newWebSocket`) | Recorded. Application interceptors run for WebSockets, network interceptors do not. | Not recorded |

The application position wins on the failures, which an inspector must show, and on the existing
body contract. The headers OkHttp adds are a known gap, shared with iOS. A later, separate network
interceptor could add them to the same record. That is not v1.

## The interceptor (`HopiOkHttpInterceptor`)

On OkHttp's calling thread (a `Dispatcher` thread for `enqueue`, the caller's thread for
`execute`):

1. If capture is not started, `chain.proceed(chain.request())` and return. Nothing else happens.
   Rewrite 1 already returns the plain list in that case, so this check is only a guard. A request
   carrying the Ktor plugin's pass-through marker (`X-Hopi-Pass-Through`, see "Dedupe with the Ktor
   plugin") goes on with the marker removed and nothing recorded.
2. `id = store.nextId()`, and start time as wall clock plus `System.nanoTime()`. Hopi keeps a
   reference to the `Request` (immutable) and posts "started" to the capture dispatcher (see
   Threading). Headers are not converted here.
3. **Request body.** Skipped (`request.body` stays the same object) when null, or when `isDuplex()`
   (`"duplex, not captured"`). Otherwise the request goes on as
   `request.newBuilder().method(method, HopiRequestBody(original)).build()`: `newBuilder()`
   copies headers, tags and the 5.x `cacheUrlOverride`. `HopiRequestBody` delegates
   `contentType()`, `contentLength()`, `isOneShot()` and `isDuplex()` to the original. Its
   `writeTo(sink)` calls the original's `writeTo` with a `ForwardingSink` around `sink`, which
   copies the first `maxBodyBytes` of the **first complete** `writeTo` and counts the rest. A
   later `writeTo` (a redirect or retry resending a repeatable body, or a network interceptor that
   writes the body into a buffer to sign it) passes through without a copy. Hopi never calls
   `writeTo` itself, so a one-shot body (Ktor's `StreamRequestBody`) is read exactly once, by
   OkHttp, and is captured. That is better than iOS, where streams are not captured. A
   `writeTo` that throws is recorded as `"incomplete"`. When `writeTo` never runs (GET, or a
   failure before the body was written), the body is recorded as `"not sent"`. Multipart and form
   bodies are captured as their encoded bytes, up to the cap. The capture is sized once from the
   body's `contentLength()` when known (at most the cap), so a large upload grows no array.
4. `response = chain.proceed(request)`. An exception is recorded as `Failed` (`"<Class>:
   <message>"`, or `"cancelled"` when `chain.call().isCanceled()`) and **rethrown unchanged**.
5. **Response body.** No body is expected (HEAD, 1xx, 204, 304, or a `101` upgrade, whose body
   must stay untouched for the WebSocket): the record ends here, and the response is returned
   as it is. `ResponseSkip` (on the raw `Content-Type` header: OkHttp's `contentType()` parses a
   `MediaType` per response, which nothing here needs) says binary or `text/event-stream`: the body
   is wrapped all the same, but with a capture cap of 0, so the wrapper only counts what the app
   reads (no copy, no array, nothing beyond one addition per read; an SSE stream is delayed by
   nothing). Its record ends with the body's real end, like any other: `totalBytes` is the count of
   what the app read (not the `Content-Length`), `durationMs` runs to the end of the body, a binary
   download that fails is `Failed`, and an SSE stream stays `Pending` until the app closes it,
   then is `Complete` with its size (closing is how a stream ends). Before 2026-10-24 (hardening)
   these bodies were returned unwrapped and the record closed at the headers: `Complete`, the size
   from `Content-Length`, the duration without the body, also for a download that failed later.
   Otherwise Hopi returns `response.newBuilder().body(HopiResponseBody(original)).build()`. `HopiResponseBody` delegates
   `contentType()` and `contentLength()`. Its `source()` is
   `ForwardingSource(original.source()).buffer()`, created once. The `ForwardingSource.read` runs
   on whichever thread the app reads from. For each read it copies the bytes just read into the
   capture while below the cap (`Buffer.copyTo(capture, offset, n)`: Okio shares the segments
   instead of copying bytes; the bounded copy happens once, off the reader's thread, when the
   record is built). Past the cap it only adds to `totalBytes`. The end is `read` returning -1:
   `Complete`. A read that throws gives `Failed`, and the exception goes up unchanged, unless the
   call was cancelled (`call.isCanceled()`, the check step 4 makes): `call.cancel()` closes the
   socket under a blocked read, which is how okhttp-sse's `EventSource.cancel()` and Ktor's SSE
   session on this engine end a stream, so a stream is then `Complete` with what was read and any
   other body `Failed`, `"cancelled"`, never the cancel's exception text. `close()`
   before the end gives `Failed`, `"cancelled"`, the Ktor plugin's rule. A failure after the
   response arrived keeps the status, headers and protocol, and the part of the body that arrived
   as a partial body (`failedAfterResponse`). A body nobody reads or
   closes stays `Pending`, and OkHttp itself reports that as a leaked connection. A body closed
   without ever being opened (`response.close()`, `use { it.code }`) is closed through the
   original, with no tee built, and recorded `"not read"`; the capture array is allocated by the
   first copy only, so no body costs a cap-sized array before a byte is read.
6. Response fields when the response arrives: `code`, a reference to `headers` (immutable),
   `protocol` (`response.protocol`, written as Ktor writes it: `HTTP/1.1`, `HTTP/2.0`), and
   `finalUrl` = `response.request.url` when its canonical string differs from the original's
   (`HttpUrl` is canonical on both sides).

**Why not `peekBody`.** `response.peekBody(n)` does `source.peek().request(n)`, which blocks the
interceptor until n bytes or the end have arrived. A long poll, a slow chunked body (`/slow`) or an
SSE stream would reach the app only after the cap filled or the stream ended. That changes timing,
and it is a read-ahead. The tee reads nothing the app did not read.

What the app sees that differs, stated: `response.body` and `request.body` (for interceptors and
event listeners below Hopi, and in `response.request`) are Hopi's delegating classes, not the
originals. A `body is MultipartBody` check below Hopi's position would fail. Only OkHttp's own
interceptors and the app's network interceptors run below it, and those rarely do type checks.
Everything else is the same objects or the same values. `durationMs` runs from `intercept` to the
end of the body as the app reads it, the Ktor plugin's definition, and it is not labelled
"network".

## The `URLConnection` wrapper

`HopiUrlHooks.openConnection(url)` calls `url.openConnection()`. It returns the result unchanged
when capture is not started, when the result is not an `HttpURLConnection`, or when the result's
class is in `okhttp3.`. In that last case OkHttp's `okhttp-urlconnection`/`ObsoleteUrlFactory`
stands behind a `URLStreamHandlerFactory`, the OkHttp interceptor records the request, and
wrapping it too would make two records. Otherwise it returns a `HopiHttpsURLConnection` (which
extends `HttpsURLConnection`) for an `HttpsURLConnection`, or a `HopiHttpURLConnection` (which
extends `HttpURLConnection`), so the app's casts work. Android's platform `HttpURLConnection` is the
framework's internal `com.android.okhttp` (boot class path, not instrumented, not `okhttp3`), so it
is recorded once, by the wrapper.

Rules for the wrapper:

- **Every overridable method is delegated**: `URLConnection`'s, `HttpURLConnection`'s and
  `HttpsURLConnection`'s public and protected non-static, non-final methods, plus `toString()`. The
  wrapper's inherited fields (`url`, `connected`, `doOutput`, …) are never read, because each
  getter goes to the delegate. `equals`/`hashCode` keep identity. A test reads the class files of
  `java/net/URLConnection`, `HttpURLConnection` and `javax/net/ssl/HttpsURLConnection` from the
  compile SDK's `android.jar` with ASM, and fails on any such method the wrapper does not
  declare. A second test does the same through reflection on the test JDK.
- **The wrapper never calls a delegate method the app did not call**, except reading state after
  the app's own call has already connected. In detail:
  - Before the delegate's first connecting call (`connect`, `getOutputStream`, `getInputStream`,
    `getErrorStream`, `getResponseCode`, `getResponseMessage`, any `getHeaderField*`,
    `getContent*`, `getContentLength*`, `getContentType`, `getContentEncoding`, `getDate`,
    `getExpiration`, `getLastModified`), the wrapper takes the request snapshot: method
    (`getRequestMethod()`), URL (`getURL()`), and `getRequestProperties()`. On Android that call
    throws once connected, so it is read first. It has no side effect before the connection.
    Android's implementation turns a GET into a POST at connect when `doOutput` is true, so the
    recorded method is POST when the snapshot reads GET and `getDoOutput()` is true. Studio does
    the same.
  - It then makes the app's call on the delegate, unchanged, and returns its result or rethrows
    its exception. An `IOException` from it ends the record as `Failed`, except a
    `FileNotFoundException`/error status from `getInputStream`, where the response exists.
  - After a connecting call has returned or thrown with a response, the wrapper reads
    `getResponseCode()` and `getHeaderFields()` once. The delegate has them cached by then, so
    these reads do not touch the network. The status-line entry with a null key is dropped.
    `protocol` comes from Android's `X-Android-Selected-Protocol` header when present, else null.
    `finalUrl` = `getURL()` when it differs from the snapshot (`HttpURLConnection` follows
    redirects itself).
- **Request body**: `getOutputStream()` returns a `FilterOutputStream` subclass that overrides all
  three `write` methods (no byte-by-byte fallback, and `write(int)` copies its byte with no array
  of its own, as `read()` does), forwards each one, copies up to the cap and counts the rest.
  `flush`/`close` are forwarded. Fixed-length and chunked streaming modes are the delegate's
  business; a fixed length sizes the capture once.
- **`getErrorStream()`** connects on Android (`HttpURLConnectionImpl` sends the request when
  nothing has), so it takes the snapshot first, as every connecting call does. It returns null
  without reading the response when the delegate's is null.
- **Response body**: `getInputStream()` and `getErrorStream()` return an `InputStream` tee:
  `read()`, `read(byte[], int, int)`, `skip` (counted as read, but the skipped bytes are not
  captured, so the capture is marked truncated), `available`, `close`, `mark`/`reset` (reported as
  unsupported only when the delegate does not support them, and passed through otherwise). It
  completes at -1. `close()` before the end, or `disconnect()`, gives `"cancelled"`, keeping the
  response and what arrived of the body. `disconnect()` usually comes from another thread while
  the reader is blocked in a read: the end of a capture (`BoundedCapture.freeze`, the first of a
  read's end, its throw, a close and a disconnect) and every copy into it hold the capture's
  monitor, so the record holds exactly the bytes taken before the end, and a read that returns
  after it reaches the app but changes nothing in the record (OkHttp's tee works the same way). HEAD, 1xx, 204 and 304 complete at the response.
  `ResponseSkip` types are wrapped with a capture cap of 0, counted only, and end with their stream
  as for OkHttp (a stream closed by the app is `Complete`). The response headers are read by index
  (`getHeaderField(i)`/`getHeaderFieldKey(i)`), not through `getHeaderFields()`, which builds a
  fresh sorted map per call on Android (the costliest read of a response in the profile, about
  13 µs); a connection class that implements only the map falls back to it.
- A connection that the app reads a status from and then neither reads, closes nor disconnects
  stays `Pending` (with its response fields, as a pending snapshot), as an unread OkHttp body does.

## Threading and per-request state

- **The app's request thread** (OkHttp's, or whatever thread calls the `URLConnection`): one state
  object per request (id, two times, references to the immutable request/headers, the capture
  `okio.Buffer` or a `ByteArray` block list for the `URLConnection` tee, counters, one
  `AtomicBoolean` for "terminal posted"), and two queued writes (`store.submitDeferred`). The body
  copy below the cap happens on the reader's thread: zero-copy segment sharing for OkHttp, one
  bounded array copy per chunk for `URLConnection`. Hopi converts no header, trims no array and
  formats nothing on this thread.
- **The store's consumer thread** (`hopi-store`, background priority, core's, see ARCHITECTURE.md
  "Store"): builds each record from the request's state (converts `Headers`/the header strings to
  `List<Pair>`, trims the bodies' arrays, `source = "okhttp"`/`"urlconnection"`), applies it and,
  in the same pass, writes its summary line: one thread. While no viewer is attached
  (`HopiStore.attachViewer`: a bridge connection, the UI on screen) a request's start wakes
  nothing, and its end wakes the consumer only from its untimed sleep: after a pass it dozes 50 ms,
  and the ends that arrive meanwhile only mark the work, so a burst of requests pays one wake in
  all and a record (and its summary line) shows at most 50 ms after its end. With a viewer
  attached every start and end wakes it, so the request shows as pending at once and complete at
  once; a bridge client that connects is served only after the store applied what waited
  (`awaitApplied`), and an uncaught exception or `exitProcess` waits at most 200 ms for the same
  pass before the platform's (or a crash reporter's) handler runs, so the request whose response
  crashed the app still has its logcat line (a `SIGKILL` can still lose the last 50 ms). Writes are queued start before end, and the consumer drains
  the pending lane before the terminal one, so "started before finished" holds per request. It
  blocks with no timeout while no request runs (after a burst's last doze). Recorders queue
  themselves (`HopiStore.submitStart`/`submitEnd` with the `OkHttpRecord` or the
  `UrlConnectionRecorder` as a `DeferredRecord`), so a hand-off allocates nothing on this thread.
  Before 2026-10-24 (hardening) records were built on `Dispatchers.Default.limitedParallelism(1)`,
  then submitted to the store's consumer coroutine, whose events woke the log sink's coroutine:
  three `Dispatchers.Default` hand-offs per request, each a futex wake plus the woken worker
  spinning through steal and park (profiled: about 51% of Hopi's cost per request).
- **The main thread**: only the auto-start (below). If an app or SDK runs a `URLConnection` or an
  OkHttp `execute()` on the main thread (it would need `StrictMode.permitAll`), the bounded copy
  runs there as part of the app's own read. This is the Ktor plugin's rule too.
- First use: `CaptureInstallation.install(…)` (store caps, redaction union, summary sink, bridge
  request) runs once per process, lazily, on the thread of the first recorded request, behind a
  lock-free once. It never runs on the main thread at startup.

## Auto-start

`hopi-core`'s `AndroidManifest.xml` declares one provider (`hopi-android`'s until the
runtime-split plan's stage 4, 2026-10-28; the class kept its package). With the plugin it is
merged only into debuggable variants, because only they depend on `hopi-core`: the plugin adds it
with the runtime to debuggable build types. An app that links a real Hopi module without the plugin
merges it wherever that module goes, where it starts nothing and writes one notice line
(ARCHITECTURE.md, Android capture, "Start").

```xml
<provider
    android:name="dev.koga.hopi.android.internal.HopiInitProvider"
    android:authorities="${applicationId}.hopi-init"
    android:exported="false"
    android:initOrder="2147483647" />
```

A plain `ContentProvider` is used, not androidx.startup, so there is no new dependency and no
merge with the app's own `InitializationProvider`. The highest `initOrder` makes it start before
other SDKs' providers that may make requests from their own providers (Firebase's
`FirebaseInitProvider` uses 100). `onCreate` (main thread) does four things. (1) It checks
`FLAG_DEBUGGABLE` in the app's `ApplicationInfo` (a companion function of the flags, which fails
closed when there is none): an app that is not debuggable gets one notice line, always, since the
configuration is not read, `not started: <package> is not debuggable (FLAG_DEBUGGABLE unset); a
Hopi runtime reached a non-debuggable build, check the app's dependencies`, and nothing else. (2)
It reads the configuration: not resources (see "As built"), but a Java class the plugin generates
into each debuggable variant from the `hopi { … }` block,
`dev.koga.hopi.android.generated.HopiGeneratedConfig extends HopiConfigValues` (core's), whose
constructor passes the global settings (the store caps, redaction, `summaryLog`, `agentBridge`)
and then the tool list, each tool constructed with its own settings:
`new NetworkTool(okhttp, urlConnection, rules)` and, with `perf { enabled = true }`,
`new PerfEntry(autoStart, hangThresholdMs, hangHeartbeat)`. One `Class.forName` and one
constructor call. Without that class (a runtime added without the plugin) it writes one notice line,
`not started: no generated configuration; apply the dev.koga.hopi Gradle plugin to the app`, and
starts nothing: the tool list lives in that class, and nothing was rewritten anyway (before stage 4
it started with defaults). (3) It registers core's current-activity callbacks (perf-monitors.md,
"How the monitors find the app"). (4) It runs core's start sequence (ARCHITECTURE.md, "Start
sequence") with the tool list: invalid caps give a notice line and no start, `HopiUrlSession`'s
rule; the default store's caps are preset; with `agentBridge` the store installation is made at
once on a background thread; then each tool starts. The network tool sets `started`. If
`Class.forName("okhttp3.internal.connection.RealCall", false, loader)` (or
`okhttp3.RealCall`) loads and carries `@HopiInstrumented`, it also calls
`PlatformCapture.coverWithPassThrough("io.ktor.client.engine.okhttp.")`; for `okhttp3.RealCall`
only when `okhttp3.RequestBody` has `isDuplex()` (OkHttp 3.14 and later, one reflective lookup made
only for 3.x). When the generated configuration says the `URL` calls of Ktor's Android engine were
rewritten (`urlConnection`, false when an exclude pattern matches
`io.ktor.client.engine.android.AndroidClientEngine`), it also covers
`io.ktor.client.engine.android.`. The class is not initialized, and no OkHttp class runs code. The consumer keep rules (below) keep `RealCall`'s name and the annotation in
a minified debug build, and `hopi-core`'s keep the provider and the generated class. Requests made
before the provider runs (`Application.attachBaseContext`, other processes) are not recorded. With
`summaryLog` the provider ends with one line of its own cost, in parts: `android capture started
(okhttp instrumented, <total> us; config <n> us, network <n> us, perf <n> us)`.

## Dedupe with the Ktor plugin

The iOS rule is reused unchanged: **platform capture is the source of truth for the engines it
covers, and the plugin defers.** The auto-start covers `io.ktor.client.engine.okhttp.` only when
the `RealCall` annotation proves that rewrite 1 is in the APK. So a Ktor OkHttp client yields one
record, `source = "okhttp"`, written by the interceptor. The plugin's install-time work still
happens, and it records nothing for that client, from its next request. Without the annotation
(an unknown OkHttp version, OkHttp excluded by the developer, the runtime added by hand without
the plugin, a Robolectric test where nothing is transformed) nothing is covered and the plugin
records as today, `source = "ktor"`. So "the plugin defers to nothing" cannot happen. Ktor's CIO
and MockEngine are never covered. Ktor channel bodies are captured on Android, because the
interceptor tees `StreamRequestBody` without reading it itself. Ktor's redirects stay one record
per hop.

**Ktor's Android engine** (`HttpClient(Android)`) sends through `URL.openConnection()` in its own
classes (`AndroidClientEngine`), which the scope-`ALL` rewrite reaches, so the wrapper records its
requests (`source = "urlconnection"`). The auto-start covers `io.ktor.client.engine.android.` when
the generated configuration says that rewrite is in the APK, and the plugin defers there too.

**A Ktor client that decodes** (`ContentEncoding`, or a request that sets its own
`Accept-Encoding`). OkHttp's `BridgeInterceptor`, and Android's platform `HttpURLConnection`,
decode gzip only when they added `Accept-Encoding` themselves. When the request names it, the
platform stack hands the body over encoded, and Ktor decodes it. The interceptor (or the wrapper)
would record the gzip bytes, under `Content-Type: application/json`, while the app reads JSON:
that breaks "the body as the app receives it". So for a covered engine the plugin keeps such a
request: when the request carries `Accept-Encoding` and the covering recorder honors the marker
(`PlatformCapture.honorsPassThrough`, true for both Android recorders, false for URLSession, which
always records what it decoded and could not remove a marker from the wire), the plugin records it
(`source = "ktor"`, its usual decoded plan) and adds `X-Hopi-Pass-Through: 1` to the request
after taking its snapshot. The interceptor, last among the application interceptors, removes it
before `chain.proceed` and records nothing; the wrapper drops it in `setRequestProperty` /
`addRequestProperty` (Ktor's Android engine uses the latter), never sets it on the delegate, and
records nothing for that connection. The wire is unchanged. What differs, stated: the app's own
OkHttp application interceptors, which run before Hopi's, see the marker on these requests (a
debuggable build, a Ktor client that decodes, capture on). A plain OkHttp or `HttpURLConnection`
request that asks for gzip itself is still recorded raw, because that is what its app reads.

The timing is tighter than on iOS. `cover()` runs in the provider, before `Application.onCreate`,
so a Ktor client built in `Application.onCreate` or later has no window in which a request is
recorded twice. Only a Ktor request sent from another `ContentProvider` with a higher `initOrder`
could fall into one, and none can rank above `Int.MAX_VALUE`; such a request is not recorded twice
either since 2026-10-27: `start()` covers the engines before it sets `started`, and the plugin
withdraws its record when it finds its engine covered by the time the engine answers, as on iOS
(urlsession-capture.md, "Dedupe with the Ktor plugin").

The other duplicate paths are closed too. OkHttp behind `URLStreamHandlerFactory` is recorded by
the interceptor only (wrapper rule above). Android's platform `HttpURLConnection` is not OkHttp 3,
and it is recorded by the wrapper only. Hopi's own code is never rewritten.

Rejected: the interceptor skipping every request that comes from Ktor, which would miss Ktor
clients without the plugin, as on iOS; and decoding gzip in the record, which would change the
record of an app that reads the raw bytes itself. Ktor's OkHttp engine sets no request tag, so the
narrow marker above is the only link from the plugin to the request OkHttp sees.

**OkHttp 3.12 and 3.13.** Rewrite 1 cannot tell OkHttp versions apart, so a 3.12 `RealCall` is
rewritten and annotated. The interceptor calls `RequestBody.isDuplex()`, which exists from 3.14
only, so on 3.12/3.13 the auto-start reports `Unsupported` (a notice line: "OkHttp before 3.14"),
covers nothing, and the hook returns the client's own list (it reads `okHttpInstrumented`, not only
`started`): those calls are not recorded, and they behave as without Hopi. Host tests load the real
3.12.13 and 3.14.9 jars through the rewriter: 3.12 is rewritten, not recorded, and its POST works;
3.14 is recorded with its bodies.

## The `source` field

Already built with the iOS part (model, summary line, bridge summary and detail, `BridgeDecoder`,
UI detail row and list row, desktop, HAR `_source`, CLI `[okhttp]`/`[urlconnection]`). Nothing
changes. Android records set `"okhttp"` and `"urlconnection"`, and their `durationMs` is not
labelled "(network)", because it ends when the app reads the end of the body, as the plugin's
does.

## The Gradle plugin

### Layout

```text
settings.gradle.kts         pluginManagement { includeBuild("gradle-plugin") }
gradle-plugin/              an included build (its own settings.gradle.kts, the root's version catalog through versionCatalogs { from(files("../gradle/libs.versions.toml")) })
  instrument/               plain JVM (kotlin("jvm")), artifact dev.koga.hopi:hopi-instrument: the ASM visitors, HookDescriptors; ASM compileOnly (AGP supplies it at run time), testImplementation for its tests
  plugin/                   java-gradle-plugin, id "dev.koga.hopi", class dev.koga.hopi.gradle.HopiPlugin, artifact dev.koga.hopi:hopi-gradle-plugin; compileOnly com.android.tools.build:gradle-api:9.4.0; implementation project(":instrument")
hopi-android/               KMP library, android target only (com.android.kotlin.multiplatform.library, like the other modules), explicitApi(), ABI validation; depends on hopi-core; compileOnly OkHttp 4.12.0 (the app brings its own; 4.12 is the lowest API Hopi compiles against, so 5.x-only API cannot creep in); consumer keep rules (as built: no resources, the configuration is a generated class; the manifest provider is hopi-core's since the runtime-split plan's stage 4)
hopi-android-noop/          the same public API, no dependency, no manifest entry, no resources
sample/fakesdk/             com.android.library, Java only, no Hopi plugin, no Hopi dependency (the third-party stand-in, below)
```

One runtime module rather than `hopi-okhttp` plus `hopi-urlconnection`. Both captures share the
start, the configuration, the capture dispatcher and the store installation, and the plugin always
installs both rewrites. Two modules would mean two parity pairs and two providers for no
selection a developer needs (`hopi { okhttp = false }` turns one off at build time instead).
`hopi-android` is not a dependency of `hopi-ktor` or of any KMP module. Only the plugin adds it,
to an Android application.

Public API (what the noop mirrors):

```kotlin
public object HopiAndroid {
    /** True once automatic capture runs in this process (never in the noop, never in release). */
    public val isStarted: Boolean
    /** True when this APK's OkHttp was instrumented (the RealCall annotation was found). */
    public val okHttpInstrumented: Boolean
}
```

Nothing else is public. `HopiInitProvider`, the hooks, the interceptor and the wrappers are in
`dev.koga.hopi.android.internal`. They are internal Kotlin declarations, or `@HopiInternalApi`
where a class must be public for the manifest. `HopiInitProvider` is a public class. Its package
is excluded from the ABI dumps the same way `hopi-ui` excluded `dev.koga.hopi.ui.desktop` (until
the runtime-split plan's stage 5 left `hopi-ui` without a dump), with the reason recorded in the
build script: the class is the manifest's, and the noop has no manifest. The
pair is added to `noopTwins` in the root build, so `:checkNoopParity` covers it, with a
`DefaultsParityTest`-style check that both `isStarted` and `okHttpInstrumented` read false in the
noop. (Since the runtime-split plan's stage 4, 2026-10-28, `HopiInitProvider`, its manifest entry,
`HopiConfigValues` and `GeneratedConfig` are `hopi-core`'s, in the same package; `hopi-android`'s
excluded package keeps the hooks, the interceptor, the wrappers and the network tool the generated
configuration names, which the app's own classes must reach.)

### DSL

```kotlin
hopi {                                   // all optional; defaults are HopiUrlSessionConfig's
    enabled = true                       // false: every variant gets the noop and nothing is instrumented
    okhttp = true                        // rewrite 1 and 2
    urlConnection = true                 // rewrites 3 to 7
    selectTwins = true                   // see "Twin selection"
    maxEntries = 500
    maxTotalBytes = 16L * 1024 * 1024
    maxBodyBytes = 256L * 1024
    redactHeaders("X-Api-Key")
    redactQueryParameters("token")
    unsafeDisableRedaction()
    summaryLog = true
    agentBridge = false
    excludeClasses("com.vendor.pinned.**")   // passed to Instrumentation.excludes
}
```

Every value is a Gradle `Property` and is configuration-cache safe. A task per debuggable variant
(`generate<Variant>HopiConfig`) writes the generated configuration class (Auto-start), registered
with `variant.sources.java?.addGeneratedSourceDirectory(…)`; the design's
`values/hopi_config.xml` became that class while building it (see "As built").

### Variant wiring

- The plugin applies only together with `com.android.application`
  (`pluginManager.withPlugin`). Applied anywhere else, it fails with "apply dev.koga.hopi to the
  Android application module; it instruments the libraries from there". Instrumenting from the app
  with `ALL` is what covers the libraries.
- `androidComponents.finalizeDsl`: for each build type, it adds `<buildType>Implementation`
  of `dev.koga.hopi:hopi-android:<version>` when `isDebuggable` (and `enabled`), else of
  `hopi-android-noop`; since the runtime-split plan's stage 4, a debuggable one also gets
  `hopi-core`, because javac compiles the generated configuration against core's types and every
  runtime takes core as `implementation` only. Debuggable is a build-type property, and
  finalizeDsl is the last point where dependencies can be declared.
- `androidComponents.onVariants`: for `variant.debuggable && enabled`, it calls
  `variant.instrumentation.transformClassesWith(HopiClassVisitorFactory::class.java,
  InstrumentationScope.ALL) { okhttp.set(…); urlConnection.set(…) }` and adds the excludes.
  Non-debuggable variants are never registered: no transform task exists for them. It fails the
  build if a variant's debuggable value disagrees with what finalizeDsl added, which flavors
  cannot cause today but a future AGP might.
- `isInstrumentable(classData)`: false for `dev.koga.hopi.android.*`. For OkHttp, true for the two
  `RealCall` names. For `URL` call sites, true for every other class: call sites cannot be seen
  from `ClassData`, so every class of the variant is read and written by ASM. AGP caches the
  transform per dependency jar (artifact transform), so this cost is paid once per dependency
  version. The build-time cost is measured (see Performance).
- **Version**: the plugin's build generates a `HopiPluginVersion` constant from the repository's
  `gradle.properties` (`hopi.version`, new, together with `group = dev.koga.hopi` on every
  published module). A unit test reads both files and compares them.
- **This repository** (the runtime is a project of the root build, and Gradle substitutes only an
  included build's projects for coordinates): the plugin adds `project(":hopi-android")` /
  `project(":hopi-android-noop")` (and, since stage 4, `project(":hopi-core")`) when
  `project.rootProject.findProject` finds that project, else the coordinates. Consumers always get the coordinates. The `instrument` module goes the
  other way: `hopi-android`'s host tests depend on `dev.koga.hopi:hopi-instrument`, which Gradle
  substitutes from the included build.
- Debug minify: the instrumentation runs on class files before R8, so R8 sees the rewritten
  calls. `hopi-android`'s consumer rules keep `dev.koga.hopi.android.internal.**`, the
  `HopiInstrumented` annotation (`-keepattributes RuntimeVisibleAnnotations`) and the two
  `RealCall` class names
  (`-keep,allowoptimization,allowshrinking class okhttp3.internal.connection.RealCall`). They apply
  only where the real runtime is, which is debuggable variants only. Since stage 4 `hopi-core`'s
  consumer rules keep the same two packages, for the provider and the generated configuration it
  loads by name.

### Twin selection (why `-Phopi.enabled` is no longer needed on Android)

With `selectTwins = true` (the default), the plugin adds a dependency substitution to each
variant's `compileConfiguration` and `runtimeConfiguration`. Debuggable variants get
`hopi-ktor-noop`→`hopi-ktor` and `hopi-ui-noop`→`hopi-ui` (the `hopi-ui` pair went in the
runtime-split plan's stage 5). The other variants get the reverse.
This applies to Maven coordinates in group `dev.koga.hopi` and to this repository's project
paths. A KMP shared module compiled against either twin links against the other, because the ABI
is the same (`:checkNoopParity`) and no public declaration of either side is `inline` or `const`
(checked against the dumps today, and made a test of the parity task: an `inline`/`const` line in
a parity-checked dump fails it, because its body would be inlined from the side compiled against).
So an Android app builds debug with the real inspector and release with the noop whatever the
property says, and the property keeps its meaning for iOS frameworks and for apps without the
plugin. A release build can no longer ship the real modules by passing a property. To do that, a
developer must remove the plugin, which is the "deliberate, visible choice" the architecture asks
for.

Consequence for the sample: `sample/androidApp` applies the plugin. A debug build without the flag
is now real (Ktor plugin, UI, capture), and the release build is always noop. The generated
`HOPI_ENABLED` constant stays for iOS. The Android app shows `HopiAndroid.isStarted` instead. The
`-Phopi.enabled=true` instructions for the bridge checks become unnecessary on Android, and the
architecture doc's sample section changes accordingly.

## Noop twin and release exclusion

- `hopi-android-noop`: the public API above, both values false, no manifest, no resources, no
  dependency. ABI dump, in `:checkNoopParity`.
- `:sample:androidApp:verifyReleaseUsesNoop` (exists): the expected release classpath becomes
  `{:hopi-ktor-noop, :hopi-ui-noop, :hopi-android-noop}` (`:hopi-ui-noop` until the runtime-split
  plan's stage 5 deleted it). The dex reader is extended from
  `class_defs` to `type_ids` and `method_ids`. The release APK must have no type in
  `Ldev/koga/hopi/android/internal/` or `Ldev/koga/hopi/core/` (a reference in instrumented
  bytecode would show up there even though no such class is packaged). It must have no provider
  `dev.koga.hopi.android.internal.HopiInitProvider` in the merged manifest. It must have the
  original `invoke-virtual Lokhttp3/OkHttpClient;->interceptors()` inside `RealCall` and
  `Ljava/net/URL;->openConnection()` inside the fake SDK's class: the negative control, so the
  check is known to look at the right code.
- New `:sample:androidApp:verifyDebugCapture` (part of the app's `check`), the positive control on
  the debug APK. The reader decodes each class's `class_data_item` and its `code_item`
  instructions (`invoke-*` formats 35c/3rc, the method index in the second code unit). It requires
  that `RealCall.getResponseWithInterceptorChain*` invokes
  `HopiOkHttpHooks.interceptors(Lokhttp3/OkHttpClient;)`. It requires that **no** class outside
  `dev.koga.hopi.android.internal` invokes `URL.openConnection`/`openStream`/`getContent`, and
  that the fake SDK's classes, the sample's own classes and `kotlin.io.TextStreamsKt` invoke
  `HopiUrlHooks.*`. The stdlib class is a valid check because the sample calls `URL.readText()`
  from its Diagnostics and the debug build has no R8 to remove it. It requires the provider in the
  merged manifest. The debug classpath must hold `:hopi-android`, `:hopi-core`, `:hopi-ktor` and,
  until the runtime-split plan's stage 5, `:hopi-ui`.
- `verifyHopiSelection` keeps its role for the `-Phopi.enabled` direction. With the plugin applied
  it checks the plugin's rule (release is always noop) instead of the property.

## Proving third-party coverage: `sample/fakesdk`

A Java-only Android library module that neither applies the plugin nor depends on any Hopi module.
It is compiled on its own and reaches the app as a library jar. With `InstrumentationScope.ALL`,
AGP treats a project library exactly as it treats an external AAR's classes, which is what the
check above inspects. Its OkHttp dependency is `implementation(okhttp 4.12.0)`. The app resolves
it to the 5.x that Ktor brings, which is itself a check that a library compiled against 4.x is
covered on 5.x. Contents (`dev.koga.fakesdk.FakeSdk`, started from the sample's Diagnostics,
"Third-party SDK"):

- a `private static final OkHttpClient` with its own application interceptor (adds
  `X-Fake-Sdk: 1`, which Hopi must record because Hopi runs after it) and a network interceptor,
  `enqueue` of a JSON POST and `execute` of a GET on a worker thread;
- `class SdkClient extends OkHttpClient`, built by reflection (`getDeclaredConstructor().newInstance()`);
- `client.newBuilder().addInterceptor(…).build()`, a derived client;
- `HttpURLConnection` GET with `getInputStream`, a POST with fixed-length streaming, a 404 read
  through `getErrorStream`;
- `new URL(…).openStream()` and a `Function<URL, URLConnection> f = URL::openConnection` method
  reference (rewrite 7).

A checked-in prebuilt AAR was considered and rejected. It would put a binary in the repository and
prove nothing more, because the transform does not distinguish a project library from an external
one, and the dex check proves the rewrite on the SDK's own classes.

## Architecture contract changes

`docs/ARCHITECTURE.md` changes before the code:

- Modules table: `hopi-android` (core; automatic OkHttp and `HttpURLConnection` capture on
  Android, added by the Gradle plugin to debuggable variants), `hopi-android-noop` (nothing), and
  the included build `gradle-plugin` (`dev.koga.hopi`, never a runtime dependency).
- "Idle cost is zero", the global interception row, gains: "On Android, global capture exists only
  in debuggable variants of an app that applies `dev.koga.hopi`. It rewrites at build time one
  call inside OkHttp's `RealCall` and the `java.net.URL` connection call sites, adds no method,
  field or interface to any class (one annotation on `RealCall`), changes no list or object the app
  can read, and wakes nothing while no request runs."
- "Selecting real vs noop": the plugin's twin selection for Android applications.
- "Sample app": the plugin, `sample/fakesdk`, the debug build being real without the flag, and the
  two dex checks.
- `docs/ROADMAP.md`: the Android line changes from "designed separately, not built" to the built
  state once measured.

## Tests

Host and JVM tests are part of the base check. Device tests are not, because they boot the
emulator.

**`gradle-plugin:instrument:test`** (JVM, ASM, no AGP):

1. Each rewrite on fixture classes compiled in the test (Java source via `javax.tools`, Kotlin
   fixtures as checked-in `.class` resources produced by the module's own test compilation):
   `invokevirtual` replaced, nothing else in the method changed (instruction lists compared
   one by one, minus the replaced instruction), `CheckClassAdapter.verify` clean, frames intact.
   Java method references, Kotlin indy callable references, Kotlin `url.readText()` (the stdlib
   class), call sites inside lambdas, `try`/`finally` blocks and constructors are covered.
2. The real `RealCall` from OkHttp 4.12.0, 5.3.2 and 3.14.9 (test dependencies): exactly one
   instruction rewritten, the annotation present, and no other OkHttp class touched.
3. `isInstrumentable` excludes `dev.koga.hopi.android.*`.
4. The whole runtime classpath of a Ktor + OkHttp + Okio + kotlin-stdlib app rewritten and
   verified: no `VerifyError` shape, and a count of rewritten call sites per jar (a regression
   signal).

**`hopi-android` host tests** (`testAndroidHostTest`, JVM; MockWebServer and `okhttp-tls`
`HeldCertificate` as test dependencies; OkHttp loaded through a child-first class loader whose
classes pass through the `instrument` rewriter, so these are instrumentation-equivalence tests):

5. Equivalence: for a scenario matrix (GET/POST, `Content-Length`/chunked, gzip with transparent
   decoding and with an app-set `Accept-Encoding`, 204/304/HEAD, a followed redirect, a cache hit,
   `execute`/`enqueue`, `newBuilder()` clients, a subclass, a reflection-built client), the app's
   observations with capture are identical to those without it: bytes (SHA-256), status, headers,
   the callback thread names, `client.interceptors()`/`networkInterceptors()` and builder lists
   (`==` and size), the `EventListener` event sequence, and the count of requests MockWebServer
   received (no re-issue). Each scenario gives exactly one record with method, URL, headers
   (including the app interceptor's), bodies, `protocol`, `finalUrl`, `source = "okhttp"`.
6. Bodies: an 8 MB response with a 256 KB cap reaches the app whole, the record keeps 256 KB,
   `truncated`, and `totalBytes` is exact. A one-shot request body (a `RequestBody` subclass that
   throws on a second `writeTo`) is sent once and captured. A duplex body is untouched. A body
   whose `writeTo` counts its calls is called exactly as often as without capture. An SSE
   response's first event reaches the app at the same time as without capture, within 5 ms
   (MockWebServer throttled body, timestamps), and a `/slow`-style trickle as well, which proves
   there is no read-ahead.
7. Failures: unresolvable host, refused, timeout, `cancel()` before and during the body, a reset
   mid-body, and app `close()` before the end. Each is `Failed` with the expected error, and the
   app sees the identical exception class and message.
8. `URLConnection` wrapper: the method-completeness tests (android.jar via ASM, and JDK
   reflection); a spy delegate that proves no delegate method is called before the app's own
   first call and that the only extra calls are the cached reads after it; HTTP and HTTPS
   (`HeldCertificate`, `HttpsURLConnection` casts, `getServerCertificates`, `setSSLSocketFactory`
   reaching the delegate); `getErrorStream` on 404; fixed-length and chunked uploads;
   `disconnect` mid-body; `openStream`/`getContent`; the `okhttp3.`-backed connection
   (`ObsoleteUrlFactory`) left unwrapped and recorded once.
9. Ktor dedupe: Ktor's OkHttp engine in the instrumented loader with `hopi-ktor` installed gives
   one record per request, `"okhttp"`. Two concurrent POSTs with different bodies give the right
   bodies. A channel body is captured. Redirects are one record per hop. Without the annotation
   (a non-rewritten OkHttp), the plugin records as `"ktor"`, and nothing is recorded twice.
10. Concurrency: 64 parallel calls with distinct bodies, each record with its own body. The
    capture dispatcher's order holds: no `Started` after its terminal record.
11. Allocation bound: with the cap at 1 byte and at 256 KB, the reader thread's allocation for an
    8 MB body (`ThreadMXBean.getThreadAllocatedBytes`) exceeds the run without capture by at most
    the kept bytes plus a fixed slack, mirroring `TeedBodyTest`.

**`hopi-android-noop` host tests**: the parity defaults, and no `hopi-core` on its classpath
(the existing noop rule).

**`gradle-plugin:plugin:test`**: unit tests of the DSL-to-resource generation and of the version
constant. Gradle TestKit functional tests (`functionalTest`, wired into the included build's
`check`, and the root `check` depends on it through `gradle.includedBuild("gradle-plugin")`) run on
one fixture app: AGP 9.4.0, `debug` and `release` plus a `staging` build type with
`isDebuggable = true, isMinifyEnabled = true`, a Java library with an `OkHttpClient` and a
`URL.openConnection`. In one `assembleDebug assembleStaging assembleRelease` run they check:
the transform tasks exist for debug and staging only; each APK's dex (the same reader, moved
into the `instrument` module so the sample and the tests share it) has the expected
references and classes; the merged manifests have the provider in debuggable variants only; the
classpaths hold real or noop; configuration-cache reuse on a second run; and the error when the
plugin is applied to a library. A second fixture has no `hopi-*` project, so the plugin takes the
coordinates a consumer of Maven Local gets: stand-in jars, published by a build of their own into a
fixture repository, for the runtime, `hopi-perf` and two twin pairs, which a library names in both
directions (`hopi-ktor-noop`, `hopi-rules`); the debug APK must hold every real stand-in and the
release APK every noop. The TestKit directory is a dedicated one under the plugin's
`build/`, never the developer's Gradle user home. A `--stop` there would also stop the outer
build's daemon, which has the same Gradle version and the same home. AGP is not downloaded: the
nested build runs `--offline`, and its environment sets
`GRADLE_RO_DEP_CACHE=~/.gradle/caches/modules-2`, Gradle's read-only dependency cache. Whether
Gradle 9.7.1 resolves AGP's module metadata from that cache is a spike item. If it does not, a
file repository that the build fills from the outer build's resolved artifacts replaces it. The
fixture's `gradle.properties` sets `org.gradle.jvmargs=-Xmx1g` and
`org.gradle.daemon.idletimeout=30000`, so the nested daemon exits soon after the last test. An
`@AfterAll` stops it explicitly, addressed to the TestKit directory only: a `gradle --stop`
process started with `GRADLE_USER_HOME=<testkit dir>` and the wrapper's distribution, as an
argument array, because `GradleRunner` may reject `--stop` as a non-build argument. The base
check ends by confirming that no `GradleDaemon` process is left (`jps`).

**Device** (API 35 emulator, not in the base check): `sample/androidApp` gains
`connectedDebugAndroidTest` cases, plus an end-to-end script in `benchmark/android-capture/e2e/`.
It starts the dev server and `adb reverse`, runs the app's Diagnostics actions (the Ktor client,
`FakeSdk`'s eight shapes, `URL.readText()`), and matches `hopi list/get --json` against the dev
server's request log: every request exactly once, the right `source`, bodies by SHA-256, none
`"ktor"` for the OkHttp engine. The same script on the release build proves that the app behaves
the same (the same server log) and that `hopi` finds no bridge.

## Performance A/B method

- **Arms**, both debuggable `debug` builds with the same toolchain and no R8: A = the plugin on;
  C = `hopi { enabled = false }` through a sample property (`-Psample.hopiCapture=false`), which
  means noop runtime and no transform. The Ktor/UI selection is the same in both (real, so the
  difference is capture and its instrumentation only; the Ktor plugin defers in A and records in
  C, so the Ktor rows also measure the swap).
- **Workload** (`--es captureBench <spec>`, the `GiantSpec` style): sequential requests through
  `FakeSdk`'s OkHttp client (`execute` on one worker thread), `FakeSdk`'s `HttpURLConnection`, and
  the app's Ktor client, at 1 KB, 64 KB, 1 MB and 5 MB, `Content-Length` and chunked. It records
  wall time per request (call to body end) and the calling thread's CPU
  (`Debug.threadCpuTimeNanos()`), one JSON line per block under `HopiSample`.
- **Blocks**: C, A, C, A interleaved, 3 rounds of 15 runs per size with the first 5 dropped, each
  A block's median against the mean of the C blocks around it, then the median of the rounds
  (`abn.sh` with `HOST_LOAD_MAX=3`, guest load under 2.5, `paired.py`), the method of the inline-tee
  A/B.
- **Startup**: 20 cold `am start -W` per arm, interleaved, `TotalTime` and the provider's own
  `onCreate` time (`SystemClock.elapsedRealtimeNanos` around it, logged in A only).
- **Idle**: 60 s with no requests, `/proc/<pid>/task/*/stat`. A adds no thread, and its CPU
  matches C.
- **Hook microbenchmark** (JVM, opt-in like the giant-body benchmarks): ns per call of
  `HopiOkHttpHooks.interceptors` with capture off and on, per-request interceptor overhead
  against MockWebServer at 1 KB, and per-chunk tee cost below and past the cap.
- **Build**: clean `assembleDebug` time and an incremental one-line change, A against C (the cost
  of `ALL`-scope instrumentation), 3 runs each.
- **Targets** (stated, not promised): the hook under 100 ns off and under 300 ns on; interceptor
  overhead under 30 µs per request on the emulator (debuggable ART); per-request wall time
  within the C-to-C spread at 1 KB and 64 KB, at most +2% at 1 MB; provider `onCreate` under
  1 ms; idle identical. Emulator figures are an upper bound for a device.

## Implementation steps

1. `gradle-plugin/instrument`: `HookDescriptors`, the visitors, and tests 1 to 4.
2. `hopi-android`: hooks, interceptor, request/response bodies, wrappers, capture dispatcher,
   provider, resources, keep rules; tests 5 to 11. `hopi-android-noop`, ABI dumps, `noopTwins`.
3. `gradle-plugin/plugin`: DSL, finalizeDsl dependencies, onVariants instrumentation, resource
   generation, twin selection, local-project switch; unit and TestKit tests. The parity task's
   `inline`/`const` rule.
4. `sample/fakesdk`, the sample applies the plugin, Diagnostics actions, `HopiAndroid` status, dex
   reader extension, `verifyReleaseUsesNoop` and `verifyDebugCapture`.
5. Device e2e and the A/B, then `ARCHITECTURE.md` and `ROADMAP.md` with the measured figures.

## Open spike items (resolved by tests before the code is final)

- OkHttp 3.12/3.14 (resolved): the runtime links against 3.14.9 with Okio 1.17 (host test). 3.12
  and 3.13 lack `RequestBody.isDuplex()`; the rewrite still happens (the plugin sees no version),
  and the runtime leaves them unrecorded (above).
- Whether any OkHttp 5.x release calls `interceptors()` from somewhere other than `RealCall`
  (5.3.2 does not).
- Whether `Buffer.copyTo`'s segment sharing raises the reader's allocation measurably, because
  shared segments are not recycled to the pool. The alternative is one bounded `ByteArray` copy
  per read, compared in test 11 and the microbenchmark.
- Android's `HttpURLConnectionImpl`: whether `getURL()` reflects a followed redirect on API 24 to
  37, and whether `X-Android-Selected-Protocol` is present on every version.
- AGP: whether `transformClassesWith` on a variant also applies to its `androidTest` and unit-test
  components (decide whether that is wanted: it would record the test APK's own requests), and how
  the `ALL`-scope transform behaves with `android.enableJetifier`.
- The KMP Android library plugin's support for manifest providers, `consumerKeepRules` and
  resources in the same module.
- Whether TestKit's nested build on Gradle 9.7.1 resolves AGP offline through
  `GRADLE_RO_DEP_CACHE`.

## Risks

1. **OkHttp internals change.** Rewrite 1 depends on `RealCall` calling the public
   `interceptors()`. A rename or a direct field read makes capture silently absent for OkHttp.
   Mitigation: the annotation gate (no false Ktor dedupe), a runtime notice line, a build warning
   when `OkHttpClient` is present without a rewritten `RealCall`, and the version matrix in
   test 2. Residual: a version that keeps the call but builds the chain somewhere else as well
   would capture only part of the traffic.
2. **Coverage gaps by construction.** Shaded or relocated OkHttp, code outside the APK (Play
   services dynamite modules, downloaded dex), Cronet's native API, WebView, native stacks, other
   processes, requests before the provider runs, and reflective `URL.openConnection`. A developer
   reading "every request" must see this list. It belongs in the architecture doc's coverage
   paragraph.
3. **Visible object changes.** `request.body` and `response.body` are Hopi's delegating classes
   below and above Hopi's position. Any `instanceof` on them, or on the `URLConnection` (`getClass()`
   is Hopi's wrapper), behaves differently. Reflection on `HttpURLConnection`'s private fields (the
   old `method`-field PATCH hack) would hit the wrapper's own fields. Mitigation: nothing else
   changes, and tests 5 and 8 pin it. Residual: SDKs that do this.
4. **Wrapper completeness across API levels.** A method added to `URLConnection`/`HttpsURLConnection`
   in a future Android release, not overridden by the wrapper, would run the superclass's
   implementation on the wrapper's unconnected state. Mitigation: the android.jar completeness
   test fails the build when `compileSdk` moves up. Residual: an app running on a newer OS than
   the compile SDK.
5. **Laziness of `URLConnection`.** The wrapper must never trigger a connection. The rule and the
   spy test guard it. Its request-property snapshot depends on `getRequestProperties()` being
   side-effect-free before connect.
6. **Header gap.** Headers OkHttp's `BridgeInterceptor` and the platform `HttpURLConnection` add
   (`Host`, `User-Agent`, `Accept-Encoding`, `Cookie`) are not recorded, the same as on iOS.
7. **Build cost.** `ALL`-scope instrumentation reads and writes every class of every dependency
   for debuggable variants, because `URL` call sites cannot be found from `ClassData`. It is
   cached per jar, but the first build and every dependency bump pay it. Measured in the A/B.
   `urlConnection = false` removes the whole-classpath pass and leaves only `RealCall`.
8. **Twin selection by substitution.** Running code compiled against the noop with the real
   module (or the reverse) is safe only while the ABI is identical and has no `inline`/`const`
   bodies. The parity check gains that rule. Compose stability metadata (`$stable` fields) of the
   two UI modules must also keep matching, which the existing dumps exclude. A test that
   runs the sample's shared module against the substituted twin covers it.
9. **Reader-thread copy.** The bounded copy runs on whatever thread reads the body, the main
   thread included, if an app reads there. That is the Ktor plugin's rule, and it is bounded by
   the cap, but the owner's "no work on the main thread" is met only as long as apps do not read
   bodies there.
10. **Pending records.** An OkHttp body or a `URLConnection` stream that the app never reads,
    closes or disconnects stays `Pending`, as in the Ktor plugin.
11. **Memory**: every request in flight holds at most the cap per body, request and response (one
    shared-segment buffer or block list each), plus the store's own bound, the same formula as the
    plugin; the records whose end waits for a store consumer that fell behind hold at most
    `maxTotalBytes` more between them (as built, see "The store thread's priority").
12. **TestKit on 8 GB.** The functional tests run a second Gradle daemon (1 GB heap) next to the
    outer build. They run serially, once per base check, and stop their daemon. If memory
    pressure shows, they move to an opt-in task, and the base check keeps the unit tests only.
    That trade-off must then be written down.
13. **Contract change.** Android joins iOS in having global capture. The "observe only, no app
    class changed" bar must hold as strictly. Here the only library class changed is OkHttp's
    `RealCall`, in one instruction plus one annotation.

## As built (2026-10-24)

Built as designed, with the deviations below; each has its reason. Files: `gradle-plugin/`
(`hopi-instrument`, `hopi-gradle-plugin`), `hopi-android/`, `hopi-android-noop/`, `sample/fakesdk/`,
`sample/androidApp` (plugin applied, checks), `benchmark/android-capture/run.py`.

### Deviations

- **Configuration: a generated subclass, not generated resources.** The plugin writes
  `dev.koga.hopi.android.generated.HopiGeneratedConfig extends HopiConfigValues` into each debuggable
  variant's Java sources; its no-argument constructor passes the `hopi { }` values to the runtime's
  constructor. The provider reads it with one `Class.forName` and one constructor call. Reason: the
  KMP Android library plugin would need `androidResources` enabled and an R class just for nine
  values, and a resource overlay has to be merged per variant; a class is one lookup and no resource
  processing. (A first version used `public static final` boxed fields read by reflection; eleven
  `getField` calls were replaced by the constructor.) Without the class (the runtime added by hand)
  the defaults apply. (History: since the runtime-split plan's stage 4 the class extends core's
  `HopiConfigValues` and carries the tool list, and without it nothing starts; see "Auto-start".)
- **Subproject names.** The included build's projects are `hopi-instrument` and
  `hopi-gradle-plugin`, not `instrument` and `plugin`: Gradle substitutes an included build's
  project for a coordinate by its project name, and hopi-android's tests name
  `dev.koga.hopi:hopi-instrument`.
- **No build-time warning for an unrewritten `RealCall`.** AGP runs the instrumentation as an
  artifact transform per jar, so no task sees both "this jar has `OkHttpClient`" and "no `RealCall`
  was rewritten". The runtime notice line (`okhttp: not instrumented (unknown OkHttp version), …`)
  is the signal; the annotation gate keeps the Ktor dedupe correct either way.
- **Bodies the app never reads.** A response body the app closes without reading a byte (an SDK
  ping that only looks at the status) is `Complete` with the body skipped as `"not read"` (size from
  `Content-Length`), not `Failed`/`"cancelled"`: the request succeeded, and the device run showed two
  of the fake SDK's calls doing exactly that. A body closed after some but not all of its bytes is
  still `"cancelled"`, and a body closed after its declared length was read whole is `Complete` (a
  JSON parser stops after the last byte without asking for more). The same rules hold for the
  `URLConnection` wrapper, and `disconnect()` with a response whose stream was never opened is
  `Complete`, `"not read"`.
- **Capture buffer.** The tee copies with `Buffer.copyTo(OutputStream, …)` into one bounded
  `ByteArray` per body, not into a shared-segment `okio.Buffer` (the spike item): it keeps no
  segment of the app's alive, and the array starts at the cap when `Content-Length` says the body
  reaches it (no growth copies; the first device run, which grew from 8 KB, cost the reader about
  0.5 ms more per 1 MB body).
- **The URLConnection header maps** (`getRequestProperties()` before the connection,
  `getHeaderFields()` after it) are kept as the returned maps and flattened on the capture
  dispatcher, not on the app's thread.
- **The dex checks decode instructions** (`DexReader` in `hopi-instrument`: class data, code items,
  every `invoke-*` format) as designed. `verifyDebugCapture` does not require the sample's own
  classes to call a URL hook: the sample's `URL.readText()` is an inline function whose body calls
  `kotlin.io.TextStreamsKt.readBytes`, so the stdlib class is the call site, and it is checked.
- **TestKit.** The fixture resolves AGP and `dev.koga.hopi` from a repository the plugin and the
  rewriter are published to (`build/functional-repo`), not from `withPluginClasspath()`: the
  plugin then shares AGP's classloader, as in a real build. Dependencies come from the developer's
  cache read-only (`GRADLE_RO_DEP_CACHE`; AGP 9.4.0 and its dependencies resolved from it), with
  network as the fallback (the first run downloaded 58 MB of Kotlin build tools into the TestKit
  directory; an offline run is untested); the TestKit directory is the plugin's `build/testkit`, and its daemons
  are stopped after each test by the pids their logs name (`ProcessHandle.destroy`), so no
  `--stop` can reach the developer's daemon. The fixture's `hopi-android`/`hopi-android-noop` are
  Java stand-ins with the runtime's class names (the plugin prefers a project of that name to the
  coordinates); the real runtime is covered by its own tests and the sample. A third fixture app
  has a `tier` flavor dimension (`free`, `paid`), a `staging` build type (debuggable, minified with
  R8 and the runtime's real `consumer-rules.pro`, which the stand-in ships) and a `qa` one (not
  debuggable), on OkHttp 5.3.2, which an Android app resolves to `okhttp-android`: the transform
  and the generated configuration exist for `freeDebug` and `freeStaging` only, both APKs have
  `RealCall` calling the hook (the Android artifact's own classes present) and the provider, the
  minified one keeps the hooks, provider and configuration, and `freeQa` has no Hopi class, the
  noop stand-in and the original `interceptors()` and `openConnection()` calls.
- **Tests not written** from the design list: the
  allocation bound (test 11), the hook microbenchmark, the EventListener sequence, cache hits,
  WebSocket, `ObsoleteUrlFactory`, HTTPS through the wrapper on the host (exercised on the device since, below; the completeness test
  covers its methods; the device run covers plain HTTP only). Ktor on the host: the OkHttp engine
  with the OkHttp 5 it needs (loaded from their jars in place of the tests' 4.12) and the Android
  engine, each with and without `ContentEncoding`, give exactly one record per request (`okhttp` /
  `urlconnection`, or `ktor` with the decoded body), and the marker never reaches the server.

### Verified on the emulator (API 35, debug builds)

`benchmark/android-capture/run.py e2e` against the sample's debug APK and the same APK built with
`-Psample.hopiCapture=false` (noop runtime, noop twins, no instrumentation, as release): the fake
SDK's nine requests (OkHttp `enqueue` POST and `execute` GET of a static client with its own
application and network interceptors, a subclass built by reflection, a `newBuilder()` client,
`HttpURLConnection` GET, fixed-length POST, a 404 through `getErrorStream`, `URL.openStream()`, a
`URL::openConnection` method reference), Kotlin's `URL.readText()`, and one Ktor request through the
OkHttp engine (`/giant?mb=1`) each appear exactly once in the summary log and in `hopi list --json`
over the bridge, matched against the dev server's own request log: OkHttp calls `"okhttp"`, the
`URL` calls `"urlconnection"`, the Ktor request `"okhttp"` and no record `"ktor"`. `hopi get` shows
the headers the SDK set (`X-Fake-Sdk`, from its own interceptor) and the request bodies the server
received. The noop build sends the same 11 requests and records nothing, and reports
`HopiAndroid.isStarted = false`.

### Measured (2026-10-24, same emulator, debuggable builds, no R8)

Interleaved blocks (noop, capture, noop, capture, …), each block a fresh install and launch, one
worker thread, sequential GETs through the fake SDK's OkHttp client (`execute`) and its
`HttpURLConnection`, each body read to the end; the dev server ran with `--nodelay` (without it every
small keep-alive response waited about 40 ms for Nagle's algorithm, which hid everything else).
Median wall time per request (call to body end) and the calling thread's CPU per request
(`Debug.threadCpuTimeNanos`), per block:

| Body | Stack | Wall, noop | Wall, capture | CPU, noop | CPU, capture |
| --- | --- | --- | --- | --- | --- |
| 17 B (`/json`, 1000 after 1000 warm-up, 3 rounds) | OkHttp | 1096 to 1121 µs | 1487 to 1490 µs | 259 to 262 µs | 343 to 345 µs |
| | HttpURLConnection | 979 to 993 µs | 1337 to 1351 µs | 114 to 118 µs | 207 to 209 µs |
| 300 KB (`/large`, cap 256 KB, 200 after 200, 2 rounds) | OkHttp | 3011 to 3065 µs | 3378 to 3400 µs | 919 to 939 µs | 1176 to 1190 µs |
| | HttpURLConnection | 2271 to 2289 µs | 2585 to 2589 µs | 454 to 470 µs | 621 to 639 µs |
| 1 MB (`/giant?mb=1`, 30 after 20, 2 rounds) | OkHttp | 5859 to 5990 µs | 6511 to 6592 µs | 2794 to 2964 µs | 3350 to 3372 µs |
| | HttpURLConnection | 4500 to 4501 µs | 4674 to 4702 µs | 1095 to 1810 µs | 1502 to 1512 µs |

So capture costs the calling thread about 85 to 95 µs of CPU per small request, about 170 to
260 µs for a body copied up to the 256 KB cap, and adds 350 to 390 µs of wall time per small
request on this emulator. The wall cost is about four times the calling thread's CPU: the rest is
the work Hopi does on other threads per request (two posts to the capture dispatcher, the store's
consumer, the summary log line through logd) competing for the emulator's CPUs. Both numbers are far
above the design's targets (30 µs, "within the noop spread"); the targets were stated for a
JIT-warmed path, and a debuggable app on ART runs much of this code interpreted. Not yet broken
down further; a simpleperf profile of the capture build is the next step. Against a real network
request (tens to hundreds of milliseconds) the added wall time is under 1%.

Cold start (`am start -W`, `TotalTime`, 16 launches per arm in two interleaved rounds, 3 minutes
after boot): 1152 ms median (1091 to 1247) for the noop build against 1200 ms (1112 to 1298) for the
capture build, the same +45 to +55 ms in two earlier runs. That difference is the whole debug Hopi
(the real Ktor plugin and UI, the bridge, the provider), not capture alone. The provider's own
`onCreate`, timed inside it and written in its notice line in three parts: 5.4 ms median (2.9 to
15.4 ms), of which reading the generated configuration 0.24 ms, the `RealCall` lookup 0.5 ms and
`AndroidCapture.start` 4.2 ms (loading core's `CaptureInstallation` and `PlatformCapture` and the
Kotlin collection classes `PlatformCapture.cover` uses, which the app loads moments later anyway).
The design target was 1 ms; it is not met. An earlier build that turned the configuration's string
arrays into lists with Kotlin's array extensions spent 9 to 50 ms there (loading and verifying
`kotlin.collections.ArraysKt___ArraysKt` on a debuggable build); it uses `java.util.Arrays` now.
Raw data: `benchmark/android-capture/2026-10-24/` (`e2e/`, `ab/ab.ndjson`, `startup-run*.json`).

### End-to-end pass on the emulator (2026-10-24, second pass)

`benchmark/android-capture/e2e/run.sh` then `check.py` (the Android twin of the URLSession check),
on the API 35 emulator with the capture debug APK and the noop one (`-Psample.hopiCapture=false`).
`--es verify all` runs 44 scenarios, each tagged `X-Hopi-Verify`: 14 through Ktor (OkHttp engine),
15 through the fake SDK's OkHttp client, 15 through `HttpURLConnection`/`HttpsURLConnection`: JSON
GET, POST with `Authorization`, 404 (through `getErrorStream`), connection refused, unresolvable
host, 300 KB past the cap, chunked, redirect, 20 MB with `Content-Length` and chunked, gzip that
the client decodes, gzip the app asked for itself (raw bytes), a body cut off half way, a 1 MB
upload, and `https://example.com/` (OkHttp negotiates `h2`, recorded `HTTP/2.0`; the wrapper's
`getCipherSuite`/`getServerCertificates` work). Result: 54 checks, 0 failed. Every request the dev
server saw has exactly one record (Ktor's redirect is two calls, so two records; OkHttp's and the
wrapper's own redirects one record with `finalUrl`), sources `okhttp` 30 and `urlconnection` 15,
none `ktor`; `hopi list`, `get` and `tail --json` agree, and each record has one summary log line
with its source. Captured bodies equal the bytes the app read (SHA-256), truncated ones equal the
first 256 KB of the server's body with the full `totalBytes`; request bodies and redaction are as
sent. The noop build read the same bytes in all 44 scenarios, and the server saw the same requests,
headers and bodies from both builds (nothing re-sent, nothing changed on the wire).

Finding (by design, not fixed): a response of a `ResponseSkip` type (binary, `text/event-stream`) is
not wrapped, so its record ends at the headers: `complete`, `totalBytes` taken from `Content-Length`
and `ms` without the body. A binary body that fails half way is therefore recorded `complete`
(first run, `/drop` as `application/octet-stream`, all three stacks); a JSON one is `failed` with
`ProtocolException: unexpected end of stream`. Wrapping skipped bodies with a counting-only source
would make state, size and duration exact at the cost of one wrapper per binary response.

Measured on the same emulator (interleaved noop, capture, noop2 blocks; noop2 − noop is the noise):
small `/json` GETs (`--nodelay`, 1000 after 1000 warm-up, 4 rounds): OkHttp +366 µs wall
(noise ±7), +73 µs calling-thread CPU (noise ±2); `HttpURLConnection` +336 µs wall (noise ±11),
+76 µs CPU. Ktor request plus parse (`abn.sh`, 3 rounds, 15 runs after 5): 1 MB +4.3 ms total
(+6.3%, noise −4.5..+2.0 ms), fetch +5.5 ms; 5 MB +20 ms total (+7.3%, noise −8.5..+8.0 ms), fetch
+6.3 ms. The +2% target at 1 MB is not met. Idle 60 s after a burst: every `hopi-*` thread and
every `DefaultDispatcher` worker 0 ticks, with and without `hopi tail` attached; process CPU 50 ms
for both builds. Raw data: `build/evidence/okhttp/`.

**One post per request, tried and dropped (2026-10-24).** Each post to an idle capture dispatcher
wakes a `Dispatchers.Default` worker, billed to the request thread (8 to 17 µs of its CPU per post
on the host JVM), so a variant queued the start without a wakeup (a lock-free queue drained in
order, plus a flush timer armed at most once per 50 ms). On the host it saved about 6 µs of the
request thread's CPU per request. On the API 35 emulator (interleaved, 4 rounds, `/json`, 1000
requests after 1000 warm-up, `--nodelay`), against the previous capture APK: OkHttp +6 µs CPU
(noise −23..+26), −8 µs wall; `HttpURLConnection` +6 µs CPU (noise −30..+28), −19 µs wall: no
difference outside the noise. It was reverted (it added a thread and delayed pending records by up
to 50 ms for nothing measurable). The two posts are not where the calling thread's cost goes; the
same run, with the other fixes of that day, measured capture against noop at +55 µs (OkHttp) and
+60 µs (`HttpURLConnection`) of calling-thread CPU, +395 / +365 µs wall. A simpleperf profile is
still the next step. Raw data: `build/evidence/okhttp/ab-handoff/`.

## Hardening (2026-10-24, third pass)

A simpleperf profile of the second pass (`build/evidence/hardening/profile.md`) found that most of
Hopi's per-request cost was hand-offs, not work: the calling thread posted to the capture
dispatcher, which submitted to the store's consumer coroutine, whose events woke the log sink's
coroutine, three `Dispatchers.Default` dispatches each paying a futex wake and the woken worker's
spin before parking (216 µs of other threads' CPU and about 4 wakes per request). It also found
that about 215 to 270 µs of the earlier "+336 to +366 µs wall" was the harness streaming Hopi's
logcat lines to the host during the block. Changes:

- Records are built on the store's own consumer thread (`store.submitDeferred`), which applies
  them and writes the summary line in the same pass (the sink is a store listener): no scheduler
  spin, at a lower priority (ARCHITECTURE.md, "Store"). This pass still woke the parked consumer
  twice per spaced request, at its start and at its end (the back-to-back benchmark hid the
  first: the next start found the consumer still busy); since 2026-10-25 a start wakes it only
  while a viewer is attached (below, "Review fixes").
- `responded` reads the raw `Content-Type` header instead of OkHttp's parsed `MediaType`;
  `ResponseSkip.reason` matches the MIME type in place without allocating; `HttpURLConnection`
  response headers are read by index instead of through the per-call map; the redirect check
  compares the URL object first; a body's trimming copy runs on the store's thread, never the
  reader's.
- Binary and `text/event-stream` bodies are wrapped with a counting-only capture (cap 0), so
  their records end with the body: exact size, duration and failure state; an SSE stream stays
  `Pending` until the app closes it (`AndroidCaptureTest`: chunked binary, a connection dropped
  mid-body, SSE pending mid-stream then complete with its exact size).
- A failure after the response keeps the status, headers and the part of the body that arrived.

Measured (API 35 emulator, debuggable builds, dev server `--nodelay`, no logcat reader during a
block: `build/evidence/hardening/fixes/ab4.sh`, arms noop / before (the 19:31 build) / after /
noop, 3 rounds of 1000 after 1000 warm-up per stack, deltas against the mean of the two noop
blocks of the same round, µs per small request):

| | Before | After | Noise (noop − noop) |
| --- | --- | --- | --- |
| OkHttp wall | +147 to +155 | +68 to +76 | −8 to +1 |
| `HttpURLConnection` wall | +135 to +168 | +80 to +84 | −10 to +13 |
| OkHttp calling-thread CPU | +77 to +82 | +65 to +67 | −5 to +4 |
| `HttpURLConnection` calling-thread CPU | +83 to +91 | +72 to +74 | −4 to +3 |
| Process CPU (both stacks, 4000 requests) | +460 | +183 | |

The targets (calling-thread CPU ≤ 30 µs, wall within noise) are **not met** on this emulator.
Profiled after the change, the calling thread's Hopi cost is about 65 µs (×0.65 unprofiled, about
42): the semaphore release that wakes the parked store thread at the end of the body, 37 µs
profiled (a futex wake on a virtualized CPU, the kernel's own time), two channel `trySend`s, 13,
and the rest a few µs each; OkHttp's own code runs about 20 µs slower while the store thread works.
The wake is the floor of an event-driven design that publishes each completed request at once (no
timer, no polling); the remaining lever is a lighter queue than kotlinx `Channel`. Other threads'
Hopi CPU fell from 293 to 123 µs profiled per request and the scheduler spin from 108 µs to 0.

Ktor through the OkHttp engine, 1 and 5 MB request plus parse (`abn.sh`, noop / hopi / noop, 3
rounds of 15 runs, host load about 4, median ms): 1 MB +7.5, +17, +7.5 against noise −23, +14, +3;
5 MB +32.5, +9, +15 against noise +13, −2, +4. Before: 1 MB +14.5, +19, 0; 5 MB +25.5, +23.5, −1.
The 1 MB difference is inside the noise band; the 5 MB one is at its edge (about +4% median),
against a profiled direct cost of about 0.4%. The host was loaded (another app), so this stays
open. Raw data: `build/evidence/hardening/fixes/` (`small2/`, `giant-ab.*`, `cpu/`).

## Review fixes (2026-10-25)

A review of the hardening pass confirmed seven defects; all are fixed, with tests:

- **A call cancelled mid-body** (`call.cancel()`, the normal end of an okhttp-sse `EventSource` or
  a Ktor SSE session on OkHttp) was `Failed` with the exception text (`"SocketException: Socket
  closed"`). `OkHttpRecord` now has the `Call`: after a cancel a stream is `Complete` with its
  exact size, any other body `Failed`, `"cancelled"` with what arrived (`AndroidCaptureTest`:
  `aStreamEndedByCancellingItsCallIsCompleteWithWhatWasRead`,
  `aBodyWhoseCallIsCancelledMidReadIsCancelledAndKeepsWhatArrived`).
- **A disconnect or close from another thread mid-read** could build the record from a capture the
  reader was still writing (a zero-filled tail, a count that disagrees with the bytes). The tees'
  ends are now the capture's `freeze()`, and every copy and the freeze hold its monitor
  (`BoundedCaptureTest.aFreezeOnAnotherThreadSeesEveryTakeBeforeItAndNoneAfter`,
  `AndroidCaptureTest.aDisconnectFromAnotherThreadMidBodyIsCancelledAndLaterReadsChangeNothing`).
  An uncontended monitor per read, tens of ns.
- **Two wakes per request.** Every pending write woke the parked consumer, so a spaced request
  paid a futex wake on its own thread at its start as well as at its end. A pending write now
  wakes the consumer only while a viewer is attached (`HopiStore.attachViewer`: every bridge
  session for its lifetime, the in-app and desktop list while composed); otherwise the start
  waits in its lane and the next wake (its own end at the latest, or an attach) applies it in the
  same pass. Consequences: with nobody watching, a request in flight is not in the store until
  something wakes the consumer; attaching wakes it at once, so a `tail` sees every request in
  flight (the backlog or a live `Started`), and a `list`/`get` right after connecting depends on
  that one pass running before the request arrives (the hello round trip in practice), the same
  eventual consistency every terminal write already has. With nobody watching, more than 256
  requests started and not ended drop the oldest starts (the pending lane's `DROP_OLDEST`); those
  requests appear when they end. Tests: `StoreWakeTest` (thread mode: one wake per request with no
  viewer, every start wakes with one), `HopiStoreTest` (scope mode).
  Measured (API 35 emulator, debuggable builds, dev server `--nodelay`, no logcat reader;
  interleaved noop / before (this tree without these fixes) / after / noop, 3 rounds, `/json`;
  `build/evidence/review/android/`, `final-table.md`). The sample's `captureBench` takes
  `gapUs` (an idle gap before each request, outside its timed window). `hopi-store` wake-ups per
  request (schedstat timeslices): spaced 2 ms, 2.07 before and 1.04 to 1.05 after in every round;
  back to back 1.13 to 1.17 before and 1.06 to 1.07 after. Process CPU per spaced request, against
  noop: before +344 to +567 µs, after +306 to +471 (noise +115 to +142), so about 40 to 110 µs
  less. The calling thread's CPU on spaced requests is not measurable at this size on the
  emulator: a spaced request costs the noop build itself 800 to 1450 µs of CPU (290 back to back),
  and noop − noop is ±70 to ±170 µs; after − before there is −36 µs mean for both stacks, inside
  that noise. Back to back nothing moved outside the noise: calling-thread CPU OkHttp +64..+68 →
  +68..+71, `HttpURLConnection` +68..+74 → +70..+78 (noise −7..+8), wall median within ±30 µs of
  before. The calling-thread target (≤ 30 µs) is still not met.
- **The store thread's priority** was `THREAD_PRIORITY_BACKGROUND`, which also switches the
  scheduling policy (`SCHED_SP_BACKGROUND`, on many devices the background cpu cgroup with its
  small share): a busy foreground app could starve the only consumer for seconds. It is now nice 5,
  below the app's threads, in the app's own group. On the API 35 emulator (Android 15) both values
  leave the thread in `cpu:/top-app` and `cpuset:/top-app` (`ps -T` policy `ta`), so the emulator
  cannot show the difference; a phone under foreground load can. While the consumer is behind,
  each queued end kept its captures alive outside the store's byte cap (a request and a response,
  each up to the cap, so up to 512 KB at the default and 16 MB at the 8 MB clamp), up to the
  terminal lane's 4096 entries. Closed 2026-10-27: the store counts what the queued ends hold
  (`DeferredRecord.heldBytes`: the arrays of the request capture and of the response capture, one
  atomic add per end that holds any), and an end that would take that past `maxTotalBytes` while
  another waits lets go of its captures on the request thread before it is queued (`shedBodies`),
  recorded with each body's size and `"store backlog over byte cap"`; half of `maxTotalBytes`
  queued wakes the consumer out of its doze. A start here carries no request body (each capture is
  made for its end alone), so an end counts and lets go of arrays only it holds, unlike the Ktor
  plugin's and URLSession capture's ends, which share their request body with the start
  (ARCHITECTURE.md, "Store"). Nothing is captured after the end: an output stream the app opens
  then (a third-party connection whose first connect failed, opened again) and a rule's body sent
  after a `disconnect()` reach the connection uncopied, so `heldBytes` stays what the store
  counted. Still held by a queued record: the app's own `Request`
  and its body object (OkHttp) or the connection with whatever the platform buffered
  (`HttpURLConnection`), as before. The request thread now pays that atomic add plus one volatile
  read of the request capture per end. Measured on 2026-10-27 with every fix of that day in
  (`benchmark/android-capture/2026-10-27/`, criteria in
  `benchmark/perf/2026-10-27/session-verdicts.md`): API 35 emulator, debuggable builds, dev server
  `--nodelay`, `/json` back to back, 1000 after 1000 warm-up, arms noop, before (9356117, the tree
  without the fixes), after (1c81938) and noop2. The 4 rounds registered before any data, in that
  order: after minus before calling-thread CPU +4, −22, −2, +37 µs on OkHttp and +1, −6, +1, +12
  on `HttpURLConnection`, inside the noop2 − noop band in 3 of 4 rounds on each (pass); median
  wall inside it on OkHttp (pass), not on `HttpURLConnection` (+84, −70, −1, −39 against −19..+0,
  scattered both ways: fail). A follow-up set after reading those, 4 rounds with after and before
  swapped on a quieter guest: CPU +11, −3, +9, +2 µs on OkHttp and −1, −3, +3, −2 on
  `HttpURLConnection`, outside that run's narrower bands (−15..0, −2..+1). Over the 8 rounds the
  CPU difference is a median of +3 µs on OkHttp (mean +4.5; the pooled band's +39 edge comes from
  the session's first block, a noop at guest load 8.4 that read 267 µs) and 0 on
  `HttpURLConnection`: no cost shows on `HttpURLConnection`, and on OkHttp one of up to about
  10 µs is not ruled out. Process CPU per block (the whole process, launch and JIT included) was
  lower after than before in 6 of 8 rounds, inconclusive by the follow-up's own rule, and the
  difference sits in the JIT pool and the main thread, not in Hopi's threads (within ±22 ms a
  block). Against noop, after the fixes, 8 rounds: calling-thread CPU +24 µs median on OkHttp
  (2 rounds over 30) and +22.5 on `HttpURLConnection` (1 over 30), median wall +27 and +12 µs.
- **An older CLI** shows a `failedAfterResponse` record's status (`200`) in its code column
  instead of `ERR` (ARCHITECTURE.md, "A failure after the response arrived keeps the response").
- **The iOS idle harness** summed the CPU of the threads alive at each end, so a thread that
  exited in the window took its whole lifetime off the process figure; it now uses
  `getrusage(RUSAGE_SELF)` at both ends (urlsession-capture.md).

