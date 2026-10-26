# Roadmap

v1 is the HTTP inspector (see `ARCHITECTURE.md`). Everything below is backlog, to be explored and
designed before it is built.

## Rule for every new tool

Each tool is its own module with its own `-noop` twin, **off by default**, and opt-in individually.
Its cost when off is zero, its cost when on is stated in its design doc and measured on the sample
app before it ships. Prefer platform-provided signals the OS already computes over sampling loops
that wake threads. Anything that has to sample states its interval and its overhead. This is the
line between Hopi and DebugSwift, which slows apps down by running every monitor at once.

Every tool exposes its data on all three surfaces: the in-app screen, the realtime log line, and
the bridge/CLI for AI agents.

## Backlog

### Accessibility audit
Explore. Inspect the Compose semantics tree of the current screen and flag problems: missing
content descriptions on images and icon buttons, touch targets under 48 dp / 44 pt, insufficient
text contrast, unlabeled inputs, focus order, text that does not scale with font size. On demand
only (a button or an agent command), never continuous. Open question: one CMP semantics walker for
both platforms vs the platform accessibility APIs (`AccessibilityNodeInfo`, UIAccessibility).

### Performance monitors (built)
Design: `docs/design/perf-monitors.md` (`hopi-perf` / `-noop`, each monitor off by default, its
cost stated per monitor). Built in four stages: the core model, bridge `perf.*`, log lines,
`HopiPerf` API and parity, Gradle `perf { }` and the release checks; the Android monitors
(FrameMetrics, the Looper printer with an input bracket or a heartbeat, one sampler); the iOS
monitors (run-loop observers and stack capture in C, stalls, display-link cadence on request,
`task_info` CPU and footprint, memory warnings); the surfaces (CLI `hopi perf …` and `hopi hangs`,
`hopi-connect`, the desktop Perf panel, the in-app HUD and Perf tab); and the sample's perf lab and
bench with `benchmark/perf/`. Verified on the Android emulator (28 of 28 checks: jank, hangs
including one inside a Compose `onClick`, a dialog, CPU, heaps, trim levels) and on the iOS
simulator (21 of 21 since stage 5, which fixed a leased hang watchdog that missed hangs, CPU's
first sample after memory, a tail that lost a hang's end, the Perf tab formatting every stack, the
spin lock, rings allocated while off, and the iOS 300 ms reading; design, "As built", stage 5). Measured: off costs nothing (identical to the noop build, 0 CPU and 0 wakes
idle); the printer costs +6.2 to +6.5 µs per main-thread message and +29 µs per `Dispatchers.Main`
resume (its 3 µs gate is not met; it stays the default, the heartbeat's alternative misses hangs
near the threshold and wakes 8 times a second; design, "As built", stage 4). Open:
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
- The CMP `MTLDrawable` spike; the HUD's redraw cost on a device; a frozen process mid-hang.

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
  analytics SDKs. No swizzling, no interception of vendor SDK internals. Noop twin, as always.
- **Constraints.** Declarative rule sets, built in for common providers and user-definable:
  max event name length, allowed name pattern (e.g. `^[a-zA-Z][a-zA-Z0-9_]*$`), reserved names and
  prefixes, max params per event, max param key / string value length, allowed value types,
  required params per event, max user properties. Example: Firebase limits event names to 40 chars,
  params to 25 per event, and string values to 100 chars.
