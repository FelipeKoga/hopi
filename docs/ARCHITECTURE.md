# Hopi architecture

Hopi is a set of real-time devtools for Kotlin Multiplatform / Compose Multiplatform apps on
Android and iOS, inspired by DebugSwift. Each tool is a module of its own, off until opted in. The
tools built so far: the network inspector (the Ktor client plugin, plus automatic capture of every
URLSession request of an iOS process and of every OkHttp and `HttpURLConnection` request of an
Android app, third-party SDKs included, see "URLSession capture" and "Android capture"), rules
that mock, fail, delay or edit those requests (see "Rules"), and the performance monitors (see
"Performance monitors"). It is built for two audiences at once: a developer looking at an in-app
screen, and an AI coding agent reading the same data from a terminal.

This document is the contract every module is built against. Change it before changing code.

**Migration in progress (2026-10-28).** `docs/design/api-runtime-split-plan.md` replaces the noop
twins with an optional `hopi-api` and per-tool runtimes that only debug builds link, removes the
in-app UI (the desktop viewer and the CLI are the surfaces) and keeps a thin Ktor plugin. Principle
1 states the target, and the Modules table names the stage of the plan that changes each row; the
other sections describe what is built until their stage lands.

## Non-negotiable principles

1. **Zero production impact by default.** Hopi leaves a release build by not being linked, never by
   `#if DEBUG`. Each tool is a runtime module that only debug builds link. The API an app calls
   (`hopi-api`: rules, perf) is optional and does nothing alone; a release build may link it and
   the thin Ktor plugin `hopi-ktor`, nothing else. A runtime in a release build fails the build (the
   Gradle plugin's guard on Android, the release guard on iOS), and the Android runtime refuses to
   start in an app that is not debuggable. Until the plan's stages land, the `-noop` twins give the
   same guarantee: every consumer-facing artifact has one, with an identical public API, and only
   debuggable variants get the real artifact.
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

| Module                | Depends on                           | Purpose                                                    | Runtime-split plan stage |
| --------------------- | ------------------------------------ | ---------------------------------------------------------- | --- |
| `hopi-core`      | kotlinx-coroutines                   | Models, store, redaction, cURL, JSON writer, event stream, log sink, agent bridge server | 3: depends on `hopi-api`; 4: start sequence, Android provider; 7: rules runtime |
| `hopi-ktor`      | core, ktor-client-core               | The Ktor client plugin                                     | 10: thin, over `hopi-api`; its recorder moves to `hopi-network` |
| `hopi-ktor-noop` | ktor-client-core                     | Same public API as `hopi-ktor`, does nothing          | goes in 10 |
| `hopi-ui`        | core, Compose Multiplatform          | In-app inspector screen and launcher                       | 5: the desktop viewer's screens only (JVM, unpublished) |
| `hopi-ui-noop`   | compose-runtime, compose-ui          | Same public API as `hopi-ui`, renders nothing         | goes in 5 |
| `hopi-urlsession` | core                                | Automatic URLSession capture on iOS (`HopiUrlSession.start()`); does nothing on Android and the JVM | 6: its start moves to `hopi-start`; 9: merged into `hopi-network` |
| `hopi-urlsession-noop` | nothing                        | Same public API as `hopi-urlsession`, does nothing; no dependency at all | goes in 9 |
| `hopi-android`   | core (Android only)                  | Automatic OkHttp and `HttpURLConnection` capture on Android; added by the Gradle plugin to debuggable variants, started by its manifest's provider | 4: the provider moves to core; 9: merged into `hopi-network` |
| `hopi-android-noop` | nothing                           | Same public API as `hopi-android` (`HopiAndroid`), does nothing; what non-debuggable variants get | goes in 9 |
| `hopi-rules`     | core                                 | `HopiRules`, the in-process API over core's rule engine (mock, fail, delay, edit; docs/design/rules.md) | 7: the API moves to `hopi-api`, the runtime to core; the module goes |
| `hopi-rules-noop` | nothing                             | Same public API as `hopi-rules`, does nothing; no dependency at all | goes in 7 |
| `hopi-perf`      | core                                 | `HopiPerf`, the performance monitors (frames, hangs, CPU, memory; docs/design/perf-monitors.md); each off by default. Android and iOS monitors built | 8: its API moves to `hopi-api`; a runtime started as a tool |
| `hopi-perf-noop` | nothing                              | Same public API as `hopi-perf`, does nothing; no dependency at all | goes in 8 |
| `gradle-plugin/` (included build) | AGP API and ASM at build time only | `hopi-instrument` (the bytecode rewriter and `DexReader`) and `hopi-gradle-plugin` (`dev.koga.hopi`); never a runtime dependency | 4: generates the tool list; 9: release guard; 11: no twin selection |
| `hopi-connect`   | core (JVM), kotlinx-serialization    | Host side of the bridge: targets, `bridge.json`, bridge client (all `@HopiInternalApi`) | 12: iPhones over USB |
| `hopi-cli`       | connect, core (JVM)                  | Host CLI for developers and AI agents                      |  |
| `hopi-desktop`   | connect, ui (JVM), core (JVM), Compose Desktop | Desktop viewer app (macOS `.dmg`); never a dependency of an app |  |
| `sample/*`            | everything                           | CMP demo app (Android + iOS) making real requests; `sample/fakesdk` is a Java library that stands in for a third-party SDK and knows nothing of Hopi | every stage |
| `hopi-api` (planned)  | nothing                              | The API an app calls (rules, perf), `@HopiInternalApi` and the runtime registry; does nothing alone; the only Hopi module a release build links besides `hopi-ktor` | 3 (rules: 7, perf: 8) |
| `hopi-start` (planned) | core, network, perf                | `Hopi.start { }`, the iOS entry (exported by Debug frameworks) and the JVM entry | 6 (JVM: 10) |
| `hopi-network` (planned) | core                             | Android and iOS network capture and the Ktor adapter; replaces `hopi-android` and `hopi-urlsession` | 9 (Ktor adapter: 10) |

Targets for every KMP library module: `android`, `iosArm64`, `iosSimulatorArm64`, and `jvm` (the JVM
target exists so logic tests run on the host in seconds; it is also a legitimate desktop target).

`hopi-core` is public so the other Hopi modules can use it, but every declaration that is
not part of the consumer API is annotated `@HopiInternalApi` (a `RequiresOptIn` annotation).
The exceptions are the two iOS cinterop bindings, which cinterop cannot annotate: the os_log shim
(see Agent access) lives in `dev.koga.hopi.core.internal.oslog` and binds only `hopi_os_log_line`
(no os/log.h declarations are re-exported), and the URLSession capture library (see URLSession
capture) lives in `dev.koga.hopi.core.internal.urlsession` and binds only its own `hopi_us_*` C
functions and constants (no Objective-C class).
Consumers only ever touch the API of `hopi-ktor`, `hopi-ui`, `hopi-urlsession`, `hopi-android`,
`hopi-rules` and `hopi-perf`, which is what the noop twins mirror. The os_log shim also binds
`hopi_os_log_perf_line` (category `perf`, see "Performance monitors"). `hopi-perf` has a third
binding of the same kind: its iOS monitors' native library in `dev.koga.hopi.perf.internal.native`
(only its own `hopi_perf_*` C functions, constants and one struct; no Objective-C class).

## Consumer API

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
        rules = true                         // apply Hopi rules to the requests this plugin records (default true)
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

`hopi-urlsession` (call once, first thing at startup, in debug builds; Swift:
`HopiUrlSession.shared.start()` or `HopiUrlSession.shared.start { $0.agentBridge = true }`):

```kotlin
HopiUrlSession.start()                       // idempotent; the first call's configuration wins
HopiUrlSession.start {
    enabled = true                           // false: start() installs nothing
    maxEntries = 500                         // the same store settings as the Ktor plugin
    maxTotalBytes = 16L * 1024 * 1024
    maxBodyBytes = 256L * 1024
    redactHeaders("X-Api-Key")
    redactQueryParameters("token")
    unsafeDisableRedaction()
    summaryLog = true                        // the NDJSON line per transaction, as logSink = Summary
    agentBridge = false
    rules = true                             // false: no rule protocol, no factory hook, rules never read
}
HopiUrlSession.isStarted                     // true once capture runs (never on Android/JVM, never in the noop)
```

The store semantics are the plugin's (one helper in core, `CaptureInstallation`, does both): the
first installation of either sets the caps, redaction is the union, the summary sink and the bridge
start once per process. A second `start` with a different configuration writes a notice line
instead of changing anything. Invalid caps (`maxEntries` or `maxTotalBytes` not positive,
`maxBodyBytes` outside `0..Int.MAX_VALUE`) write a notice line and start nothing, without using up
the first call: `start()` is called from Swift, where an exception would abort the app, and the
noop twin accepts any value. The Ktor plugin, a Kotlin API, still throws on them. The name is not `Hopi` because that is the Ktor plugin, and two
`Hopi` declarations in one framework collide in the Objective-C export. On Android and the JVM
`start()` returns at once, so common code can call it.

Android capture (the app module's `build.gradle.kts`; nothing to call):

```kotlin
plugins { id("dev.koga.hopi") }
hopi {                                       // optional; the store settings of HopiUrlSessionConfig
    enabled = true                           // false: every variant gets the noops, nothing is instrumented
    okhttp = true                            // rewrite OkHttp's RealCall
    urlConnection = true                     // rewrite java.net.URL connection call sites
    selectTwins = true                       // real hopi-ktor/hopi-ui in debuggable variants, noops elsewhere
    agentBridge = false                      // plus maxEntries, maxTotalBytes, maxBodyBytes, redact…, summaryLog
    rules = true                             // false: the interceptor and the wrappers never read the rule state
}
HopiAndroid.isStarted                        // true once capture runs (never in the noop, never in release)
HopiAndroid.okHttpInstrumented               // true when this APK's OkHttp was rewritten
```

`hopi-rules` (tests and debug menus; Swift: `HopiRules.shared`; see "Rules" below):

```kotlin
object HopiRules {
    fun mock(match: String, block: HopiMock.() -> Unit = {}): Long          // the rule's id
    fun fail(match: String, failure: HopiFailure, block: HopiRuleOptions.() -> Unit = {}): Long
    fun delay(match: String, block: HopiDelay.() -> Unit): Long
    fun edit(match: String, block: HopiEdit.() -> Unit): Long
    fun list(): List<HopiRuleInfo>
    fun setEnabled(id: Long, enabled: Boolean): Boolean
    fun remove(id: Long): Boolean
    fun clear()
}
```

`match` is the shorthand of the CLI's `--match`. The builders (`HopiMock`, `HopiDelay`, `HopiEdit`
with `request { }`/`response { }`, `HopiRequestEdit`, `HopiResponseEdit`, `HopiRuleOptions` for
label, priority, times, enabled, `sources(…)`, query and header predicates and `delay(…)`) and the
enums `HopiFailure` (`Timeout`, `Reset`, `UnknownHost`, `Cancel`) and `HopiSource` are plain
classes with internal constructors. A validation error throws `IllegalArgumentException` with the
bridge's message, a limit `IllegalStateException`. In the noop every call returns `-1`, `false` or
an empty list and nothing is stored; the builders accept and keep every setting. The `rules`
switch of each recorder is accepted and ignored by its noop, with the same default
(`DefaultsParityTest`).

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

**Parity is checked mechanically.** The real and noop modules enable the Kotlin Gradle plugin's built-in ABI
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
for the UI). `hopi-android` excludes its package `dev.koga.hopi.android.internal` from its dump:
the hooks the rewritten bytecode calls, the interceptor, the wrappers and the manifest's provider
are public in the binary (`@HopiInternalApi`) but no consumer API, and the noop has no manifest and
no hook. Because the Gradle plugin swaps twins under code compiled against the other side (below),
the parity task also fails on an `inline` function or a `const` in any parity-checked dump: its body
would be copied from the side the caller was compiled against.

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

To call `HopiUrlSession` from Swift, the shared module also exports it from the framework (an
Objective-C framework carries only its own declarations and those it exports):

```kotlin
val urlSession = if (hopiEnabled) "dev.koga.hopi:hopi-urlsession:$v" else "dev.koga.hopi:hopi-urlsession-noop:$v"
iosMain.dependencies { api(urlSession) }
target.binaries.framework { export(urlSession) }
```

Debug builds pass `-Phopi.enabled=true` (the Xcode build phase that calls Gradle; on Android the
`dev.koga.hopi` plugin, below, replaces the flag). Release builds get the noop unless someone
deliberately passes the flag.

An Android-only app module does have build types, so it can select with `debugImplementation`
(real) and `releaseImplementation` (noop) instead. An app module that consumes a KMP shared module
must not: the shared module already brings one of the two, and real and noop declare the same
classes. `sample/androidApp` therefore names neither and takes whatever `sample/shared` selected.

