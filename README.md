# Hopi

Hopi is a real-time HTTP inspector for Kotlin Multiplatform and Compose Multiplatform apps on
Android and iOS that use the Ktor client. It can also record requests automatically, third-party
SDKs included: every URLSession request of an iOS process, and every OkHttp and
`HttpURLConnection` request of an Android app (one Gradle plugin line, no code). Every request shows up in these places:

- an in-app screen (list, detail, cURL, share) behind a small floating button,
- one NDJSON line per request in logcat or the iOS unified log,
- a host CLI, `hopi`, whose `--json` output is a stable contract for AI coding agents,
- a desktop viewer (Compose Desktop, macOS first) that shows the same traffic on your screen.

Rules change what a request does while you debug: a made-up response (**mock**), a failure the
platform's own way (**fail**), a **delay**, or a rewritten request or response (**edit**). They
live in the app's memory for the session only, apply on every source to the extent each can
without weakening the app's TLS or pinning, and mark every record they touch (see
[Rules](#rules-mock-fail-delay-edit)).

It is built to cost nothing when nobody is looking: no timers, no polling, no request of its own,
a rule check of one volatile read per request while no rule exists,
and a `-noop` twin of every artifact, so release builds ship none of it. The design contract is
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); what may come later is in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

## Install

Hopi is not published to a Maven repository yet: the coordinates below are the ones it will
use. Until then, include the modules from this repository (as `sample/shared` does with
`project(":hopi-ktor")`).

| Artifact              | What it is                                              |
| --------------------- | ------------------------------------------------------- |
| `hopi-ktor`      | The Ktor client plugin                                  |
| `hopi-ui`        | `HopiScreen` and `HopiLauncher` (Compose MP)  |
| `hopi-ktor-noop` | Same public API as `hopi-ktor`, does nothing       |
| `hopi-ui-noop`   | Same public API as `hopi-ui`, renders nothing      |
| `hopi-urlsession` | Automatic capture of every URLSession request on iOS (`HopiUrlSession.start()`) |
| `hopi-urlsession-noop` | Same public API as `hopi-urlsession`, does nothing |
| `hopi-rules`     | `HopiRules`, rules from Kotlin or Swift code (tests, debug menus); the CLI, desktop and in-app screen need no dependency for rules |
| `hopi-rules-noop` | Same public API as `hopi-rules`, does nothing |
| `hopi-perf`      | `HopiPerf`, the performance monitors (frames, hangs, CPU, memory), each off until started |
| `hopi-perf-noop` | Same public API as `hopi-perf`, does nothing |
| `dev.koga.hopi` (Gradle plugin) | Automatic OkHttp and `HttpURLConnection` capture on Android; adds `hopi-android` to debug builds and `hopi-android-noop` to release builds |

Pick the real artifacts or the noop twins with a Gradle property in the shared module, because a
KMP module has no build types and iOS frameworks link the same klibs for debug and release:

```kotlin
// shared/build.gradle.kts
val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(if (hopiEnabled) "dev.koga.hopi:hopi-ktor:$v" else "dev.koga.hopi:hopi-ktor-noop:$v")
            implementation(if (hopiEnabled) "dev.koga.hopi:hopi-ui:$v" else "dev.koga.hopi:hopi-ui-noop:$v")
            // only to call HopiRules from code
            implementation(if (hopiEnabled) "dev.koga.hopi:hopi-rules:$v" else "dev.koga.hopi:hopi-rules-noop:$v")
        }
    }
}
```

Debug runs pass `-Phopi.enabled=true` (the Xcode build phase for the Debug configuration, as in
`sample/iosApp`). Anything built without it ships the noops, which contain no Hopi logic and no
`hopi-core`. On Android, apply the `dev.koga.hopi` plugin (below) to the application module instead:
it resolves the real modules in debug builds and the noops in release builds, whatever the
property says. Without the plugin, an Android-only app can select by build type
(`debugImplementation` real, `releaseImplementation` noop); an app module on top of a KMP shared
module must not, since the shared module already brings one of the two.

Install the plugin on each `HttpClient` you want to see, when the app starts:

```kotlin
val client = HttpClient(engine) {
    install(Hopi) {
        logSink = HopiLogSink.Summary   // one NDJSON line per request (the default)
        agentBridge = true                   // let the CLI connect (default false)
        redactHeaders("X-Api-Key")           // Authorization, Cookie, Set-Cookie… are redacted already
        redactQueryParameters("token")
    }
}
```

### Android: every OkHttp and HttpURLConnection request, automatically

