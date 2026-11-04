# Roadmap

Hopi is a set of devtools for KMP and CMP apps. Built so far: the network inspector, rules and the
performance monitors (see `ARCHITECTURE.md`). Everything below not marked built is backlog, to be
explored and designed before it is built.

## Rule for every new tool

Each tool is its own runtime module, **off by default**, and opt-in individually. The API an app
calls lives in the optional `hopi-api`, which does nothing alone; the runtime is linked into debug
builds only; the Gradle plugin's DSL turns the tool on. (This replaced the noop twins, removed by
2026-10-30: `docs/design/api-runtime-split-plan.md`.)
Its cost when off is zero, its cost when on is stated in its design doc and measured on the sample
app before it ships. Prefer platform-provided signals the OS already computes over sampling loops
that wake threads. Anything that has to sample states its interval and its overhead. This is the
line between Hopi and DebugSwift, which slows apps down by running every monitor at once.

Every tool exposes its data on three surfaces: the realtime log line, the bridge/CLI for AI agents,
and the desktop viewer. There is no in-app screen (decided 2026-10-28; the in-app UI was removed in
the plan's stage 5).

## Backlog

### Accessibility audit
Explore. Inspect the Compose semantics tree of the current screen and flag problems: missing
content descriptions on images and icon buttons, touch targets under 48 dp / 44 pt, insufficient
text contrast, unlabeled inputs, focus order, text that does not scale with font size. On demand
only (a button or an agent command), never continuous. Open question: one CMP semantics walker for
both platforms vs the platform accessibility APIs (`AccessibilityNodeInfo`, UIAccessibility).

### Performance monitors (built)
Design: `docs/design/perf-monitors.md` (`hopi-perf`, the runtime behind `hopi-api`'s `HopiPerf`
since the runtime-split plan's stage 8; each monitor off by default, its cost stated per monitor).
Built in four stages: the core model, bridge `perf.*`, log lines,
`HopiPerf` API, Gradle `perf { }` and the release checks; the Android monitors
(FrameMetrics, the Looper printer with an input bracket or a heartbeat, one sampler); the iOS
monitors (run-loop observers and stack capture in C, stalls, display-link cadence on request,
`task_info` CPU and footprint, memory warnings); the surfaces (CLI `hopi perf …` and `hopi hangs`,
`hopi-connect`, the desktop Perf panel; the in-app HUD and Perf tab until the runtime-split plan's
stage 5 removed them); and the sample's perf lab and
bench with `benchmark/perf/`. Verified on the Android emulator (28 of 28 checks: jank, hangs
including one inside a Compose `onClick`, a dialog, CPU, heaps, trim levels) and on the iOS
simulator (21 of 21 since stage 5, which fixed a leased hang watchdog that missed hangs, CPU's
first sample after memory, a tail that lost a hang's end, the Perf tab formatting every stack, the
spin lock, rings allocated while off, and the iOS 300 ms reading; design, "As built", stage 5). Measured: off costs nothing (identical to the noop build of 2026-10-25, 0 CPU and 0 wakes
idle); the printer costs +6.2 to +6.5 µs per main-thread message and +29 µs per `Dispatchers.Main`
resume (its 3 µs gate is not met; it stays the default, the heartbeat's alternative misses hangs
near the threshold and wakes each of its two threads 8 times a second, 16 in all, since its
cadence fix (7.8 to 7.9 each on the emulator on 2026-10-27, its hangs read within the 125 ms
bound; 4 each before it); design, "As built", stage 4). Open:
- A physical Android phone and iPhone; every figure so far is from the emulator or the simulator.
- iOS overhead (observer cost per run-loop iteration, display link, sampler): `bench.py --platform ios`.
- The sampler's tick costs about 1.0 ms of CPU in a debug build (`schedstat`; budget 5 to 50 µs):
  a debuggable app runs it in ART's interpreter and cannot be AOT-compiled. All four monitors idle
  cost +0.10 % of a core (budget 0.1 %), at the line.
- iOS: the hang watchdog wakes once per main run-loop wake (+4.8/s on the simulator, whose main run
  loop wakes 6.6/s with nothing to do); fewer would take a timer armed on the main thread per unit.
- A cheaper busy/idle signal than the Looper printer, if one exists in the public SDK.
- iOS: the memory-pressure source is not driven; stack capture suspends 47 µs to 1.49 ms on the
  simulator, median 176 µs (budget 100 µs). Android: one capture of 11 paused 5.4 ms (budget 2 ms).
- Android: input inside a dialog's, sheet's or popup's own window is not bracketed (no public API
  reaches the window); only `hangHeartbeat` sees a block there.
- `dumpsys meminfo` does not show the lab's 64 MB of bitmap pixels as Native Heap, while
  `mallinfo` and RSS do.
- The CMP `MTLDrawable` spike; a frozen process mid-hang.

The original sketch, kept for the record:
- **Jank / frame timing.** Event-driven, not polled: Android `FrameMetrics` / `JankStats`, iOS
  `CADisplayLink` only while the monitor is on. Report janky frames with what was on screen.
- **Hangs / ANR.** Main-thread watchdog that detects stalls over a threshold (e.g. 250 ms) and
  captures the main thread's stack. One cheap heartbeat, documented cost.
- **CPU.** App process only: `/proc/self/stat` on Android, `task_info` / `thread_info` on iOS.
  Sampled, so interval and overhead stated; off by default.
- **Memory.** App process only: `Debug.MemoryInfo` / `ActivityManager` on Android,
  `task_vm_info.phys_footprint` on iOS. Sampled on demand or at a stated interval.
- **FPS overlay.** Derived from the frame-timing source above, never a separate timer.

### Analytics debugger
Explore. An inspector for the analytics events the app emits, with validation against
per-provider constraints, so bad events are caught in debug instead of silently dropped in prod.
- **Capture.** A tiny `hopi-analytics` API the app's own analytics wrapper calls
  (`HopiAnalytics.track(provider, name, params)`), plus optional adapters for common KMP
  analytics SDKs. No swizzling, no interception of vendor SDK internals. The API in `hopi-api`,
  the runtime a module of its own.
- **Constraints.** Declarative rule sets, built in for common providers and user-definable:
  max event name length, allowed name pattern (e.g. `^[a-zA-Z][a-zA-Z0-9_]*$`), reserved names and
  prefixes, max params per event, max param key / string value length, allowed value types,
  required params per event, max user properties. Example: Firebase limits event names to 40 chars,
  params to 25 per event, and string values to 100 chars.
- **Output.** Each event is shown with violations highlighted in the desktop viewer, emitted as an
  NDJSON log line (`"violations":[…]`), and available through the bridge/CLI
  (`hopi events --violations --json`) so an AI agent can audit tracking in a test run.
- **Cost.** Validation runs off the caller's thread, only for events Hopi receives; bounded ring
  buffer like the HTTP store.
- Open question: whether to also cross-check against a tracking plan file (event schema) checked
  into the repo.

### State tracker (UiState, actions, events)
Explore. A Redux-DevTools-style timeline for MVI/MVVM screens: every action (user intent) a
ViewModel receives, every UiState it emits, and every one-off event (navigation, toast, effect).
- **Capture.** Explicit, no reflection or bytecode tricks: `HopiState.track(scope, name, flow)`
  for a `StateFlow<UiState>`, plus `action(name, payload)` / `event(name, payload)` calls (or thin
  helpers for common patterns like `Channel`/`SharedFlow` events). The API in `hopi-api`, the
  runtime a module of its own.
- **Timeline.** Per screen/ViewModel: action, then the state transition it caused, then any
  events, linked by order and timestamp. State shown as a diff against the previous state, not a
  full dump, with the full value one tap away. HTTP transactions started during an action can be
  linked to it.
- **Rendering.** States are rendered lazily via `toString()` (data classes) or a user-supplied
  formatter / kotlinx-serialization serializer; nothing is formatted unless something is looking.
- **Agent access.** NDJSON lines (`{"hopi":1,"kind":"action|state|event","screen":…}`) and
  `hopi state tail --screen <name> --json`, so an AI agent can verify "tap X led to state Y"
  without reading the UI.
- **Cost.** Capture stores a reference plus a timestamp; diffing and formatting happen off the main
  thread, only on demand. Bounded ring buffer per tracked flow. Large or high-frequency states
  (e.g. scroll positions) are sampled or excluded by config.
- Open questions: time travel (replaying an old state into the UI) vs read-only in v1; how to
  capture states that are not data classes; privacy redaction of fields.

### Desktop viewer
Built in v1 (`hopi-desktop`, see the architecture doc's "Desktop viewer" section). A
Compose Desktop app (macOS first, an unsigned `.dmg`) that shows the app's traffic on the
developer's screen instead of inside the emulator, over the existing bridge protocol (plus two
additive fields: `reqHeld`/`resHeld` on summaries of cut bodies, `through` on the `clear` reply),
so the app pays no extra cost.
- **Connection (built).** The CLI's target code in the shared JVM module `hopi-connect`:
  `adb forward` + proof check on Android, `bridge.json` + token on the iOS simulator and the JVM,
  and on an iPhone over USB `bridge.json` copied with `devicectl` + a relay through usbmuxd (built
  2026-10-30, the runtime-split plan's stage 12; architecture doc, "iPhone over USB"),
  a device and app picker (Android lists the bridges listening on the device), auto-reconnect
  when the app restarts or adb resets. Reconnects are event-driven: `adb track-devices`, `adb
  track-jdwp` and a watch on the `bridge.json` directory, never a polling loop (the JDK's watch
  service polls on macOS, so a watch runs only while the viewer waits for the app, or for the
  picker's JVM processes while the window has focus and nothing is connected). On Android a
  bounded settle (at most 4 attempts over 5.5 s) follows an event, because the process appears
  before its bridge binds.
- **UI (built).** `hopi-ui`'s list/detail composables on the JVM target (`HopiPanes`),
  list and detail side by side, keyboard navigation (↓/↑, Enter, Esc, ⌘F, ⌘K, ⌘E, ⌘R), a status
  bar, the Rules (⌘L) and Perf (⌘P) panels; a shadcn/ui-style design system, light or dark with the system,
  with color for status only (the owner's decision of 2026-10-28). Later: multiple windows/tabs per
  device, larger bodies shown more comfortably.
- **Desktop extras.** Built: export a session as HAR 1.2 and NDJSON (bodies fetched on demand,
  bounded), copy as cURL. Later: open a body in an external editor, diff two transactions, filter
  by app when several are connected.
- **Bodies (built).** Fetched on demand with `get` only for the selected transaction; the list
  streams summaries via `tail`, so a giant body never crosses the bridge unless opened.
- **Distribution.** Built: an unsigned `.dmg` (`:hopi-desktop:packageDmg`, 65 MB). Later:
  signing and notarization, `jpackage` builds for Linux/Windows. Never shipped as a dependency of
  the app.
- **Cost on the app.** Same as a connected CLI `tail`: measured on the JVM bridge, the app's
  bridge threads take 0 ns CPU while the viewer is connected and idle; on the API 35 emulator
  (`DeviceViewerUiTest`, 2026-10-23, before automatic capture) the sample's 5 Hopi threads took
  0 ticks over 60 s with the viewer connected (6 threads, 0 ticks on 2026-10-27 and 2026-10-28),
  and 500 `POST /items` took 2660 to 2781 ms connected against 2526 to 2704 ms disconnected (3
  alternating rounds each; 2627 to 2744 against 2529 to 2696 ms on 2026-10-28).
- **`DeviceViewerUiTest` on Android, fixed 2026-10-28** (the runtime-split plan's stage 4; stale
  from 2026-10-27, 17 of 18): "5 MB body opens" failed because OkHttp capture recorded the giant
  GET at the `hopi { }` cap, which `--el maxBodyBytes` never reached; the test now needs the debug
  build installed with `-Psample.maxBodyBytes=8388608`, which the start presets. Its overhead step
  counted the POSTs in a stale `build/evidence/desktop/devserver.log` and looked for the result
  line below the fold of the Diagnostics tab; it now counts them in `<evidence>/devserver.ndjson`
  and reads the line from logcat. Its adb reset accepts a server that another adb client started
  first. 18 of 18 pass with figures.
- Open: an iPhone over USB is tested against a stand-in usbmuxd only (and the framing against
  Apple's daemon with no phone attached): the run on a phone, which also settles what the
  architecture doc lists as assumed there, is the owner's, as is an Android phone over USB and
  wireless adb. usbmuxd's attach and detach events (`Listen`) are not used, so the viewer sees
  neither a phone plugged in nor its app starting: Refresh and Reconnect (⌘R) do it by hand.
  (Whether the in-app screen becomes optional is settled: it was removed on 2026-10-28, and the
  desktop viewer and the CLI are the surfaces.)

### Automatic platform capture (every request, third-party SDKs included)
- **iOS, URLSession (built).** `hopi-network` (since the runtime-split plan's stage 9),
  started by `hopi-start`'s `Hopi.start { }`: records every URLSession request of the process,
  observe only, with no per-client setup (architecture doc, "URLSession capture"; design in
  `docs/design/urlsession-capture.md`). Ktor's Darwin engine is one of its URLSession clients, one
  record per request: since the runtime-split plan's stage 10 the Ktor plugin installs nothing on
  iOS, so nothing defers to capture any more (from 2026-10-27 until then, a Ktor request in flight
  when `start()` ran was recorded once or missed, never twice, and a viewer attached at that moment
  saw its start end as failed, "withdrawn: …"; the desktop still ends a row it missed while away or
  behind as gone when it resumes); a Ktor engine that does not send through URLSession (CIO) is not
  recorded on iOS. Tested on iOS 18.2 and 27.0 simulators; per-hook cost
  and a per-request A/B measured on the simulator only. Open: a physical device run, background
  sessions beyond metadata, WebSocket tasks.
- **Android, OkHttp and `HttpURLConnection` (built).** The `dev.koga.hopi` Gradle plugin
  (`gradle-plugin/`) and `hopi-network` (since the runtime-split plan's stage 9; the plugin's
  release guard fails a release variant that resolves a runtime): one plugin line records
  every OkHttp call and every `HttpURLConnection` of the app and its dependencies, observe only,
  debuggable variants only (architecture doc, "Android capture"; design and measurements in
  `docs/design/android-capture.md`).
  Ktor's OkHttp and Android engines defer to it, except a request Ktor itself decodes
  (`ContentEncoding`), which the Ktor plugin's recorder keeps (`hopi-network`'s since the
  runtime-split plan's stage 10, registered by the network tool). OkHttp 3.14 is recorded,
  3.12/3.13 are left alone. Verified end to end and measured on the API 35 emulator only.
  Open: the per-request cost after the speed fixes (2026-10-25, debuggable emulator, small
  request, capture minus noop): calling-thread CPU +16 to +26 µs on OkHttp and +26 to +31 µs on
  `HttpURLConnection` (the 30 µs target is met on OkHttp, at its limit on `HttpURLConnection`;
  on 2026-10-27, after the bug fixes, 8 rounds: medians +24 µs on OkHttp, 2 rounds over 30, and
  +22.5 µs on `HttpURLConnection`, 1 over 30; the fixes, the store's queued-bytes budget among
  them, cost a median of +3 µs of calling-thread CPU on OkHttp, up to about 10 not ruled out, and
  0 on `HttpURLConnection` against the tree before them, android-capture.md, "Review fixes"),
  median wall still 10 to 30 µs above the noop-vs-noop noise on both (about the calling thread's
  own CPU, so the wall target needs the CPU itself lower: the two kotlinx `Channel` lanes, profile
  item 2, and the `HttpURLConnection` response-header read, item 4, are the next levers); 10
  non-matching rules add about 7 µs on `HttpURLConnection` and 7 to 19 µs of process CPU on
  URLSession against a 3 µs target; a phone run of the same `captureBench` (on the emulator a
  futex wake is a VM exit, so the ranking may differ on hardware); the provider's startup
  (5.4 ms median on a debuggable emulator, target 1 ms); OkHttp 3.12/3.13; HTTPS through the
  wrapper on a device; a physical device run; Cronet and WebView.

### Rules: mock, edit (built)
- **Built** (architecture doc, "Rules"; design in `docs/design/rules.md`): **mock**, **fail**
  (timeout, reset, unknown host, cancel, each source's own error), **delay** (before request and/or
  response) and **edit** (request method, URL parts, headers, body; response status, headers,
  body), on every source (the Ktor plugin on Android and the JVM, OkHttp, `HttpURLConnection`,
  URLSession, which also rules Ktor's Darwin engine) as far as each can
  without weakening the app's TLS, pinning or challenge handling; unsupported pieces are reported
  when a rule is added (URLSession: response edits, a before-response delay on a real request, body
  edits of file and stream uploads, background sessions, sessions created before `start()` other
  than `shared`). A rule that names no sources is checked against the platform's own sources only,
  so an Android app hears nothing of URLSession; the desktop panel (and the in-app list, until the
  runtime-split plan's stage 5) show a source
  that never applies a rule as "not applied on <source>: …" and a rule as "inactive" when every
  source it is checked against (the named ones, or the platform's when it names none) excludes it;
  a rule naming none never reads "inactive", since its line does not carry the app's platform
  (on Android and the JVM the Ktor recorder excludes nothing anyway; on iOS, URLSession alone since
  the runtime-split plan's stage 10, a response edit naming none applies nowhere, see Open). First
  match wins, one recorder applies it, every touched record is marked. Every platform mock and
  response edit carries `Cache-Control: no-store` (since stage 10), so a cache above the stack,
  such as Ktor's `HttpCache`, never serves one after its rule is gone.
  Replay was built too and then removed (see "Replay (no priority)").
- Zero cost with no rule: one volatile read and a reference comparison per request (one
  `canInitWithTask:` answer per task on a URLSession session created after `start()`). Rules live
  in memory for the session only, never persisted, and exist only in the runtime (`hopi-core`):
  since the runtime-split plan's stage 7 `HopiRules` is in `hopi-api`, in every build, and does
  nothing until a debug build's start registers core's rules runtime (a rule added before it is
  dropped and counted in one notice line); the release checks cover it.
  `rules = false` on every recorder (Ktor's `install(Hopi) { }`, `Hopi.start { }` on iOS and the
  JVM, `hopi { network { } }`; the network tool's covers the Ktor recorder it registers).
- Surfaces: bridge write commands (after a verified `hello`, never pipelined), blobs for large
  bodies, `hello.rules`, rules notices on `tail`; the CLI (`mock|fail|delay|edit add`,
  `rules ls|rm|clear|enable|disable|reset`, exit code 4); `HopiRules` (Kotlin, Swift
  `HopiRules.shared`); the desktop Rules panel (⌘L), "Mock this response"; the in-app rules list
  and "Mock this response" (removed with the in-app UI in the runtime-split plan's stage 5). The
  sample's rules self-test and
  host targets (`--es rulesVerify all`, `-verifyRules`) drive every action on every source.
- Open:
  - Device verification ran on the API 35 emulator and the iPhone 17 (iOS 27.0) and
    iPhone 16 (iOS 18.2) simulators (rules self-test 44/44 and 32/32), and the desktop panel was
    checked against both through a composed `ViewerContent` test (41/41, 42/42); again on the
    emulator on 2026-10-27, after the bug fixes: 36 of 36 (replay's checks gone with it), and an
    untouched list now keeps the newest row in view after a burst, which it did not on
    2026-10-25; the iOS run was not repeated. Driving the real window needs Screen Recording and
    Accessibility grants this machine lacks.
  - The performance plan: 10 non-matching rules are measured (design, "Performance"; not met on
    `HttpURLConnection` and URLSession, unresolvable at 3 µs on OkHttp) and idle with rules set is
    0 wakes on both platforms; 1 and 64 rules and mock latency are still unmeasured.
  - The review pass over the whole feature and the "As built" notes in the design.
  - Pinning on device uses a public HTTPS host (the dev server has no TLS port); a background
    URLSession check is untested.
  - The desktop's rule "edit" changes only priority, times and hits (`rules.update` supports
    nothing else); the desktop form has no field for header predicates.
  - The desktop's hits are the app's (re-listed on a ruled summary or `get` reply, 2026-10-27).
  - No Ktor-plugin rule source exists on iOS (since the runtime-split plan's stage 10 the plugin
    installs nothing there; the Darwin engine is ruled by URLSession); sessions created before
    `start()` cannot be mocked.
  - On iOS a response edit, or a before-response delay on a real request, that names no sources
    applies nowhere (URLSession, the platform's only source since stage 10, excludes both), yet
    still reads active (switch on, no "inactive"): its "not applied on urlsession" line is the only
    sign, since the rule line does not carry the app's platform and the hosts mark only a rule
    whose named sources all exclude it.
  - A rule naming only sources the platform lacks (okhttp on iOS) reads active with no line, and
    the match shorthand does not show its sources.
  - On iOS an app without URLSession capture still gets the URLSession exclusion line for a rule
    that names no sources: the platform's sources are a fixed set, whether `Hopi.start` started
    capture or not.
  - A mock copied from a record (`fromTransaction`, "Mock this response", `--from`) decodes the
    single gzip or deflate an OkHttp or `HttpURLConnection` record holds raw (up to 1 MiB) and
    refuses br, zstd and stacked codings; a Ktor client without `ContentEncoding` whose request
    names its own `Accept-Encoding` records raw bytes under `ktor` (on Android and the JVM; on iOS
    URLSession decodes, stage 10's R3), and those are still copied as held.
  - Every mock and response edit on OkHttp and `HttpURLConnection`, and every mock on URLSession,
    carries `Cache-Control: no-store` (since 2026-10-29), so an app cannot exercise its own HTTP
    cache through such a rule; an exemption for a rule's own `Cache-Control` is the owner's
    decision.
  - Later: live breakpoints (pause and edit a request in flight), matching on the request body,
    stacking rules (a `continue` flag), mid-body faults and throttling. No separate mock server.

### Gradle plugin on KMP modules
Done (the runtime-split plan's O8, 2026-11-03): `dev.koga.hopi` in a KMP shared module adds
`hopi-api` to every build and `hopi-start` to a Debug one (`-Phopi.enabled`, else Xcode's
`CONFIGURATION` as the Kotlin plugin reads it), generates `HopiSetup` for Swift's one call and
applies the iOS release guard itself. Left: CocoaPods' configuration property
(`kotlin.native.cocoapods.configuration`) and JVM targets, which the plugin does not handle.

### Consumer floor: Kotlin 2.2, AGP 8.0, Gradle 8.0
Done (compat W1 and W2, 2026-11-04): Hopi is built by Kotlin 2.2.21, with Ktor 3.3.3 and
kotlinx-serialization 1.9.0, so apps on Kotlin 2.2.0 (Android and JVM) and 2.2.21 (iOS) consume
it; the AARs ask for compileSdk 34; the plugin runs on Gradle 8.0, AGP 8.0 and, in a KMP module,
the Kotlin Gradle plugin 2.2.0 (README's requirements). `:checkPublication` holds the published
artifacts to the floor, `functionalTestFloor` runs the plugin's TestKit tests on it, and
`compat/run.sh` builds an Android, a JVM and a KMP app on it and on the current toolchain. While
the floor stands, Hopi stays on dependencies built by Kotlin 2.2 (Ktor 3.4 and
kotlinx-serialization 1.10 are built by 2.3). Left:
- Kotlin/Native 2.2.0 to 2.2.20 reading Hopi's klibs (`compat/README.md` has the 2.2.0 run; it
  needs 5 GB free), which would lower the iOS floor from 2.2.21.
- An Xcode older than 27.1 (Kotlin 2.2.21 lists 26.0), which needs a CI Mac.
- The fixtures' APKs and frameworks at work on a device or simulator, and a KMP module with an
  Android target on the floor.

### Replay (no priority)
Backlog, no priority. Re-sending a recorded request (optionally edited) through the app's own
client was built on every source and then removed from the product, because it is the only feature
in which Hopi itself originates a request; everything else only observes or answers requests the
app makes. A full implementation (bridge `replay`, `hopi replay [--wait]`, `HopiRules.replay`, the
desktop and in-app buttons, the per-source hooks for Ktor, OkHttp, `HttpURLConnection` and
URLSession, and their tests) exists in git commit `a5d160b`; `docs/design/rules.md`, "Replay
(removed)", says what it took. Constraints if it is ever revived:
- Explicit command only: a bridge command, a CLI command or a button press, never automatic, never
  on a schedule, never as a side effect of another action.
- Through the app's own client or session only, so its interceptors, TLS settings, pinning, proxy,
  cookies and challenge delegate apply; Hopi never opens a connection of its own.
- Debug only: absent from release builds, checked automatically like the rest of Hopi, with zero
  cost on every request while no replay runs.

### Later candidates (from DebugSwift's feature set)
- Console / log capture (app logs alongside HTTP).
- WebSocket and SSE inspection for Ktor.
- App info: build, device, environment, feature flags.
