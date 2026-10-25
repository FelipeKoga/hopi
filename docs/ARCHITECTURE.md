# Hopi architecture

Hopi is a real-time debugging toolkit for Kotlin Multiplatform / Compose Multiplatform apps on
Android and iOS, inspired by DebugSwift. v1 ships one tool: an HTTP inspector for apps that use the
Ktor client. It is built for two audiences at once: a developer looking at an in-app screen, and an
AI coding agent reading the same traffic from a terminal.

This document is the contract every module is built against. Change it before changing code.

## Non-negotiable principles

1. **Zero production impact by default.** Every consumer-facing artifact has a `-noop` twin with an
   identical public API and no dependencies beyond what the API itself names. Shipping the real
   artifact in a release build is a deliberate, visible choice in the consumer's Gradle file, never
   an accident.
2. **The caller's request path stays fast.** On the request coroutine Hopi only copies headers
   and a bounded slice of body bytes, then hands off. It never suspends the caller on its own work,
   never applies backpressure, never consumes a body the app needs, and never buffers an unbounded
   stream. Formatting (JSON pretty-print, cURL, NDJSON) happens lazily, off the request path, and
   only when something is actually looking.
3. **Bounded memory.** The store is a ring buffer bounded by both entry count and total captured
   bytes. Bodies are capped per transaction and marked truncated.
4. **No network exposure.** Nothing listens on a routable interface. The agent channel uses
   device-local transports only (details below) and authenticates every connection.
5. **Secrets are redacted by default** in every outward channel (UI copy, cURL, logs, agent
   channel): `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, the userinfo of a URL
   (`https://user:password@host` becomes `https://<redacted>@host`), plus user-configured headers
   and query parameters. Opting out is explicit.
6. English everywhere: code, comments, docs, commits.

## Modules

| Module                | Depends on                           | Purpose                                                    |
| --------------------- | ------------------------------------ | ---------------------------------------------------------- |
| `hopi-core`      | kotlinx-coroutines                   | Models, store, redaction, cURL, JSON writer, event stream, log sink, agent bridge server |
| `hopi-ktor`      | core, ktor-client-core               | The Ktor client plugin                                     |
| `hopi-ktor-noop` | ktor-client-core                     | Same public API as `hopi-ktor`, does nothing          |
| `hopi-ui`        | core, Compose Multiplatform          | In-app inspector screen and launcher                       |
| `hopi-ui-noop`   | compose-runtime, compose-ui          | Same public API as `hopi-ui`, renders nothing         |
| `hopi-connect`   | core (JVM), kotlinx-serialization    | Host side of the bridge: targets, `bridge.json`, bridge client (all `@HopiInternalApi`) |
| `hopi-cli`       | connect, core (JVM)                  | Host CLI for developers and AI agents                      |
| `hopi-desktop`   | connect, ui (JVM), core (JVM), Compose Desktop | Desktop viewer app (macOS `.dmg`); never a dependency of an app |
| `sample/*`            | everything                           | CMP demo app (Android + iOS) making real requests          |

Targets for every KMP library module: `android`, `iosArm64`, `iosSimulatorArm64`, and `jvm` (the JVM
target exists so logic tests run on the host in seconds; it is also a legitimate desktop target).

`hopi-core` is public so the other Hopi modules can use it, but every declaration that is
not part of the consumer API is annotated `@HopiInternalApi` (a `RequiresOptIn` annotation).
The one exception is the iOS os_log shim (see Agent access): cinterop cannot annotate its binding,
so it lives in `dev.koga.hopi.core.internal.oslog` and binds only `hopi_os_log_line`
(no os/log.h declarations are re-exported).
Consumers only ever touch the API of `hopi-ktor` and `hopi-ui`, which is what the noop
twins mirror.

## Consumer API (what the noop twins mirror)

`hopi-ktor`:

```kotlin
val client = HttpClient(engine) {
    install(Hopi) {
        enabled = true                       // runtime kill switch, default true
        maxEntries = 500                     // ring buffer entry cap
        maxTotalBytes = 16L * 1024 * 1024    // ring buffer byte cap across all bodies
        maxBodyBytes = 256L * 1024           // per-body capture cap; bodies beyond it are truncated; at most maxTotalBytes / 2
        redactHeaders("X-Api-Key")           // added to the default redaction list
        redactQueryParameters("token")
        unsafeDisableRedaction()             // explicit opt-out, logged once at install
        logSink = HopiLogSink.Summary   // Off | Summary (default) — one NDJSON line per transaction
        agentBridge = true                   // start the device-local agent bridge, default false
    }
}
```

`Hopi` is a `ClientPlugin<HopiConfig>`. Installing it in several clients is supported:
all clients share one process-wide store (`HopiStore.default`), so the UI and the CLI see one
timeline. The store's caps are taken from the first installation that runs.

Redaction belongs to the store, because every outward channel reads the shared timeline: the store's
`redactor` is the union of every installation's lists (`includeRedaction`), and it is disabled only
when every installation called `unsafeDisableRedaction()`. When an installation with
`logSink = Summary` leaves the store's redaction disabled, one notice line,
`{"hopi":1,"notice":"redaction disabled by unsafeDisableRedaction()"}`, is written to the
platform log, once per process; `logSink = Off` writes nothing. The summary log sink starts once per process, for the default store only, at the first
installation with `logSink = Summary`; it reads the store's `redactor` per line.

`agentBridge = true` calls `AgentBridgeHook.requestStart(store)` in `hopi-core` (default store
only; an installation with an injected store starts nothing). The hook's starter is core's own
platform bridge; `register` replaces it (tests do, to observe the request without binding a
socket). The first request starts it, once per process, on a background thread: the install path
only spawns that thread. `agentBridge = false` never touches the hook.

`hopi-ui`:

```kotlin
@Composable fun HopiScreen(onClose: () -> Unit, modifier: Modifier = Modifier)
@Composable fun HopiLauncher(content: @Composable () -> Unit)   // wraps app content, adds a floating button
```

The noop twins expose exactly these declarations with the same signatures. `HopiConfig` in
the noop accepts every setter and ignores it (a property reads back what was set, with the same
defaults); `Hopi` is a `createClientPlugin("Hopi", ::HopiConfig) {}` that registers
no hook. `HopiScreen` in the noop renders nothing; `HopiLauncher` in the noop renders
`content()` and nothing else, laid out exactly as the real launcher lays it out (one compose-ui
`Layout` with the same measure policy: every root child of `content` gets the launcher's own
constraints unchanged and is placed at the top start, and the launcher is as large as its largest
child), so a release build has the layout tested in debug. `LauncherLayoutTest`, the same file in
both modules' JVM tests, checks it. `hopi-ktor-noop` depends on ktor-client-core only (the API names
`ClientPlugin`). `hopi-ui-noop` depends on compose-runtime and compose-ui and applies only the
Compose compiler plugin (for the `@Composable` calling convention), not the Compose Multiplatform
Gradle plugin: compose-ui is there because `HopiScreen`'s `modifier` parameter is
`androidx.compose.ui.Modifier`, which lives in compose-ui (it brings ui-graphics, ui-text and
ui-unit, no foundation and no Material). Neither noop depends on `hopi-core`; each noop's JVM
tests assert that its test runtime classpath holds no `hopi-core` and that core's classes
cannot be loaded.