**An Android application with the `dev.koga.hopi` plugin needs no property.** The plugin resolves
`hopi-ktor`/`hopi-ui` per variant, whichever of the two the app's modules name: the real module in
debuggable variants, the noop twin in the others (a dependency substitution on each variant's
compile and runtime configurations, for this repository's projects and for the `dev.koga.hopi`
coordinates), and it adds `hopi-android` or `hopi-android-noop` the same way. A KMP shared module
compiled against either twin links against the other because the two have the same ABI with no
inlined body (`:checkNoopParity`). So a debug build is real and a release build is noop whatever
`-Phopi.enabled` says; to ship the real modules in a release, a developer must remove the plugin,
which is the deliberate, visible choice principle 1 asks for. The property keeps its meaning for
iOS frameworks and for Android apps without the plugin. (The generated `HOPI_ENABLED` of
`sample/shared` follows the property, so only the iOS app labels its build with it; the Android
activity passes `HopiAndroid.isStarted` to `SampleBuild`, so its Diagnostics caption and evidence
lines say what the plugin selected.)

The sample checks the switch mechanically. `:sample:androidApp:verifyReleaseUsesNoop` reads the
release APK's dex files with the plugin's `DexReader` (class definitions, every referenced type and
method, and each class's `invoke-*` instructions) and its merged manifest, and fails unless the
release runtime classpath holds exactly `:hopi-ktor-noop`, `:hopi-ui-noop` and
`:hopi-android-noop`, its Hopi classes (outside `dev.koga.hopi.sample`, D8's
`$$ExternalSynthetic…` classes counted as their owner's) are exactly those the noop twins' jars
hold, no type of `dev.koga.hopi.core`, `dev.koga.hopi.android.internal` or
`dev.koga.hopi.android.generated` is defined or even referenced, no class calls a Hopi hook, and the
manifest declares no Hopi provider; as its negative control, OkHttp's `RealCall` must still call
`OkHttpClient.interceptors()` and the fake SDK `URL.openConnection()`. `verifyHopiSelection` (part
of the app's `check`) is the same check, kept to prove that `-Phopi.enabled=true` no longer
reaches the release APK. `:sample:androidApp:verifyDebugCapture` (also in `check`) is the positive
control on the debug APK: `RealCall` calls `HopiOkHttpHooks.interceptors` and no longer the getter,
no class outside the runtime opens a `URL` connection directly, the fake SDK and
`kotlin.io.TextStreamsKt` call the URL hooks, the provider is in the merged manifest, and the
debug runtime classpath holds `:hopi-android`, `:hopi-core`, `:hopi-ktor` and `:hopi-ui`.
Measured on this change: the release APK has 13 Hopi classes, 0 referenced core or capture types and
0 classes calling a hook; the debug APK has 6 classes calling a URL hook (the fake SDK, the stdlib,
Ktor's `URIFileContent`, kotlinx-coroutines' `FastServiceLoader`, Okio's `ResourceFileSystem`, and
the runtime itself) and 0 still opening connections directly. Before the plugin: 12 Hopi classes and
0 core classes without the flag, 345 and 97 with it. On iOS, a framework linked without the flag has no
`kfun:dev.koga.hopi.core` symbol; the Debug app the Xcode project builds has 503.
`:sample:shared:verifyIosReleaseExcludesCapture` (part of `:sample:shared:check` on macOS) links the
release framework for `iosSimulatorArm64` and checks it mechanically: without the flag it fails on
any `_hopi_us_` symbol, any `HopiUSTask`/`HopiUSObserver` class, any `kfun:dev.koga.hopi.core`
symbol (`xcrun nm -m`, an argument array), or the C strings of the capture queue's label and of the
private selectors `_didReceiveData:`/`_didFinishWithError:` in its bytes; with
`-Phopi.enabled=true` it requires all of them (the positive control). Measured: 0, 0, 0 and none
without the flag; 99 `_hopi_us_` symbols, 36 class symbols, 185 core symbols and all three strings
with it. `hopi-urlsession` is an iOS-only dependency of the sample, so the Android checks are
unchanged.

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
    val source: String? = null,   // what recorded it: "ktor", "urlsession", "okhttp", "urlconnection"; null from an older app
    val finalUrl: String? = null, // the URL the response came from, when a redirect followed inside the client changed it
    val ruleId: Long? = null,     // the rule applied to this request (docs/design/rules.md)
    val ruleApplied: String? = null, // what it did: "delay,edit-request,mock,fail,edit-response" (that order, a subset)
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

**A failure after the response arrived keeps the response.** `failedAfterResponse(error, …)`
builds a `Failed` snapshot that keeps `responseCode`, `responseHeaders`, `protocol` and, as
`responseBody`, what arrived of the body before the failure (a partial body: its `totalBytes`
counts the bytes received, `truncated` says whether the cap cut them). Every recorder uses it when a
body read fails, the app closes a body part-way (`"cancelled"`) or the connection drops: the Ktor
plugin (teed, saved and decoded bodies), URLSession capture (a task that ends with an error after
its response), OkHttp and `HttpURLConnection`. A failure before any response stays as it was
(`code` null, no response). On the wire nothing new is needed: a failed summary line now has its
`code` and a non-zero `resBytes`, the detail its response headers and body, fields every reader
already decodes. The in-app and desktop detail read "Failed after 200 OK" and "Response received
… (partial)"; `hopi get` titles the body "Response (partial)"; `hopi list` keeps `ERR` in the code
column and writes `(HTTP 200, then <error>)`. An older CLI parses these lines but shows them
differently: its code column prefers a present `code` over the state, so it prints `200` where the
current one prints `ERR`, and only the `(error)` after the URL says the request failed (release
note: update the CLI with the app; the older desktop viewer, which reads the state first, is
unaffected).

`source` and `finalUrl` are additive in both directions: written only when set (the summary line
and bridge summary carry `"source"` right after `"state"`, then `"finalUrl"`, cut to fit the line
like the URL and marked `"finalUrlTruncated":true` when it was; the detail carries `"finalUrl"`
whole after `"url"` and `"source"` after `"state"`), decoded as null when absent, so an older app
and an older viewer keep working. A host viewer therefore knows where a redirect ended from the
summary alone: its list row reads `host → final-host` (`→ redirected` on the same host) and a
search matches the final URL too; the CLI's `list`/`tail` text appends `  -> <final URL>`. The in-app detail and the desktop viewer (the same composables) show a
"Source" row (`Ktor`, `URLSession`, …) and a "Final URL" row when set; a list row's second line
ends with the source when it is not the Ktor plugin; a platform-captured duration reads
"… (network)"; HAR export adds `_source` and `_finalUrl` to an entry; the CLI's `list`/`tail` text
appends `[urlsession]` (any source but `ktor`) and `get` shows both rows, `--json` passes the lines
through.

The rule marks follow the same rule: `"rule":3,"applied":"delay,mock"` come after `"finalUrl"` (or
`"source"`) and before `"error"` on the summary line and bridge summary, after `"source"` in the
detail, only when set; they are fixed fields of the line budget (at most 72 bytes with a 19-digit
id). HAR export adds `_rule` and `_ruleApplied`; `hopi list`/`tail` text appends `[delay,mock #3]`,
and `get` shows a "Rule" row. An app built before replay was removed (docs/ROADMAP.md, "Replay (no
priority)") may still send `"replayOf"`; every reader ignores it.

### Store

`HopiStore`: ring buffer of `HttpTransaction`, bounded by `maxEntries` and `maxTotalBytes`
(oldest evicted first, except the entry just written, which is evicted last: a long download that
started first and completes large is not evicted by its own completion while newer, smaller
entries stay). Writes are applied by a single consumer fed by two bounded channels, so producers
never suspend: pending snapshots go through one of capacity ~256 with `BufferOverflow.DROP_OLDEST`
(losing one is harmless, the terminal snapshot carries everything); terminal snapshots go through
their own of capacity 4096 that never drops silently. The consumer is a thread of the store's own,
`hopi-store`, at a lower priority (Android nice 5: below the app's threads, but above
`THREAD_PRIORITY_BACKGROUND`, which would also move it to the background scheduling policy and, on
many devices, the background cpu cgroup; iOS utility QoS), that
blocks on a semaphore with no timeout while nothing is queued (`WakeSignal`: a producer that finds
it awake pays one atomic exchange, and only one that finds it asleep pays the semaphore release).
While a viewer is attached (`attachViewer()`: each bridge session for its lifetime, the in-app and
desktop list while composed) every write wakes it, so a request shows the moment it starts and the
moment it ends. While none is, a pending write wakes nothing, and a terminal write wakes the
consumer only when it sleeps with no timer: after each pass the consumer dozes for
`batchWindowMillis` (50 ms), and the terminal writes that arrive meanwhile only mark the work (one
compare-and-set, no system call), applied together when the doze ends; a doze with nothing marked
ends in the untimed sleep. So with nobody watching, a burst of requests costs its threads one wake
in all (the first request's), a request's start is applied with its end, and a record (with its
summary log line) shows at most 50 ms after the request ended. Attaching a viewer, a `clear`, a
`close`, a lost terminal write and half the terminal lane queued without a wake all wake the
consumer at once, also out of its doze (since 2026-10-25, speed fixes; before, every terminal
write woke it, a futex wake that was about half of the calling thread's cost per request on the
emulator: Android capture has the numbers). Waking is not applying, so two callers wait for the
pass with `awaitApplied(timeout)`, which returns once a pass that began after the call has drained
both lanes and run the listeners (the summary line included): a bridge session, after it attaches
and before it reads the client's first request (at most 250 ms; a pass takes well under a
millisecond), so a `list` or `get` sent with the token and `hello` in one write sees every request
that ended before the client connected; and a dying process. With the summary log on, the default
store's installation chains an uncaught-exception handler and a shutdown hook (Android, JVM) or an
uncaught Objective-C exception handler and an `atexit` handler (iOS) that wait at most 200 ms for
that pass, then hand on to the handler they replaced, so the request whose response crashed the
app, or that ended just before `exitProcess`/`exit()`, still gets its log line. Neither runs on a
request's path or while idle. What no handler sees (`SIGKILL`, `abort()`, a Swift trap,
Kotlin/Native's own termination on an uncaught Kotlin exception) can still lose the records of
the last 50 ms, which before the batch window was a race of about 0.1 ms.
With nobody watching, a request in flight is therefore not in the store yet, and more
than the pending lane's capacity of unfinished starts loses the oldest (they appear when they end);
a store built with a `scope` (tests, which drive it with a test dispatcher) runs the same consumer
as a coroutine woken by a conflated doorbell channel. Either way the consumer waits on its wake-up,
not on a lane, so a lane holds exactly its capacity. Before, a coroutine on `Dispatchers.Default`
selected over the lanes and every write woke a pool worker, whose scheduler then spun before
parking again (measured on Android: about 4 wakes and 216 µs of other threads' CPU per request,
see Android capture).
A terminal write that does not fit is rejected (`submit` returns false) and counted in
`droppedTerminalWrites: StateFlow<Long>`; the summary log sink turns every increase into a line
`{"hopi":1,"notice":"…","dropped":N}` (N is the running total), so an agent knows
transactions are missing. A pending snapshot that arrives after its transaction's terminal one (the
two channels can reorder them) is ignored. An entry whose bodies together exceed three quarters
of `maxTotalBytes` is stored with its larger body (the request's on a tie; then, if still needed,
the other) replaced by a skipped body with reason `"over store byte cap"`, so one entry never
evicts the whole history: at least a quarter of the store stays for older entries. The plugin's
largest body cap (half the store) still fits whole with a small other body; a POST with both
bodies at that cap keeps the response.
The terminal writes queued and not yet applied hold at most `maxTotalBytes` of bodies between them
(or one write alone), so a consumer starved by a busy app cannot let a backlog of captures grow the
heap (since 2026-10-27; before, each of up to 4096 queued ends kept its bodies, up to two captures
at the cap each). The request thread pays one atomic add per end that holds bodies; the consumer
takes a pass's bytes off once the pass is published, so the events it holds until then are counted
too. A recorder that queues itself (`DeferredRecord`) reports what its captures' arrays take
(`heldBytes`); a snapshot (the Ktor plugin's, URLSession capture's) counts its bodies' arrays,
except a request body that is the very one the store holds for its pending start: both build an
end on its start, so that array is counted in the store, not again behind it, and stays with the
end whatever the queue holds (one volatile read and a binary search of the published snapshot, for
an end whose request body has bytes). A start still in the pending lane is not enough, since that
lane drops its oldest under a flood and the end then holds the array alone. An
end that would take the queue past `maxTotalBytes` while another end waits is not rejected: it goes
in with each body reduced to its size and reason `"store backlog over byte cap"` (a recorder lets
go of its captures first, `shedBodies`); one that went in without the request body its start
holds, counted while that start waited in its lane, takes the start's back when it is applied
after it (the same array, so letting it go freed nothing). An end that brings the queue to half
of `maxTotalBytes` wakes the consumer out of its doze, as half the lane does, so a burst the
consumer only dozed through keeps its bodies. `withdraw(last)` drops a record that another recorder
may also have made (the Ktor plugin's, for a request the engine had when a platform recorder
started, see URLSession capture) and ignores every later snapshot of its id. It is queued like an
end; `last`, the record's final snapshot (failed, the error says why), is published as `Completed`
(not retained) only when an earlier pass published the record's start and a viewer is attached,
since only then can a list or a tail show that start; otherwise nothing of the record goes out. A
viewer that showed the start and was detached or behind by then misses that end, as it misses the
end of a record evicted or cleared before its withdrawal: the desktop's `RemoteSession` asks for
the rows it still holds pending when it resumes and ends as gone one the app no longer has (see
Agent access, `RemoteSession`).
Exposes:

- `transactions: StateFlow<List<HttpTransaction>>` for the UI (newest last),
- `events: SharedFlow<TransactionEvent>` (Started / Updated / Completed) for collectors that may lose
  events (no Hopi code collects it per request any more: the log sink and the bridge are listeners),
- `get(id)`, `since(id)`, `clear()`,
- `addEventListener(listener)`: called on the consumer for every event, in order and without loss,
  after the snapshot containing it is published. `events` drops the oldest for a slow collector
  without telling it, which a lossless `tail` cannot afford; a listener must return at once (the
  bridge's only does a `trySend`). With no listener registered it costs nothing.

Updates are cheap: a transaction is captured as one `Started` write and one terminal write.
`submit(transaction)` is non-suspending (`trySend`) and inserts or replaces by id; entries stay in
id order. `submitDeferred(terminal) { … }` queues a write whose snapshot the consumer builds, so a
recorder's request thread only queues it (Android capture builds its records this way; no hop to a
capture thread first). The entries are kept in chunks of 32 that published snapshots share
(`ChunkedTransactions`): publishing copies the chunk index, and a write to a published chunk copies
that chunk first, so one update copies about two chunks and the index (about 80 references at 500
entries) instead of the whole list, and the `StateFlow`'s equality check of the old and new list
compares only the chunks that differ. `transactions` keeps its `StateFlow<List<…>>` contract (each
value an immutable list, structurally equal as `List` requires); `get` and `since` binary-search
the same snapshot. Measured per request in a full store (500 entries), consumer run inline:
Kotlin/Native debug (iOS simulator test binary) 157.6 → 19.3 µs, 190.4 → 27.9 µs with a
`transactions` collector; JVM 2.65 → 1.89 µs (`StoreHotPathBenchmark`, `-Phopi.hotpath`). `Completed` is emitted for both terminal states (`Complete` and `Failed`), after the
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
  store's non-suspending `submit`, or none when the record is withdrawn because a platform
  recorder started while the engine had the request (see URLSession capture, "One record per
  request").
- `enabled = false` returns before installing any hook or touching the store.

## URLSession capture (`hopi-urlsession`)

One call at startup in a debug build, `HopiUrlSession.start()`, records every URLSession request
of the iOS process into the same store, whoever makes it: the app, a third-party SDK (analytics,
ads, crash reporting), Ktor's Darwin engine. No session, client or SDK is configured. The full
design, the spike evidence and the reasons are in `docs/design/urlsession-capture.md`; this is the
contract.

**Observe only** (until the developer adds a rule; see "Rules"). Nothing is re-sent (DebugSwift, Netfox and Wormholy re-issue every request
through a `URLProtocol`, which is what makes them slow and changes caching, cookies, redirects,
authentication, HTTP/2 and metrics). An Objective-C library (`hopi-core/src/nativeInterop/objc`,
compiled per iOS target by two Gradle `Exec` tasks with argument arrays and embedded in the
cinterop klib, so consumers need no linker flags) replaces, once, inside one `dispatch_once`:

1. `-[NSURLSessionTask resume]` on every class of the task class chains that defines it (found
   from never-resumed probe tasks): creates the per-task state, before calling the original, on
   the first resume of a suspended task;
2. `-[__NSCFURLLocalSessionConnection _didReceiveData:]` (CFNetwork's per-task connection, below
   every API and delegate shape): after the original, retains the chunk (no copy) while the kept
   bytes are below `maxBodyBytes`, copies only the part of the chunk that crosses the cap, and past
   the cap only counts;
3. `-[__NSCFURLLocalSessionConnection _didFinishWithError:]`: after the original, reads the
   response, the error and the metrics' protocol and ends the record;
4. `NSURLSession`'s upload factories (`fromData:`, `fromFile:`, streamed, and the private one the
   async `upload(for:from:)` uses): after the original, notes the body data (an immutable
   `NSData`'s `copy` is the same object; a mutable body over the cap keeps a copy of its first
   `maxBodyBytes` and its length) or that the body is a file or a stream.

Each replacement calls the previous implementation with its own selector, so swizzlers installed
before or after (Firebase Performance, Pulse, FLEX, AFNetworking) keep working in both orders
(tested with an exchange-style and two set-style swizzlers). No method is added, no app or SDK
class, delegate, `respondsToSelector:` answer or task class changes (tested over 25 delegate
selectors and four delegate shapes), and no `protocolClasses` changes except, with `rules` on
(the default), `HopiUSRuleProtocol` first in the configuration of a session created after
`start()` (background sessions untouched) and registered with `+[NSURLProtocol registerClass:]`
for `URLSession.shared` while rules exist; the protocol claims only a task whose first matching
rule is a mock or a fail (see "Rules"). With `rules` on, the session factory
`+sessionWithConfiguration:delegate:delegateQueue:` is replaced too, and the task factories
(public and private, request and URL forms) and `-suspend` only once the first edit or delay rule
was added. The private selectors are used only after a
start-time self-check (class present, both selectors defined on it with the expected type
encodings, the `_task` ivar typed `NSURLSessionTask`); otherwise, and for background sessions
(out of process), a metadata path records request, status, headers, sizes, timing and error with
bodies `"not observable"`, using KVO on the task's `state` as its end, and a notice line says so.

**One record per request.** Once capture runs, the Ktor plugin defers to it for the engines it
covers: `PlatformCapture` (core) holds the covered engine packages, `start()` covers
`io.ktor.client.engine.darwin.`, and the plugin checks its client's engine class on every send
(one atomic read) and records nothing for a covered one, so a client built before `start()` defers
from its next request. A request the engine already has when `start()` runs is never recorded
twice: `start()` covers the engine before its hooks go live, and the plugin reads the coverage
once more when the engine answers or fails (one more atomic read); covered by then, the request
may have been seen by the resume hook, so the plugin withdraws its record (`HopiStore.withdraw`)
and the request is recorded once by URLSession capture, or missed when it was resumed before the
hooks (docs/design/urlsession-capture.md, "Dedupe with the Ktor plugin"). Other engines (CIO, OkHttp in an app without the Android plugin's instrumentation, MockEngine)
are recorded by the plugin as before, `source = "ktor"`. The plugin's install-time work (caps,
redaction, summary sink, bridge) still happens.

**Threads.** The app's thread that calls `resume` pays an allocation, an associated object, two
retains and one `dispatch_async`; it reads no header and copies no byte. The connection hooks run
on CFNetwork's own queue, never the main thread (counted in the tests, also for a session whose
delegate queue is the main queue). Records are built on one serial utility-QoS dispatch queue
(`dev.koga.hopi.urlsession-capture`), which holds no thread while empty: idle cost stays zero.
Kotlin runs only there, called through `staticCFunction` callbacks.

**What a record holds.** `originalRequest`'s method, URL and headers (headers URLSession adds
itself are not seen); the request body when it is `HTTPBody` or upload data, else skipped with
`"stream, not captured"`, `"file, not captured"` or `"not observed"` (an upload created before
`start()`); the final response's status and headers (a native redirect is one record, with
`finalUrl`); the response body as the app receives it (decoded), up to the cap, `totalBytes`
exact (Hopi's own count, replays excluded); downloads `"download"` with their size and no bytes;
binary and event-stream content types skipped by the same rule as the plugin (`ResponseSkip` in
core, mirrored in the library and compared by a test); `protocol` as Ktor writes it (`HTTP/1.1`,
`HTTP/2.0`); errors as `NSURLErrorDomain <code>: <description>`, `"cancelled"` for a cancel;
`durationMs` from the first `resume` to the network's end (not to the app's delegate queue).
CFNetwork can hand `_didReceiveData:` the same chunk object twice (seen on a redirect and a
mid-body cancel); a chunk already counted is ignored by identity: kept chunks are compared
directly, chunks past the cap through a ring of four `__weak` references, which keep nothing alive
and read nil once freed. `countOfBytesReceived` is not used to cut a record: at
`_didFinishWithError:` it is still 0 or partial for uploads, redirects and downloads (measured).

