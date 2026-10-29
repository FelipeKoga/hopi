# Hopi

Hopi is a set of real-time devtools for Kotlin Multiplatform and Compose Multiplatform apps on
Android and iOS. Each tool is a module of its own, off until you opt in, and shows what it sees in
the same places:

- one NDJSON line per event in logcat or the iOS unified log,
- a host CLI, `hopi`, whose `--json` output is a stable contract for AI coding agents,
- a desktop viewer (Compose Desktop, macOS first) that shows the same data on your screen.

The tools so far:

- **Network**: every HTTP request of the app, from the Ktor client plugin or recorded
  automatically, third-party SDKs included: every URLSession request of an iOS process, and every
  OkHttp and `HttpURLConnection` request of an Android app (one Gradle plugin line, no code). List,
  detail, cURL, HAR export.
- **Rules**: change what a request does while you debug: a made-up response (**mock**), a failure
  the platform's own way (**fail**), a **delay**, or a rewritten request or response (**edit**).
  They live in the app's memory for the session only, apply on every source to the extent each can
  without weakening the app's TLS or pinning, and mark every record they touch (see
  [Rules](#rules-mock-fail-delay-edit)).
- **Performance**: frames, hangs (with the main thread's stack), CPU and memory, each monitor
  started on its own (see [Performance monitors](#performance-monitors-frames-hangs-cpu-memory)).

More tools are planned (accessibility audit, analytics debugger, state tracker); see
[`docs/ROADMAP.md`](docs/ROADMAP.md).

It is built to cost nothing when nobody is looking: no timers, no polling, no request of its own,
a rule check of one volatile read per request while no rule exists,
and no runtime in a release build, which links only the `-noop` twins and, for `HopiRules` and
`HopiPerf`, `hopi-api`, an API that stores nothing without a runtime. The design contract is
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); what may come later is in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

## Install

Hopi is published to Maven Local only: no remote repository, nothing downloaded from anywhere but
the repositories your build already uses. Every artifact has the group `dev.koga.hopi` and the
version of `hopi.version` in this repository's `gradle.properties` (`0.1.0`, the version the
Gradle plugin adds the runtime at). From a clone of this repository:

```bash
./gradlew publishToMavenLocal   # every artifact below, its noop twin, and the dev.koga.hopi plugin
```

Republishing the same version after a change is enough: Gradle does not cache `mavenLocal()`, so
the next build of your app picks it up. Then, in your app's `settings.gradle.kts`, add
`mavenLocal()` to both repository lists, limited to the `dev.koga.hopi` group (the libraries, the
plugin and its marker), so nothing else is looked up in `~/.m2`, whose entries Maven or another
build may have left incomplete:

```kotlin
pluginManagement {
    repositories {
        mavenLocal { content { includeGroup("dev.koga.hopi") } }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("dev.koga.hopi") } }
        google()
        mavenCentral()
    }
}
```

What the published artifacts need from your build (the versions they are built and tested with;
older ones are not tested):

- **Gradle 9.7.1 and JDK 17**; the plugin is compiled against **AGP 9.4.0**'s API.
- **Kotlin 2.4.20**: the klibs and the Kotlin metadata are written by it.
- **Android**: the app module applies `com.android.application` (the plugin refuses any other
  module) with `compileSdk = 37` or higher, since the AARs declare `minCompileSdk=37` and AGP
  refuses a lower one, and `minSdk = 24` or higher. A KMP shared module uses
  `com.android.kotlin.multiplatform.library`, with the same `compileSdk` in its `android { }` block.
- **Ktor 3.5.2** for `hopi-ktor`.

| Artifact              | What it is                                              |
| --------------------- | ------------------------------------------------------- |
| `hopi-ktor`      | The Ktor client plugin                                  |
| `hopi-ktor-noop` | Same public API as `hopi-ktor`, does nothing       |
| `hopi-urlsession` | `HopiUrlSession.start()`, which starts URLSession capture alone; `hopi-start` does not need it (it stays until stage 9 of the runtime-split plan) |
| `hopi-urlsession-noop` | Same public API as `hopi-urlsession`, does nothing |
| `hopi-start`     | `Hopi.start { }`, the iOS start: URLSession capture and the performance monitors from one call; Debug frameworks only, no noop twin |
| `hopi-api`       | `HopiRules`, rules from Kotlin or Swift code (tests, debug menus), and `HopiPerf`, the performance monitors from code; the same artifact in every build: until a debug build's start registers the runtime behind each (on Android and iOS; nothing starts one on the JVM yet), every `HopiRules` call returns `-1`, `false` or an empty list and every `HopiPerf` call does nothing. No noop twin. The CLI and the desktop viewer need no dependency for rules or perf |
| `hopi-perf`      | The performance monitors (frames, hangs, CPU, memory) behind `HopiPerf`, each off until started: added by the plugin on Android (`perf { enabled = true }`, debug builds only) and by `hopi-start` on iOS; never named |
| `dev.koga.hopi` (Gradle plugin) | Automatic OkHttp and `HttpURLConnection` capture on Android; adds `hopi-android` to debug builds and `hopi-android-noop` to release builds, and `hopi-perf` to debug builds with `perf { enabled = true }` |

`hopi-core` (shared by the real modules, and the runtime behind `HopiRules`), `hopi-android`,
`hopi-android-noop` and `hopi-perf` (added by the plugin; `hopi-perf` on iOS by `hopi-start`) and
`hopi-instrument` (the plugin's rewriter) are published too; you never name them. `HopiPerf` is
in `hopi-api`. Until stage 9 of the runtime-split plan adds the release guard, a module that names
`hopi-perf` itself ships it, and `hopi-core`, in release builds: nothing swaps it for a noop any
more. Each KMP artifact has its Android, JVM, `iosArm64` and
`iosSimulatorArm64` publications with sources and Gradle module metadata (`hopi-start` has the two
iOS ones only), so `dev.koga.hopi:hopi-ktor:0.1.0` in `commonMain` resolves the right one per
target.

Pick the real artifacts or the noop twins with a Gradle property in the shared module, because a
KMP module has no build types and iOS frameworks link the same klibs for debug and release:

```kotlin
// shared/build.gradle.kts
val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false
val v = "0.1.0"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(if (hopiEnabled) "dev.koga.hopi:hopi-ktor:$v" else "dev.koga.hopi:hopi-ktor-noop:$v")
            // only to call HopiRules or HopiPerf from code: one artifact for every build, no twin
            implementation("dev.koga.hopi:hopi-api:$v")
        }
    }
}
```

Debug runs pass `-Phopi.enabled=true` (the Xcode build phase for the Debug configuration, as in
`sample/iosApp`). Anything built without it ships the noops, which contain no Hopi logic and no
`hopi-core`, and `hopi-api`, an API with no runtime behind it. Swift calling `HopiRules` or
`HopiPerf` needs `hopi-api` as `api` and exported from the framework
(`export("dev.koga.hopi:hopi-api:$v")`) in every configuration, since those calls are not inside
`#if HOPI`. On Android, apply the
`dev.koga.hopi` plugin (below) to the application module instead: it resolves the real modules in
debug builds and the noops in release builds, whatever the property says. Without the plugin, an
Android-only app can select by build type (`debugImplementation` real, `releaseImplementation`
noop); an app module on top of a KMP shared module must not, since the shared module already
brings one of the two. Such an app's debug build still merges `hopi-core`'s start provider, which
finds no generated configuration and at each launch writes one notice line, `not started: no
generated configuration; …`; the Ktor plugin records as before, and `HopiRules` and `HopiPerf`
do nothing, since nothing started to register their runtimes. `HopiPerf` no longer starts the
monitors itself (it did before stage 8 of the runtime-split plan): only perf's tool registers its
runtime, and on Android only the plugin's `perf { enabled = true }` lists that tool.

Install the plugin on each `HttpClient` you want to see, when the app starts:

```kotlin
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.ktor.HopiLogSink

val client = HttpClient(engine) {
    install(Hopi) {
        logSink = HopiLogSink.Summary   // one NDJSON line per request (the default)
        agentBridge = true                   // let the CLI connect (default false)
        redactHeaders("X-Api-Key")           // Authorization, Cookie, Set-Cookie… are redacted already
        redactQueryParameters("token")
    }
}
```

The `hopi` CLI, which reads a running app's records (headers, bodies) and drives its rules, is
built from this repository, not published: `./gradlew :hopi-cli:installDist` (see
[For AI agents](#for-ai-agents)).

### Android: every OkHttp and HttpURLConnection request, automatically

One line in the app module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("dev.koga.hopi") version "0.1.0"
}
```

(or `id("dev.koga.hopi") version "0.1.0" apply false` in the root `build.gradle.kts` and
`id("dev.koga.hopi")` here.) That is the whole setup. In debuggable variants the plugin rewrites, at build time, OkHttp's call
class and every `java.net.URL` connection call site of your app **and of every library it
depends on**, so every OkHttp client (yours, Retrofit's, an analytics or ads SDK's, Ktor's OkHttp
engine) and every `HttpURLConnection` is recorded, with no client to configure and no code to call:
the runtime starts itself from a provider in the debug manifest. Ktor's OkHttp engine is then
recorded once, by the capture (`"source":"okhttp"`), not also by the plugin. It only observes
(unless a rule you added applies): nothing is re-sent, no interceptor list your code can read
changes, and bodies are copied as your code reads them, up to `maxBodyBytes`, never read ahead.

Release (non-debuggable) variants are not instrumented at all and get `hopi-android-noop`; with the
plugin, the Ktor plugin is also resolved per variant (real in debug, noop in release), so an
Android app needs no `-Phopi.enabled`. `hopi-api` (`HopiRules`, `HopiPerf`) is the same in both;
in debuggable variants the plugin holds it at its own version, that of the `hopi-core` and
`hopi-perf` it adds, whose runtimes implement internal interfaces of `hopi-api`. Optional
settings:

```kotlin
hopi {
    agentBridge = true                  // let the CLI and the desktop viewer connect (default false)
    maxBodyBytes = 256L * 1024
    redactHeaders("X-Api-Key")
    urlConnection = false               // OkHttp only: skips the whole-classpath rewrite
    rules = false                       // never read the rule state (default true; see Rules)
}
```

`HopiAndroid.isStarted` says whether capture runs (always false in release). Not recorded: Cronet,
WebView, native HTTP stacks, relocated OkHttp copies, code downloaded at run time, other processes,
and requests made before the app's content providers start. Records carry `"source":"okhttp"` or
`"source":"urlconnection"`. Apply the plugin to the application module, not to a library: it
instruments the libraries from there.

### iOS: every URLSession request, automatically

One call, `Hopi.shared.start` from `hopi-start`, records every URLSession request of the process:
your own code, the SDKs you do not own (analytics, ads, crash reporters), `URLSession.shared`,
custom delegates, Swift `async`/`await`, and Ktor's Darwin engine (the plugin then leaves those
requests to it, so each request is recorded once). The same call registers the performance
monitors for the CLI, the desktop viewer and `HopiPerf`, none started (see
[Performance monitors](#performance-monitors-frames-hangs-cpu-memory)). It only observes: nothing
is re-sent and nothing your app sees changes, unless a rule you added applies (and with rules on,
sessions created after the start list Hopi's protocol class first in
`configuration.protocolClasses`; `rules = false` turns that off).

`hopi-start` has no noop twin. Add it, and export it from your framework, only with the flag, so a
Release framework holds no Hopi start at all:

```kotlin
// shared/build.gradle.kts
val start = "dev.koga.hopi:hopi-start:$v"
kotlin {
    iosSimulatorArm64 { binaries.framework { if (hopiEnabled) export(start) } }   // and iosArm64
    sourceSets { iosMain.dependencies { if (hopiEnabled) api(start) } }
}
```

Its API is `Hopi` and `HopiStartConfig`, and no internal Hopi declaration or type reaches your
framework's Swift header. Call it once, first thing, from Swift (or from Kotlin as
`Hopi.start { }`), inside `#if HOPI`:

```swift
@main
struct MyApp: App {
    init() {
        #if HOPI
        Hopi.shared.start { $0.agentBridge = true }
        #endif
    }
    // …
}
```

`HOPI` is a Swift compilation condition derived from the build setting your Gradle build phase
reads, so Swift calls `Hopi` exactly when the framework has it. In the app target's Debug
configuration (and in that of a test target that calls `Hopi`): `HOPI_ENABLED = YES`,
`HOPI_SWIFT_CONDITION_YES = HOPI`, `HOPI_SWIFT_CONDITION_true = HOPI` (for `HOPI_ENABLED=true` on
the command line) and `SWIFT_ACTIVE_COMPILATION_CONDITIONS = $(inherited)
$(HOPI_SWIFT_CONDITION_$(HOPI_ENABLED))`; the build phase passes `-Phopi.enabled=true` only when
`CONFIGURATION` is `Debug` and `HOPI_ENABLED` is `YES` or `true`, the two values that set `HOPI`.
`sample/iosApp/project.yml` does both. `#if DEBUG` would not do: it removes nothing from the
framework, which links whatever the flag says, and a Debug build without the flag
(`xcodebuild … HOPI_ENABLED=false`) has no `Hopi` to call.

Its settings (Swift sets the same members on the closure's argument):

```kotlin
Hopi.start {
    agentBridge = true                  // let the CLI and the desktop viewer connect at start (default false)
    maxBodyBytes = 256L * 1024          // and maxEntries, maxTotalBytes: the store's caps
    redactHeaders("X-Api-Key")          // Authorization, Cookie, Set-Cookie… are redacted already
    redactQueryParameters("token")
    summaryLog = true                   // one NDJSON line per request and perf's lines (the default)
    rules = false                       // never read the rule state (default true; see Rules)
    perf = false                        // leave the performance monitors out (default true)
    perfRemoteControl = false           // refuse the bridge's perf starts and stops (default true)
}
```

Requests whose task was resumed before `Hopi.shared.start` are not recorded, so call it before
anything else, SDK initialisation included. Records carry `"source":"urlsession"`; request bodies
sent as a stream or a file are recorded as skipped (they are single-use, Hopi never reads them),
downloads by their size. WebSocket and stream tasks, AVPlayer and WKWebView traffic are not
recorded. `HopiUrlSession.shared.start { }` (`hopi-urlsession` or its noop, exported like the
other twins) still starts URLSession capture alone, until stage 9 of the runtime-split plan
merges it into the network runtime; `Hopi.shared.start` is the start to use, and the only one
that registers the runtime behind `HopiRules` (see [Rules](#rules-mock-fail-delay-edit)).

There is no in-app screen: `HopiScreen`, `HopiLauncher` with its floating button, and the perf HUD
were removed in stage 5 of the runtime-split plan (D7', `docs/design/api-runtime-split-plan.md`).
The app's records are read with the [CLI](#for-ai-agents) and the [desktop viewer](#desktop-viewer).

A Debug Kotlin/Native framework links every wrapper of each platform library it uses, built
against the SDK Xcode builds with, so a symbol newer than your deployment target becomes a strong
import. Built with Xcode 27.1, the sample's Debug app aborted in dyld before `main` on iOS 18.2:
first `_kCGAdaptiveMaximumBitDepth` (CoreGraphics, which `hopi-ui` then used), then libresolv's
`___res_9_p_cert_syms`. If yours does, weak-link the library dyld names in the app target's
`OTHER_LDFLAGS`, as `sample/iosApp/project.yml` does (`-weak_framework CoreGraphics -weak-lresolv`).
A weak-linked symbol is NULL on the older runtime, so Kotlin code must check the OS version
(`NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion`) before it reads one newer than the
deployment target.

## For AI agents

The log stream needs no setup. With `logSink = Summary` every finished request is one line (lines
without `"id"` are notices; skip them):

```json
{"hopi":1,"id":42,"method":"GET","url":"https://api.example.com/users?page=2","code":200,"ms":183,"reqBytes":0,"resBytes":5120,"state":"complete"}
```

```bash
# Android
adb logcat -s Hopi:I -v raw
# iOS simulator
xcrun simctl spawn booted log stream --style ndjson --predicate 'subsystem == "dev.koga.hopi"'
```

The CLI reads the full detail (headers, bodies) from the app's device-local bridge
(`agentBridge = true`; Android devices and emulators over adb, iOS simulators). Build it with
`./gradlew :hopi-cli:installDist`; the binary is
`hopi-cli/build/install/hopi-cli/bin/hopi`.

```bash
hopi devices --json                        # reachable targets
hopi list --app <applicationId or bundle id> --json --limit 20
hopi tail --app <id> --json                # live, one line per event, until interrupted
hopi get  <transaction id> --app <id> --json
hopi curl <transaction id> --app <id>      # a cURL command that replays the request
hopi clear --app <id> --json
```

`--platform android|ios` and `--device <serial|udid>` choose the target when more than one is
reachable. Exit codes: 0 ok, 1 usage (and a rule the app rejects), 2 not reachable, 3 not found,
4 unsupported (rules off or absent in the app's Hopi); errors are one line on stderr. The rule
commands are in [Rules](#rules-mock-fail-delay-edit).
Secrets are redacted on every channel; the `--json` line shapes are specified in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#agent-access).

## Desktop viewer

`hopi-desktop` shows a running app's traffic in a window on your Mac: the list and detail side by
side, over the same bridge as the CLI (`agentBridge = true`, debug builds). The app
needs nothing new, and a connected viewer costs it what a connected `hopi tail` does. Run it
from the repository, or build a `.dmg` (unsigned, about 65 MB with its own Java runtime):

```bash
./gradlew :hopi-desktop:run
./gradlew :hopi-desktop:packageDmg   # hopi-desktop/build/compose/binaries/main/dmg/Hopi-1.0.0.dmg
```

Pick a device (adb devices and emulators, booted iOS simulators, JVM processes with a bridge),
enter the app's id (on Android the bridges listening on the device are offered), and connect.
Only the selected request's headers and bodies are fetched from the app, so a giant body never
crosses unless you open it. When the app restarts, or adb resets, the viewer reconnects by itself.

| Keys | |
| --- | --- |
| ↓ / ↑ | next / previous request |
| Enter, Esc | select the first request, clear the selection |
| ⌘F | search |
| ⌘K | clear (the app's store too) |
| ⌘E, ⇧⌘E | export the session as HAR 1.2, as NDJSON (`hopi get --json` lines) |
| ⌘R | reconnect |
| ⌘L | the Rules panel |

Ctrl replaces ⌘ on Linux and Windows. An export fetches the bodies not opened yet, up to 32 MB
per request and 256 MB in all; larger ones keep their sizes and a comment.

The viewer's text is set in Open Runde (SIL Open Font License 1.1), bundled with its license in
`hopi-ui/src/jvmMain/resources/dev/koga/hopi/ui/font/`.

## Rules: mock, fail, delay, edit

A rule matches a request (methods, scheme, host glob, port, path glob, query and header
predicates, sources) and does one thing to it. The first enabled rule that matches wins (higher
`priority` first, then the newest), and only the layer that records the request applies it, so a
rule is never applied twice. Rules live in the app's memory: nothing is written anywhere, nothing
is imported from a file, an intent or a launch argument, and restarting the app clears them all.
`hopi clear` leaves them in place; `hopi rules clear` removes them.

From the CLI (the app needs `agentBridge = true`, like every other command):

```bash
hopi mock add --app <id> --match 'GET */users/*' --status 500 --header 'Content-Type: application/json' --body @err.json
hopi mock add --app <id> --match 'GET https://api.example.com/v1/me' --from 42     # the response of record 42
hopi fail add --app <id> --match 'POST */upload' --with timeout --after 30s           # also reset, unknown-host, cancel
hopi delay add --app <id> --match '* *.cdn.example.com/**' --before-response 2s
hopi edit add --app <id> --match 'GET /v1/items?page=2' --set-header 'X-Env: staging' --path /v2/items --status 503
hopi rules ls --app <id>                                                               # ID ON PRI HITS MATCH ACTION
hopi rules disable 3 --app <id>                                                        # enable, reset (hits), rm, clear
```

`--match` is `[METHODS ]URL-PATTERN`: `GET`, `GET|POST` or `*`, then
`[scheme://]host[:port]path[?query]`; in a host `*` matches any run (`*.example.com` is any
subdomain), in a path `*` matches one segment and `**` any number; a pattern starting with `/`
matches any host; `?page=2&debug&!trace` requires `page=2`, requires `debug` and forbids `trace`.
Every `add` also takes `--times N`, `--priority N`, `--source ktor,okhttp,urlconnection,urlsession`,
`--label`, `--query`, `--match-header 'K: glob'` and `--disabled`. `--body` is `@file`, `@-` or the
text itself (up to 1 MiB; a large one is uploaded in pieces). A part of a rule a source cannot
apply is printed as `hopi: warning: …` when it is added. `hopi list` marks a ruled record
`[delay,mock #3]`.

In the desktop viewer, ⌘L (or the Rules menu) opens the Rules panel: every rule with its switch,
hits and warnings, forms for mock, fail, delay and edit (headers as a table, a body from a file),
and on a selected request "Mock this response". The in-app rules list and its "Mock this response"
were removed with the in-app UI in stage 5 (D7'). Replay (Hopi re-sending a recorded request) is
not part of the product; it is in the backlog with no priority ([`docs/ROADMAP.md`](docs/ROADMAP.md)).

From code, for tests and debug menus (`hopi-api`; with no runtime registered, as in a release
build, every call returns `-1`, `false` or an empty list):

```kotlin
val id = HopiRules.mock("GET */users/*") {
    status = 500
    header("Content-Type", "application/json")
    body("""{"error":"boom"}""")
    delay(beforeResponse = 2.seconds)
    times = 1
}
HopiRules.fail("POST */upload", HopiFailure.Timeout)
HopiRules.edit("GET */v1/items") { request { setHeader("X-Env", "staging") }; response { status = 503 } }
HopiRules.clear()                                                      // in a test's teardown
```

Swift calls the same API as `HopiRules.shared` once the framework exports `hopi-api`.

A debug build's start registers the runtime behind `HopiRules` before anything else, so a rule
added before Hopi starts is dropped: its call returns `-1`, and the start writes one notice line
with the number dropped, never a match (a match can carry a secret):
`{"hopi":1,"notice":"HopiRules: 2 rules added before Hopi started were dropped (each call returned -1)"}`.
On Android the start runs before `Application.onCreate`, so only code in the `Application`
constructor, `attachBaseContext` or another content provider is that early. On iOS, call
`Hopi.shared.start` first: a Debug binary also writes one line to the system log at the first
rule added while no runtime is registered (`HopiRules: no Hopi runtime is registered, …`), which
is also what an app that never calls the start sees. Without a runtime, in release builds, the
block still runs and the builder keeps what it is given (a body is copied), so a call costs that
much and nothing more. On the JVM nothing starts Hopi until stage 10 of the runtime-split plan
adds its JVM entry, and a plain Android host unit test runs no provider: there (a JVM app
recording with the Ktor plugin, a JVM test, an Android host unit test) nothing registers the
runtime, so `HopiRules` returns `-1` and nothing reports it, where `hopi-rules` worked with no
start before stage 7. Rules added over the bridge (the CLI, the desktop viewer) still apply to
the requests a JVM app's Ktor plugin records.

| | Ktor plugin | OkHttp | `HttpURLConnection` | URLSession |
| --- | --- | --- | --- | --- |
| mock, fail | yes | yes | yes | yes, except background sessions and sessions created before `start()` (`URLSession.shared` is covered) |
| delay before request | yes | yes | yes | yes (adds to the session's timeouts) |
| delay before response | yes | yes | yes | with a mock or fail only |
| edit request | yes | yes | yes | yes; a body only for `HTTPBody` and upload-from-data tasks |
| edit response | yes | yes | yes | no |

A rule never weakens the app's security. A mock or fail contacts nothing; an edited request goes
through the app's own client or session, with its TLS, certificate pins, `HostnameVerifier`, proxy
and challenge delegate. The scheme cannot change, an edit to another host or port drops
`Authorization`, `Cookie` and the redacted headers unless it sets them itself, a mock may not set a
cookie, and no mocked or edited response enters an HTTP cache. What a source cannot do that way is
unsupported there, and the rule says so when it is added. A mocked 3xx, 401 or 407 is not
followed by OkHttp, `HttpURLConnection` or URLSession themselves (their follow-up logic sits below
Hopi); Ktor's sits above Hopi on every source, so a Ktor client follows them. Every recorder has a
switch, `rules = false` (`install(Hopi) { }`, `Hopi.start { }` on iOS, `hopi { }`), that keeps
it from ever reading the rule state; the app's `hello` then tells the CLI that rules are off.

## Performance monitors: frames, hangs, CPU, memory

`hopi-perf` watches the app itself: **frames** (FPS and janky frames; iOS: main-thread stalls),
**hangs** (main-thread units of work over a threshold, 250 ms by default, with the main thread's
stack), **CPU** (the process, Hopi's own share, and per thread while a viewer shows the list) and
**memory** (heaps, resident memory, GC counts, the OS's trim and memory-warning signals). Every
monitor is off by default and opt-in on its own. A monitor that is off has no thread, no
listener, no timer and no monitor class loaded (perf's start creates only the controller, a
backend that forwards to the monitors, and the model, whose rings are allocated at their first
write); one that is on states its cost in
[`docs/design/perf-monitors.md`](docs/design/perf-monitors.md) ("Performance budget", with what is
measured so far under "As built"). Nothing is persisted or sent anywhere: the data lives in
bounded rings in memory.

Android, with the `dev.koga.hopi` plugin: `perf { enabled = true }` adds `hopi-perf` to debug
builds only and registers it, so the CLI, the desktop viewer and `HopiPerf` can start monitors
with no other code; without it `HopiPerf` does nothing in any build. `autoStart` starts some at
launch:

```kotlin
hopi {
    agentBridge = true
    perf {
        enabled = true
        autoStart(HopiMonitor.Hangs)   // default: none
        hangThresholdMs = 250          // 100..10 000
        hangHeartbeat = false          // true: keep another tool's Looper printer (wakes main and the watchdog 8x/s each while idle)
        remoteControl = true           // false: refuse the bridge's perf starts and stops (reads still work)
    }
}
```

iOS: `Hopi.shared.start` (see [iOS](#ios-every-urlsession-request-automatically)) registers the
monitors, none started, unless its `perf` is false, so the CLI, the desktop viewer and `HopiPerf`
can start them with no other code (`perfRemoteControl = false` refuses the bridge's starts and
stops); `xcrun simctl launch booted <bundle id> -hopiPerf frames,hangs` starts monitors at launch.

To start, stop or label monitors from code, name `hopi-api` (one artifact for every build, as for
`HopiRules`; exported from the iOS framework to call it from Swift), never `hopi-perf`:

```kotlin
HopiPerf.start { hangs = true; frames = true }         // starts monitors from code
HopiPerf.start { sampleIntervalMs = 500 }              // changes only what the block sets
HopiPerf.screen("Checkout")                            // labels the janky frames and hangs that follow
HopiPerf.stop()
```

Every call does nothing until a debug build's start registers the runtime, as in a release build:
`HopiPerf` no longer creates the runtime itself (before stage 8 of the runtime-split plan its
first call did, and an `install` call registered it). A `start` made before then is dropped and
counted; the start that registers the runtime writes one notice line with the count, under perf's
log (logcat tag `HopiPerf`, os_log category `perf`), not under `Hopi` like the rules' one, and an
iOS Debug binary also writes one line at the first dropped start, in case `Hopi.shared.start` (or
its `perf`) is missing. `screen` keeps its last label for a runtime that registers later. A block
applies only what it sets: a setting left unset keeps the value in force (the plugin's
`perf { }`, a remote `hopi perf start --hang-ms`), and an out-of-range value writes a notice and
starts nothing. The agent bridge and the summary log are the start's settings (`hopi { }`,
`Hopi.start { }`), and `summaryLog = false` there now silences perf's log lines too (its notices
are always written); remote control is perf's own, set at start only
(`perf { remoteControl }`, `perfRemoteControl`). A start with invalid caps registers nothing, so
`HopiPerf` stays inert until a valid start runs. On the JVM nothing registers it before stage 10
of the runtime-split plan.

From an agent or a terminal (the app's bridge, as for the requests):

```bash
hopi perf start --app <id> --hangs --frames        # held for 10 minutes (--for 30m, --no-expiry)
hopi perf status --app <id>
hopi perf snapshot --app <id> --json [--threads]    # summaries, series, recent events
hopi perf tail --app <id> --json --samples --start hangs   # live; the monitors stop when it ends
hopi hangs --app <id>                               # every hang held, with full stacks
hopi perf stop --app <id>
```

The log needs nothing: `adb logcat -s HopiPerf:I -v raw` (iOS: os_log category `perf`) gives one
line per hang and per second with janky frames, a `sample` line every 10 s while CPU or memory
runs, at most 5 lines per second:

```json
{"hopi":1,"perf":"hang","seq":12,"state":"ongoing","ms":250,"screen":"PerfLab","stack":["java.lang.Thread.sleep(Native Method)","…","dev.koga.hopi.sample.PerfLab.hang(PerfLab.kt:76)","…"]}
{"hopi":1,"perf":"hang","seq":12,"state":"ended","ms":1003}
```

On Android a hang is seen in any main-thread message and in input to an activity's window; input
to a dialog's, bottom sheet's or popup's own window is not bracketed (no public API reaches it), so
a click that blocks there is caught only with `hangHeartbeat = true` (`perf.status` says
`"inputWindows":"activities only"`).

The desktop viewer has a Perf panel (⌘P) with charts of FPS, CPU and memory. The in-app Perf tab
and HUD were removed with the in-app UI in stage 5 (D7').

The sample's perf lab injects known jank, hangs, CPU burn and allocations and checks Hopi's record
against them; see [Sample app](#sample-app) and `benchmark/perf/`.

## Performance

Measured so far (the methods and caveats are in the architecture doc):

| What | Result |
| --- | --- |
| Added latency per request, JVM benchmark against `ktor-client-mock`, 1 KB JSON response (`HopiOverheadBenchmark`, 20 000 requests per arm) | median +8.4 µs (p95 +10.4 µs) with `Content-Length`, +4.7 µs (p95 +10.1 µs) chunked; budget < 50 µs |
| Row model for 500 transactions, JVM, median of 41 runs | build all rows 0.45 ms, one transaction changed 0.021 ms, search plus filter 0.124 ms |
| Idle, Android emulator (API 35), launcher on screen, 6 transactions stored | 0 frames over 10 s (`dumpsys gfxinfo`), 0 CPU ticks over 10 s (`/proc/<pid>/stat`) |
| Idle, inspector screen open | 0 frames over 5 s |
| Idle 60 s, launcher on screen, real vs noop build (emulator) | 0 CPU ticks for both after ART's post-install work; Hopi's bridge thread 0 ticks; 0 frames |
| Idle, iPhone 16 simulator (iOS 18.2), Debug (real) vs Release (noop) | 0.18–0.19 s vs 0.11 s CPU per minute, both near 0 %; Hopi's bridge thread 0 |
| Scrolling 500 transactions vs the app's own 1005-row list, emulator with `-gpu host` | 0.96 % vs 0.79 % janky frames, both p90 21 ms, p95 22 ms (the emulator's GPU path alone is 14–15 ms); inspector UI thread p95 3.5 ms |
| The same on the default headless renderer (SwiftShader), current build, 6 rounds | inspector 1–6.2 % janky, GPU p50 17 ms, UI thread p50 1.9–4.0 ms, p95 6–13 ms; the app's list 21–27 % janky, UI p95 15–18 ms. The app is no longer drawn under the open screen (overdraw 1x instead of 2x–4x) |
| Opening the screen / a detail / the Response tab of a 256 KB JSON body (emulator) | 284–335 ms / 267–317 ms (both include the 200 ms transition) / 41–77 ms |
| Scrolling a 300 KB JSON response body on the emulator | 92 frames, 8.7 % janky, 26 ms at p50, 89 ms at p99 (debug build without R8 on a headless emulator: a rough upper bound, not a device number) |
| Giant bodies, JVM (`*GiantBodyBenchmark`, opt-in), app's request with the default 256 KB cap, 50 MB response, MockEngine and a real socket | no measurable added time, +0.3 MB allocated, the same as for 1 MB |
| The same with an 8 or 16 MB cap | 1x the cap extra with a `Content-Length` (was 2x: the buffer grew by doubling), about 2x chunked, of which the app's reader pays 1x (the capture grows in blocks; the join runs off the reader); independent of the body |
| App's request + parse on the emulator (API 35, debug build, warm), real vs noop, Content-Length / chunked, median of 3 paired rounds | 1 MB +1.0% / +9.1% (fetch -0.3 / +4.0 ms; with the old pump +5.7% / +12.6%, fetch +5.3 / +9.3 ms); 5 MB -1.6% / +0.5% (pump +4.0% / +4.9%); noop against noop spreads up to 11% at 1 MB, so most of this is noise; a 1-byte-cap control shows the same chunked 1 MB fetch gap, so the capture itself is not measurable |
| Giant bodies, detail view formatting off the main thread, 16 MB, JVM | minified 60 ms, 4.4x allocated (was 69 ms, 8.1x); truncated 13 ms (was 57 ms); 16 MB on one line 2–5 ms to split (was 1.6 s, quadratic) |
| Giant bodies, detail screen main thread, 1 / 8 / 16 MB body, headless | first frame 6–12 ms; scroll frames median 0.4–0.7 ms, p95 3.7–6 ms, max 7 ms, at every size |
| Copy cURL / Share of a 16 MB request body, JVM | 0.4–1 ms, 0.05–0.15x the body (was 9–25 ms, 4–10x), cut inside a closed quote with a warning line |
| Giant bodies, bridge `get` of a 16 MB body | 1.0x an ASCII body allocated in the app (was 7.9x), transient 18 MB (was 80–130 MB); CJK/emoji 3.3x, base64 2.7x |
| 8 MB JSON detail on the emulator (8 MB cap, pretty-printed), old vs new build | settle 600–768 vs 569–771 ms; Java heap peak while opening 68.7 vs 56.4 MB; main-thread frame gap at most 67 ms (frame ticker on) |
| 16 MB JSON detail, 16 MB cap and 32 MB store, default 192 MB heap | Android: settles in 1.0–1.2 s, Java heap peak 84 MB, no OOM; iOS simulator: frame gaps at most 77 ms |
| A tail client that stops reading | holds body-free events only (was up to ~2,400 full snapshots, 600 MB at the default cap) |
| Desktop viewer connected to an idle app, JVM bridge in-process (`ViewerTest`) | the app's 5 bridge threads (accept, a reader and a writer per connection) 0 ns CPU over 3 s; exactly 2 connections |
| Desktop viewer connected to the sample, Android emulator (API 35, `DeviceViewerUiTest`, 2026-10-23) | Hopi's 5 threads 0 CPU ticks over 60 s idle; 2 connections on the app side, never more through a force-stop, a relaunch and an adb server reset; 500 `POST /items` 2660 to 2781 ms connected, 2526 to 2704 ms disconnected |
| Release APK of the sample built without the flag | 12 Hopi classes (the noop twins), 0 from `hopi-core`; 345 and 97 with `-Phopi.enabled=true` (before the Gradle plugin; with it, 13 noop classes, no core or capture type referenced, whatever the flag) |
| Automatic Android capture, emulator (API 35), debuggable builds, small loopback GET | +22.5 to +24 µs of the calling thread's CPU and +12 to +27 µs median wall per request against the noop build (2026-10-27, after the bug fixes, medians of 8 rounds; +85 to +95 µs and +350 to +390 µs at the first measurement, 2026-10-24) (`benchmark/android-capture/`) |
| Performance monitors, emulator (API 35, debug build), perf lab against outside references | 28 of 28 checks: 30 frames blocked 50 or 100 ms are 31 janky each (gfxinfo 29 to 30); hangs of 300 ms to 3 s and a 600 ms spin inside a Compose `onClick` caught once each within 0 to 5 ms, with stacks naming the lab's function; 2 burning threads 198 % (`/proc` 199 %); 64 MB heap and native allocations +64 MB; three trim levels. Rerun 2026-10-26 after the stage 5 fixes: 28 of 28 again; iPhone 17 simulator 21 of 21 (a 300 ms hang reads 300 to 302 ms on fresh installs, once Hopi names stacks only after the hang ended). Android again 28 of 28 on 2026-10-27, after the bug fixes; iOS not rerun |
| Performance monitors' cost, emulator, 10 000 main-thread messages per run, 9 runs per arm | nothing started = noop (0 CPU, 0 wakes over 60 s idle); frames +0; hangs (Looper printer) +6.2 to +6.5 µs per message, +29 µs per `Dispatchers.Main` resume, 0 idle; hangs on the heartbeat +0 per message, 7.8 to 7.9 wakes/s idle on each of its two threads, 16 in all (8 in all before its cadence fix); CPU and memory 1 wake per second, about 1.0 ms CPU per sample (`schedstat`, a debuggable app runs it interpreted); all four on and idle +0.10 % of one core; iOS simulator: hangs +4.8 context switches/s (one per main run-loop wake, which wakes 6.6/s in the noop build too), all four +6 (`benchmark/perf/`) |

The first idle and the 300 KB body figures were taken with the earlier, Android-only version of
the sample; the rest in the phase 3 device acceptance. Physical devices have not been measured yet.
The rows on the launcher, the inspector screen and its detail on a device measured the in-app UI,
removed in stage 5 (D7'); they stay as its record.

## Sample app

`sample/` is a Compose Multiplatform app (a small shop: item list, detail, a form that POSTs, and a
diagnostics tab that fires one request of every kind the inspector handles) for Android and iOS.

```bash
python3 sample/devserver/devserver.py           # dev server on 127.0.0.1:8765

# Android (emulator): the dev.koga.hopi plugin makes every debug build real
./gradlew :sample:androidApp:installDebug
adb reverse tcp:8765 tcp:8765
# the rules self-test on the Ktor plugin, Ktor's OkHttp engine, and the fake SDK's OkHttp and
# HttpURLConnection, then a pinned client; one {"rules":…} line per scenario under HopiSample
adb shell am start -n dev.koga.hopi.sample/.MainActivity --es rulesVerify all
# requests tagged rv=host-<target> for rules you add yourself, then e.g.
#   hopi mock add --app dev.koga.hopi.sample --match 'GET /json?rv=host-sdk-okhttp' --status 418
adb shell am start -n dev.koga.hopi.sample/.MainActivity --es rulesTargets all
# the perf lab (no dev server needed): starts every monitor, injects jank, hangs, a dialog, CPU burn
# and allocations, one {"perfLab":…} line each; verify.py also taps the click-burn button and
# checks Hopi's record against the injections and its own references (gfxinfo, /proc, meminfo)
adb shell am start -n dev.koga.hopi.sample/.MainActivity --es perfVerify all
python3 benchmark/perf/verify.py build/evidence/perf
# the monitors' overhead: main-thread message cost per arm (bench.py) and idle CPU and wakes (idle.py)
python3 benchmark/perf/bench.py build/evidence/perf --arms off=none,hangs=hangs,all=all
python3 benchmark/perf/idle.py --arms off=none,all=all

# iOS simulator: Debug passes the flag itself
open sample/iosApp/iosApp.xcodeproj             # or:
xcodebuild -project sample/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
# a pure-Swift "third-party SDK" making URLSession requests at launch, recorded with no setup
xcrun simctl launch booted dev.koga.hopi.sample -thirdPartySDK
# the rules self-test: every action on every URLSession shape and on Ktor Darwin, then a pinned
# session (also the "Rules self-test" button of the diagnostics tab)
xcrun simctl launch --console-pty booted dev.koga.hopi.sample -verifyRules
# the perf lab (the click burn is a button to tap yourself), and its check
xcrun simctl launch --console-pty booted dev.koga.hopi.sample -verifyPerf
python3 benchmark/perf/verify.py build/evidence/perf --platform ios
# the Swift async/await capture tests (starts the dev server itself)
sample/iosApp/scripts/capture-swift-test.sh 'platform=iOS Simulator,name=iPhone 17'

# The release APK must ship only the noop twins and hopi-api; the iOS release framework no capture code
./gradlew :sample:androidApp:verifyReleaseUsesNoop
./gradlew :sample:shared:verifyIosReleaseExcludesCapture
```

The Debug app launches on both simulators, the iPhone 17 (iOS 27.0) and the iPhone 16 (iOS 18.2):
an earlier Xcode 27.1 build aborted in dyld on iOS 18.2 (a CoreGraphics symbol iOS 18.2 lacks), and
the current build no longer does, since the app target weak-links CoreGraphics and libresolv (see
the iOS install section; perf lab run on the iPhone 16, 2026-10-26).

## Building

JDK 17, Gradle through `./gradlew` (9.7.1), Kotlin 2.4.20, Compose Multiplatform 1.12.0, Ktor
3.5.2. `./gradlew check` runs the tests (including the iOS simulator tests), the ABI dump checks and
the noop parity check (a real module and its twin must have exactly the same public API) and the
publication check. The base check, one Gradle invocation:

```bash
./gradlew :hopi-api:jvmTest :hopi-core:jvmTest :hopi-ktor:jvmTest :hopi-ui:jvmTest :hopi-ktor-noop:jvmTest \
  :hopi-cli:test :hopi-connect:test :hopi-desktop:test :hopi-urlsession:jvmTest :hopi-urlsession-noop:jvmTest \
  :hopi-perf:jvmTest :hopi-perf:testAndroidHostTest :checkNoopParity check \
  :sample:androidApp:assembleDebug :sample:androidApp:verifyReleaseUsesNoop :hopi-android:testAndroidHostTest \
  :gradle-plugin:hopi-gradle-plugin:functionalTest :checkPublication
```

`:checkPublication` publishes every artifact into `build/publication-check` (the plugin, its marker
and `hopi-instrument` into `gradle-plugin/build/functional-repo`), never `~/.m2`, and fails unless
every expected coordinate is there (each KMP module's root, Android, JVM and iOS publications,
sources jars, the cinterop klibs of `hopi-core` and `hopi-perf`), every Gradle module file and
POM of `hopi.version` points only at files and `dev.koga.hopi` artifacts that are published at it,
and no archive holds this repository's absolute path (the klibs store source paths relative to it).
`hopi.version` is set in `gradle.properties` only: the plugin's build reads that file, so the root
build refuses a `-Phopi.version` that differs from it. The
artifacts and the plugin are published by `gradle/publishing.gradle.kts`, which each published
module applies; `hopi-connect`, the CLI, the desktop viewer with its screens (`hopi-ui`) and the
sample are not published.
The giant-body benchmarks are opt-in and slow (minutes): run one module at a time with
`./gradlew :hopi-ktor:jvmGiantBodyBenchmark` (also `:hopi-core:` and `:hopi-ui:`)
or `./gradlew :hopi-cli:testGiantBodyBenchmark`; tables land in `<module>/build/giant-body/`.