**Parity is checked mechanically.** The four modules enable the Kotlin Gradle plugin's built-in ABI
validation (`kotlin { abiValidation { … } }`, KGP 2.4.20, `@ExperimentalAbiValidation`), which
writes JVM, Android and klib (iOS) dumps to each module's `api/` directory (`updateKotlinAbi`) and
fails `check` when the code no longer matches them (`checkKotlinAbi`). The dumps are checked in.
The root task `:checkNoopParity` (part of the root `check`) runs every `checkKotlinAbi` of both
sides, then compares each real module's dumps with its twin's file by file, line by line; the only
normalisation is the module's own name in the dump file names and in the klib dump's
`// Library unique name:` line. A declaration on one side only, a different signature, or a
missing target fails the build with the differing lines. The two UI modules exclude the same
Compose compiler output from their dumps (`**.ComposableSingletons.*` lambda holders and the
`$stableprop`/`$stableprop_getter` stability markers of internal classes): it is public in the
binary but no declaration of ours, and the noop has none of it. Both also exclude the package
`dev.koga.hopi.ui.desktop` (jvm only): the desktop viewer's entry point, `HopiPanes`, is
`@HopiInternalApi` and names core's `HopiStore`, so it is Hopi-internal rather than
consumer API, and a noop that must not depend on core could not declare it (the theme accessors
for the viewer's own chrome live there too, for the same reason). A change to the consumer API is
therefore made in both modules and both dumps together
(`./gradlew :hopi-ktor:updateKotlinAbi :hopi-ktor-noop:updateKotlinAbi`, and the same
for the UI).

The dumps hold signatures, not initializers, so they cannot see a default that changed on one
side (the noop cannot take core's constants: it copies them). `DefaultsParityTest` in
`hopi-ktor-noop`'s JVM tests closes that gap: it loads the real `hopi-ktor` and its
`hopi-core` from a resolvable configuration of their own (`realKtorJvm`, handed to the test as
a system property, never on the noop's test classpath) in a child-first classloader, and compares
every public getter of a fresh `HopiConfig`, the class's runtime-visible annotations, and
`HopiLogSink`'s entries in order.

### Selecting real vs noop in a KMP project

A KMP shared module has no build types, and iOS frameworks link the same klibs for debug and
release, so `debugImplementation`/`releaseImplementation` do not exist where the plugin is
installed. The selection happens in the consumer's shared `build.gradle.kts`:

```kotlin
val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false
commonMain.dependencies {
    implementation(if (hopiEnabled) "dev.koga.hopi:hopi-ktor:$v" else "dev.koga.hopi:hopi-ktor-noop:$v")
}
```

Debug builds pass `-Phopi.enabled=true` (Android Studio run configuration, the Xcode build
phase that calls Gradle). Release builds get the noop unless someone deliberately passes the flag.

An Android-only app module does have build types, so it can select with `debugImplementation`
(real) and `releaseImplementation` (noop) instead. An app module that consumes a KMP shared module
must not: the shared module already brings one of the two, and real and noop declare the same
classes. `sample/androidApp` therefore names neither and takes whatever `sample/shared` selected.

The sample checks the switch mechanically. `:sample:androidApp:verifyReleaseUsesNoop` reads the
class definitions of the release APK's dex files and fails unless the release runtime classpath
holds exactly `:hopi-ktor-noop` and `:hopi-ui-noop`, the APK has no
`dev.koga.hopi.core` class, and its Hopi classes (outside `dev.koga.hopi.sample`, D8's
`$$ExternalSynthetic…` classes counted as their owner's) are exactly those the noop twins' jars
hold. `:sample:androidApp:verifyHopiSelection` makes the same comparison for whatever the
property selected, so with `-Phopi.enabled=true` it requires `:hopi-core`,
`:hopi-ktor` and `:hopi-ui` and all of their classes (it is part of the app's `check`).
Measured on this change: 12 Hopi classes and 0 core classes without the flag, 345 and 97 with
it (the noop launcher's layout policy added two classes; earlier counts were 10 and 327). On iOS, a framework linked without the flag has no
`kfun:dev.koga.hopi.core` symbol; the Debug app the Xcode project builds has 503.

## Data model (`hopi-core`)

```kotlin
class HttpTransaction(            // immutable snapshot; the store replaces, never mutates
    val id: Long,                 // monotonic, process-wide, starts at 1
    val startedAtEpochMs: Long,
    val method: String,
    val url: String,              // full URL as sent, before redaction
    val requestHeaders: List<Pair<String, String>>,   // ordered, duplicates kept
    val requestBody: CapturedBody?,
    val state: TransactionState,  // Pending | Complete | Failed
    val responseCode: Int?,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: CapturedBody?,
    val durationMs: Long?,        // request start to response body fully read (or failure)
    val error: String?,           // exception class + message on failure
    val protocol: String?,        // e.g. "HTTP/2.0"
)

class CapturedBody(
    val bytes: ByteArray,         // at most maxBodyBytes
    val totalBytes: Long?,        // total size if known (Content-Length or counted), else null
    val truncated: Boolean,
    val contentType: String?,
    val skippedReason: String?,   // e.g. "binary content-type", "streaming", "disabled" — bytes empty
)
```

`CapturedBody.NOT_FETCHED` (`"not fetched"`, built with `CapturedBody.notFetched(totalBytes)`) is a
skipped reason the app never sets: a host viewer stores a bridge summary with both bodies in that
state (size known, bytes still on the app's side), so its UI says "Loading body…" and
"Loading headers…" rather than "No body", and copy cURL waits for the detail. `notFetched` also
keeps what the app holds of the body (`originalCapturedBytes`, read as `heldBytes`: less than the
size for a truncated or skipped body), which is what a `get` would move. `CapturedBody.GONE`
(`"gone from the app"`, `CapturedBody.gone`) is the other viewer-only reason: the `get` was
answered `NOT_FOUND` (the app evicted or cleared it), so the detail says the body and headers are
gone instead of loading forever, and the row is not asked for again.

Body text decoding and pretty-printing are functions over `CapturedBody`, computed on demand.

### Store

`HopiStore`: ring buffer of `HttpTransaction`, bounded by `maxEntries` and `maxTotalBytes`
(oldest evicted first, except the entry just written, which is evicted last: a long download that
started first and completes large is not evicted by its own completion while newer, smaller
entries stay). Writes come from a single consumer coroutine on `Dispatchers.Default` fed by
two bounded channels, so producers never suspend: pending snapshots go through one of capacity ~256
with `BufferOverflow.DROP_OLDEST` (losing one is harmless, the terminal snapshot carries
everything); terminal snapshots go through their own of capacity 4096 that never drops silently.
A terminal write that does not fit is rejected (`submit` returns false) and counted in
`droppedTerminalWrites: StateFlow<Long>`; the summary log sink turns every increase into a line
`{"hopi":1,"notice":"…","dropped":N}` (N is the running total), so an agent knows
transactions are missing. A pending snapshot that arrives after its transaction's terminal one (the
two channels can reorder them) is ignored. An entry whose bodies together exceed three quarters
of `maxTotalBytes` is stored with its larger body (the request's on a tie; then, if still needed,
the other) replaced by a skipped body with reason `"over store byte cap"`, so one entry never
evicts the whole history: at least a quarter of the store stays for older entries. The plugin's
largest body cap (half the store) still fits whole with a small other body; a POST with both
bodies at that cap keeps the response. Exposes:

- `transactions: StateFlow<List<HttpTransaction>>` for the UI (newest last),
- `events: SharedFlow<TransactionEvent>` (Started / Updated / Completed) for the bridge and log sink,
- `get(id)`, `since(id)`, `clear()`,
- `addEventListener(listener)`: called on the consumer for every event, in order and without loss,
  after the snapshot containing it is published. `events` drops the oldest for a slow collector
  without telling it, which a lossless `tail` cannot afford; a listener must return at once (the
  bridge's only does a `trySend`). With no listener registered it costs nothing.

Updates are cheap: a transaction is captured as one `Started` write and one terminal write.
`submit(transaction)` is non-suspending (`trySend`) and inserts or replaces by id; entries stay in
id order. `Completed` is emitted for both terminal states (`Complete` and `Failed`), after the
snapshot containing it is published, so a subscriber's `get(id)` finds it when the store retained
it (not when it was cleared or evicted in the same batch). `clear()` drops every
transaction whose id was allocated before the call, including a pending one whose completion
arrives later. `get` and `since` honor a clear at once, before the consumer prunes the buffer, so
a bridge `list` right after `clear` never shows cleared transactions (`transactions` follows on
the consumer's next pass). Ids come from `store.nextId()`.

Body text is a body that is valid UTF-8 (strict, no replacement characters) and contains no NUL; a
truncated body drops an incomplete trailing sequence before decoding. Everything else is binary.

## Ktor plugin (`hopi-ktor`)

Built with `createClientPlugin`. Body observation starts from Ktor's own `Logging` plugin
(`.reference/ktor-3.5.2/ktor-client-logging`): the same pipeline phases and `replaceResponse`, with
Hopi's own pass-through (request) and delegating (response) channels in place of `split`
(reasons below):

- Request bodies: `ByteArrayContent`/`TextContent` are read directly; `ReadChannelContent` and
  `WriteChannelContent` are teed (copy to both the wire and a bounded capture) through a
  pass-through `WriteChannelContent` rather than `split`. `MultiPartFormDataContent`
  and `ProtocolUpgrade` are recorded with `skippedReason`, not captured.
- Response bodies: tee the raw content with `replaceResponse`, as `Logging` does, but through a
  delegating channel of Hopi's own rather than `split` (details below), except a body whose
  `Content-Length` fits `maxBodyBytes`, which is read from the saved copy after `SaveBody`. A body
  with a `Content-Encoding` is captured as the app receives it, after any decoder.
  The capture keeps at most `maxBodyBytes` and counts the rest without holding back the app side.
  Content types that are clearly binary (`image/*`, `video/*`, `audio/*`, `application/octet-stream`,
  `application/zip`, …) and `text/event-stream` are not captured (`skippedReason`).
- Failures (exceptions in send) produce a `Failed` transaction with `error`.
- The app must receive byte-identical request and response bodies with the plugin installed.

How the plugin implements this (Ktor 3.5.2):

- One transaction per execution of the send pipeline, recorded at `HttpSendPipeline.Monitoring`
  (where `Logging` hooks in). A redirect or retry hop is therefore its own transaction. The URL and
  headers are what that phase sees, plus the content's `Content-Type`/`Content-Length` when the
  headers lack them. Headers that the engine adds itself are not seen.
- Request bodies: `ByteArrayContent` (which includes `TextContent`) keeps a copy of at most
  `maxBodyBytes`. A `ContentWrapper` is unwrapped and rebuilt with `copy()`. `ReadChannelContent` and
  `WriteChannelContent` are replaced by a pass-through `WriteChannelContent` that copies every chunk to
  the wire and the first `maxBodyBytes` into the capture, flushing the wire whenever the original has
  nothing more available (engines' channels do not auto-flush, so a live body would otherwise reach
  the network only per 1 MiB). This differs from `Logging`, which uses
  `split`. The pass-through re-reads the original on every `writeTo`, so a redirect or retry resends
  the whole body, which `Logging`'s single-use channel cannot do. It needs no second channel and no
  orphaned reader, and it runs on the engine's writer coroutine. If the transaction ends before the
  body was fully sent, the request body is recorded as skipped with reason `"incomplete"`.
- Response bodies, decided when the response arrives:
  - `HEAD`, 1xx, 204 and 304 responses: no body (`responseBody = null`).
  - Binary content types and `text/event-stream`: not teed at all, so they cost nothing. The body is
    recorded with `skippedReason` (`"binary content-type"` or `"streaming"`), and its `totalBytes` is
    the `Content-Length`. `image/svg+xml` counts as text.
  - A `Content-Encoding` other than `identity`: nothing happens before `SaveBody`, because the raw
    bytes are still encoded. In a phase inserted after `HttpReceivePipeline.State`, where Ktor's
    `ContentEncoding` has decoded and `BodyProgress` (at `After`) has not yet wrapped the response, a
    saved body is read from a fresh `rawContent` access, off the call's coroutine in the client's
    scope, because decoding costs time proportional to the body (one extra decode of a body that is
    already in memory). If two accesses return the same channel, it is teed instead. A body that was
    not saved is teed there. The raw response headers (with `Content-Encoding`) are recorded.
  - `Content-Length` ≤ `maxBodyBytes`: nothing happens before `SaveBody`. In a phase inserted after
    `HttpReceivePipeline.Before`, a saved body is read directly (it is already in memory and fits the
    cap); `BodyProgress` has not wrapped it yet, so the app's `onDownload` listener does not fire for
    Hopi's read. A body that was not saved (`prepareGet().execute {}`) is teed there.
  - Otherwise (unknown or larger length), the raw channel is teed with `replaceResponse` in a phase
    inserted before `HttpReceivePipeline.Before`, that is, before `SaveBody` reads it. Reading a saved
    body afterwards would copy all of it (`SavedHttpResponse.rawContent` builds a new buffer over the
    whole array).
  - The tee is a delegating `ByteReadChannel` (`TeedBody`) handed to the app in place of the raw
    one, with no coroutine and no second channel of its own: the app's reader (`SaveBody`,
    `BodyProgress`, `bodyAsChannel`'s copy, or the app itself) reads the source directly, so a chunk
    takes no extra coroutine hop. When the reader asks for more, what the source holds moves into
    the tee's read buffer (a segment move, no byte copy, as Ktor's `CountedByteReadChannel` does).
    While the capture is below `maxBodyBytes`, each move also copies its new bytes into the capture;
    past the cap a move only adds its size to `totalBytes`, which is always the exact count. The
    capture grows in blocks (the `Content-Length` when it is known, else 8 KB, then blocks of up to
    64 KB), so growing never copies what it kept, and at the end the blocks are joined into one
    array once: on the reader when that join is at most 64 KB (cheaper than a dispatch), else in
    one coroutine on `Dispatchers.Default`, with the transaction's duration taken at the end
    itself. **The app's reader pays at most one bounded copy of `maxBodyBytes`, never a hop per
    chunk, nothing beyond the cap**, plus at most a 64 KB join (a body whose `Content-Length` is
    right needs none: its one block is the array). Simpleperf on a debuggable build on the
    emulator, measured with the earlier doubling buffer: about 0.2 ms of CPU per request for the
    default 256 KB, at most 0.5 ms for all the moves of a 1 MB body, counting the source's own
    read-buffer handoff that the reader pays without the plugin too; acceptable even when that
    reader is the main thread. That buffer grew by doubling and trimmed at the end, all on the
    reader, so an unsized body cost the reader two to three times the cap; blocks remove both. The copy reads the tee's buffer in place
    (`UnsafeBufferOperations.iterate`), one array copy per segment from the first new byte; a peek
    source with a skip cost about three times as much there. A wait that the source can answer at
    once allocates nothing either.
  - A source failure reaches the app unchanged: the tee rethrows the source's own exception. An
    app that cancels the body before its end (leaving `execute {}`) while the body is still
    arriving cancels the source, as without the plugin, so the engine can release the connection,
    and the transaction fails with `"cancelled"`. The transaction completes once the source ended
    and the reader took in everything it delivered, or the app cancelled after the whole body had
    arrived. All of it happens on the reader's own calls (or the cancel), never by blocking,
    suspending or posting per chunk; the only post is the one off-reader join above. A failure
    looks to the reader exactly as the source's own channel shows it: the wait that a reset ends
    returns false without moving anything (as `ByteChannel.awaitContent` does when the close comes
    just before it suspends), a wait on an already failed source rethrows its close cause, and the
    read buffer of a failed source throws even while bytes remain, so `readAvailable` sees the end,
    `readRemaining` throws the close cause and `readFully` an `EOFException`, exactly as without
    the plugin, and `BodyProgress`'s copy closes its channel with the source's failure. A body nobody reads and nobody cancels stays `Pending`, and
    nothing notices a failure of its source either: inside `execute {}` that lasts until the block
    returns, when Ktor cancels the body, which settles it (`Complete` if it had arrived whole).
    Through `prepareGet().body<ByteReadChannel>()` Ktor's own copy reads the tee into its 1 MB
    buffer, so a body that fits completes even if the app never reads it; a larger one, or one
    still arriving, stays `Pending` until the app reads or cancels its channel. A cancel from another thread while a reader
    runs (Ktor's `bodyAsChannel` copy forwards the app's cancel) reads only the source's state;
    only a cancel before any read moves the arrived bytes in to capture them.
  - Saved and decoded bodies keep their own paths, which never touch the app's reader: a saved body
    whose `Content-Length` fits the cap is read once from memory on the call's coroutine, and a
    saved decoded body is read from a fresh `rawContent` off the call's coroutine.
- `durationMs` uses `TimeSource.Monotonic`. It runs from the send hook to the moment the app's reader
  takes in a teed body's end, the off-path read of a decoded body ends, or the send pipeline returns
  (all other cases; a saved body has been fully read by then).
- A failed transaction keeps the request but no response fields (`code` stays null).
  `error` is `"<ExceptionClass>: <message>"`, or `"cancelled"` for a `CancellationException`.
  A teed body that ends with an error (including an app that leaves `execute {}` before reading to
  the end) also makes the transaction `Failed`. An exception thrown after the send pipeline returned
  (`HttpCallValidator` response validation, for example) leaves it `Complete`.
- Exactly one terminal snapshot is submitted per transaction (an atomic flag), all through the
  store's non-suspending `submit`.
- `enabled = false` returns before installing any hook or touching the store.

## cURL export

`toCurl(transaction, redaction)`: `curl -X METHOD 'url'` (`curl -I 'url'` for a `HEAD` without a
body, since `-X HEAD` makes curl wait for a body), one `-H` per header in order (`-H 'Name;'` for an
empty value, which curl would otherwise not send), POSIX
single-quote escaping (`'` becomes `'\''`), `--data-raw` for text bodies, `--data-binary` with a
note for non-text, `--compressed` when the request advertised gzip/deflate/br. Truncated or skipped
request bodies and multipart requests produce a cURL with a leading `# warning:` comment line
instead of a silently wrong command. `Content-Length` and `Transfer-Encoding` request headers are
not emitted, since curl frames the body it sends; a `# note:` line says so when the captured
`Content-Length` differs from the bytes the command sends, and whenever `Transfer-Encoding` is
dropped. Redacted header values become `<redacted>`, and the URL is the redactor's (userinfo and
redacted query values masked).

The command is one line; comment lines precede it. A method that is not a plain token is quoted.

`toCurl(transaction, redaction, maxChars)` bounds the command for a clipboard or a share sheet
(the UI passes 100 000): a text body that would not fit is cut at a code point, inside a closed
quote, with a `# warning: request body cut to its first N characters (B of T bytes) to fit the
copy; this command sends the cut body` line, so the copied command is still valid shell. A body
more than 4 bytes per char of the budget is decoded only up to that, so copying the cURL of a
16 MB upload costs 0.5 ms and 0.05x the body (was 9 to 25 ms and 4 to 9x, the whole command built
to keep 100k chars). When the URL and headers alone fill the limit the body is left out, with a
warning saying so.
A binary body is written as `--data-binary '@body.bin'` preceded by
`# note: binary request body (N bytes) is not inlined; save it as body.bin before running this command`.

## Agent access

AI agents get two channels.

### 1. Realtime log stream (any device, no setup)

With `logSink = Summary`, every completed or failed transaction emits one NDJSON line, no bodies:

```json
{"hopi":1,"id":42,"method":"GET","url":"https://api.example.com/users?page=2","code":200,"ms":183,"reqBytes":0,"resBytes":5120,"state":"complete"}
```

A failed transaction has `"code":null` and adds `"error":"…"` (at most 512 chars, cut at a code
point boundary) after `state`.
Not every line is a transaction: notice lines carry `"notice"` and no `"id"`, e.g.
`{"hopi":1,"notice":"…","dropped":N}` when the store had to drop terminal writes (N is the
running total). Agents must skip lines without `id`.
Occurrences of the request URL inside `error` are replaced with the redacted URL, here and on the
bridge.

- Android: `android.util.Log.i("Hopi", line)`. Agent: `adb logcat -s Hopi:I -v raw`.
- iOS: `os_log` with subsystem `dev.koga.hopi`, category `http`, `%{public}s`, default level.
  `os_log` is a C macro Kotlin/Native cannot call, so `hopi-core` ships a cinterop shim
  (`src/nativeInterop/cinterop/oslog.def`) with an inline C function that calls it. The def file
  includes the system headers in its body and filters them out, so the generated binding (package
  `dev.koga.hopi.core.internal.oslog`) is only `hopi_os_log_line`.
  Agent: `xcrun simctl spawn booted log stream --style ndjson --predicate 'subsystem == "dev.koga.hopi"'`.
- JVM: stderr.

Lines stay within a per-platform byte budget: 3999 UTF-8 bytes on Android and the JVM (logcat
limit), 1000 on iOS. The iOS value is measured: `IosAgentBridgeTest` emits summary lines of exact
sizes through the shim on an iPhone 16 simulator (iOS 18.2, Xcode 16.2), read back with
`xcrun simctl spawn booted log show --last 5m --style ndjson --predicate 'subsystem == "dev.koga.hopi"'`.
Lines of 1000 to 1015 bytes arrive intact; every longer one (1016, 1017, 1020, 1024, 2165 bytes)
arrives as its first 1015 bytes followed by `<…>`, which is no longer JSON. The budget keeps 15
bytes of margin below that cut for other OS versions. Physical devices have not been measured. A URL and an error that do not fit share the budget,
are cut at code point boundaries, and the line gains `"urlTruncated":true` and/or
`"errorTruncated":true` as its last fields. Unpaired surrogates are written as `\u` escapes, so a
line is always valid UTF-8. The bridge's summaries have the same shape with `"type":"transaction"`
in place of `"hopi":1` and the 3999-byte budget, plus `"reqHeld"`/`"resHeld"` after `state`
when the app holds fewer bytes of that body than its size (truncated, or skipped: `0`): what a
`get` would move, so a host viewer can budget its fetches. A body-free live copy keeps the number.

### 2. The bridge + CLI (full detail)

The app runs a bridge server; the host CLI connects to it.

- **Android:** `LocalServerSocket` on the abstract name `hopi.<process name>` (the
  applicationId for the main process, see below). No TCP listener, no token.
  Each accepted connection's peer uid (`LocalSocket.peerCredentials.uid`) must be the shell uid
  (2000, i.e. `adbd` forwarding) or the app's own uid; anything else is closed before any byte.
  (`adb root` makes adbd uid 0, which is refused.) On an API 35 emulator another app cannot even
reach the socket: SELinux denies `connectto` between `untrusted_app` domains of different MLS
categories (`avc: denied { connectto } … tclass=unix_stream_socket`, the caller sees
`IOException: Permission denied`), so the uid check is the second layer, not the only one. The CLI runs
  `adb forward tcp:0 localabstract:hopi.<applicationId>` (adb binds host loopback) and connects.
  **The name is not owned.** Abstract socket names have no owner and no permissions: any app may
  bind `hopi.<applicationId>` while the app is stopped, and adbd (not an `untrusted_app`)
  reaches it like the real bridge, so `hello.app` alone authenticates nothing. The app therefore
  proves itself: each start generates a 256-bit proof (the token generator), binds, then writes the
  proof to `<cache dir>/hopi/<process name>.proof` (the framework sets `java.io.tmpdir` to the
  cache dir before `Application.onCreate`, so core still needs no `Context`; owner-only file in an
  owner-only directory, temporary file renamed into place), and `hello` carries it as `"proof"`.
  Only peers that passed the uid check see it, and they could read the file anyway. The CLI reads
  the file with `adb exec-out run-as <package> cat cache/hopi/<process name>.proof` and
  requires `hello.proof` to equal it (constant-time compare; never printed), so a squatter, which
  cannot read the app's private storage, is refused before any of its lines reach stdout, and
  `clear` never reaches it. `run-as` works for debuggable builds only, which is where the bridge
  belongs. When the bind fails because the name is taken, the app deletes its stale proof file and
  logs `agent bridge failed to start: the abstract socket @hopi.<name> is already bound
  (another process, possibly another app, holds it; …)`; the CLI then reports the bridge as
  unverifiable. A squatter can still deny the bridge (it holds the name), never impersonate it.
- **iOS simulator:** macOS caps `sun_path` at 104 bytes and simulator container paths are longer, so
  the bridge listens on `127.0.0.1` port 0 (loopback only, never `0.0.0.0`) and writes
  `{"port":N,"token":"…"}` to `<app data container>/Library/Caches/hopi/bridge.json`. The CLI
  finds it with `xcrun simctl get_app_container <udid> <bundleId> data`. Every connection must send the
  token as its first line: the 64 lowercase hex characters of `token`, nothing else. A wrong,
  missing (end of input), empty or oversized first line closes the connection before any byte is
  sent, whatever follows it. The token is 256 bits from the OS CSPRNG, never logged. `bridge.json`
  is written to a temporary file created 0600 and renamed over the old one (the directory is
  0700), and deleted when the bridge is closed or stops accepting (see Threads). Accepted sockets get `SO_NOSIGPIPE`, so a CLI that
  disconnects mid-write cannot kill the app. `hello` reports the bundle identifier.
- **Physical iOS devices:** no bridge in v1 (no sane host↔app channel without a network listener).
  The os_log stream works.
- **JVM:** same as the iOS simulator (`127.0.0.1` port 0 plus token), with `bridge.json` written to
  a directory given by the system property `hopi.bridgeDir` (default
  `<java.io.tmpdir>/hopi/<pid>`, mode 0700, file 0600, the same temporary-file-and-rename
  write). `hello` reports the system property `hopi.appId`, else `"jvm"`. The bridge that
  `agentBridge = true` starts registers a JVM shutdown hook that closes it, so `bridge.json` (and
  the per-pid default directory, once empty; a directory named by `hopi.bridgeDir` is kept)
  does not outlive a normal exit or SIGTERM; only SIGKILL leaves a stale file. It exists so the
  CLI's integration tests drive the real server in-process with no device
  (`JvmAgentBridge.start(store, directory)` returns the port and file and can be closed), and it is
  a legitimate desktop target.

**Android socket name.** Core has no `Context` and never auto-starts (no ContentProvider, no
initializer), so the name comes from the process: `Application.getProcessName()` on API 28+, else
`/proc/self/cmdline` up to the first NUL. For the main process that is the applicationId; an app
that runs Ktor in an `android:process` process gets that process name. The CLI requires `--app`.
Verified on an API 35 emulator (google_apis_playstore arm64): the shell can read `/proc/net/unix`
(`adb shell cat /proc/net/unix` lists `… 01 <inode> @hopi.dev.koga.hopi.sample`, plus one
line per connected client), so listing bridges from `@hopi.` entries is possible on that API
level; other API levels are unverified.

**Layering.** The protocol handler (`BridgeSession`) is transport-agnostic `commonMain` code over a
blocking byte connection (`BridgeConnection`: read, write, shutdown, close) and the store; its line
source (`BridgeLineReader`) enforces the 4096-char cap while framing, before anything parses the
line, and keeps at most that much of a line (the rest is discarded as it arrives), and the token
check runs before any byte is sent. Transports are thin platform layers: `LocalServerSocket`
(Android), POSIX `AF_INET` bound to `127.0.0.1` (iOS), `java.net.ServerSocket` bound to
`127.0.0.1` given as an address (JVM; not `getLoopbackAddress()`, which is `::1` under
`java.net.preferIPv6Addresses`, while `bridge.json` carries only a port).
Never `INADDR_ANY` / `0.0.0.0`. Tokens are 256 bits from `SecRandomCopyBytes` (iOS) or
`SecureRandom` (JVM), compared in constant time, never logged.

**Threads.** One thread blocks in `accept()`. Each connection gets two dedicated threads, never the
app's `Dispatchers.IO`: one reads and answers requests, one writes, joined by a bounded reply queue
(64 lines), so a client that reads slowly only slows its own replies. `shutdown` unblocks the other
thread (`Socket.close` on the JVM, `shutdownInput`/`shutdownOutput` on Android, `shutdown(2)` on
iOS); the handle is closed only after both threads left it. At most 8 connections are served at
once, authenticated or not; more are closed at once. A local process that connects and stays
silent therefore holds a slot until it disconnects (there is no timer to evict it): at worst it
denies the bridge, never the app. If the bridge cannot start (bind, directory), one notice line
`{"hopi":1,"notice":"agent bridge failed to start: …"}` goes to the platform log.
If `accept()` itself fails later (file descriptors exhausted, say) while a client is connected,
the accept thread waits for a session to end, since that frees what accept lacked, and tries
again: a wake-up per session end, never a timer or a retry loop. With no client connected
nothing would ever wake it, so the bridge stops for good: the listening socket is closed (clients
get connection refused, which reads as "not running", instead of hanging in a backlog nobody
accepts), `bridge.json` or the Android proof file is deleted, connected clients keep their
sessions, and one notice `agent bridge stopped accepting (restart the app to restore it): …` is
logged.

**Large replies.** A `get` reply is queued as the snapshot itself and encoded by the writer thread
as it sends, in slices of 32K body chars (escaped, UTF-8 encoded into one reused buffer, written,
dropped), so a 16 MB body costs the decoded text plus one slice instead of the whole line built,
copied and encoded at once (measured on the JVM for an ASCII body: 1.0x the body allocated per
`get` instead of 7.9x, a transient of 18 MB instead of 80 to 130 MB; a CJK/emoji body still
allocates 3.3x with a 55 MB transient, a base64 one 2.7x and 43 MB, since a non-ASCII char is
escaped and encoded at up to 3 bytes). The 64 queued replies of a pipelining client therefore hold
references to snapshots, never 64 encoded copies; a snapshot evicted meanwhile stays reachable
until its reply is written, so a pipelining client can hold up to 64 snapshots outside
`maxTotalBytes` (its own `get`s, which it reads back).

**Slow clients.** Each tailing client has its own bounded live queue, filled by its store event
listener with a non-suspending `trySend` and nothing else; encoding happens on the client's
writer thread. A queued event is a body-free copy of the snapshot (no body bytes, no headers, each
body's reported size and flags kept), so the summary line is the same and a client that stops
reading pins a few hundred bytes per event, not the captured bodies: before, a paused
`hopi tail | less` held up to about 2,400 completed bodies (600 MB at the default 256 KB cap)
outside `maxTotalBytes` until `SLOW_CLIENT`. The backlog/live seam compares those copies by value. The store hands a listener one consumer batch in a single synchronous burst (up to
`maxBatchSize` = 1 + 256 + 4096 events, far faster than any writer sends them), so the queue holds
one full batch plus 512 (4865 events with the default store; the channel allocates slots only as
they fill): a client that reads at full speed is never cut by a burst, only one that fell 512
events behind beyond it. When it overflows, the writer finishes the line it may be writing (being
inside a write says nothing about the socket being full) and sends
`{"type":"error","code":"SLOW_CLIENT","message":"…","since":N}` as its last line, then shuts the
connection down. Should a write park instead (a client that stopped reading, its socket full),
the store's next events cut the connection once the client has fallen behind by another full
queue, so the disconnect needs no timer; that client sees the connection end without the line.
`since` is where to tail again without losing a transaction: ids are allocated when a request
starts, so the last id received is not safe; N is one below the lowest id this client was sent as
pending and never as terminal, else the highest id it was sent (before the live stream began, the
`since` it asked for). Resuming there may repeat summaries, never skip one, except a transaction
whose pending snapshot the store itself dropped under a flood (more than 256 queued) and that had
not completed yet. A client never suspends the store's consumer or another client.

**Holes in the timeline.** `hello` includes `"dropped":N` (the store's `droppedTerminalWrites`), and
`tail` sends `{"type":"notice","dropped":N}` first when the count is already above 0 (drops made
before the client connected), then whenever it grows. The first notice uses the count read after
the listener was registered, so no drop falls between `hello` and the stream.

The bridge starts only when `agentBridge = true`, on a background thread, and costs nothing until a
client connects: the accept thread blocks in `accept()` (no polling, no timer), and events are
encoded to JSON only while at least one client is subscribed.

#### Wire protocol (NDJSON, one JSON object per line, both directions)

Client to app:

```json
{"cmd":"hello"}                       // → {"type":"hello","protocol":1,"app":"<id>","platform":"android|ios|jvm","count":N,"dropped":N[,"proof":"…"]}
{"cmd":"list","since":0,"limit":100}  // → {"type":"transaction",...summary...}* then {"type":"end"}
{"cmd":"get","id":42}                 // → {"type":"transaction",...full detail, bodies as text or base64...}
{"cmd":"tail","since":0}              // → summaries since `since`, then live events until disconnect
{"cmd":"clear"}                       // → {"type":"ok","through":N}
```

Errors: `{"type":"error","code":"NOT_FOUND|BAD_REQUEST|UNAUTHORIZED|SLOW_CLIENT","message":"…"}`;
`SLOW_CLIENT` adds `"since":N` (see Slow clients). `proof` is sent on Android only (see the
Android bullet).
`UNAUTHORIZED` is reserved and never sent: authentication failures close the connection before any
byte (token transports) or at accept (Android), so an unauthenticated peer learns nothing.

`tail` first sends a summary of every retained transaction with an id above `since`, in any state,
then one summary per store event (`Started`, `Updated`, `Completed`) for ids above `since`, until
the client disconnects. The listener is registered before the backlog is read, and a live event is
skipped when the backlog already sent that very snapshot or showed that transaction terminal, so
nothing falls between backlog and live and nothing repeats. A transaction can therefore appear as
`pending` and later as `complete`. Input after `tail` is read only to notice the disconnect. A
client that half-closes after `tail` (or goes away) ends the stream: no new events are queued,
and the writer still sends the backlog and the events already queued, then closes. (Reading
cannot tell a half-close from a close; a client that went away makes those writes fail, which
bounds the work.)

Full detail encodes bodies as `{"encoding":"utf8","text":"…"}` when decodable as UTF-8 text,
otherwise `{"encoding":"base64","data":"…"}`, plus `truncated`, `totalBytes`, `contentType`,
`skippedReason`. Redaction applies to everything sent over the bridge: header values, query
parameters and the URL's userinfo. Bodies are sent as captured, so a server that echoes a credential in its response
body shows it (the sample dev server masks credentials in its echo for that reason).

Full detail shape: `{"type":"transaction","id","startedAt","method","url","state","code","ms",
"protocol","error","request":{"headers":[["name","value"],…],"body":{…}|null},"response":{…}}`;
headers are `[name, value]` pairs so order and duplicates survive; each body also has
`capturedBytes`.

Requests are at most 4096 chars (UTF-16 units, not counting the `\r` of a `\r\n` ending); a longer one
is answered with `BAD_REQUEST` and the session goes on with the next line. Blank lines are ignored.
`since` and `limit` default to 0 and 100; `limit` is 1..10000; `get` needs `id` ≥ 1. Unknown fields
are ignored; anything else malformed is `BAD_REQUEST`. A client that half-closes after its requests
still receives every reply already queued, including a `tail` backlog.

#### CLI

```
hopi devices [--platform android|ios] [--json]  # what can be reached: adb devices, booted simulators
hopi list   --app <id> [--platform android|ios] [--device <serial|udid>] [--json] [--limit N] [--since ID]
hopi tail   --app <id> [...] [--since ID] [--json]  # live; NDJSON with --json, one line per event
hopi get    <id> --app <id> [...] [--json]    # full detail incl. bodies
hopi curl   <id> --app <id> [...]             # prints the cURL command
hopi clear  --app <id> [...] [--json]
hopi <command> --platform jvm --bridge-file <path> [--app <id>]   # a JVM bridge (tests, desktop)
```

`hopi-cli` is a Kotlin/JVM application. It depends on `hopi-core`'s JVM artifact for the
cURL export, the JSON writer (requests), `prettyPrintJson` and `formatBytes` (`hopi-connect`
uses its `parseFlatJsonObject` for `bridge.json`), and on kotlinx-serialization-json only for its
JSON tree parser (replies; no compiler plugin). It parses its own arguments (no CLI library). `./gradlew :hopi-cli:installDist`
produces a runnable `hopi-cli/build/install/hopi-cli/bin/hopi`. Everything that
reaches an app (adb and simctl, the targets below, `bridge.json`, the bridge client with its
handshake and proof check) lives in the JVM module `hopi-connect`, shared with the desktop
viewer; the CLI adds argument parsing, rendering and exit codes on top. Its whole public API is
`@HopiInternalApi`, kept in `hopi-connect/api/` by `updateKotlinAbi`.

For the desktop viewer, `hopi-connect` also holds `BridgeDecoder` (summary and `get` lines
back into `HttpTransaction`s: the inverse of core's `TransactionJson`, round-trip tested) and
`RemoteSession`. A `tail` reads nothing after it, so a session opens two connections on one
endpoint (one adb forward): the tail streams summaries into a `HopiStore` of the viewer's own
(2000 entries, 256 MB, keyed by the app's ids, with the store's default redaction on top of the
app's), the other serves `get` and `clear`. A body crosses the bridge only for the selected
transaction (`select`, fetched again when a pending selection completes) or an explicit `fetch`; a
later summary of a fetched snapshot (a re-tail replays the backlog) never replaces its bodies. A
`SLOW_CLIENT` reply (`SlowClientException.since`) re-tails at once from that `since` on the same
endpoint. Anything else that ends a connection closes both and the endpoint and reports
`Disconnected`; the caller connects again on an event. A connect that finds another process (a
SHA-256 of the token and the Android proof, both new with every app start, compared in memory and
never shown) starts a fresh store, because the app's ids restart at 1, and drops the selection,
whose id now means another transaction. A `get` answered `NOT_FOUND` marks the row's bodies
`CapturedBody.gone`; a `get` answered pending after the tail brought the terminal summary is sent
once more. `clear` clears this store up to the reply's `through` (all of it for an app that sends
none). Nothing polls: one tail thread blocked on its socket, one request thread blocked on its
queue.

**Targets.** `--platform` is inferred when only one of adb/simctl has a target: a `--device` names
its platform; otherwise the platform with a ready target (adb state `device`, or a booted simulator)
wins, both is a usage error, neither is "not reachable". Within a platform, exactly one ready
target is used, or `--device`; several without `--device` is a usage error that lists them, and a
named device that is `unauthorized` or `offline` is "not reachable". Adb is found at
`$ANDROID_HOME/platform-tools/adb`, `~/Library/Android/sdk/platform-tools/adb`, or `PATH`, and run
by that absolute path; simctl only as `/usr/bin/xcrun simctl`, never looked up on `PATH`.
Subprocesses are spawned with argument arrays, never through a shell, and `--app` and `--device`
must match `[A-Za-z0-9_][A-Za-z0-9._:-]*` before they reach one (no value can read as an option or
a path).

- **Android:** `adb -s <serial> forward tcp:0 localabstract:hopi.<app>`; the port is the digits
  adb prints. The CLI always removes that forward (`adb -s <serial> forward --remove tcp:<port>`)
  when the command ends, and a JVM shutdown hook, registered before the forward is created, removes
  it on SIGINT during `tail` (removal runs once either way). The CLI does not list bridges from
  `/proc/net/unix`: `--app` is required (the desktop viewer's picker does, see Desktop viewer). adb drops every forward of a device whose transport resets
  (an emulator's adb connection did twice in one session; `adb reconnect` does it on demand), which
  ends the connection while the app keeps running. When an established connection ends, the CLI
  runs `adb -s <serial> forward --list`: if its port is gone it says "adb lost its connection to
  <serial> and dropped the forward; the app may still be running: run the command again" instead of
  "the app closed the bridge connection (app stopped?)". Both exit 2.
  Once `hello` arrived (so an app that is not running still reads as not running), the CLI runs
  `adb -s <serial> exec-out run-as <package> cat cache/hopi/<app>.proof`, where `<package>` is
  `--app` up to the first `:`, and refuses a `hello` whose `proof` differs or is missing: "…is not
  <app>'s bridge (its hello lacks the proof <app> wrote): another app may hold the socket name; if
  <app> just restarted, run the command again". If `run-as` cannot read the file (not debuggable,
  no bridge started), it says "cannot verify the bridge of <app> …". Both exit 2. adbd passes these
  arguments to the device's shell, so only validated values reach it (`--app` matches the pattern
  above; the path is fixed around it). Removing the forward is best effort and never fails a
  command or escapes the shutdown hook.
- **iOS simulator:** `xcrun simctl list devices booted -j`, then
  `xcrun simctl get_app_container <udid> <bundleId> data`, then
  `<container>/Library/Caches/hopi/bridge.json`.
- **JVM:** `--bridge-file <path>` (implies `--platform jvm`); `--app` is optional and, when given,
  checked like on the other platforms.

The CLI connects to `127.0.0.1` given as an address (not `localhost`, which may be `::1`) and never
prints the token: it is the first line it sends, and no error message carries it.

**Handshake.** Every command first sends `hello`, in one write together with the token (token
transports) and its request, so a read-only command costs one round trip; `clear` is sent only after
`hello` was checked, so a mismatch can never clear another app. The CLI requires `"protocol":1`, the
expected `platform`, `app` equal to `--app` and, on Android, the proof (see Targets). A connection that closes before the `hello` reply
means nothing serves the app, which is "not reachable" with "app not running or agentBridge =
false": adb accepts the forwarded host connection even when nothing listens in the app, and a bridge
closes a wrong token (a stale `bridge.json`) without a byte, so both read the same.

**Commands.** `list` prints the newest `--limit` transactions (default 100, 1..10000) with an id
above `--since` (default 0), oldest first; it pages through the bridge's `list` (which returns the
oldest first) with `limit` 10000. `tail` sends `tail` with `--since` and runs until interrupted
(SIGINT; the JVM exits 130) or until the app closes the connection (exit 2). `get` and `curl` read
the `get` detail; `curl` rebuilds the request from it as a `HttpTransaction` (text bodies re-encoded
as UTF-8, base64 bodies decoded, truncation and skip reasons kept) and prints core's `toCurl` with
redaction disabled, because the bridge already redacted it with the app's redactor and redacting
again would hide what the app chose to show.

**`--json` output** is NDJSON and stable: it is the contract for AI agents.

- `list`, `tail`, `get`: the bridge's own lines, verbatim (wire protocol above): `list` prints one
  `{"type":"transaction",…summary…}` per transaction and no `end` line, preceded by
  `{"type":"notice","dropped":N}` when `hello` reported N > 0 drops (those transactions stay
  `pending`); `tail` prints summaries and `{"type":"notice","dropped":N}` lines (the first one at
  once when drops happened before it connected), flushed one by one; `get` prints the one detail
  line. Agents skip types they do not know.
- `clear`: `{"type":"ok","through":N}`, N being the last id the clear covers (a host viewer clears
  its own copy up to exactly that id, keeping what its tail brought from after the clear).
- `devices`: one line per target,
  `{"type":"device","platform":"android"|"ios","id":"…","state":"…","name":"…"|null,"os":"…"|null}`;
  Android's `state` is adb's word (`device` = ready, `unauthorized`, `offline`, …) and `os` is null;
  a booted simulator has `"state":"booted"` and `os` like `"iOS 18.2"`. No target prints nothing.
- `curl` takes no `--json` (its output is one shell command, with any `# warning:`/`# note:` lines first).
  Control characters in the request are shown as `\uXXXX`, after a line
  `# warning: the request contains control characters, shown as \uXXXX escapes; this command does not send them as captured`.
- Errors never go to stdout.

Human output: `list` and `tail` print aligned columns (`ID METHOD CODE TIME SIZE URL`; `...` is
pending, `ERR` failed with the error after the URL); `tail` pads to fixed widths since it cannot
measure rows ahead. `get` prints an overview block, then headers and bodies, JSON bodies
pretty-printed with `prettyPrintJson`; a binary body is only described (`--json` has its base64).
`list` ends with `! the app's store has dropped N transactions …` when `hello` reported drops.
Every value that comes from the bridge (URL, method, error, header names and values, body text,
content type, skip reason, and peer strings quoted in error messages) is printed with C0 controls
(except `\n` and `\t` in bodies), DEL and C1 controls replaced by `\uXXXX`, so a server cannot
move the cursor, rewrite lines or send OSC sequences (clipboard writes) to the developer's
terminal. `--json` needs nothing: JSON escapes them.

Exit codes: 0 ok, 1 usage (including several targets without `--device` and a bridge
`BAD_REQUEST`), 2 not reachable (no device, app not installed, app not running, bridge off, a
`SLOW_CLIENT` disconnect, which says `run hopi tail --since N to resume without losing a transaction`, the app closing the connection, adb dropping the forward, a bridge that fails the Android proof), 3 not found (`get`/`curl` of an id the
store does not hold). Every error is one actionable line on stderr, `hopi: <message>`.
stdout is a buffered writer over the raw file descriptor (not `System.out`, which hides write
errors), flushed once at the end, and after every line in `tail`; when stdout is closed (`hopi
tail | head -1`) the CLI exits 0 without a message.

## Desktop viewer (`hopi-desktop`)

A Compose Desktop application (`org.jetbrains.compose` `application`, `compose.desktop.currentOs`
with Material excluded: foundation only) that shows one app's traffic on the developer's screen.
It is a host tool like the CLI and never a dependency of an app. It talks to the app over the
bridge protocol unchanged, through `hopi-connect`: the app needs nothing new.

**Pieces** (package `dev.koga.hopi.desktop`, all `internal`):

- `Viewer`, the connection logic without UI. It lists targets (`discover`): adb devices with, for
  each ready one, the bridges listening there (`Targets.androidBridges`: the `@hopi.<name>`
  entries of `adb exec-out cat /proc/net/unix`, a fixed command; names must pass
  `TargetIds.APP`), booted simulators, and JVM bridges (`<java.io.tmpdir>/hopi/*/bridge.json`).
  It also holds the one `Target` being viewed (a device plus an app id; optional for a JVM bridge)
  and its `RemoteSession`. Every state change runs on one thread (`hopi-desktop-connect`), so
  callers never block and two connects never overlap.
- `ViewerContent`, the window content: a target bar (device chips, an app id field checked with
  `TargetIds.APP` before anything reaches a subprocess, the device's bridges as suggestions,
  Connect, Disconnect, Refresh), `HopiPanes` over the session's own store, and a status bar
  (connected, connecting, reconnecting, app stopped, device offline, the error; the request and
  pending counts; the app's dropped count from `notice` lines; the last export's outcome).
- `SessionExport` writes HAR 1.2 and NDJSON. `Main` opens the window with a native menu bar.

**Data.** A `RemoteSession` per target: its own `HopiStore` (2000 entries, 256 MB), keyed by
the app's ids, with `Redactor.Default` on top of the app's redaction. The panes select through
`Viewer.select`, so only the selected transaction's body is fetched (`get`); list rows are
summaries and show "Loading body…" until then. A new app process (another token or proof) gets
a fresh store and the selection is cleared, since the ids restart.

**Connections.** Two per app, as `RemoteSession` opens them (a streaming `tail`, and one for `get`
and `clear`) on one endpoint, so one adb forward on Android. Every attempt is a
`RemoteSession.connect`, which closes the previous sockets and forward first, so the app never
serves more than these two of its 8 slots. Disconnect, a new target and closing the window close
both sockets, remove the forward and stop the target's watcher. A JVM shutdown hook does the same
on SIGTERM or SIGINT, so neither a forward nor an adb tracking subprocess outlives the viewer.
Closing closes the session, the trackers and the watches on the closing thread, not on the
connect worker: a connect blocked on an app that does not answer (stopped at a breakpoint) can hold
the worker up to its read timeout.

**Reconnecting is event-driven.** Nothing is scheduled while no event happened:

- `adb track-devices -l` (`Targets.trackAndroidDevices`, with each device's model like `adb devices -l`, a streaming subprocess read by one thread
  blocked on it; length-prefixed frames, `0000` for none) updates the picker. When the target's
  device is listed ready again (after an adb reset, a reconnected phone), it starts a reconnect. A
  device listed offline or gone shows "Device offline". When the adb server stops, the stream ends
  and is started once more, which starts a new server; it is not restarted again until it has
  delivered a frame.
- `adb -s <serial> track-jdwp` (`Targets.trackDebuggableProcesses`) on the target's device. A
  change in its debuggable processes means the app (or another debuggable app) started or ended.
  This is the Android app-restart event: there is no `bridge.json` on Android, and `track-devices`
  does not change when an app restarts.
- A watch on the bridge file's directory (`PathWatcher`, the JDK `WatchService`) for the iOS
  simulator (`<container>/Library/Caches/hopi/`, resolved once with `simctl
  get_app_container`) and the JVM (the bridge file's directory). A restarted app writes a new
  `bridge.json` there. The nearest existing ancestor is watched until the directory exists, and a
  watch on `<java.io.tmpdir>/hopi/` keeps the picker's JVM processes current. On macOS the
  JDK 17 watch service is the JDK's polling implementation (each watched directory is listed every
  2 s at `SensitivityWatchEventModifier.HIGH`): host-side work only, the app is never asked. So
  each watch runs only while there is something to wait for: the bridge file's while the viewer
  waits for the app (it starts with one look, in case the file was written just before), the JVM
  root's while the window has focus and nothing is connected (a look when it starts refreshes the
  picker). Connected, or with the window in the background, no watch runs.
- The session's own disconnect while connected. One attempt follows at once, which is all an adb
  forward dropped under a running app needs.

The JDWP event comes before the app binds its bridge: the process registers with adbd when it is
forked, and the bridge binds when the Ktor client is created. So on Android an event (a process
change, the device ready again, a lost link) is followed by at most 4 more attempts, 0.3, 1, 2.5
and 5.5 s after the first (`Viewer.ANDROID_SETTLE_DELAYS_MS`; empty turns them off). They are
cancelled by success and by any newer event or command. An attempt against an unbound socket is
answered by adbd (it closes the forwarded connection) and never reaches the app. A change for
another debuggable app on the device triggers the same bounded attempts.

A failed attempt is classified from `Targets`' own messages: "app not running", "process not
running" and a missing bridge file mean the app is stopped. A device adb does not list as ready
means the device is offline. Anything else (a proof mismatch, an app not installed) is shown as
the error, and ⌘R or Reconnect tries again.

**Keyboard.** One `onPreviewKeyEvent` on the content root handles every shortcut, so they work
from the search field too: ↓/↑ (`HopiPanesState.selectNext`/`selectPrevious`), Enter (the
first row when none is selected; left to the app id field when it has focus), Esc (clears the
selection, focus back to the root), ⌘F (search), ⌘K (`clear`, the app's store first), ⌘E and ⇧⌘E
(export), ⌘R (reconnect). Ctrl replaces ⌘ outside macOS. The menu bar lists the same commands
without binding the keys a second time.

**Export.** `SessionExport` streams one transaction at a time, giant bodies in 32K-char slices
(`JsonWriter.chunkedValue`). Each transaction known only by its summary is first completed with
`get` (`RemoteSession.fetch`, on the export thread) while it fits the budget: 32 MB per
transaction and 256 MB per export, counted in the bytes `get` would move (the summary's
`reqHeld`/`resHeld`), so a 50 MB download the app truncated to 256 KB costs 256 KB. Beyond that it
is written with its sizes and a comment, so an export never pulls every giant body over the bridge
at once. A row gone from the app is written without bodies, and its comment says so.

- **HAR 1.2:** `log.version` `"1.2"`, `creator` (`Hopi`, the app's jar version), one entry
  per transaction in store order. Each entry has `startedDateTime` (ISO 8601, UTC, milliseconds)
  and `time` (the duration). Its request has the method, the URL, `httpVersion` (the protocol),
  the headers and the percent-decoded `queryString` as name/value pairs, `postData` (`mimeType`,
  `text`; binary as base64 with `_encoding`), `headersSize` -1 and `bodySize`. Its response has
  `status` (0 when pending or failed), `content` (`size`, `mimeType`, `text`, `encoding` `"base64"`
  for binary), `redirectURL` from `Location`, and `_error` for a failure. It also has an empty
  `cache` and `timings` with only `wait` measured (send and receive 0, so they add up to `time`).
  `comment` says pending, failed, truncated, not captured or not exported, and `_hopiId`
  carries the app's id. Redaction is the store's `Redactor.Default`, as for copy as cURL.
- **NDJSON:** one bridge `get` line per transaction (`TransactionJson.writeBridgeDetail`), exactly
  what `hopi get --json` prints, which `BridgeDecoder.detail` reads back.

**Packaging.** `./gradlew :hopi-desktop:packageDmg` builds an unsigned
`Hopi-1.0.0.dmg` (64.8 MB, with a jlinked runtime that adds `jdk.unsupported` for the watch
modifier; the app image is 118 MB). `:hopi-desktop:run` runs it from the repository.

**Tests** (`:hopi-desktop:test`, no device). `SessionExportTest` checks every export against
a HAR 1.2 structure validator: required fields and types, name/value pairs, ISO dates, timings
that add up, no field outside the spec unless it starts with `_`. It also covers the budget and
the NDJSON round trip. `ViewerTest` covers a JVM app that stops and starts again under an
unchanged `bridge.json` path (reconnected by the watch alone, into a fresh store), and Android
events against scripted adb. Those events are a `track-jdwp` change followed by exactly the
bounded attempts, a device offline then ready again, and every adb forward removed. The same
suite measures the app's bridge threads at 0 ns CPU over 3 s while the viewer is connected and
idle, with exactly two connections. `ViewerUiTest` composes the window content against an
in-process `JvmAgentBridge`. It connects, streams, selects from the keyboard (only the selected
body is fetched), exports with ⌘E, clears with Esc and ⌘K, and follows the app stopping and
starting again in the status bar. It writes screenshots to `build/reports/screenshots/`.

`DeviceViewerUiTest` runs the same window content against a real target, only with
`-Phopi.desktop.device=android` (one emulator, the sample's real debug build installed, the
dev server up) or `=ios` (one booted simulator, the sample's Debug build installed); without the
property it does nothing. It drives the sample with adb and `benchmark/device/scripts/ui.py` (or
`simctl`), and writes one JSON line per check and the window's content to
`build/evidence/desktop/`. On Android it checks live rows, a 5 MB body fetched only when selected,
copy as cURL (redacted), both exports, 0 CPU ticks for Hopi's threads over 60 s idle, the
app-side connection count (connected `@hopi.<app>` sockets in `/proc/net/unix`: 2, never
more), force-stop and relaunch, an adb server reset, and 500 `POST /items` connected against
disconnected. On iOS: the connection through `bridge.json`, terminate and relaunch, live rows and
a body fetched on selection.

## Sample app (`sample/`)

A Compose Multiplatform app for Android and iOS that makes real requests to a dev server on the
host, so the inspector, the log stream and the CLI can be exercised (and measured) end to end.

- **`sample/shared`** (KMP: `android`, `iosArm64`, `iosSimulatorArm64`; foundation only, no
  Material) holds everything both apps share. It selects `hopi-ktor`/`hopi-ui` or their
  noop twins with the `hopi.enabled` property exactly as in "Selecting real vs noop", and
  generates `HOPI_ENABLED` from the same property, so the app says which one it was built
  with. One process-wide Ktor client (`SampleHttp`; OkHttp on Android, Darwin on iOS,
  `ContentNegotiation` with kotlinx-serialization JSON) installs Hopi with
  `logSink = Summary` and `agentBridge = true`. The UI, wrapped in `HopiLauncher`, is a small
  shop with three tabs: **Items** (`GET /items`, a lazy list keyed by id, then `GET /items/{id}`
  for the detail), **New item** (a validated form that POSTs JSON to `/items` with an
  `Authorization` header and opens the created item), and **Diagnostics**, whose "Fire requests"
  sends, one after the other, a GET returning JSON, a POST with a JSON body and an `Authorization`
  header, a 404, a GET to an unresolvable `.invalid` host, a response larger than 256 KB and a
  chunked response. Two more buttons serve the device checks: "Slow request (60 s)" (`GET /slow`,
  a transaction that stays pending for a minute) and "Create 500 items" (500 `POST /items`, 8 at a
  time), which fills the store to its default 500 entries and gives the app's own item list as
  many rows, so scrolling the inspector and scrolling the app can be compared. "Giant JSON
  (1, 5, 20 MB)" and "Giant JSON (8 MB)" fetch `/giant` and parse each body into a `JsonElement`
  with `body<JsonElement>()` on `Dispatchers.Default`, as an app would, logging one JSON line per
  run (fetch and parse times, on Android ART's GC counters and heap, and the main thread's frame
  gaps, which a frame ticker drawn on every vsync during the run exposes). A device run passes a
  spec instead (`GiantSpec`: `mb=1,5,20;shape=minified|pretty|string|nested;chunked=0|1;runs=5;
  hold=S;post=0|1;delay=S`): `--es giant <spec>` on Android, the launch argument `-giant <spec>`
  on iOS; `--el maxBodyBytes N` / `-maxBodyBytes N` sets `SampleHttp.maxBodyBytes`, the plugin's
  cap, before the client exists (force-stop the app first). The log goes to the `HopiSample`
  tag on Android and stdout on iOS (`simctl launch --console-pty`). The back gesture (ui-backhandler `BackHandler`) returns to the item list. iOS
  gets `MainViewController()`, a `ComposeUIViewController`; the static framework is `Shared`.
- **`sample/androidApp`** (`applicationId` `dev.koga.hopi.sample`) hosts the shared UI in one
  activity and depends on `sample/shared` only. It adds what only Android has, as a Diagnostics
  button and two intent extras. "Bridge self-check" connects to the app's own bridge as the app's
  uid and logs the `hello` reply under the `HopiSample` tag, its `proof` value replaced by
  `<redacted>`; `am start … --es selfCheck <process name>` runs it at launch (opening Diagnostics)
  against another app's bridge, and a second install built with
  `-Psample.applicationIdSuffix=.probe` (another uid) uses that to prove the bridge cannot be
  reached from another app. `am start … --es squat <process name>` makes that second install bind
  the other app's socket name first and answer with a forged `hello` (right app id, no proof) plus
  one forged transaction, to prove the CLI refuses a squatter. **Both need the real bridge, so the
  APKs for these checks are built with `-Phopi.enabled=true`**
  (`./gradlew :sample:androidApp:installDebug -Phopi.enabled=true`); a debug build without
  the flag packages the noop like a release build does. `-Psample.largeHeap=true` sets
  `android:largeHeap` (512 MB instead of 192 MB on the API 35 emulator), so the giant-body runs
  can see past the app's own heap limit.
- **`sample/iosApp`**: a SwiftUI app (bundle id `dev.koga.hopi.sample`, iOS 16+) whose only
  view hosts `MainViewController()`. `project.yml` describes it and the checked-in
  `iosApp.xcodeproj` is generated from it with XcodeGen (2.46.0); no CocoaPods, no SwiftPM. A
  pre-build phase runs `./gradlew :sample:shared:embedAndSignAppleFrameworkForXcode`, with
  `-Phopi.enabled=true` for the Debug configuration only, so Release links the noop twins.
  Script sandboxing is off for that phase (Gradle writes to `sample/shared/build`). ATS: the Debug
  configuration's `Info-Debug.plist` allows insecure HTTP loads to `127.0.0.1` and `localhost`
  only (`NSExceptionDomains`; no `NSAllowsArbitraryLoads`, no `NSAllowsLocalNetworking`);
  `Info-Release.plist` has no ATS exception. Run on an iPhone 16 simulator (iOS 18.2), both
  configurations reach `http://127.0.0.1:8765`: ATS does not apply to IP address literals, so the
  `127.0.0.1` exception is not what lets the requests through (the `localhost` one is the exception
  that matters, and the sample does not use that name). The simulator build excludes `x86_64`
  (`EXCLUDED_ARCHS[sdk=iphonesimulator*]`), since the framework has only an `iosSimulatorArm64`
  slice and a Release build otherwise builds, and fails on, every architecture.

`sample/devserver/devserver.py` (Python 3 standard library, bound to `127.0.0.1:8765`) serves those
routes (`/slow?s=N` sends a chunked body over N seconds, one chunk every 5 s so no client read
timeout fires; `/items` keeps created items in memory until it exits and answers 400 to a body that is not
an item; `/giant?mb=N&shape=S&chunked=0|1` generates about N MiB of JSON while sending it, with
a Content-Length computed in advance or chunked; `POST /sink` reads and drops a body of any size)
and logs every request as one JSON line on stdout. The Android emulator reaches it with
`adb reverse tcp:8765 tcp:8765`; the iOS simulator shares the host's loopback, so it needs nothing.
The Android debug build's network security config allows cleartext to `127.0.0.1` only. Nothing
reaches an external network.

## UI (`hopi-ui`)

Compose Multiplatform, no Material dependency (foundation only), monochrome, dense, fast:

- **List:** newest first; method, status code, host + path, duration, size; Pending/Failed visible at
  a glance. Search by URL substring, filter by status class (2xx, 3xx, 4xx, 5xx, failed, pending,
  each chip with its count). Lazy list keyed by id. `transactions` is collected only while
  `HopiScreen` is composed (a `produceState`, not `collectAsState`, so that the mapping runs
  off the main thread): each emission goes through a row cache on `Dispatchers.Default` that keeps
  one precomputed row (every string the row shows) per id and rebuilds it only when that id maps
  to a different snapshot instance (the store replaces, never mutates; `HttpTransaction.equals`
  compares body bytes, so identity is the cheap and exact test), then through the search and
  filter. Composing a row formats nothing. URLs shown, searched, copied and shared are the store
  `redactor`'s, which masks the userinfo (`https://<redacted>@host/…`), so a password in a URL is
  never shown, found by search or copied; only `unsafeDisableRedaction()` on every installation
  shows it. The row's host line drops the userinfo in any case.
- **Detail:** Overview (URL, method, code, duration, sizes, protocol), Request (headers, body),
  Response (headers, body). Headers redacted by the store's `redactor`; a header value or the URL
  longer than 4096 chars is cut for display with "… N more chars" (copy keeps it whole). Body
  views (strict UTF-8 decode, JSON pretty-printing, a hex preview of the first 512 bytes for
  binary bodies) are computed on `Dispatchers.Default` and cached by body instance: at most 16
  bodies and 4M characters of formatted text, least recently used first; the view just formatted
  is kept even when it alone is larger. Each view is cut into pieces of at most 64 lines and 4096
  chars, so a large body is many lazy items and never one text layout. JSON is pretty-printed
  straight into those pieces (core's `prettyPrintJsonTo` into a `PieceWriter`, which takes the
  output in 32K-char batches and cuts each with `splitText`'s own pass), so no whole pretty string,
  nor a builder grown to hold it (2 bytes per char on ART and Kotlin/Native), is built next to the
  text. A truncated body is printed only when it ends a container (`}` or `]`), else it is split
  raw without a wasted attempt. The pieces are the only copy of the text a
  view keeps (with one flag per piece for the newline it dropped); copy rebuilds the text from
  them off the main thread. The cache lives only while the screen is composed. A detail is
  formatted again when its transaction's snapshot or the store's `redactor` changes (the redactor
  grows when another client installs the plugin; it is read on each store emission, so the open
  detail follows it at the next request). Truncation and skip reasons visible. Text is selectable
  (within the pieces on screen).
- **Actions:** copy cURL (core's `toCurl` with the store's `redactor`), copy URL, copy response
  body, share, clear. Copy uses Compose's text clipboard. A clip crosses the same binder limit as
  an intent (past about 1 MB `setPrimaryClip` throws in the app), so copied text is cut at
  100 000 chars too, at a code point boundary, and the confirmation says so ("Response body
  copied (first 100k characters)"); a clipboard that still throws shows "… could not be copied"
  instead of crashing the app. The confirmation is a polite live region, so TalkBack and VoiceOver
  announce it. Share is the platform sheet (`Intent.ACTION_SEND` on Android,
  `UIActivityViewController` on iOS) with the method, URL, status, duration and cURL, cut at
  100 000 chars (the Android binder limit); the JVM has no share sheet, so the action is absent
  there.
- **Desktop panes:** `HopiPanes(store, onSelected, onClear, …)` in the jvm-only package
  `dev.koga.hopi.ui.desktop` (`@HopiInternalApi`) reuses the list and detail panes, the
  row cache, the detail formatter and the actions over any store: side by side from 720 dp wide
  (the selected row marked), one at a time below. It has no close button, back handler or system
  bar handling (the window owns those), reports every selection so the caller fetches its body,
  and leaves clearing to the caller (the app's store too). `HopiPanesState` carries the
  selection and the keyboard moves over the rows as shown. `HopiScreen` composes the same
  list, detail and actions helpers and is otherwise unchanged. `HopiDesktopTheme`,
  `hopiDesktopColors` and `HopiDesktopType` (same package, same exclusion from the
  parity surface) give the desktop window's own chrome the monochrome palette and text styles.
- **Launcher:** a small draggable floating button over the app content opening the screen full
  screen. It never touches the store (resolving `HopiStore.default` only when the screen
  opens), recomposes only when tapped (dragging moves it in the layout phase), keeps its position
  with `rememberSaveable` as fractions of the safe area and settles on the nearer side edge. The
  content is laid out as by the noop (see Consumer API): its constraints pass through unchanged,
  and the button and the screen cover exactly the content's area, which is the window when the
  app's root fills it (wrap the app's root, as the sample does). While the screen is open the
  app's content is hidden from accessibility services, cannot hold or take focus (opening clears
  focus, so a focused field of the app drops the keyboard and stops receiving keys; a focus group
  cancels any focus entering it), and, once the enter transition has ended, is not drawn: the
  launcher skips drawing it (`drawWithContent`, reading the transition state in the draw phase
  only), so the opaque screen is not drawn over a full-window layer of the app on every frame. The
  app stays composed, so its state survives, and draws again as soon as the screen starts to
  leave.
- **Back:** the system back gesture (ui-backhandler `BackHandler`) leaves a detail, then calls
  `onClose`.
- **System bars:** the screen covers the host window's bar areas, so while it is composed it sets
  the bars' icons dark over its light background and light over its dark one, on Android
  (`WindowInsetsController.setSystemBarsAppearance` on API 30+, the `SYSTEM_UI_FLAG_LIGHT_*` flags
  below; no androidx.core), and restores the host's appearance when it leaves. API 30 reads an
  appearance of 0 until the app itself called `setSystemBarsAppearance`, even when its theme
  made the bars light, so on API 30 the screen first calls `setSystemBarsAppearance(0, 0)`, which
  makes the controller own the appearance the window derived from the theme and legacy flags, and
  reads it then (androidx's `WindowInsetsControllerCompat` does the same). Not run on an API 30
  device yet. Without it, a host
  with light icons (any app not calling `enableEdgeToEdge`, the sample before this change) showed
  white icons on the light inspector. iOS asks the view controller for the status bar style, which
  follows the system appearance there, so iOS does nothing.
- **Design:** foundation only, no Material; monochrome greys, light or dark following the system;
  the platform's default and monospace fonts; touch targets of at least 48 dp; content
  descriptions on icon-only controls and on the status badges; containers that hold text have
  minimum sizes, never fixed ones, so a large font scale (200% on Android, accessibility Dynamic
  Type on iOS) grows them instead of clipping their text, and a status badge widens rather than
  wrapping its code; a binary body's hex dump scrolls sideways; safe-drawing insets respected;
  short transitions only on open, close and list/detail navigation.

Opening the screen before any client installed the plugin creates the default store with default
caps (`HopiStore.default` creates it on first access); a later installation's caps then do not
apply. Install the plugin when the app starts.

## Performance budget

Measured with a JVM benchmark against `ktor-client-mock`, plugin installed vs not:

- Added latency per request on the caller path: < 50 µs median for a 1 KB JSON response.
- No added allocation proportional to body size beyond `maxBodyBytes`.
- The app's reader pays at most one bounded copy of `maxBodyBytes`, never a hop per chunk, nothing
  beyond the cap, plus at most a 64 KB join at the end (`TeedBodyTest`: reading 8 MB past the cap
  allocates the same bytes on the reader as without the plugin, and the capture copies nothing
  more; from the first byte to the end of a chunked body, at 64 KB and 8 MB caps, the reader
  allocates less than the kept bytes plus 128 KB over the run without the plugin).
- A 10 MB response is received intact by the app while at most `maxBodyBytes` is retained.
- With `enabled = false` the plugin installs no hooks at all.

### Giant bodies

Opt-in JVM benchmarks (`./gradlew :hopi-ktor:jvmGiantBodyBenchmark`, and the same task in
`:hopi-core` and `:hopi-ui`; `:hopi-cli:testGiantBodyBenchmark`), excluded from the
normal test tasks, generate six JSON shapes in memory (minified and pretty records, nesting 128K+
deep, one huge string, CJK/emoji with byte 262144 inside a sequence, a number array) at 256 KB,
1, 5, 20 and 50 MB, and save their tables under each module's `build/giant-body/`. The tables of
record are in `benchmark/giant-body/2026-10-23/` (`before/`, `after/`, and `after-fixes/` for the
second round). Measured on JDK 17, Apple M2; every allocation figure below is allocation per
call, not a peak, and JDK 17 builds Latin-1 strings and builders (1 byte per ASCII char) where ART
and Kotlin/Native builders take 2, so device figures are measured separately below.

- **The app's request:** beyond the cap the plugin's cost does not grow with the body. With the
  default 256 KB cap a 50 MB response costs the app no measurable time (within noise on MockEngine
  and a real local socket, `Content-Length` and chunked, saved and streamed) and 0.3 MB more
  allocation, the same as for a 1 MB one (run-to-run noise on a 50 MB request is about ±5 ms).
  With a raised cap the extra allocation is 1x the cap when the body has a `Content-Length` (the
  capture buffer is allocated at the cap at once: socket, 50 MB, +7.1 MB at the 8 MB cap and
  +15.1 MB at 16 MB, was +14.7 and +30.8 MB while it grew from 8 KB by doubling, which also held
  1.5x the cap during its last copy) and still about 2x the cap without one (chunked: +15.2 MB at
  8 MB, +31.2 MB at 16 MB: no size to start from). The 16 MB copy costs up to 9 to 13 ms at p50 on
  MockEngine (none measurable on the socket). A request body is copied up to the cap on the
  caller's coroutine: 0.02 ms at the default cap, 0.5 to 8 ms for 16 MB. A body whose
  `Content-Length` fits the cap is copied once.
- **Formatting, off the main thread:** linear in the body for every shape. `splitText` was
  quadratic for a body with long lines: 1.6 s for 16 MB on one line, now 2 to 5 ms. A pure-ASCII
  body is decoded with one byte allocated per byte (was three), 2.8x faster. The detail view
  pretty-prints JSON straight into its pieces in 32K-char batches: 16 MB minified 60 ms with 4.4x
  the body allocated (was 69 ms and 8.1x), pretty records 44 ms and 3.4x (52 ms, 4.6x), CJK/emoji
  32 ms and 6.6x (10.3x); a 16 MB truncated body is no longer pretty-printed only to fail at its
  end: 13 ms and 2.0x (was 57 ms and 5.8x). The view keeps 16 to 27M chars while it is shown (the
  just-formatted view is kept above the 4M-char cache budget). Core's `prettyPrintJson`, which the
  CLI uses, pre-sizes for 1.75x and caches each depth's newline and indent: 16 MB minified 32 ms
  and 3.4x (was 43 ms and 5.4x; the 65 ms in `after/core-formatting.md` was a noisy run, two
  reruns gave 33 and 32 ms). cURL for copy and share is built within the 100k-char limit:
  0.4 to 1 ms and 0.05 to 0.15x for any 16 MB body (was 9 to 25 ms and 4 to 10x).
- **The main thread:** composing the Response tab of a 1, 8 or 16 MB body (pretty, minified,
  truncated to one line, one huge string) takes 6 to 12 ms headless and does not grow with the
  body. Scroll frames, headless: median 0.4 to 0.7 ms, p95 3.7 to 6 ms, max 7 ms. No piece reaches
  a text layout above 4096 chars, and neither does a header value or the URL (cut for display at
  4096 chars with "… N more chars", rows at 1024; copy keeps the whole value).
- **Memory, the real bound:** the store holds at most `maxTotalBytes` of bodies (on G1 an array of
  1 MB or more is rounded up to whole regions, so the heap shows up to 2x for 1 MB bodies; ART
  does not round), and one entry at most three quarters of it. Outside it, in normal running:
  every capture in flight (1x the cap each with a `Content-Length`, up to 2x without), the
  `events` buffer (up to 1,024 snapshots with their bodies, if the summary log sink lagged that
  far), and up to 64 queued bridge `get` replies (each a snapshot, which may have been evicted).
  So: `maxTotalBytes` + concurrent captures x cap (x2 when chunked) + those queued snapshots.
  A tail client's live queue is no longer part of it (body-free events, see Agent access). While
  the store's consumer is stalled, its terminal channel holds every queued body (up to 4096
  snapshots; 256 MB for 32 queued 8 MB bodies in the benchmark), released as soon as it runs.
- **CLI `get`:** linear, 59 to 152 ms for 16 MB, 4 to 20x the body allocated in the CLI's own
  process (JSON tree, pretty-print, escaping); it never touches the app. The bridge reply that
  feeds it costs the app 1.0x an ASCII body (3.3x CJK/emoji, 2.7x base64).

Measured on device (2026-10-23, sample debug builds, API 35 emulator and an
iPhone 16 simulator on iOS 18.2; raw data and scripts in `build/evidence/giant-json/` and, for the
second round, `build/evidence/giant-json-fixes/`; the scripts are kept in `benchmark/device/scripts/`).
Metrics are named as measured: *settle* is tap to the last frame drawn (gfxinfo framestats);
*UI thread* is one frame's `Vsync` to `SyncQueued`; *frame gap* is the longest time between two
frames while the sample's frame ticker draws every vsync, so only there does a gap mean a blocked
main thread (a gap in framestats alone can be an idle one, since Compose draws only on change):

- **Android, the app's request + parse, warm runs (after the 5th), real vs noop:** 1 MB 79 vs
  66.5 ms (+19%; fetch 30 vs 21 ms), 5 MB 344 vs 307.5 ms (+12%), 20 MB within noise, 50 MB
  (large heap) 3,048 vs 3,049 ms. Compiled ahead of time (`cmd package compile -m speed`) the 1 MB
  gap falls to 73 vs 70 ms. The tee's pump was about 1 ms of CPU per MB in a debuggable build;
  the rest was the second coroutine hop per chunk (engine channel, pump, app channel) and the
  debuggable runtime. The pump is gone since: the tee is now a delegating channel read by the
  app's own reader (see Ktor plugin), which pays at most one bounded copy of the cap. Measured
  again on the same emulator (`build/evidence/inline-tee/android-ab5.*`, scripts in
  `benchmark/device/scripts/`: `abn.sh` with `HOST_LOAD_MAX=3`, every block started at a host load
  under 3 and a guest load under 2.5; 3 rounds of 15 runs per size, the runs after the 5th; each
  arm's block median against the mean of a noop block before and after it, then the median of
  the 3 rounds, `paired.py`). Fetch gap over noop, Content-Length / chunked: 1 MB pump (9c82848)
  +5.3 / +9.3 ms, inline tee with capture blocks -0.3 / +4.0 ms; 5 MB pump +4.8 / +5.0 ms,
  inline tee -6.0 / +3.0 ms. Request + parse: 1 MB pump +5.7% / +12.6%, inline tee +1.0% / +9.1%;
  5 MB pump +4.0% / +4.9%, inline tee -1.6% / +0.5%. The noise is as large as most of these
  differences: noop against noop is +3.5 / -1.5 ms fetch and +11% / -5% request + parse at 1 MB,
  and up to 22 ms at 5 MB, and the same APK run twice in the same round (the tee arm and the
  control below) differs by up to 23 ms at 5 MB. What holds round by round: the inline tee's
  chunked 1 MB fetch gap is at most the pump's (+1.8 vs +9.3, +5.0 vs +10.5, +4.0 vs +4.0 ms),
  and its Content-Length 1 MB gap is smaller in two rounds of three (-0.3 vs +7.8 and -1.3 vs
  +5.3 ms; +6.3 vs +2.3 ms in the first); and the capture itself is not measurable at 1 MB: a control arm, the same build with
  a 1-byte cap (the tee with only the count past the first byte), shows the same chunked 1 MB
  fetch gap, +4.0 ms (+1.5 to +4.8). Whatever is left there comes from the tee's delegation and
  the rest of the plugin (request side, store) on a debuggable runtime, not from the capture
  copy, and it is within about twice the noop-to-noop spread. On a debuggable APK `cmd package compile -m speed` now leaves `status=verify`,
  so no AOT figure was taken. On the iPhone 16 simulator the new tee and the pump are within
  1.5 ms of each other at 1, 5 and 20 MB. With an 8 MB cap a body that fits is copied on the
  request coroutine: 5 MB fetch 88 vs 64.5 ms. At 20 MB with the 8 MB cap on the default 192 MB
  heap the app (179 MB after its own parse) still pays blocking GCs: median 138 ms with
  `Content-Length` and 90 ms chunked after the capture buffer fix (122 and 114 ms before, 5 runs
  each: within run-to-run noise), and frame gaps up to 100 ms (167 ms before). The GCs come from
  the app's own 20 MB parse near the heap limit plus what the raised cap keeps, not from the
  capture's growth; with the large heap there were none. A cap must leave the app its own headroom.
- **Android main thread and UI:** no ANR, no OOM. 50 MB bodies (every shape) and the 20 MB nested
  body run out of the 192 MB heap in the noop build too (the app's own `JsonElement` tree). The
  detail of an 8 MB minified body (8 MB cap, pretty-printed), the same emulator, old and new build
  back to back, 5 opens each: settle 600 to 768 ms before and 569 to 771 ms after; the Java heap's
  peak while it opens (dumpsys every 0.3 s) 68.7 MB before and 56.4 MB after, 38.9 MB for both
  once collected. The heaviest frame's UI thread is 15 to 64 ms (the first open after launch 57 to 63 ms); with
  the frame ticker on, the longest frame gap while the detail opens is 67 ms (none over 100 ms),
  so the 330 to 480 ms without frames that framestats shows is the formatting running off the
  main thread with the main thread idle. The Response tab then draws in one frame (UI thread 14
  to 35 ms); scrolling the body has a UI thread of 1.5 ms at p50 and 8 ms at p95. With a 16 MB cap
  (`maxTotalBytes` 32 MB) the 16 MB detail settles in 1.0 to 1.2 s, heaviest UI thread 18 to
  66 ms, Java heap peak 84 MB and 58 MB once collected, on the default 192 MB heap. Copy cURL of
  an 8 MB request body settles in 1.9 to 2.1 s in both builds (the confirmation's display time;
  heaviest UI thread under 25 ms), and the command is now cut inside a closed quote with its
  warning line; Share opens the system sheet in 17 to 58 ms with the text within 100k chars. The
  open list after a giant run had a first frame of 127 to 164 ms of UI thread (one-off composition
  of a new pane in a debug build). `hopi get --json` of the 8 MB body takes 0.9 to 1.0 s and
  the app's frames meanwhile show no gap over 50 ms.
- **iOS (Debug, real, vs Release, noop, so only fetch is comparable):** fetch within noise for
  minified and pretty bodies, +5 to 10 ms for 20 and 50 MB of one string; no frame gap over
  43 ms during any request run. The 8 MB detail (8 MB cap), driven by coordinate taps with the
  frame ticker on, two runs: frame gaps at most 78 ms in one and one gap of 134 ms while the
  detail opened in the other; Response tab, scrolling and reopening under 65 ms. The 16 MB detail
  (16 MB cap, 32 MB store) at most 77 ms, footprint 190 MB after. The first round, driven with
  accessibility queries (which block the main thread too), had shown gaps of 116 and 435 ms
  during the detail and scrolling. `hopi get --json` of the 8 MB body takes 1.1 s with no
  gap over 50 ms in the app.

### Idle cost is zero — rules learned from DebugSwift

DebugSwift is known to make apps and emulators noticeably slow. The causes are design choices, and
Hopi rejects each of them:

| DebugSwift does                                                  | Hopi rule                                                                 |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| Many monitors on by default (FPS, CPU, memory, leaks, UI tree)   | One tool, HTTP only, and only on the clients the developer installs it on      |
| Polling timers sampling the app continuously                     | **No timers, no polling, no periodic work anywhere.** Everything is event-driven |
| Global interception (URLSession swizzling, every request in the process) | Opt-in per `HttpClient`; nothing global, no swizzling, no reflection           |
| UI/overlay work on the main thread while the app runs            | The launcher is one static composable: no recomposition unless dragged or tapped, no observation of the store |
| Formatting and storing everything eagerly                        | Capture raw bytes only; decode, pretty-print, encode only when a screen, log line, or agent asks |

Concretely, when nothing is looking (screen closed, no agent connected, log sink off):

- the only per-request work is header copy, bounded byte copy, one `trySend`;
- the store consumer coroutine is suspended on its channel between events;
- the bridge thread is blocked in `accept()` (not spinning) and encodes nothing;
- the UI collects nothing: `transactions` is collected only while `HopiScreen` is composed;
- no thread wakes up on its own. A CPU profile of an idle app with Hopi installed must be
  indistinguishable from one without it.

When the screen is open, the list renders only visible rows (lazy, keyed, precomputed row strings
built once per transaction), detail formatting runs on `Dispatchers.Default`, and nothing animates
continuously. These rules are reviewed in every phase and measured in the sample app.

Measured on device (phase 3 acceptance, sample debug builds, API 35 emulator and an
iPhone 16 simulator on iOS 18.2):

- **Idle, launcher visible, screen closed, 60 s** (`/proc/<pid>/stat` and every
  `/proc/<pid>/task/*/stat`): the real build and the noop build both use 0 CPU ticks once ART's
  post-install `Profile Saver`/JIT work is over (that first window costs both, 9 to 19 ticks), and
  draw 0 frames. The real build has 2 more threads; the one of Hopi's own,
  `hopi-bridge` (blocked in `accept()`), uses 0 ticks. On iOS the Debug (real) app used
  0.18 to 0.19 s of CPU per minute and the Release (noop) app 0.11 s, both near 0%; per thread
  (`ps -M`) the bridge thread uses 0 and the difference is in the Kotlin/Native GC's own thread,
  which both builds run. Debug and Release differ in optimisation too, so that comparison is not
  exact.
- **Scrolling 500 transactions** (identical flings, `dumpsys gfxinfo` summary plus `framestats`,
  UI thread = `Vsync` to `SyncQueued`), measured again on the build that stops drawing the hidden
  app (debug, `-Phopi.enabled=true`, default headless renderer, SwiftShader, guest load under
  2, 6 rounds of two flings each, the inspector opened over the app's own item list): the
  inspector list had 1 to 6.2% janky frames per round, p95 29 to 61 ms, GPU 17 ms at the median,
  UI thread 1.9 to 4.0 ms at the median and 6 to 13 ms at p95. The app's own list on the same
  emulator: 21 to 27% janky, p95 53 to 61 ms, GPU 20 to 21 ms, UI thread 7.5 to 10 ms at the median
  and 15 to 18 ms at p95. The same build with the draw guard removed (an A/B APK, everything else
  equal) measured the same (1 to 5.2% janky, GPU 17 ms, UI p95 3.6 to 12 ms): on SwiftShader the
  hidden app's layer is not what costs the 17 ms, so no frame of either list meets 16.7 ms there
  either way. HWUI's overdraw view (`debug.hwui.overdraw show`) does show the difference: with the
  guard the screen over the item list is 1x everywhere, without it the app's cards show through at
  2x to 4x, which a real GPU pays for in fill rate. The earlier rounds' "UI thread under 1 ms at the
  median" was optimistic for this build: the phase 3 device report noted a p95 of 16 to 17.5 ms in
  4 of 6 rounds of its final build on a loaded host (no raw data kept), and these settled rounds
  put it at 6 to 13 ms. With `-gpu host` (phase 3
  build, not re-measured) both lists had 0.8 to 1% janky frames and the same p90 21 ms, p95 22 ms,
  of which the emulator's GPU path is 14 to 15 ms at the median; the inspector list's UI thread
  p95 was 3.5 ms. An emulator's frame percentiles are an upper bound, not a device number.
- **Opening:** the screen settles 284 to 335 ms after the tap, a detail 267 to 317 ms (both
  include the 200 ms transition), and the Response tab of a 256 KB JSON body (300 KB, truncated)
  draws in 1 or 2 frames, 41 to 77 ms after the tap. The first composition of a new pane is one
  long frame (UI thread 20 to 50 ms in a debug build without R8).