**Coverage**, every row tested on the iPhone 16 simulator (iOS 18.2) and the iPhone 17 simulator
(iOS 27.0) unless marked: completion-handler data, upload (data, file) and download tasks with a
URL or a request, with and without a handler; `URLSession.shared`, default and ephemeral sessions;
full, partial, nil, `NSProxy` and subclass-calling-super delegates on the main and a background
queue; Swift `data(for:)`, `data(from:)`, `upload(for:from:)`, `bytes(for:)`, `download(for:)`
(XCTest target, iOS 27.0 only, see Sample app); sessions created before `start()` (their tasks
resumed after it are recorded; a task resumed before it is not, even if suspended and resumed
after it; created before and resumed after is); `HTTPBodyStream`; 204, 304, HEAD, a native redirect, gzip; reset, refused, timeout and cancel
(before the first byte and mid-body); 64 parallel uploads; an 8 MB body; Ktor Darwin (one record,
concurrent POSTs, a channel body, redirects recorded per hop because Ktor follows them itself).
Not recorded: WebSocket and stream tasks, `data:`, `file:` and custom-scheme loads, AVFoundation
media loading, WKWebView, `NSURLConnection`, raw sockets; background sessions take the metadata path while the app runs.

**Measured** (2026-10-24, simulator only, no physical iOS device; `benchmark/urlsession/`):
Hopi's own part of each hook, timed inside the library over sequential loopback requests on the
iPhone 17 simulator (iOS 27.0): `resume` 6 to 10 µs, `_didReceiveData:` 4.5 to 5 µs per chunk for
1 KB and 64 KB bodies (16 µs averaged over a 1 MB body's chunks, the one crossing copy of up to the
256 KB cap included), `_didFinishWithError:` 9 to 12 µs. On the iPhone 16 simulator (iOS 18.2): `resume` 4 to 6 µs (32 µs
averaged over the first, cold block), `_didReceiveData:` 2.7 to 10 µs, `_didFinishWithError:` 12 to
17 µs. Per request, capture on against off in
one binary (`ab.m`, 200 sequential requests, interleaved, 6 blocks per arm, host load high): wall
time from `resume` to the completion handler 466 to 476 µs on against 469 to 478 µs off at 1 KB
(one off block at 648 µs), 1036 to 1108 µs on against 1079 to 1096 µs off at 1 MB, so within the
off-to-off spread; CPU of the thread calling `resume` 21.7 to 22.2 µs on against 19.0 to 19.5 µs off
at 1 KB. Retained bytes per task in flight never exceeded the cap (262144 for an 8 MB body).
Simulator figures are an upper bound for CPU, not a device number.