One line in the app module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("dev.koga.hopi")
}
```

That is the whole setup. In debuggable variants the plugin rewrites, at build time, OkHttp's call
class and every `java.net.URL` connection call site of your app **and of every library it
depends on**, so every OkHttp client (yours, Retrofit's, an analytics or ads SDK's, Ktor's OkHttp
engine) and every `HttpURLConnection` is recorded, with no client to configure and no code to call:
the runtime starts itself from a provider in the debug manifest. Ktor's OkHttp engine is then
recorded once, by the capture (`"source":"okhttp"`), not also by the plugin. It only observes
(unless a rule you added applies): nothing is re-sent, no interceptor list your code can read
changes, and bodies are copied as your code reads them, up to `maxBodyBytes`, never read ahead.

Release (non-debuggable) variants are not instrumented at all and get `hopi-android-noop`; with the
plugin, the Ktor plugin and UI modules are also resolved per variant (real in debug, noop in
release), so an Android app needs no `-Phopi.enabled`. Optional settings:

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

`HopiUrlSession.start()` records every URLSession request of the process: your own code, the
SDKs you do not own (analytics, ads, crash reporters), `URLSession.shared`, custom delegates,
Swift `async`/`await`, and Ktor's Darwin engine (the plugin then leaves those requests to it, so
each request is recorded once). It only observes: nothing is re-sent and nothing your app sees
changes, unless a rule you added applies (and with rules on, sessions created after `start()` list
Hopi's protocol class first in `configuration.protocolClasses`; `rules = false` turns that off). Export the module from your framework, with the same real/noop switch:

```kotlin
// shared/build.gradle.kts
val urlSession = if (hopiEnabled) "dev.koga.hopi:hopi-urlsession:$v" else "dev.koga.hopi:hopi-urlsession-noop:$v"
kotlin {
    iosSimulatorArm64 { binaries.framework { export(urlSession) } }   // and iosArm64
    sourceSets { iosMain.dependencies { api(urlSession) } }
}
```

Then call it once, first thing, from Swift (or from Kotlin as `HopiUrlSession.start()`):

```swift
@main
struct MyApp: App {
    init() {
        HopiUrlSession.shared.start { $0.agentBridge = true }   // in a Release build: the noop, does nothing
    }
    // …
}
```

Requests whose task was resumed before `start()` are not recorded, so call it before anything
else, SDK initialisation included. Records carry `"source":"urlsession"`; request bodies sent as
a stream or a file are recorded as skipped (they are single-use, Hopi never reads them), downloads
by their size. WebSocket and stream tasks, AVPlayer and WKWebView traffic are not recorded. On
Android and the JVM `start()` does nothing.

Wrap the app's content to get the floating button, or show the screen yourself:

```kotlin
HopiLauncher {
    App()
}
// or
HopiScreen(onClose = { showInspector = false })
```

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

`hopi-desktop` shows a running app's traffic in a window on your Mac: the in-app list and
detail side by side, over the same bridge as the CLI (`agentBridge = true`, debug builds). The app
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
and on a selected request "Mock this response". In the app, the inspector's "Rules" button lists
the rules (on/off, remove, remove all), and a request's detail offers "Mock this response" (with
Undo). There is no rule editor on the phone. Replay (Hopi re-sending a recorded request) is not
part of the product; it is in the backlog with no priority ([`docs/ROADMAP.md`](docs/ROADMAP.md)).

From code, for tests and debug menus (`hopi-rules`; its noop twin does nothing and returns `-1`):

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

Swift calls the same API as `HopiRules.shared` once the framework exports `hopi-rules`.

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
unsupported there, and the rule says so when it is added. A mocked 3xx, 401 or 407 is the final
response on OkHttp, `HttpURLConnection` and URLSession (their follow-up logic sits below Hopi); the
Ktor plugin's sits above it, so Ktor follows them. Every recorder has a switch, `rules = false`
(`install(Hopi) { }`, `HopiUrlSession.start { }`, `hopi { }`), that keeps it from ever reading the
rule state; the app's `hello` then tells the CLI that rules are off.

## Performance monitors: frames, hangs, CPU, memory

`hopi-perf` (and its twin `hopi-perf-noop`) watches the app itself: **frames** (FPS and janky
frames; iOS: main-thread stalls), **hangs** (main-thread units of work over a threshold, 250 ms by
default, with the main thread's stack), **CPU** (the process, Hopi's own share, and per thread
while a viewer shows the list) and **memory** (heaps, resident memory, GC counts, the OS's trim
and memory-warning signals). Every monitor is off by default and opt-in on its own. A monitor that
is off has no thread, no listener, no timer and no class of its own loaded; one that is on states
its cost in [`docs/design/perf-monitors.md`](docs/design/perf-monitors.md) ("Performance budget",
with what is measured so far under "As built"). Nothing is persisted or sent anywhere: the data
lives in bounded rings in memory.

Android, with the `dev.koga.hopi` plugin: `perf { enabled = true }` adds `hopi-perf` to debug
builds (`hopi-perf-noop` to release builds) and registers it, so the CLI, the desktop viewer and the
in-app Perf tab can start monitors with no code. `autoStart` starts some at launch:

```kotlin
hopi {
    agentBridge = true
    perf {
        enabled = true
        autoStart(HopiMonitor.Hangs)   // default: none
        hangThresholdMs = 250          // 100..10 000
        hangHeartbeat = false          // true: keep another tool's Looper printer (wakes 8x/s while idle)
    }
}
```

KMP and iOS: depend on `hopi-perf` or `hopi-perf-noop` like the other twins (export it from the
iOS framework to call it from Swift) and install it once, before the UI:

```kotlin
HopiPerf.install { agentBridge = true }               // registers, starts nothing
HopiPerf.start { hangs = true; frames = true }         // or start from code
HopiPerf.screen("Checkout")                            // labels the janky frames and hangs that follow
HopiPerf.stop()
```

```swift
HopiPerf.shared.install { $0.agentBridge = true }
// xcrun simctl launch booted <bundle id> -hopiPerf frames,hangs   starts monitors at launch
```

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

In the app, the inspector's list gains a Perf button (monitor switches, summaries, trend charts,
the thread list, hangs with stacks), and a long press on the launcher button shows a small HUD
("58 fps · 4 jank · CPU 12% · 142 MB") that redraws at most once a second and not at all while
the app is idle. The desktop viewer has a Perf panel (⌘P) with charts of FPS, CPU and memory.

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
| Desktop viewer connected to the sample, Android emulator (API 35, `DeviceViewerUiTest`) | Hopi's 5 threads 0 CPU ticks over 60 s idle; 2 connections on the app side, never more through a force-stop, a relaunch and an adb server reset; 500 `POST /items` 2660 to 2781 ms connected, 2526 to 2704 ms disconnected |
| Release APK of the sample built without the flag | 12 Hopi classes (the noop twins), 0 from `hopi-core`; 345 and 97 with `-Phopi.enabled=true` (before the Gradle plugin; with it, 13 noop classes, no core or capture type referenced, whatever the flag) |
| Automatic Android capture, emulator (API 35), debuggable builds, small loopback GET | +85 to +95 µs of the calling thread's CPU and +350 to +390 µs wall per request against the noop build (`benchmark/android-capture/`) |
| Performance monitors, emulator (API 35, debug build), perf lab against outside references | 28 of 28 checks: 30 frames blocked 50 or 100 ms are 31 janky each (gfxinfo 29 to 30); hangs of 300 ms to 3 s and a 600 ms spin inside a Compose `onClick` caught once each within 0 to 5 ms, with stacks naming the lab's function; 2 burning threads 198 % (`/proc` 199 %); 64 MB heap and native allocations +64 MB; three trim levels. Rerun 2026-10-26 after the stage 5 fixes: 28 of 28 again; iPhone 17 simulator 21 of 21 (a 300 ms hang reads 300 to 302 ms on fresh installs, once Hopi names stacks only after the hang ended) |
| Performance monitors' cost, emulator, 10 000 main-thread messages per run, 9 runs per arm | nothing started = noop (0 CPU, 0 wakes over 60 s idle); frames +0; hangs (Looper printer) +6.2 to +6.5 µs per message, +29 µs per `Dispatchers.Main` resume, 0 idle; hangs on the heartbeat +0 per message, 8 wakes/s idle; CPU and memory 1 wake per second, about 1.0 ms CPU per sample (`schedstat`, a debuggable app runs it interpreted); all four on and idle +0.10 % of one core; iOS simulator: hangs +4.8 context switches/s (one per main run-loop wake, which wakes 6.6/s in the noop build too), all four +6 (`benchmark/perf/`) |

The first idle and the 300 KB body figures were taken with the earlier, Android-only version of
the sample; the rest in the phase 3 device acceptance. Physical devices have not been measured yet.

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

# The release APK must ship only the noop twins; the iOS release framework no capture code
./gradlew :sample:androidApp:verifyReleaseUsesNoop
./gradlew :sample:shared:verifyIosReleaseExcludesCapture
```

The Debug app launches on both simulators, the iPhone 17 (iOS 27.0) and the iPhone 16 (iOS 18.2):
an earlier Xcode 27.1 build aborted in dyld on iOS 18.2 (a CoreGraphics symbol iOS 18.2 lacks), and
the current build no longer does (perf lab run on the iPhone 16, 2026-10-26).

## Building

JDK 17, Gradle through `./gradlew` (9.7.1), Kotlin 2.4.20, Compose Multiplatform 1.12.0, Ktor
3.5.2. `./gradlew check` runs the tests (including the iOS simulator tests), the ABI dump checks and
the noop parity check (a real module and its twin must have exactly the same public API).
The giant-body benchmarks are opt-in and slow (minutes): run one module at a time with
`./gradlew :hopi-ktor:jvmGiantBodyBenchmark` (also `:hopi-core:` and `:hopi-ui:`)
or `./gradlew :hopi-cli:testGiantBodyBenchmark`; tables land in `<module>/build/giant-body/`.
