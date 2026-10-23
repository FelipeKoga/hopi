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
   channel): `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, plus user-configured
   headers and query parameters. Opting out is explicit.
6. English everywhere: code, comments, docs, commits.

## Modules

| Module                | Depends on                           | Purpose                                                    |
| --------------------- | ------------------------------------ | ---------------------------------------------------------- |
| `hopi-core`      | kotlinx-coroutines                   | Models, store, redaction, cURL, JSON writer, event stream, log sink, agent bridge server |
| `hopi-ktor`      | core, ktor-client-core               | The Ktor client plugin                                     |
| `hopi-ktor-noop` | ktor-client-core                     | Same public API as `hopi-ktor`, does nothing          |
| `hopi-ui`        | core, Compose Multiplatform          | In-app inspector screen and launcher                       |
| `hopi-ui-noop`   | compose-runtime                      | Same public API as `hopi-ui`, renders nothing         |
| `hopi-cli`       | JVM, kotlinx-serialization           | Host CLI for developers and AI agents                      |
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
the noop accepts every setter and ignores it. `HopiScreen` in the noop renders nothing;
`HopiLauncher` in the noop renders `content()` and nothing else.

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
The sample app will demonstrate this once the noop twins exist; until then `sample/androidApp`
depends on `hopi-ktor` directly (see Sample app).

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

Body text decoding and pretty-printing are functions over `CapturedBody`, computed on demand.

### Store

`HopiStore`: ring buffer of `HttpTransaction`, bounded by `maxEntries` and `maxTotalBytes`
(oldest evicted first). Writes come from a single consumer coroutine on `Dispatchers.Default` fed by
two bounded channels, so producers never suspend: pending snapshots go through one of capacity ~256
with `BufferOverflow.DROP_OLDEST` (losing one is harmless, the terminal snapshot carries
everything); terminal snapshots go through their own of capacity 4096 that never drops silently.
A terminal write that does not fit is rejected (`submit` returns false) and counted in
`droppedTerminalWrites: StateFlow<Long>`; the summary log sink turns every increase into a line
`{"hopi":1,"notice":"…","dropped":N}` (N is the running total), so an agent knows
transactions are missing. A pending snapshot that arrives after its transaction's terminal one (the
two channels can reorder them) is ignored. An entry whose bodies together exceed `maxTotalBytes`
is stored with its larger body (then, if still needed, the other) replaced by a skipped body with
reason `"over store byte cap"`, so one entry never evicts the whole history. Exposes:

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
Hopi's own pass-through channels in place of `split` (reasons below):

- Request bodies: `ByteArrayContent`/`TextContent` are read directly; `ReadChannelContent` and
  `WriteChannelContent` are teed (copy to both the wire and a bounded capture) through a
  pass-through `WriteChannelContent` rather than `split`. `MultiPartFormDataContent`
  and `ProtocolUpgrade` are recorded with `skippedReason`, not captured.
- Response bodies: tee the raw content with `replaceResponse`, as `Logging` does, but through a
  pass-through channel of Hopi's own rather than `split` (details below), except a body whose
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
  - The tee is one coroutine in the client's scope that moves the source to the app's channel in the
    chunks the source makes available (as `copyTo` does, so the app's reader sees the same chunking
    and the same flush points), keeping the first `maxBodyBytes` and counting the rest, so
    `totalBytes` is always the exact count. A source failure reaches the app with the source's own
    cause (not wrapped once more). An app that cancels the body before its end (leaving
    `execute {}`) while the body is still arriving cancels the source too, so the engine can release
    the connection, and the transaction fails with `"cancelled"`. The transaction completes once the
    source ended and the app's reader reached the end, or the app cancelled after the whole body
    had arrived.
- `durationMs` uses `TimeSource.Monotonic`. It runs from the send hook to the moment the app's reader
  reaches a teed body's end, the off-path read of a decoded body ends, or the send pipeline returns
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
dropped. Redacted header values become `<redacted>`.

The command is one line; comment lines precede it. A method that is not a plain token is quoted.
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
in place of `"hopi":1` and the 3999-byte budget.

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

**Slow clients.** Each tailing client has its own bounded live queue, filled by its store event
listener with a non-suspending `trySend` and nothing else; encoding happens on the client's
writer thread. The store hands a listener one consumer batch in a single synchronous burst (up to
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
{"cmd":"clear"}                       // → {"type":"ok"}
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
`skippedReason`. Redaction applies to everything sent over the bridge: header values and query
parameters. Bodies are sent as captured, so a server that echoes a credential in its response
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
cURL export, the JSON writer (requests), `prettyPrintJson`, `formatBytes` and `parseFlatJsonObject`
(`bridge.json`), and on kotlinx-serialization-json only for its JSON tree parser (replies; no
compiler plugin). It parses its own arguments (no CLI library). `./gradlew :hopi-cli:installDist`
produces a runnable `hopi-cli/build/install/hopi-cli/bin/hopi`.

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
  it on SIGINT during `tail` (removal runs once either way). Listing bridges from `/proc/net/unix` is
  not implemented: `--app` is required. adb drops every forward of a device whose transport resets
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
- `clear`: `{"type":"ok"}`.
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

## Sample app (`sample/`)

`sample/androidApp` is the start of the CMP sample, Android only for now: one Compose (foundation
only) activity, `applicationId` `dev.koga.hopi.sample`, a Ktor OkHttp client with
`agentBridge = true` and `logSink = Summary`. "Fire requests" sends, one after the other, a GET
returning JSON, a POST with a JSON body and an `Authorization` header, a 404, a GET to an
unresolvable `.invalid` host, a response larger than 256 KB and a chunked response. "Bridge
self-check" connects to the app's own bridge as the app's uid and logs the `hello` reply under the
`HopiSample` tag, its `proof` value replaced by `<redacted>`; `am start … --es selfCheck <process name>` runs it at launch against another
app's bridge, and a second install built with `-Psample.applicationIdSuffix=.probe` (another uid)
uses that to prove the bridge cannot be reached from another app. `am start … --es squat <process
name>` makes that second install bind the other app's socket name first and answer with a forged
`hello` (right app id, no proof) plus one forged transaction, to prove the CLI refuses a squatter.

`sample/devserver/devserver.py` (Python 3 standard library, bound to `127.0.0.1:8765`) serves those
routes and logs every request as one JSON line on stdout. The emulator reaches it with
`adb reverse tcp:8765 tcp:8765`; the debug build's network security config allows cleartext to
`127.0.0.1` only. Nothing reaches an external network.

## UI (`hopi-ui`)

Compose Multiplatform, no Material dependency (foundation only), monochrome, dense, fast:

- **List:** newest first; method, status code, host + path, duration, size; Pending/Failed visible at
  a glance. Search by URL substring, filter by status class. Lazy list keyed by id; `transactions`
  collected with `collectAsState`; no per-row formatting work beyond what is visible.
- **Detail:** Overview (URL, method, code, duration, sizes, protocol), Request (headers, body),
  Response (headers, body). JSON bodies pretty-printed lazily off the main thread and cached.
  Truncation and skip reasons visible.
- **Actions:** copy cURL, copy URL, copy response body, share (platform share sheet), clear.
- **Launcher:** a small draggable floating button over the app content opening the screen.

## Performance budget

Measured with a JVM benchmark against `ktor-client-mock`, plugin installed vs not:

- Added latency per request on the caller path: < 50 µs median for a 1 KB JSON response.
- No added allocation proportional to body size beyond `maxBodyBytes`.
- A 10 MB response is received intact by the app while at most `maxBodyBytes` is retained.
- With `enabled = false` the plugin installs no hooks at all.

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