**Verified end to end** (2026-10-24, iPhone 17 simulator, iOS 27.0, the sample's Debug app with Hopi
against the same Debug app built with `HOPI_ENABLED=false`; `benchmark/urlsession/e2e/`, evidence in
`build/evidence/urlsession/`): 19 URLSession shapes (`-verifyCapture`), 9 Ktor Darwin requests
(`-ktorVerify`) and the `ThirdPartySDK` file each appear exactly once through `hopi list/get/tail
--json`, matched against the dev server's own request log, all `source = "urlsession"`, none
`"ktor"`; method, URL, status or error, headers (Authorization redacted), request bodies and captured
response bytes match what the app read (SHA-256), truncated bodies match the server's first 256 KB;
the bytes every scenario read and the requests the server saw are identical on the noop build; the
os_log line carries `source`; the desktop viewer shows the rows as URLSession. Cost in the sample's
Debug build (Kotlin/Native debug framework, summary log on, bridge on, no client connected): wall
time per small loopback request 0.587 to 0.677 ms against 0.567 to 0.570 ms noop at steady state
(5000 sequential requests, 3 alternating rounds); 5 and 20 MB request plus read within the
noop-vs-noop band, 1 MB consistently about 0.8 ms (wall) and 4 ms (CPU) above noop (4 rounds);
process CPU per small request 915 to 1026 µs against 562 to 565 µs, of
which a `sample` profile puts about half in core's `HopiStore` consumer (a full `toList()` copy of the
retained list per batch) and the summary log line, not in the hooks. Idle with no client connected
every Hopi thread uses 0 µs over 60 s; with a `tail` connected the `hopi-bridge-writer` thread uses
about 0.35 ms of CPU per second while parked in `runBlocking` (`BridgeSession.writeLoop`): the
profile shows kotlinx-coroutines' `ThreadLocalKeepAlive` re-arming `Worker.executeAfter` on it. Since
the hardening pass (2026-10-24) the writer blocks on a dispatch semaphore instead (0 µs over 60 s with
`tail` connected), and process CPU per small request is 679 to 698 µs against 575 to 580 noop and
878 to 961 for the build before (iPhone 17 simulator, iOS 27.0; details in the design doc,
"Hardening"). After the speed fixes and their follow-ups (2026-10-25, the same simulator, 3 rounds
of noop / before / after / noop2, 40000 steady small requests per block, arm minus the mean of the
round's two noop blocks; `build/evidence/speed/final/ios/`): process CPU per small request +48 to
+81 µs (the tree before the follow-ups +51 to +86; noise noop2 − noop −43 to +37), median wall
within ±0.015 ms of noop; 1, 5 and 20 MB request plus read inside or near that session's wide
noop-vs-noop spread. Every `hopi-*` thread 0 µs over 60 s idle with no client, a tail, the desktop
and 10 rules; live tail latency over 2000 back-to-back requests p50 0.40, p99 3.9, max 8.8 ms.

## Android capture (`hopi-android`, the `dev.koga.hopi` Gradle plugin)

One line in the Android application module, `plugins { id("dev.koga.hopi") }`, records every OkHttp
call and every `HttpURLConnection` of the app into the same store, whoever makes it: the app,
Retrofit, image loaders, a third-party SDK compiled long before (analytics, ads, crash reporting),
Kotlin's `URL.readText()`, Ktor's OkHttp and Android engines. No client, SDK or session is configured and no
code runs at startup. The full design, the reasons and the measurements are in
`docs/design/android-capture.md`; this is the contract.

**Build time, debuggable variants only.** The plugin (an included build, `gradle-plugin/`) registers
AGP instrumentation (`variant.instrumentation.transformClassesWith`, scope `ALL`: the app's classes
and every dependency's) with an ASM rewriter (`hopi-instrument`) for each debuggable variant, and for
no other: a release variant has no transform task at all.

1. In OkHttp's `RealCall` (`okhttp3.internal.connection.RealCall`, 4.x and 5.x; `okhttp3.RealCall`
   for 3.x: 3.14 is recorded, 3.12 and 3.13 are rewritten but the runtime, which needs 3.14's
   `RequestBody.isDuplex()`, leaves their chains alone), the one `invokevirtual OkHttpClient.interceptors()` becomes `invokestatic
   HopiOkHttpHooks.interceptors(OkHttpClient)` (the same stack effect), and the class gains
   `@HopiInstrumented`. The hook returns the client's own list while capture is off, and a
   read-only view with Hopi's interceptor last once it is on. It covers every client: subclasses,
   clients built by reflection or before capture started, `newBuilder()` copies. No list the app
   can read changes (`client.interceptors()`, the builder's, a copy's), unlike hooking
   `OkHttpClient` itself.
2. Every `URL.openConnection()`, `openConnection(Proxy)`, `openStream()`, `getContent()` and
   `getContent(Class[])` call site, and the same methods as method-reference handles of a
   `LambdaMetafactory` bootstrap, go to the static hook of the same name in `HopiUrlHooks`, which
   calls the real method and wraps an HTTP(S) connection (`java.net.URL` itself is a boot class no
   transform sees).

Each rewrite replaces one instruction or handle by one of the same stack effect, so frames are
copied, not computed, and no class hierarchy is loaded. Hopi's own classes are never rewritten.
The plugin also adds the runtime (`hopi-android` to debuggable build types, `hopi-android-noop`
to the others), generates the optional `hopi { }` block into the variant as a class the runtime
reads with one lookup, and resolves the Ktor and UI twins per variant (see "Selecting real vs noop").

**Start.** `hopi-android`'s manifest declares `HopiInitProvider` (`initOrder` the highest, so it runs
before other SDKs' providers). It reads the generated configuration, looks `RealCall` up by name
without initializing it, and sets the started flag; the store installation (`CaptureInstallation`,
the same semantics as the Ktor plugin and URLSession capture) is made by the first recorded request,
on its thread. It writes one notice line with its own duration.

**Observe only** (until the developer adds a rule; see "Rules"). The interceptor runs as the last application interceptor: it sees the request as
the app's interceptors left it, DNS, connect and TLS failures, cache hits and WebSocket handshakes,
and the response as the app receives it (decoded). It wraps a request body in a delegating body
that copies what OkHttp writes during the first complete `writeTo`, so a one-shot body is still
written once, by OkHttp, and captured (duplex bodies are skipped); it wraps a response body in a
delegating body whose source copies what the app reads, up to `maxBodyBytes`, and counts the rest:
no `peekBody`, no read-ahead, so a long poll or an event stream reaches the app as fast as without
Hopi (tested by holding a body's second half on the server until the app has read the first). A
failure is recorded and rethrown unchanged. `HttpURLConnection` and `HttpsURLConnection` wrappers
delegate every overridable method of the compile SDK's classes (a test reads `android.jar` and fails
on a missing one), never call a delegate method the app did not call except reading the request
before the first connecting call and the cached status and headers after it, and tee the input,
error and output streams the same bounded way. A connection OkHttp implements
(`okhttp-urlconnection`) is left unwrapped, since the interceptor records it.

What the app can observe differently: `request.body`/`response.body` below Hopi's interceptor (and
in `response.request`) are Hopi's delegating classes, and a wrapped connection's class is Hopi's,
so `instanceof` checks on them or reflection on `HttpURLConnection`'s private fields behave
differently. Headers OkHttp's `BridgeInterceptor` and the platform stack add themselves (`Host`,
`User-Agent`, `Accept-Encoding`, `Cookie`) are not recorded, as on iOS.

**One record per request.** The Ktor plugin defers to Android capture through `PlatformCapture`, as
on iOS: the provider covers `io.ktor.client.engine.okhttp.` only when `RealCall` carries the
annotation (and links: 3.14 or later), and `io.ktor.client.engine.android.` only when the
generated configuration says the plugin rewrote that engine's `URL` calls, so the plugin can never
defer to capture that is not there (an unknown OkHttp version writes a notice line and the plugin
keeps recording, `source = "ktor"`). One exception keeps the body as the app reads it: a request of
a covered engine that names its own `Accept-Encoding` (Ktor's `ContentEncoding`) gets its body from
OkHttp or the platform connection still encoded, and Ktor decodes it, so the plugin records it
itself (`"ktor"`, decoded) and marks it with `X-Hopi-Pass-Through`, which the interceptor removes
before sending (the wrapper never sets it) and which makes them record nothing for that request.
The app's own OkHttp interceptors see the marker on those requests; the wire does not. Ktor
disables OkHttp's redirects, so a Ktor redirect is one record per hop; OkHttp's own redirects and
retries are one record, with `finalUrl`.

**What a record holds.** `source = "okhttp"` or `"urlconnection"`; method, URL and headers as the
interceptor (or the connection before connecting) saw them, with the body's `Content-Type` when only
the body names it; the request body up to the cap (`"duplex, not captured"`, `"not sent"` when
`writeTo` never ran, `"incomplete"` when it threw); status, headers, `protocol` (`HTTP/1.1`,
`HTTP/2.0`; for `HttpURLConnection` from Android's `X-Android-Selected-Protocol`) and `finalUrl`;
the response body as the app read it, up to the cap, `totalBytes` exact; binary and event-stream
types not kept (`ResponseSkip`) but counted as the app reads them, so their record ends with the
body (exact size and duration, a failed download is `Failed`, an SSE stream stays `Pending` until
the app closes it); a failure after the response keeps it, with what arrived of the body; `durationMs` from the interceptor (or the first connecting call) to
the end of the body as the app reads it, not labelled "network". A body the app closes without
reading a byte is `Complete` with the body `"not read"`, one closed part-way is `Failed`,
`"cancelled"`, one read to its declared length and closed is `Complete`; a body nobody reads or
closes stays `Pending`, as with the Ktor plugin.

**Threads.** The app's request thread allocates the per-request state, copies body bytes below the
cap as its own reads move them (one bounded array per body, allocated by the first copy and sized
to the cap when `Content-Length` says the body reaches it) and queues two writes
(`store.submitDeferred`). The store's consumer thread builds the records (converts headers, trims
the array), applies them and writes the summary line in the same pass. The main thread runs only the provider; if an app reads a body
on the main thread, its bounded copy runs there, as with the Ktor plugin.

**Not recorded:** shaded or relocated OkHttp, code outside the APK (Play services dynamite modules,
code loaded at run time), Cronet's native API, WebView, `DownloadManager`, native HTTP stacks,
processes other than the main one, requests made before the provider runs, `URLConnection`s
obtained by reflection or through a `MethodHandle` constant, WebSocket frames.

**Release.** Non-debuggable variants get `hopi-android-noop` (`HopiAndroid`, both values false, no
dependency, no manifest entry) and no instrumentation. `:sample:androidApp:verifyReleaseUsesNoop`
proves it on the release APK, and the plugin's TestKit tests (`:gradle-plugin:hopi-gradle-plugin:functionalTest`,
part of the root `check`) build a fixture app with AGP and check that its debug APK is instrumented
and its release APK untouched, and that an app without OkHttp builds and is instrumented.

**Measured** (2026-10-24, API 35 emulator, debuggable builds against the same app built
with `hopi { enabled = false }`, interleaved; details in the design doc): the calling thread's CPU
per small request +85 to +95 µs (OkHttp 259 to 345 µs, `HttpURLConnection` 116 to 208 µs), wall time
per small loopback request +350 to +390 µs (about 1.1 to 1.5 ms); a 300 KB body copied to the 256 KB
cap +250 µs (OkHttp) and +170 µs (`HttpURLConnection`) of CPU; cold start 1200 ms median against 1152 ms for the whole debug
Hopi (Ktor plugin, UI, bridge and capture); the provider itself 5.4 ms median (2.9 to 15.4 ms) on a
debuggable build, most of it loading core's and Kotlin's classes, above its 1 ms target (open). Raw
data in `benchmark/android-capture/2026-10-24/`. After the hardening pass (records built and logged on the store's
consumer thread; design doc, "Hardening"), with no logcat reader during a
block: wall per small request +68 to +84 µs (was +135 to +168 in the same session), the calling
thread's CPU +65 to +74 µs (was +77 to +91), process CPU +183 µs (was +460). The calling-thread
target of 30 µs is not met: about 25 µs of it is the futex wake of the parked store thread. Those
requests ran back to back, which hid a second wake: a spaced request also woke the parked store
thread at its start. Since 2026-10-25 a start wakes it only while a viewer is attached (design doc,
"Review fixes", which has the spaced and back-to-back numbers). Speed fixes (2026-10-25, raw data in the ignored
`build/evidence/speed/fixes/`): with no viewer attached a terminal write only marks the work while
the store's consumer dozes in its 50 ms batch window (Store, above), recorders queue themselves
(no `Deferred` or lambda per hand-off), the `application/json` type skips the `ResponseSkip` scan,
the interceptor list view is cached for the last four clients (so an app whose API and image clients interleave still hits), the pass-through header is scanned for only once the
Ktor plugin used it, and the `URLConnection` record's URL text is made on the store's thread.
Back to back on the same emulator, capture minus noop: the calling thread's CPU +17 to +30 µs for
OkHttp (was +49 to +65) and +23 to +27 µs for `HttpURLConnection` (was +67 to +76), median wall
+9 to +56 and −27 to +26 µs (was +52 to +83 and +69 to +103; noise noop2 − noop −15 to +43 and
−26 to +15), the store thread 0.07 timeslices and about 60 µs per request (was 1.08 and 92). Live
tail latency, with the viewer attached and so unbatched, stays at p50 6.2 ms, max 17 ms.
Re-measured after the follow-up fixes (the apply barrier, the crash flush, the four-client list
cache, the method looked up once per request for rules; raw data in the ignored
`build/evidence/speed/final/`), 4 rounds of noop / before / after / before + 10 rules / after + 10
rules / noop2: the calling thread's CPU, after − noop, +16 to +26 µs for OkHttp (median +21) and
+26 to +31 µs for `HttpURLConnection` (median +28.5; before, the same tree without these fixes,
+24 to +31), noise noop2 − noop −5 to +5 and 0 to +4 (on 2026-10-27, after the bug fixes, 8
rounds: medians +24 and +22.5 µs; android-capture.md, "Review fixes"). Median wall +9 to +19
and +14 to +29 µs, against a noop2 − noop band of −51 to +9 and −29 to +18: still about 10 to
30 µs above the noise, which is roughly the calling thread's own added CPU, so the wall target
cannot be met while that CPU stays above the band. 10 enabled, non-matching rules add −6 to +6 µs (median +2) on OkHttp,
inside the bench's CPU noise, and +6 to +19 µs (median +7.5; before +7 to +8) on
`HttpURLConnection`. `hopi-store` 0.23 to 0.35 timeslices and 226 to 251 µs on-CPU per counted
request (both stacks), unchanged. Idle: every `hopi-*` thread 0 µs and 0 context switches over
60 s with no client, a tail, the desktop's two connections, 10 rules, and 10 rules plus a tail.
Live tail latency (200 spaced requests): OkHttp p50 6.17, p99 10.2, max 11.2 ms (before 6.28 / 9.05
/ 9.60), `HttpURLConnection` 6.11 / 8.83 / 9.01 ms (before 6.04 / 8.78 / 8.80).

**Verified end to end** on the same emulator (`benchmark/android-capture/run.py e2e`): the fake SDK's
nine request shapes, `URL.readText()` and a Ktor request through the OkHttp engine each recorded
exactly once (summary log and `hopi list`/`get` over the bridge, matched against the dev server's
log), with the right source, the SDK's own headers and the request bodies the server received; the
noop build sends the same requests and records nothing.