- **Output.** Each event is shown with violations highlighted in the in-app screen, emitted as an
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
  helpers for common patterns like `Channel`/`SharedFlow` events). Noop twin.
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
  a device and app picker (Android lists the bridges listening on the device), auto-reconnect
  when the app restarts or adb resets. Reconnects are event-driven: `adb track-devices`, `adb
  track-jdwp` and a watch on the `bridge.json` directory, never a polling loop (the JDK's watch
  service polls on macOS, so a watch runs only while the viewer waits for the app, or for the
  picker's JVM processes while the window has focus and nothing is connected). On Android a
  bounded settle (at most 4 attempts over 5.5 s) follows an event, because the process appears
  before its bridge binds.
- **UI (built).** `hopi-ui`'s list/detail composables on the JVM target (`HopiPanes`),
  list and detail side by side, keyboard navigation (↓/↑, Enter, Esc, ⌘F, ⌘K, ⌘E, ⌘R), a status
  bar. Later: multiple windows/tabs per device, larger bodies shown more comfortably.
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
  emulator (`DeviceViewerUiTest`) the sample's 5 Hopi threads took 0 ticks over 60 s with
  the viewer connected, and 500 `POST /items` took 2660 to 2781 ms connected against 2526 to
  2704 ms disconnected (3 alternating rounds each).
- Open questions: whether the in-app screen becomes optional (noop-by-default UI artifact, with the
  desktop as the main viewer); physical iOS devices (no bridge in v1).

### Automatic platform capture (every request, third-party SDKs included)
- **iOS, URLSession (built).** `hopi-urlsession` / `-noop`: `HopiUrlSession.start()` records every
  URLSession request of the process, observe only, with no per-client setup (architecture doc,
  "URLSession capture"; design in `docs/design/urlsession-capture.md`). Ktor's Darwin engine
  defers to it (one record per request). Tested on iOS 18.2 and 27.0 simulators; per-hook cost
  and a per-request A/B measured on the simulator only. Open: a physical device run, background
  sessions beyond metadata, WebSocket tasks.
- **Android, OkHttp and `HttpURLConnection` (built).** The `dev.koga.hopi` Gradle plugin
  (`gradle-plugin/`) and `hopi-android` / `-noop`: one plugin line records every OkHttp call and
  every `HttpURLConnection` of the app and its dependencies, observe only, debuggable variants only
  (architecture doc, "Android capture"; design and measurements in `docs/design/android-capture.md`).
  Ktor's OkHttp and Android engines defer to it, except a request Ktor itself decodes
  (`ContentEncoding`), which the Ktor plugin keeps. OkHttp 3.14 is recorded, 3.12/3.13 are left
  alone. Verified end to end and measured on the API 35 emulator only.
  Open: the per-request cost after the speed fixes (2026-10-25, debuggable emulator, small
  request, capture minus noop): calling-thread CPU +16 to +26 µs on OkHttp and +26 to +31 µs on
  `HttpURLConnection` (the 30 µs target is met on OkHttp, at its limit on `HttpURLConnection`),
  median wall still 10 to 30 µs above the noop-vs-noop noise on both (about the calling thread's
  own CPU, so the wall target needs the CPU itself lower: the two kotlinx `Channel` lanes, profile
  item 2, and the `HttpURLConnection` response-header read, item 4, are the next levers); 10
  non-matching rules add about 7 µs on `HttpURLConnection` and 7 to 19 µs of process CPU on
  URLSession against a 3 µs target; a phone run of the same `captureBench` (on the emulator a
  futex wake is a VM exit, so the ranking may differ on hardware); the provider's startup (5.4 ms median on a debuggable emulator, target 1 ms); OkHttp 3.12/3.13; HTTPS through the
  wrapper on a device; a physical device run; Cronet and WebView.

### Rules: mock, edit (built)
- **Built** (architecture doc, "Rules"; design in `docs/design/rules.md`): **mock**, **fail**
  (timeout, reset, unknown host, cancel, each source's own error), **delay** (before request and/or
  response) and **edit** (request method, URL parts, headers, body; response status, headers,
  body), on every source (Ktor plugin, OkHttp, `HttpURLConnection`, URLSession) as far as each can
  without weakening the app's TLS, pinning or challenge handling; unsupported pieces are reported
  when a rule is added (URLSession: response edits, a before-response delay on a real request, body
  edits of file and stream uploads, background sessions, sessions created before `start()` other
  than `shared`). First match wins, one recorder applies it, every touched record is marked.
  Replay was built too and then removed (see "Replay (no priority)").
- Zero cost with no rule: one volatile read and a reference comparison per request (one
  `canInitWithTask:` answer per task on a URLSession session created after `start()`). Rules live
  in memory for the session only, never persisted, and exist only in the real artifacts
  (`hopi-core`); `hopi-rules-noop` has the same API and does nothing; the release checks cover it.
  `rules = false` on every recorder (Ktor, `HopiUrlSession`, `hopi { }`).
- Surfaces: bridge write commands (after a verified `hello`, never pipelined), blobs for large
  bodies, `hello.rules`, rules notices on `tail`; the CLI (`mock|fail|delay|edit add`,
  `rules ls|rm|clear|enable|disable|reset`, exit code 4); `HopiRules` (Kotlin, Swift
  `HopiRules.shared`); the desktop Rules panel (⌘L), "Mock this response"; the in-app rules list,
  "Mock this response". The sample's rules self-test and
  host targets (`--es rulesVerify all`, `-verifyRules`) drive every action on every source.
- Open:
  - Device verification ran on the API 35 emulator and the iPhone 17 (iOS 27.0) and
    iPhone 16 (iOS 18.2) simulators (rules self-test 44/44 and 32/32), and the desktop panel was
    checked against both through a composed `ViewerContent` test (41/41, 42/42); driving the real
    window needs Screen Recording and Accessibility grants this machine lacks.
  - The performance plan: 10 non-matching rules are measured (design, "Performance"; not met on
    `HttpURLConnection` and URLSession, unresolvable at 3 µs on OkHttp) and idle with rules set is
    0 wakes on both platforms; 1 and 64 rules and mock latency are still unmeasured.
  - The review pass over the whole feature and the "As built" notes in the design.
  - Pinning on device uses a public HTTPS host (the dev server has no TLS port); a background
    URLSession check is untested.
  - From the desktop rules check (2026-10-25, `build/evidence/speed/desktop/`): the shared list
    (`hopi-ui`, so probably the in-app screen too) keeps its first visible row in place, so new
    rows arriving on an untouched list land above the visible area (3 of 13 in one burst) — it
    needs "stay on newest" while the user has not scrolled; Android rule rows show URLSession's
    "unsupported" warnings for a rule with no sources set (by design, noise on Android); on iOS a
    response-edit rule never applies yet looks enabled apart from its warning line; `RulesPanel`'s
    Update sets `busy` before `form.ruleId ?: return` (unreachable today, a stuck panel if it
    ever were).
  - The desktop's rule "edit" changes only priority, times and hits (`rules.update` supports
    nothing else); the desktop form has no field for header predicates; a hit counted from a
    summary that arrives just after a re-list can be counted twice until the next one.
  - No Ktor-plugin rule source exists on iOS while URLSession capture runs (the Darwin engine is
    ruled by URLSession); sessions created before `start()` cannot be mocked.
  - Later: live breakpoints (pause and edit a request in flight), matching on the request body,
    stacking rules (a `continue` flag), mid-body faults and throttling. No separate mock server.

### Replay (no priority)
Backlog, no priority. Re-sending a recorded request (optionally edited) through the app's own
client was built on every source and then removed from the product, because it is the only feature
in which Hopi itself originates a request; everything else only observes or answers requests the
app makes. A full implementation (bridge `replay`, `hopi replay [--wait]`, `HopiRules.replay`, the
desktop and in-app buttons, the per-source hooks for Ktor, OkHttp, `HttpURLConnection` and
URLSession, and their tests) exists in git commit `c7daca0`; `docs/design/rules.md`, "Replay
(removed)", says what it took. Constraints if it is ever revived:
- Explicit command only: a bridge command, a CLI command or a button press, never automatic, never
  on a schedule, never as a side effect of another action.
- Through the app's own client or session only, so its interceptors, TLS settings, pinning, proxy,
  cookies and challenge delegate apply; Hopi never opens a connection of its own.
- Debug only: absent from release builds and from every noop twin, checked automatically like the
  rest of Hopi, with zero cost on every request while no replay runs.

### Later candidates (from DebugSwift's feature set)
- Console / log capture (app logs alongside HTTP).
- WebSocket and SSE inspection for Ktor.
- App info: build, device, environment, feature flags.
- Physical iOS device support for the CLI.
