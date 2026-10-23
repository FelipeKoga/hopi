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
only). The bridge implementation registers an `AgentBridgeStarter` there; until one is registered the
request starts nothing. The first request with a starter registered starts it, once per process.

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
The sample app demonstrates this.

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
- `get(id)`, `since(id)`, `clear()`.

Updates are cheap: a transaction is captured as one `Started` write and one terminal write.
`submit(transaction)` is non-suspending (`trySend`) and inserts or replaces by id; entries stay in
id order. `Completed` is emitted for both terminal states (`Complete` and `Failed`), after the
snapshot containing it is published, so a subscriber's `get(id)` finds it when the store retained
it (not when it was cleared or evicted in the same batch). `clear()` drops every
transaction whose id was allocated before the call, including a pending one whose completion
arrives later. Ids come from `store.nextId()`.

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
limit), 1000 on iOS (the unified logging system cuts a message's string at about 1 KiB; the value
has not yet been verified with `log stream`). A URL and an error that do not fit share the budget,
are cut at code point boundaries, and the line gains `"urlTruncated":true` and/or
`"errorTruncated":true` as its last fields. Unpaired surrogates are written as `\u` escapes, so a
line is always valid UTF-8. The bridge's summaries have the same shape with `"type":"transaction"`
in place of `"hopi":1` and the 3999-byte budget.

### 2. The bridge + CLI (full detail)

The app runs a bridge server; the host CLI connects to it.

- **Android:** `LocalServerSocket` on the abstract name `hopi.<applicationId>`. No TCP listener.
  Each accepted connection's peer uid (`LocalSocket.peerCredentials.uid`) must be the shell uid
  (2000, i.e. `adbd` forwarding) or the app's own uid; anything else is closed. The CLI runs
  `adb forward tcp:0 localabstract:hopi.<applicationId>` (adb binds host loopback) and connects.
- **iOS simulator:** macOS caps `sun_path` at 104 bytes and simulator container paths are longer, so
  the bridge listens on `127.0.0.1` port 0 (loopback only, never `0.0.0.0`) and writes
  `{"port":N,"token":"…"}` to `<app data container>/Library/Caches/hopi/bridge.json`. The CLI
  finds it with `xcrun simctl get_app_container <udid> <bundleId> data`. Every connection must send the
  token as its first line; a wrong or missing token closes the connection. The token is 128+ bits
  from the OS CSPRNG, never logged.
- **Physical iOS devices:** no bridge in v1 (no sane host↔app channel without a network listener).
  The os_log stream works.
- **JVM:** the bridge is a no-op.

The bridge starts only when `agentBridge = true`, on a background thread, and costs nothing until a
client connects: events are encoded to JSON only while at least one client is subscribed.

#### Wire protocol (NDJSON, one JSON object per line, both directions)

Client to app:

```json
{"cmd":"hello"}                       // → {"type":"hello","protocol":1,"app":"<id>","platform":"android|ios","count":N}
{"cmd":"list","since":0,"limit":100}  // → {"type":"transaction",...summary...}* then {"type":"end"}
{"cmd":"get","id":42}                 // → {"type":"transaction",...full detail, bodies as text or base64...}
{"cmd":"tail","since":0}              // → summaries since `since`, then live events until disconnect
{"cmd":"clear"}                       // → {"type":"ok"}
```

Errors: `{"type":"error","code":"NOT_FOUND|BAD_REQUEST|UNAUTHORIZED","message":"…"}`.

Full detail encodes bodies as `{"encoding":"utf8","text":"…"}` when decodable as UTF-8 text,
otherwise `{"encoding":"base64","data":"…"}`, plus `truncated`, `totalBytes`, `contentType`,
`skippedReason`. Redaction applies to everything sent over the bridge.

Full detail shape: `{"type":"transaction","id","startedAt","method","url","state","code","ms",
"protocol","error","request":{"headers":[["name","value"],…],"body":{…}|null},"response":{…}}`;
headers are `[name, value]` pairs so order and duplicates survive; each body also has
`capturedBytes`.

Requests are at most 4096 chars. `since` and `limit` default to 0 and 100; `limit` is 1..10000;
`get` needs `id` ≥ 1. Unknown fields are ignored; anything else malformed is `BAD_REQUEST`.

#### CLI

```
hopi devices                                  # what can be reached: adb devices, booted simulators
hopi list   --app <id> [--platform android|ios] [--device <serial|udid>] [--json] [--limit N]
hopi tail   --app <id> [...]  [--json]        # live; NDJSON with --json, one line per event
hopi get    <id> --app <id> [...] [--json]    # full detail incl. bodies
hopi curl   <id> --app <id> [...]             # prints the cURL command
hopi clear  --app <id> [...]
```

`--json` output is NDJSON and stable: it is the contract for AI agents. Human output is aligned
columns. Exit codes: 0 ok, 1 usage, 2 not reachable (app not running, bridge off, no device), 3 not found.
Platform is inferred when only one of adb/simctl has a target. Adb is found at `$ANDROID_HOME`,
`~/Library/Android/sdk/platform-tools/adb`, or `PATH`. Subprocesses are spawned with argument
arrays, never through a shell.

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