## Rules (`hopi-core` engine; `hopi-rules`; every recorder)

A rule matches a request and changes what happens to it: **mock** (a response made up in the app,
no network), **fail** (`timeout`, `reset`, `unknown-host`, `cancel`, as the source's own exception
or `NSError`), **delay** (before the request and/or before the response, each 0..120 s, optional on
any rule) or **edit** (method, host, port, path, query, headers, body of the request; status,
headers, body of the response). Replay (re-sending a recorded request) was removed from the
product; it is in the backlog with no priority (docs/ROADMAP.md, "Replay (no priority)"). The full
design, the reasons and the rejected alternatives are in `docs/design/rules.md`; this is the
contract.

**Engine** (`dev.koga.hopi.core.rules`, all `@HopiInternalApi`). `RuleEngine` holds the rules as
added and their compiled form in one immutable `RuleState` behind one atomic reference; a mutation
copies, validates, compiles and publishes with a compare-and-set loop. `RuleState.EMPTY` is
published whenever no enabled, unexhausted rule exists, so a recorder's
whole cost without rules is one volatile read and one reference comparison
(`if (state === RuleState.EMPTY)`). At publish each source gets only the rules it can apply
(`Capabilities`), so a rule never matches where it cannot be applied and nothing checks
capabilities per request. What a source cannot do of a rule is listed with it (`unsupported`,
`applies` false for an exclusion, true for a stated difference) for the sources the rule names,
or, when it names none, for those that can record on the platform (an expect/actual set: Ktor,
OkHttp and `HttpURLConnection` on Android; Ktor and URLSession on iOS; Ktor on a plain JVM).
The first enabled rule that matches and still has `times` left wins
(higher `priority` first, then the newest); a claim is one compare-and-increment of the rule's
shared hit counter, and the claim that exhausts a rule republishes the state without it.
Matching reads each stack's own request objects through a `RuleRequest` view, allocated only when
the state is not `EMPTY`. Globs are compiled at `add` (two-pointer matching, patterns at most 256
chars). Limits: 64 rules, 1 MiB per body, 8 MiB of rule bodies in all, 32 headers per side, 8
query and 8 header predicates. On iOS each publish also hands the URLSession
rules, compiled to C structs, to the Objective-C library synchronously (`hopi_us_rules_publish`),
whose matcher is a second implementation checked against the same JSON vectors as the Kotlin one
(`commonTest` resources); the hit counters live in C memory there, shared by both sides.

**Who applies a rule: the recorder.** Exactly one layer records each request (`PlatformCapture`'s
one-record rule), and only that layer reads the rules for it: the Ktor plugin returns before
reading them for a request a platform recorder covers, and the interceptor and the wrappers apply
nothing to a request the plugin keeps (`X-Hopi-Pass-Through`). A rule can never be applied twice,
and a record's mark always describes what its recorder did.

| | Ktor plugin | OkHttp | `HttpURLConnection` | URLSession |
| --- | --- | --- | --- | --- |
| mock, fail | yes | yes | yes | yes; not background sessions; not sessions created before `start()` (`URLSession.shared` excepted) |
| delay before request | yes | yes | yes | yes (a deferred `resume`; adds to the session's timeouts) |
| delay before response | yes | yes | yes | with a mock or fail only |
| edit request | yes | yes | yes | yes, for tasks from the hooked factories; a body only for `HTTPBody` and upload-from-data |
| edit response | yes | yes | yes | **no** |

Where it applies: the Ktor plugin at `HttpSendPipeline.Monitoring` (an edit rewrites the builder
before the record's snapshot; a mock builds the call without calling the engine); OkHttp in the
instrumented application interceptor (a mock answers without `proceed`; a before-response wait
checks for cancel every 50 ms, only while a delayed request waits); `HttpURLConnection` in the
wrappers (a mock never connects the delegate; a URL edit builds a new delegate with the app's
`SSLSocketFactory` and `HostnameVerifier`); URLSession in `HopiUSRuleProtocol` (mock and fail,
delivered on the run loop of the thread that called `startLoading`, never cached) and the factory
and `resume` hooks (edits, delays).

**Marks.** A ruled record carries `ruleId` and `ruleApplied` (see Data model) and holds the request
as sent and the response as delivered to the app. A mock's record has
the recorder's `source`, `protocol` null and a duration that includes the delay; a fail's is
`Failed` with the exception the app saw.

**Security.** No action contacts the network outside the app's own stack: a mock and a fail
contact nothing; an edited request goes through the app's client or session with its own TLS,
certificate pinning, verifier, proxy and challenge delegate. Hopi offers no action that changes
trust, a verifier, a proxy or a challenge answer. The scheme cannot change; an edit that changes
the origin removes `Authorization`, `Proxy-Authorization`, `Cookie` and every redacted header
unless it sets them itself. Mocks and response edits never set a cookie (`Set-Cookie` rejected)
and never enter an HTTP cache (OkHttp's and `HttpURLConnection`'s caches sit below Hopi; the
URLSession protocol answers with `NSURLCacheStorageNotAllowed`; on Ktor a ruled response carries
`Cache-Control: no-store` when the client has `HttpCache`). Rules, bodies and blobs live in memory
only: nothing is written to a file, preferences, `NSUserDefaults`, the proof file or `bridge.json`,
and no rule comes from a file, an intent, a deep link, a launch argument or an environment
variable; the only ways in are the authenticated bridge, `HopiRules` and the in-app screen.
Restarting the app clears every rule.

**Observable differences while a rule applies**, stated rather than hidden: `task.originalRequest`
is the edited request; `configuration.protocolClasses` lists Hopi's class; a delayed URLSession
task reads `Suspended` during the delay and its timeouts start after it; an OkHttp mock has no
handshake and skips the cache and network interceptors; a mocked 3xx, 401 or 407 is not
followed by OkHttp, `HttpURLConnection` or URLSession themselves (their follow-up logic is below
Hopi) and is followed by a Ktor client on any source (Ktor's is above it); an `HttpURLConnection`
mock has no certificates; a mocked URLSession upload reports no send progress.

**Switch.** Each recorder has `rules: Boolean = true` (`HopiConfig`, `HopiUrlSessionConfig`, the
Gradle DSL's `hopi { rules = … }`, generated into `HopiGeneratedConfig`). False means that
recorder never reads the rule state and, on iOS, injects no protocol and hooks no factory. The
engine and `HopiRules` stay; `hello` says `"rules":false` when every recorder that started has it
off, and the bridge then answers rule commands `UNSUPPORTED`.

**Surfaces.** Bridge commands (Agent access, "Wire protocol"); the CLI's `mock|fail|delay|edit add`,
`rules ls|rm|clear|enable|disable|reset` (Agent access, "CLI"); `HopiRules` (Consumer API); the
desktop viewer's Rules panel (Desktop viewer); the in-app rules list and "Mock this response" (UI).

**Compatibility.** The bridge's `"protocol"` stays 1, so a current CLI keeps working with a new
app and the other way round. A new host sends rule commands only to an app whose `hello` said
`"rules":true` (older apps omit it: "this app's Hopi does not support rules", exit 4); an older
app answers a rule command `BAD_REQUEST` ("unknown cmd", or "malformed JSON object" for a nested
one) without changing its state. A new app answers a `replay` line the same way: the command was
removed (docs/ROADMAP.md, "Replay (no priority)"). `{"type":"rules"}` notices go only to a `tail`
that asked for them, so an older client never sees an unknown line type, and the new record fields
are additive (older decoders ignore them; a new decoder reads their absence as null). On Android the generated
`HopiGeneratedConfig` gained the `rules` argument, so the plugin and `hopi-android` must come from
the same Hopi version, as they always do (the plugin adds the runtime itself).

**Release.** The engine is in `hopi-core`, which no release build contains: `hopi-rules-noop`
depends on nothing, the Gradle plugin swaps `hopi-rules` for it in non-debuggable variants (the
third twin pair, checked by its functional test), `:sample:androidApp:verifyReleaseUsesNoop`
expects exactly the four noops (`:hopi-rules-noop` included) and `verifyDebugCapture` requires
`RuleEngine` in the debug APK, and `:sample:shared:verifyIosReleaseExcludesCapture` fails on any
`HopiUSRuleProtocol` or `_hopi_us_rule` symbol and the rule queue's label without the flag and
requires them with it. `:checkNoopParity` covers the `hopi-rules` pair.

## Performance monitors (`hopi-core` model; `hopi-perf`)

Design: docs/design/perf-monitors.md. Built: the core model, its JSON and bridge commands, the log
lines, `hopi-perf`/`hopi-perf-noop` with the Kotlin API, the Android monitors, the iOS monitors,
the surfaces (CLI, `hopi-connect`, the desktop Perf panel, the in-app HUD and Perf tab), and the
sample's perf lab and bench (see "Sample app"), verified on the Android emulator and the iOS
simulator. Measured costs (emulator, debug build; design, "As built", stage 4): nothing started is
identical to the noop build (0 CPU, 0 wakes idle); frames add nothing measurable on the main
thread; hangs' Looper printer adds +6.2 to +6.5 µs per main-thread message and +29 µs per
`Dispatchers.Main` resume (the framework formats the dispatch line for any printer) and nothing
while idle; the heartbeat adds nothing per message and, idle, 8 wakes per second on each of its two
threads at 250 ms (one beat per `threshold / 2`; 7.8 to 7.9 each on the emulator, 2026-10-27, and
4 each before its cadence was fixed) with 110 to 160 ms of watchdog CPU per minute; the sampler
wakes once per interval at about 1 ms of CPU per tick.

- **Off is free.** Nothing runs until a monitor starts: no thread, no listener, no lifecycle
  callback, no monitor class loaded. `HopiPerf.install` (or, on Android, `hopi-android`'s provider
  when the plugin has `perf { enabled = true }`) registers one `PerfController` in core's
  `PerfHook` and allocates the model (about 120 KB of primitive rings). An app without `hopi-perf`
  only ever loads `PerfHook` (the bridge's `hello` reads it).
- **Model** (`dev.koga.hopi.core.perf`, all `@HopiInternalApi`): series rings of primitive arrays
  (`frames`, `cpu`, `memory`, 1200 points), one event ring per kind (jank 1000, stalls 1000, hangs
  100, memory events 200, the previous ANR 1), process-wide `seq`, summaries since start
  (frame-time histogram for p50/p90/p99, hang count/total/worst, CPU average/max, resident
  min/max). Frames are bucketed per second with no timer: only a second strictly in the past is
  closed (by a later frame, the sampler's tick or a reader). Listeners get closed seconds, samples
  and events, never per-frame writes.
- **Hang detection** is a pure state machine (`HangDetector`) over a packed 64-bit busy state
  (`BusyState`: 24-bit sequence, 40-bit start), driven by `HangWatchdog` on `hopi-perf-hang`:
  untimed while the main thread is idle, one doze of one threshold after a unit, timed to the
  unit's deadline while busy; stacks at 1x/2x/4x, discarded when the sequence moved during the
  capture; an oversleep beyond one threshold moves the start (no false hang from a frozen
  process); a detection under a debugger takes no stack and is not counted. The detector and
  watchdog read the busy state through `MainBusy`, which `BusyState` implements in Kotlin
  (Android) and `hopi-perf` implements over C-owned state on iOS; a platform whose stacks are raw
  addresses (`HangPlatform.symbolicate`) has the event recorded and logged raw first, and named
  only once the hang's unit ended (iOS `dladdr` takes the dyld lock, which a hung main thread may
  need to finish its unit). Under a remote lease the watchdog still parks before its idle sleep
  (the lease only bounds it), so a hang that starts while a lease runs is seen.
- **Frames, Android.** A frame's own time is `TOTAL_DURATION` less its wait before it started
  (`VSYNC_TIMESTAMP - INTENDED_VSYNC_TIMESTAMP`), and a frame that started late is held to the last
  on-time frame's `DEADLINE` (API 31+; the refresh rate's interval below): read from the intended
  vsync, a late frame carries the wait in both, and frames that each block 100 ms read as 1 janky
  frame in 30 on the emulator. Janky over twice the deadline, slow over it, frozen over 700 ms.
- **Android** (`hopi-perf` androidMain): the Looper printer set and cleared by posted messages (the
  stop message clears it only if Hopi's printer dispatched it), the input bracket (a delegating
  `Window.Callback` per started activity, removed only when still on top), FrameMetrics on
  `hopi-perf-frame` (nice 5; attach only with a decor view, software windows reported), the
  sampler `hopi-perf-samp` (nice 5; `Process.getElapsedCpuTime`, Hopi's own share from each perf
  thread's CPU clock before it parks, heaps, RSS from a kept-open `/proc/self/statm`, ART GC
  stats; `/proc/self/task` only while watched), `ComponentCallbacks2` while memory runs, PSS on
  demand at most once per 10 s, the previous run's ANR (API 30+) read once on the watchdog thread.
- **iOS** (`hopi-perf` iosMain plus the native library `src/nativeInterop/objc/hopi_perf.m`, built
  per target and embedded in its cinterop klib like core's URLSession library): two
  `CFRunLoopObserver`s on the main run loop for the common modes, written in C (begin at the lowest
  order for `AfterWaiting | BeforeTimers | Exit`, end at the highest for `BeforeWaiting`), publish
  the same packed busy state as Android's and enter Kotlin only for a unit over twice the frame
  deadline (a `stall`, frames tier 1; the deadline from `UIScreen.maximumFramesPerSecond`, capped
  at 60 Hz without `CADisableMinimumFrameDurationOnPhone`). Hangs add the watchdog
  `hopi-perf-hang` (an `NSThread` at the user-initiated QoS running core's `HangWatchdog`, parked
  in C calls on a semaphore of its own); a capture suspends the main thread, reads its registers,
  walks frame pointers within the stack's bounds (Swift async flag cleared, return addresses
  stripped with an explicit pointer-auth mask) and resumes it, with nothing allocated, locked or
  run in Kotlin in between; `dladdr` names the addresses afterwards. The sampler `hopi-perf-samp`
  (utility QoS) reads `task_info` (CPU of terminated and live threads, `phys_footprint`),
  `os_proc_available_memory()` and Hopi's own share; the per-thread list is `task_threads` while
  watched, every port deallocated. Memory adds the memory-warning notification and a
  memory-pressure dispatch source. Frames tier 2, a `CADisplayLink` writing the frames series with
  its callback cadence, runs only while frames runs and a viewer asks for it
  (`PerfController.watchCadence`, counted; `perf.tail` with `"fps":true`). A launch argument
  `-hopiPerf frames,hangs` is read once by the first `install`. PSS does not exist there.
- **Owners and leases.** A monitor runs while code, a lease (`perf.start`, `ttlSec` 600 by
  default, 0 no expiry) or a `perf.tail` session holds it; a lease's expiry rides an existing perf
  thread's timed wait (or one delayed message on the frames thread when frames runs alone).
- **Log lines** (`emitPerfLogLine`): logcat tag `HopiPerf`, os_log category `perf`, JVM stderr;
  `{"hopi":1,"perf":"hang|jank|memory|anr|sample|notice",…}`, never an `"id"`; at most 5 per
  second (excess counted in a `notice`), jank once per second with a janky frame, `sample` every
  10 s.
- **Bridge.** `hello` gains `"perf"` and `"perfControl"` (absent on older apps, read as false).
  `perf.start`, `perf.stop`, `perf.status`, `perf.snapshot`, `perf.event`, `perf.tail` are all
  accepted only after `hello` (they count as write commands); `UNSUPPORTED` without a controller,
  for a monitor the platform lacks, or a start/stop with `remoteControl = false`; `LIMIT` for a
  value out of range. Replies: `perf.status`, `perf.series`, `perf.frames`, `perf.summary`,
  `perf.event`, `perf.sample`, `perf.threads`, `perf.pss`, then `end` for a snapshot.
- **Release.** `hopi-perf-noop` depends on nothing; the plugin adds `hopi-perf` to debuggable
  build types and `hopi-perf-noop` to the others when `perf { enabled = true }`, and substitutes the
  pair like the other twins. `verifyReleaseUsesNoop` expects `:hopi-perf-noop`, forbids
  `dev.koga.hopi.perf.internal` and any Hopi call of `Window.addOnFrameMetricsAvailableListener`
  or `Looper.setMessageLogging`; `verifyDebugCapture` requires `PerfEntry` and both calls.
  `:checkNoopParity` covers the pair.
- **Surfaces.** `hopi perf start|stop|status|snapshot|tail` and `hopi hangs` (see "CLI");
  `hopi-connect`'s `PerfClient`, decoders and `RemotePerf` (see "Desktop viewer"); in the app, a
  Perf tab in `HopiScreen` and a HUD in `HopiLauncher` (see "UI"). Each reads the model only while
  shown: a listener registered while composed or streaming, removed after, and no timer anywhere
  (the HUD and the tab update when the model hands out a closed second, a sample or an event; both
  also when the running set changes, which `PerfController.addRunningListener` reports, so a
  stopped monitor's figures go at once and with none running the HUD reads "perf: off").
  The per-thread scan is counted across viewers (`watchThreads`), like the iOS cadence.

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

A failed transaction adds `"error":"…"` (at most 512 chars, cut at a code point boundary) after
`state`; its `code` is null unless the response arrived before the failure (then it is the status,
and `resBytes` what arrived of the body). A transaction whose recorder is known adds
`"source":"ktor"` (or `"urlsession"`, …) right after `state`, before `error`; it counts toward the
line budget like the other fixed fields. A redirect inside the client adds `"finalUrl"` after
`source`.

The lines are written by a store event listener, on the store's consumer thread, in the same pass
that publishes the terminal snapshot: no thread of its own, no wake per line, and no line lost to a
slow collector (it used to collect `events`, which woke a coroutine per transaction). The line is
built in one reused builder and measured only when it may exceed the budget (more than a third of
it in chars); on iOS it is encoded once by the runtime's native encoder and handed to os_log
pinned, with its length (`hopi_os_log_bytes`, `%{public}.*s`), instead of a C string per line.
Measured per line, Kotlin/Native debug: 11.6 → 9.0 µs for the line plus its UTF-8 bytes (the
`String.cstr` conversion that went with it on the device, 23 µs in the profile, is gone); JVM
0.54 → 0.50 µs. On Android `Log.i` (one logd socket write) is most of what is left.
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
  `dev.koga.hopi.core.internal.oslog`) is only `hopi_os_log_line` and `hopi_os_log_bytes`.
  Agent: `xcrun simctl spawn booted log stream --style ndjson --predicate 'subsystem == "dev.koga.hopi"'`.
- JVM: stderr.

Lines stay within a per-platform byte budget: 3999 UTF-8 bytes on Android and the JVM (logcat
limit), 1000 on iOS. The iOS value is measured: `IosAgentBridgeTest` emits summary lines of exact
sizes through the shim on an iPhone 16 simulator (iOS 18.2, Xcode 16.2), read back with
`xcrun simctl spawn booted log show --last 5m --style ndjson --predicate 'subsystem == "dev.koga.hopi"'`.
Lines of 1000 to 1015 bytes arrive intact; every longer one (1016, 1017, 1020, 1024, 2165 bytes)
arrives as its first 1015 bytes followed by `<…>`, which is no longer JSON. The budget keeps 15
bytes of margin below that cut for other OS versions. Physical devices have not been measured. A URL, a final URL and an error that do not fit
share the budget (each an equal share, a shorter one leaving the rest to the others; without a
final URL the URL and the error split as before), are cut at code point boundaries, and the line
gains `"urlTruncated":true`, `"finalUrlTruncated":true` and/or `"errorTruncated":true` as its last
fields. Unpaired surrogates are written as `\u` escapes, so a
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
(64 lines), so a client that reads slowly only slows its own replies. The writer is a plain
blocking loop: it takes from its queues with `tryReceive` and, when both are empty, blocks on a
semaphore (`WakeSignal`) that every producer releases after queueing (a reply, a live event, a
close); it never runs a coroutine event loop, which on Kotlin/Native woke on a keep-alive timer
while parked. A live summary line is built in a builder the writer reuses and encoded straight
from it. `shutdown` unblocks the other
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

Perf commands (docs/design/perf-monitors.md, "Bridge"; see "Performance monitors" above), after
`hello`, never pipelined:

```json
{"cmd":"perf.start","monitors":["frames","hangs"],"hangMs":250,"intervalMs":1000,"ttlSec":600}  // → {"type":"perf.status",...}
{"cmd":"perf.stop","monitors":["frames"]}            // none named: all → {"type":"perf.status",...}
{"cmd":"perf.status"}                                 // → {"type":"perf.status","platform","available","remoteControl","running":{…}}
{"cmd":"perf.snapshot","series":["cpu"],"points":120,"events":true,"since":0,"threads":true,"pss":false,"fullStacks":false}  // → series, open second, summary, events, threads, pss, status, end
{"cmd":"perf.event","seq":12}                         // → {"type":"perf.event",...all stacks} | NOT_FOUND
{"cmd":"perf.tail","since":0,"samples":true,"fps":false,"threads":false,"start":["hangs"]}  // → status, backlog events, then live events, frames seconds, samples; "fps": iOS tier 2 while the session lasts
```

Rule commands (docs/design/rules.md, "Bridge"), accepted only after `hello` on the same connection
(`BAD_REQUEST`, "hello first") and sent by `hopi-connect` only after it verified `hello` (never
pipelined with it); `UNSUPPORTED` when `hello` said `"rules":false`:

```json
{"cmd":"rules.add","rule":{...}}          // → {"type":"rule",...,"id":N,"hits":0,"exhausted":false,"unsupported":[{"source","reason","applies"}]}
{"cmd":"rules.list"}                      // → {"type":"rule",...}* then {"type":"end"}
{"cmd":"rules.update","id":3,"enabled":false,"priority":5,"times":10,"resetHits":true}  // → {"type":"rule",...}; "times":null clears it
{"cmd":"rules.remove","id":3}             // → {"type":"ok"} | NOT_FOUND
{"cmd":"rules.clear"}                     // → {"type":"ok","removed":N}
{"cmd":"blob.put","blob":1,"offset":0,"data":"<base64, ≤3072 chars>"}  // → {"type":"ok","blob":1,"size":N}
{"cmd":"tail","since":0,"rules":true}     // as `tail`, plus {"type":"rules","rev":N} whenever the rule set changes
```

`hello` gains `"rules":true|false` (absent on an older app, which a host reads as false). Rule
commands parse with a bounded tree parser (depth 6, the same 4096-char line); every other command
stays flat, so a nested value in one is still `BAD_REQUEST` "malformed JSON object". Replies echo
bodies as `{"bytes":N}` only and header values through the store's redactor. Blobs (at most 2 of
1 MiB per session, in order by `offset`, any failure discarding the blob) live in the session and
die with it. New error codes: `LIMIT` (a limit of the design's table) and `UNSUPPORTED`.

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
hopi perf start  --app <id> [--frames] [--hangs [--hang-ms 250]] [--cpu] [--memory] [--interval 1s] [--for 10m | --no-expiry]
hopi perf stop   --app <id> [--frames] [--hangs] [--cpu] [--memory]   # none named: all, every owner
hopi perf status --app <id> [--json]       # one line per monitor: setting, owners and lease, counts
hopi perf snapshot --app <id> [--json] [--threads] [--pss]
hopi perf tail   --app <id> [--json] [--since SEQ] [--samples] [--fps] [--threads] [--start hangs,frames]
hopi hangs       --app <id> [--json] [--since SEQ]   # every hang with its full stacks
```

The perf commands (docs/design/perf-monitors.md, "CLI") check their flags before connecting
(ranges as the app's `LIMIT`: `--hang-ms` 100..10000 with `--hangs`, `--interval` 250ms..10s with
`--cpu` or `--memory`, `--for` 1s..60m, never with `--no-expiry`) and send nothing before `hello`
was verified and said `"perf":true` (`PerfClient`; exit 4 otherwise). `--json` prints the bridge's
lines as received (`perf start|stop|status`: the `perf.status` line; `snapshot`: every line before
`end`; `hangs`: the hang `perf.event` lines of a snapshot with full stacks). Human `status` adds
the counts of a one-point snapshot (`perf.status` carries none); an Android frames monitor that no
window reports yet prints how to attach it. `perf tail --start` holds its monitors exactly as long
as it runs.

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
app's), the other serves `get`, `list` and `clear`. A body crosses the bridge only for the selected
transaction (`select`, fetched again when a pending selection completes) or an explicit `fetch`;
a later summary of a fetched snapshot (a re-tail replays the backlog) never replaces its bodies.
A `SLOW_CLIENT` reply (`SlowClientException.since`) re-tails at once from that `since` on the same
endpoint. That re-tail, and a connect that finds the same process, then ask the app about each row
still pending here that the resumed backlog has not ended by its turn, the oldest 64 (the rest wait
for their own summary or the next resume; resumes in the meantime coalesce into one more; since
2026-10-27): a `list` of one summary from just below the row's id, so no headers or bodies cross.
A row the app no longer has (its recorder withdrew it while this viewer was away or behind, or it
was evicted or cleared) gets no summary again, and would stay pending and hold the resume point
below it, so it ends as gone; an end the app still records for it replaces that row.
Anything else that ends a connection closes both and the endpoint and reports
`Disconnected`; the caller connects again on an event. A connect that finds another process (a
SHA-256 of the token and the Android proof, both new with every app start, compared in memory and
never shown) starts a fresh store, because the app's ids restart at 1, and drops the selection,
whose id now means another transaction. A `get` answered `NOT_FOUND` marks the row's bodies
`CapturedBody.gone` and ends a pending row as failed ("gone from the app"), which an end the app
still records (a request it evicted or cleared in flight) replaces; a `get` answered pending after
the tail brought the terminal summary is sent once more. `clear` clears this store up to the
reply's `through` (all of it for an app that sends none). Nothing polls: one tail thread blocked on
its socket, one request thread blocked on its queue.

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
store does not hold, a rule id the app does not have, a perf event it no longer holds), 4
unsupported (rules off or absent in the app's Hopi, no perf monitors in it, a monitor the platform
lacks, or a perf start or stop with `remoteControl = false`). A rule the app rejects (`BAD_REQUEST`,
`LIMIT`) is exit 1, as is a perf value out of range.
The rule commands (`mock|fail|delay|edit add`, `rules ls|rm|clear|enable|disable|reset`)
are listed in `hopi --help` and docs/design/rules.md ("CLI"). Every error is one actionable line on stderr, `hopi: <message>`.
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
- `PerfPanel` (⌘P, menu Perf; docs/design/perf-monitors.md, "Desktop viewer"), beside the list
  like the rules panel, over `RemoteSession.perf` (`RemotePerf` in `hopi-connect`). Nothing perf
  is sent until it opens; then a `perf.tail` (samples, frame seconds, events; the per-thread list
  only while its switch is on) streams on a connection of its own and one `perf.snapshot` on the
  request connection backfills the charts (1200 points kept). A switch on adds the monitor to that
  tail's `start`, so the app holds it while the panel is open and drops it when the panel or the
  viewer closes (no lease to renew); the new stream opens before the old one closes, so a monitor
  both name never restarts. A switch off is `perf.stop` (every owner). Three charts drawn with
  `Canvas` (frames per second with janky bars, CPU %, memory with its events as dashed ticks;
  last five minutes, monochrome), each monitor's owners (a lease shown with its end), the hangs
  with their stacks (expand, copy as text). Switches are hidden for an app with
  `remoteControl = false`.

**Data.** A `RemoteSession` per target: its own `HopiStore` (2000 entries, 256 MB), keyed by
the app's ids, with `Redactor.Default` on top of the app's redaction. The panes select through
`Viewer.select`, so only the selected transaction's body is fetched (`get`); list rows are
summaries and show "Loading body…" until then (a resume's check of rows still pending is a
one-line `list` each, without bodies). A new app process (another token or proof) gets
a fresh store and the selection is cleared, since the ids restart.

**Connections.** Two per app, as `RemoteSession` opens them (a streaming `tail`, and one for `get`
and `clear`) on one endpoint, so one adb forward on Android, plus a third for a `perf.tail` only
while the Perf panel is open. Every attempt is a `RemoteSession.connect`, which closes the previous
sockets and forward first, so the app never serves more than these three of its 8 slots. Disconnect, a new target and closing the window close
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
(export), ⌘R (reconnect), ⌘L (the Rules panel). Ctrl replaces ⌘ outside macOS. The menu bar lists
the same commands without binding the keys a second time; while a field of the Rules panel has
focus, the arrows, Enter and Esc stay with it.

**Rules.** `RulesPanel`, 420 dp beside the list (⌘L or the Rules menu), reads only
`RemoteSession.rules`: one row per rule (switch, match, action, hits and `times`, label, one line
per `unsupported` entry the app reported: "not applied on urlsession: …" in full contrast for an
exclusion, a muted warning for a stated difference; "inactive" after the hits when every source
the rule names excludes it), Update (priority, times, reset hits: `rules.update` changes
nothing else, and bodies and header values come back as sizes and redacted, so changing a match or
an action is remove and add), Remove, Clear. Forms for mock, fail, delay and edit
(`RuleForm`, which validates what it can before anything is sent) take headers as a table and a
body from a text area or a file (at most 1 MiB, uploaded as a blob when it does not fit a line);
the app's refusal is shown exactly as sent. The detail pane adds "Mock this response" (a mock form
prefilled with the record's exact request and `fromTransaction`, so the body is copied inside the
app and never crosses the bridge). `RemoteSession` (hopi-connect) lists the rules on connect and again on
each `{"type":"rules"}` notice of its tail (asked for only when `hello` said `"rules":true`), and
again when the tail brings a summary (or a `get` reply) whose `rule` mark the last list may not include, so the hits
shown are always the app's engine's (never counted on top of a list) and nothing polls: a burst of
ruled requests costs at most one `rules.list` in flight and one queued (docs/design/rules.md,
"Desktop viewer").

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
disconnected (on Android the two 5 MB body checks and the 500 POSTs' figure no longer test what
they name: ROADMAP.md, "Desktop viewer"). On iOS: the connection through `bridge.json`,
terminate and relaunch, live rows and a body fetched on selection.

## Sample app (`sample/`)

A Compose Multiplatform app for Android and iOS that makes real requests to a dev server on the
host, so the inspector, the log stream and the CLI can be exercised (and measured) end to end.

- **`sample/shared`** (KMP: `android`, `iosArm64`, `iosSimulatorArm64`; foundation only, no
  Material) holds everything both apps share. It selects `hopi-ktor`/`hopi-ui` or their
  noop twins with the `hopi.enabled` property exactly as in "Selecting real vs noop", and
  generates `HOPI_ENABLED` from the same property, so the iOS app says which one it was built
  with. On Android the plugin selects per variant whatever the property says, so the activity sets
  `SampleBuild.androidCaptureStarted` from `HopiAndroid.isStarted` before the UI; the Diagnostics
  caption and the evidence lines (`"build"` of the giant-JSON, perf-lab and perf-bench lines,
  `"real"` of the rules self-test's `done` line) read `SampleBuild` on both platforms. One
  process-wide Ktor client (`SampleHttp`; OkHttp on Android, Darwin on iOS,
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
  cap, before the client exists (force-stop the app first; on Android it reaches only the Ktor
  plugin, so OkHttp capture, which records the sample's Ktor requests, keeps the `hopi { }` cap:
  ROADMAP.md, "Desktop viewer"). The log goes to the `HopiSample` tag on Android and stdout on
  iOS (`simctl launch --console-pty`). The back gesture (ui-backhandler `BackHandler`) returns to
  the item list. iOS gets `MainViewController()`, a `ComposeUIViewController`; the static
  framework is `Shared`.
- **Perf lab and bench** (`sample/shared`, `PerfLab.kt`, `PerfBench.kt`; docs/design/perf-monitors.md,
  "Device verification plan" and "Performance plan"). `sample/shared` depends on `hopi-perf` or
  its noop in commonMain (api: the iOS framework exports it); on Android the plugin's
  `perf { enabled = true }` resolves it per variant and its provider registers it, on iOS
  `iOSApp.init` calls `HopiPerf.shared.install { agentBridge = true }` before the UI. Diagnostics
  opens the **Perf lab** screen: a full-width "Click burn" button (600 ms of spinning inside
  `onClick`, `PerfLab.clickBurn`), one button per step and "Stop every monitor". A run starts every
  monitor as code and injects, in order: 30 frames each blocked 20, 50 and 100 ms inside
  `withFrameNanos` (a state write per frame makes each one draw); main-thread units, each its own
  posted message or main-queue block, sleeping 200, 300, 1000 and 3000 ms (`PerfLab.hang`) and
  spinning 600 ms (`PerfLab.burn`); a Compose `Dialog` for 5 s; two threads `perf-burn-1`/`-2`
  spinning 5 s; a touched 64 MB `ByteArray` held 4 s, then 64 MB touched outside the managed heap
  (a bitmap's pixels on Android, malloc'd since API 26; `nativeHeap` on iOS) held 4 s; on iOS a posted
  `UIApplicationDidReceiveMemoryWarningNotification` (Android's trim levels come from the host).
  Each step logs one `{"perfLab":…}` line under `HopiSample` with its wall-clock window (the clock of
  Hopi's events), ending with `{"perfLab":"done"}`; the monitors stay on for the host to read.
  `--es perfVerify all` / `-verifyPerf` (or a comma list of steps) opens the lab and runs it at
  launch; `benchmark/perf/verify.py` drives it, takes its own references (gfxinfo framestats per
  jank step, `/proc/<pid>/stat` around the CPU step, `dumpsys meminfo`, `am send-trim-memory`), taps
  the click-burn button with `adb shell input tap` (input dispatch), reads `hopi perf snapshot` and
  `hopi hangs`, and checks each expectation. `--es perfBench <spec>` / `-perfBench <spec>`
  (`monitors=none|all|hangs,frames;n=10000;runs=3;mode=messages|idle`) starts the named monitors,
  then posts `n` main-thread units one after the other per kind (Android: a reused `Runnable`, a new
  lambda per message, a `yield()` on `Dispatchers.Main`; iOS: `dispatch_async` to the main queue, a
  `yield()`) and logs the median, p90, p99 and mean gap between unit starts, the main thread's CPU
  per unit and the GC count; `mode=idle` starts the monitors and does nothing.
  `benchmark/perf/bench.py` alternates arms in blocks (a second install with
  `-Psample.hopiCapture=false -Psample.applicationIdSuffix=.noop` is the noop arm) and
  `benchmark/perf/idle.py` reads every thread's CPU ticks and context switches over an idle minute.
- **`sample/androidApp`** (`applicationId` `dev.koga.hopi.sample`) hosts the shared UI in one
  activity, applies `id("dev.koga.hopi")` (with `agentBridge = true`), and depends on
  `sample/shared` and `sample/fakesdk`. The plugin makes its debug build real (the Ktor plugin, the
  UI, automatic capture) and its release build noop with no flag; `-Psample.hopiCapture=false`
  builds the debug variant as release is built (`hopi { enabled = false }`), the control arm of the
  capture A/B. **`sample/fakesdk`** is a Java-only Android library with neither the plugin nor any
  Hopi dependency, the stand-in for a third-party SDK: a static `OkHttpClient` with its own
  application and network interceptors (`enqueue` and `execute`), a subclass built by reflection, a
  `newBuilder()` client, `HttpURLConnection` GET, fixed-length POST and a 404 read through
  `getErrorStream`, `URL.openStream()` and a `URL::openConnection` method reference; it is compiled
  against OkHttp 4.12 and runs on the 5.3.2 Ktor brings. The Diagnostics button "Third-party SDK and
  URL.readText()" (`am start … --es capture all` at launch) runs it plus Kotlin's `URL.readText()`
  and logs one line per request and `HopiAndroid.isStarted`/`okHttpInstrumented` under
  `HopiSample`; `--es captureBench 'kinds=okhttp,urlconnection;path=/json;n=1000;warmup=1000'`
  runs the A/B workload (sequential GETs through the SDK's client and connection on one worker
  thread, logging the median and p90 wall time and the thread's CPU per request).
  `benchmark/android-capture/run.py` drives both (`e2e`, `ab`). The app also adds what only Android
  has, as a Diagnostics button and two intent extras. "Bridge self-check" connects to the app's own bridge as the app's
  uid and logs the `hello` reply under the `HopiSample` tag, its `proof` value replaced by
  `<redacted>`; `am start … --es selfCheck <process name>` runs it at launch (opening Diagnostics)
  against another app's bridge, and a second install built with
  `-Psample.applicationIdSuffix=.probe` (another uid) uses that to prove the bridge cannot be
  reached from another app. `am start … --es squat <process name>` makes that second install bind
  the other app's socket name first and answer with a forged `hello` (right app id, no proof) plus
  one forged transaction, to prove the CLI refuses a squatter. Both need the real bridge, which every
  debug build now has (the plugin selects the real modules; `-Phopi.enabled=true` is no longer
  needed on Android). `-Psample.largeHeap=true` sets
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
  `IOSApp.init` calls `HopiUrlSession.shared.start { … }` first thing (`agentBridge = true`, and the
  giant-body launch arguments `-maxBodyBytes`/`-maxTotalBytes`, because the Darwin client's requests
  are now recorded there, not by the plugin); the shared framework exports `hopi-urlsession` (or
  its noop, so the Release build compiles the same Swift). `ThirdPartySDK.swift` stands in for an
  SDK the app does not own: plain Swift, no Kotlin import, `URLSession.shared` with a completion
  handler, an ephemeral session with its own data delegate uploading JSON, and `data(for:)`,
  `upload(for:from:)` and `bytes(for:)`; the launch argument `-thirdPartySDK` runs it
  (`xcrun simctl launch booted dev.koga.hopi.sample -thirdPartySDK`). The XCTest target
  `iosAppCaptureTests`, hosted by the app, calls the five Swift async APIs and the SDK file against
  the dev server and reads each record through `SampleCaptureProbe` (a source directory of
  `sample/shared` compiled only with `-Phopi.enabled=true`); `sample/iosApp/scripts/capture-swift-test.sh`
  starts the dev server and runs it (not in the base check: it boots a simulator, and the
  Xcode build phase runs Gradle). An earlier Xcode 27.1 build of the Debug app did not launch on the iOS
  18.2 runtime (Kotlin/Native's `platform.CoreGraphics` binding referenced
  `kCGAdaptiveMaximumBitDepth`, which iOS 18.2 lacks, and dyld aborted before `main`), so the Swift
  tests ran on the iPhone 17 simulator, iOS 27.0. The current build launches on the iPhone 16
  (iOS 18.2) too (the perf lab ran there on 2026-10-26); the Kotlin/Native tests run on both.
  `CaptureVerification.swift` (launch arguments `-verifyCapture`, `-captureBench <spec>`,
  `-idleCPU S`) and `-ktorVerify` (`KtorVerify.kt`, iosMain) drive the end-to-end check in
  `benchmark/urlsession/e2e/`; the build setting `HOPI_ENABLED=false` (`xcodebuild ...
  HOPI_ENABLED=false`) builds the Debug app with the noop twins, for A/B runs at the same
  optimisation level.
- **Rules scenarios.** `sample/shared` depends on `hopi-rules` (or its noop, by `hopi.enabled`,
  as `api` so the Android app names it too; the iOS framework exports it for Swift).
  `RulesVerify.kt` (commonMain) runs the self-test over a list of `RulesTarget`s, each a way of
  sending a request named by the source that records it: for each target, a mock, every fail
  kind, a before-request delay, a delayed mock, a request edit (path `/missing` to `/echo` plus a
  header, which the echo shows), a request body edit, a response edit (expected unsupported, and
  the real response read, on URLSession) and `times = 1`, each rule limited to the target's
  source and to its own `rv=<target>-<scenario>` query key and removed again afterwards; one
  `{"rules":…,"ok":…}` line per scenario and a `{"rules":"done","failed":N}` line under
  `HopiSample` (stdout on iOS). The noop build answers `"ok":false` everywhere, the control.
  `runRulesTargets` instead sends a GET of `/json`, a GET of `/missing` and a POST of `/echo`
  tagged `rv=host-<target>` per target, for rules the host adds with the CLI or the desktop. Android (`--es rulesVerify all`, `--es rulesTargets all`, and
  the Diagnostics buttons "Rules self-test", "Rules targets"): the targets are the Ktor plugin (a
  request with its own `Accept-Encoding`, which the plugin keeps), Ktor's OkHttp engine (ruled by
  the interceptor), and the fake SDK's OkHttp client and `HttpURLConnection` (`FakeSdk.call`, still
  without any Hopi dependency); then `FakeSdk.pinned` checks that a client pinning the
  `verifyHttps` host to a key no server has fails the pin without a rule and under a request edit,
  and that a mock answers it without a connection (needs the emulator's network). iOS
  (`-verifyRules`, `-rulesTargets`, the same buttons): Ktor Darwin as the URLSession target from
  Kotlin, while `CaptureVerification.verifyRules` runs mock, fail (timeout, unknown host), request
  and response edits on `URLSession.shared` with a completion handler, a full delegate session,
  async `data(for:)` and a download task through `HopiRules.shared`, then the same pinning check
  with a session delegate that cancels every server-trust challenge (`-verifyRulesHttps <url>`).
  No Ktor-plugin target exists on iOS: with URLSession capture on, the Darwin engine is always
  ruled by URLSession.

`sample/devserver/devserver.py` (Python 3 standard library, bound to `127.0.0.1:8765`; `--nodelay`
sets `TCP_NODELAY`, for per-request benchmarks, since Python writes headers and body in separate
sends and Nagle's algorithm then holds each small keep-alive response about 40 ms; `/json` and
`/echo` ignore a query string, so callers can tag requests; `/echo` answers a GET too, the oracle
of a rule's request edit) serves those routes (`/slow?s=N` sends a chunked body over N seconds, one chunk every 5 s so no client read
timeout fires; `/items` keeps created items in memory until it exits and answers 400 to a body that is not
an item; `/giant?mb=N&shape=S&chunked=0|1` generates about N MiB of JSON while sending it, with
a Content-Length computed in advance or chunked; `POST /sink` reads and drops a body of any size; `/redirect?to=/path&code=302` redirects to a path on
the same server only)
and logs every request as one JSON line on stdout. The Android emulator reaches it with
`adb reverse tcp:8765 tcp:8765`; the iOS simulator shares the host's loopback, so it needs nothing.
The Android debug build's network security config allows cleartext to `127.0.0.1` only. Nothing
reaches an external network.

## UI (`hopi-ui`)

Compose Multiplatform, no Material dependency (foundation only), monochrome, dense, fast:

- **List:** newest first; method, status code, host + path, duration, size; Pending/Failed visible at
  a glance. Search by URL substring, filter by status class (2xx, 3xx, 4xx, 5xx, failed, pending,
  each chip with its count). Lazy list keyed by id, which keeps its first visible row in place when
  rows are added above it; so that new requests do not land above the visible area, a list at its
  top (first row, no offset) stays there as rows arrive, also when it comes back from a detail
  (`requestScrollToItem(0)` from a `SideEffect`, before the new rows are measured). A list scrolled
  away from its top, by hand or by a desktop selection moved from the keyboard, keeps its rows where
  they are. Rows that replace the list's instead of adding to them start at the top: after a Clear
  (the emptied list asks for it) and, in the desktop viewer, with the store of another app process
  (`HopiPanes` keeps a position per store). `transactions` is collected only while
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
  grows when another client installs the plugin; the store's `redactors` flow emits it, so the open
  detail and the list follow it at once, not at the next request). Truncation and skip reasons visible. Text is selectable
  (within the pieces on screen).
- **Actions:** copy cURL (core's `toCurl` with the store's `redactor`), copy URL, copy response
  body, share, clear. Copy cURL, copy URL and share read the store's `redactor` when they run, not
  the one the detail was formatted with, so what leaves the screen never lags a redactor that grew
  while the detail was open. Copy uses Compose's text clipboard. A clip crosses the same binder
  limit as an intent (past about 1 MB `setPrimaryClip` throws in the app), so copied text is cut at
  100 000 chars too, at a code point boundary, and the confirmation says so ("Response body
  copied (first 100k characters)"); a clipboard that still throws shows "… could not be copied"
  instead of crashing the app. The confirmation is a polite live region, so TalkBack and VoiceOver
  announce it. Share is the platform sheet (`Intent.ACTION_SEND` on Android,
  `UIActivityViewController` on iOS) with the method, URL, status, duration and cURL, cut at
  100 000 chars (the Android binder limit); the JVM has no share sheet, so the action is absent
  there.
- **Perf (with `hopi-perf`; docs/design/perf-monitors.md, "In-app"):** `HopiScreen` and
  `HopiLauncher` read core's `PerfHook` once; without a controller neither shows anything new. With
  one, the list's top bar gains a Perf button opening the Perf tab: a switch per monitor (started
  as code, `PerfOwner.Code`), the HUD's switch (and on iOS the cadence's), summaries, sparklines
  of the last 60 points, the per-thread list (`watchThreads` while shown), "Measure PSS" (off the
  main thread), memory events and the hangs with their stacks. The tab rebuilds its model on
  `Dispatchers.Default` when the model hands its listener a closed second, a sample, a hang, a
  stall or a memory event (never a frame or a jank event), when the running set changes (a CLI,
  a bridge or app code started or stopped a monitor), and after its own actions; nothing while
  the app is idle. While `HopiScreen` is composed, `PerfData.inspectorOpen` is set, so
  frames drawn under it are left out. The HUD is one line over the app ("58 fps · 4 jank · CPU 12%
  · 142 MB"; iOS: "worst stall 84 ms · 3 stalls/10 s", or "cadence 120/s" with the cadence on),
  toggled by a long press on the launcher button or the tab; its text is computed on the writer's
  thread (`HudMeter`) and published only when it changed, so it draws at most once a second and
  not at all while the app is idle; it drops a stopped monitor's figures at once (the controller's
  running set, `PerfController.addRunningListener`), and with none running it reads "perf: off";
  it takes no touch and never recomposes the button. On
  Android, a launcher composed with a controller registered stores its activity in core's
  `PerfLauncher` (a weak reference, cleared on dispose), and a frames or hangs start attaches to
  that window at once with one main-thread message. `hopi-ui-noop` is unchanged: the noop launcher
  has no button, so nothing to long-press.
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

**Rules.** A "Rules" button in the top bar opens a list read from `RuleEngine` only while it is
composed (it refreshes when the rule set or the store changes): one row per rule with its match
(header values redacted), action and hits, an on/off switch, remove, and "Remove all". A source
that will not apply the rule has a line of its own ("not applied on urlsession: …", full contrast),
a stated difference stays a muted warning, and a rule whose every named source excludes it reads
"inactive" after its hits (its switch stays as set); with no sources named, only the sources this
platform can have are checked. A list row of
a ruled record shows one badge, the strongest action first (`MOCK`, `FAIL`, `EDIT`, `DELAY`); the
overview shows "Rule: #3: delay, mock". The detail adds "Mock this response" (an enabled rule
matching the record's exact method, scheme, host, port and path, built in the app from the record
with `fromTransaction` off the main thread (it may decode the body), `times` unset, Undo offered
for 4 s), disabled with the reason when the
response body is not held in full, is held in an encoding a mock does not decode (an OkHttp or
`HttpURLConnection` record under br, zstd or stacked codings; gzip and deflate are decoded), or when
every recorder has `rules = false`. There is no rule editor on the phone. `HopiScreen`, `HopiLauncher` and
`hopi-ui-noop` are unchanged. `HopiPanes` gained `HopiPanesRules`, callbacks the desktop connects
to the bridge, so the desktop's panes never use the desktop process's own engine.

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
  So: `maxTotalBytes` + concurrent captures x cap (x2 when chunked) + those queued snapshots, plus,
  while the store's consumer is behind, at most another `maxTotalBytes` of queued ends.
  A tail client's live queue is no longer part of it (body-free events, see Agent access). The ends
  queued for a stalled consumer hold at most `maxTotalBytes` of bodies between them (see Store): a
  later end goes in with its sizes only (`"store backlog over byte cap"`), and a pass releases what
  it applied once it is published. Before 2026-10-27 the terminal channel held every queued body
  (up to 4096 snapshots): the benchmark's `stalled` row (32 queued 8 MB bodies, a 32 MB store)
  retained 288 MB, 256 MB beyond the cap; it now retains 36 MB, 4 MB beyond (G1's rounding), the
  28 later ends kept with their sizes only (measured 2026-10-27, not in the tables of record). Not
  counted: a start still in the pending lane (at most 256) keeps the request body the Ktor plugin
  or URLSession capture copied at the start, the array its request in flight or its queued end
  holds too, and still after that end went in without it (its record then keeps the start's); and
  an end whose start the store held counts only its response, so a pass that evicts that start
  before the end is applied leaves the end holding the request's array alone until then, at most
  what that pass evicted.
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
| Global interception that re-sends every request through a `URLProtocol` | Observe only, unless a rule the developer added applies: a rule's `URLProtocol` claims only the requests a mock or fail rule matches and answers them itself, re-issuing nothing, and with no rule a task of a session created after `start()` costs one `canInitWithTask:` answer; every other recorder pays one volatile read. The OkHttp before-response wait checks for cancel every 50 ms only while a delayed request waits. Global capture exists, but only after an explicit `HopiUrlSession.start()` in a debug build, never in release (noop twin, checked). It re-issues nothing, replaces method implementations only on `NSURLSessionTask`, `NSURLSession`'s upload factories (with rules on, also its session factory, and its task factories once an edit or delay rule was added) and CFNetwork's connection class (never an app or SDK class), adds no method, copies no bytes on the app's threads, and wakes nothing while no request runs. On Android, global capture exists only in debuggable variants of an app that applies `dev.koga.hopi`: it rewrites at build time one call inside OkHttp's `RealCall` and the `java.net.URL` connection call sites, adds no method, field or interface to any class (one annotation on `RealCall`), changes no list or object the app reads except the delegating bodies and connection wrappers it hands on, and wakes nothing while no request runs |
| UI/overlay work on the main thread while the app runs            | The launcher is one static composable: no recomposition unless dragged or tapped, no observation of the store |
| Formatting and storing everything eagerly                        | Capture raw bytes only; decode, pretty-print, encode only when a screen, log line, or agent asks |

Concretely, when nothing is looking (screen closed, no agent connected, log sink off):

- the only per-request work is header copy, bounded byte copy, one `trySend`;
- the store's consumer thread (`hopi-store`) is blocked on its semaphore, with no timeout, between
  bursts of requests: after a burst it dozes once for 50 ms (the window in which later requests'
  ends only mark the work), then blocks with no timer again;
- the bridge thread is blocked in `accept()` (not spinning) and encodes nothing; a connected client
  that is idle (a `tail` with no traffic) leaves its reader blocked in `read()` and its writer
  blocked on a semaphore: no coroutine event loop on either (Kotlin/Native's `runBlocking` loop
  re-armed a keep-alive timer on the writer, about 0.35 ms of CPU a second), checked by
  `IdleWakeupTest` on the JVM and the iOS simulator (0 wakes in 1.5 s, one per event);
- the UI collects nothing: `transactions` is collected only while `HopiScreen` is composed;
- no thread wakes up on its own. A CPU profile of an idle app with Hopi installed must be
  indistinguishable from one without it.

The performance monitors (docs/design/perf-monitors.md) are the stated, opt-in exceptions, each
off by default and running only while a monitor is started: the sampler (one wake per interval,
1 s by default), the hang watchdog (none while the main thread is idle; at most one per threshold
while busy, plus one per idle-to-busy transition) and a remote lease's expiry (no thread of its
own: one deadline in a perf thread's timed wait, or one `dispatch_after` when iOS frames runs
alone). The Android heartbeat (`hangHeartbeat`) and iOS tier 2's display link (only while a viewer
asks for it) are the two that wake while the app is idle.

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
