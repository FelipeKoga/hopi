# Design: performance monitors (frames, hangs, CPU, memory)

Status: design (2026-10-25, revised the same day after an adversarial review: input-dispatch
hangs, printer ownership, stack-capture races, FrameMetrics window pitfalls, false hangs, lease
owners). Stage 1 of 4 (core and Android) built 2026-10-25; stage 2 (iOS monitors) built
2026-10-25, tested on the simulator; stage 3 (surfaces: CLI, `hopi-connect`, desktop panel,
in-app HUD and Perf tab, the last two removed with the in-app UI in stage 5 of
docs/design/api-runtime-split-plan.md) built 2026-10-25, host-tested; stage 4 (the sample's perf
lab and bench, device verification on the Android emulator and the iOS simulator, the overhead
A/B) done 2026-10-25: see "As built". The printer's 3 µs gate was not met (stage 4); the printer
stays the default, for reasons stated there. The roadmap item "Performance monitors". Four monitors,
each off by default and opt-in on its own: **frames** (FPS and jank), **hangs** (main-thread
stalls with the main thread's stack), **CPU** (process and per-thread), **memory** (heaps,
footprint, OS memory warnings). This is where Hopi must differ most from DebugSwift, which runs
every monitor at once and slows the app: a monitor that is off has no thread, no listener, no
timer and no loaded class of its own; a monitor that is on states its cost below, and the cost is
measured on the sample before it ships.

**Since stage 8 of docs/design/api-runtime-split-plan.md (2026-10-29)** the consumer API
(`HopiPerf`, `HopiMonitor`, `HopiPerfConfig`) lives in `hopi-api`, the same artifact in every
build, and `hopi-perf` is a runtime no app names: perf's tool registers it at start, the Gradle
plugin adds it to debuggable build types only, `hopi-start` to Debug iOS frameworks, and
its noop twin is deleted. `HopiPerf.start` applies only what its block sets; `agentBridge` and
`summaryLog` moved to the start's configuration, `remoteControl` to perf's part of it. The
sections below describe that. History note (2026-10-30): the plan's stage 11 then removed the
parity check `:checkNoopParity` and the plugin's twin substitution; the performance plan, the
implementation order and "As built" keep each stage's record as it was, noop builds, twins and
parity included.

This design builds on the architecture's store and bridge contracts (`docs/ARCHITECTURE.md`,
"Store", "Agent access") and on the patterns of `docs/design/rules.md`: a model in core that the
bridge serves, a thin consumer API in `hopi-api` (a noop twin with no dependency until stage 8
of the runtime-split plan), write commands only after a verified `hello`.

## Goals and non-goals

Goals:

- **Off is free.** With the module in the build and no monitor started: 0 threads, 0 listeners,
  0 wakes, no monitor class loaded. `IdleWakeupTest`-style checks prove it.
- **On is bounded and stated.** Each monitor's interval, wakes per second and CPU per event are in
  the budget table below, with targets the performance plan checks on the emulator and the
  simulators. Prefer what the OS already computes (FrameMetrics, `thread_info.cpu_usage`,
  `phys_footprint`, trim and memory-pressure callbacks) over Hopi's own sampling.
- **Accurate enough to act on.** An injected 100 ms main-thread stall shows as janky frames of
  about 100 ms; an injected 600 ms hang shows once, with a stack that names the method that hung,
  whether it runs in a posted message or synchronously inside a Compose click handler (input
  dispatch, see "Hangs"). No false hang from a debugger pause or a frozen/suspended process.
- **Zero production impact.** Monitors and model exist only in debug artifacts; a release build
  holds `hopi-api`'s `HopiPerf`, which does nothing without a runtime; release checks extended
  (below).
- **Offline, in memory.** Bounded rings, never persisted, never sent anywhere; no new transport.
- **Every surface.** Realtime log lines, bridge/CLI for agents, desktop viewer charts (the in-app
  HUD and Perf tab were removed with the in-app UI; see "In-app (removed)").

Non-goals (v1):

- Leak detection, allocation tracking, heap dumps, method tracing, energy, network throughput,
  disk I/O, startup time. Each would be its own tool under the roadmap rule.
- Physical iOS devices over the bridge (none in v1); the os_log lines work there. (Since the
  runtime-split plan's stage 12, 2026-10-30, the CLI and the desktop viewer reach an iPhone over
  USB, `hopi perf` included; tested against a stand-in usbmuxd only, with no phone attached.)
- Hang detection on the JVM target (no main looper; the JVM gets CPU and memory only, for tests
  and, since the runtime-split plan's stage 10, for JVM apps through `hopi-start`'s `Hopi.start`).
- Symbolicating native (C/C++) frames on Android; release-build symbolication on iOS.

## What the existing tools do, and what we take

| Tool | Mechanism | Verdict |
| --- | --- | --- |
| DebugSwift (iOS) | FPS via `CADisplayLink`, CPU and memory on timers, leak tracking, all running together | Rejected as a whole: every monitor on at once, timers always running. Taken: `phys_footprint` and `thread_info` as the data sources. |
| androidx `JankStats` (`metrics-performance`) | `OnFrameMetricsAvailableListener` per window, `OnPreDrawListener` on the decor view for API < 24, a `PerformanceMetricsState` holder per view tree | Not taken as a dependency: it adds `metrics-performance` plus androidx.core (Hopi has neither), hooks every decor view's pre-draw, and allocates a `FrameData` per frame on API < 31. Hopi needs its classification only (about 40 lines, below). Taken: the 2x-deadline "janky" heuristic and the `DEADLINE` metric on API 31+. |
| BlockCanary, Matrix (Android) | `Looper.setMessageLogging` printer timing each message; Matrix adds a heartbeat | **Taken**: the printer as the busy/idle signal, with the watchdog armed only while a message runs. |
| Sentry, KSCrash watchdog (iOS) | `CFRunLoopObserver` plus a watchdog thread, stacks via `thread_get_state` | **Taken**, with the frame walk in C and no signal. |
| MetricKit (`MXHangDiagnostic`, `MXAnimationMetric`) | OS-aggregated, delivered once a day | Rejected for realtime: wrong latency for a debugger. |
| Android `ApplicationExitInfo` (API 30+) | The OS records an ANR's traces; readable on the next start | **Taken** as a free extra: the previous run's ANR, read once when the hang monitor starts. |

## Module layout

| Module | Content |
| --- | --- |
| `hopi-core`, package `dev.koga.hopi.core.perf` (all `@HopiInternalApi`) | The model (rings, events, histograms, summaries), its JSON (bridge replies, log lines), the `perf.*` bridge commands, the rate-limited log sink, and `PerfHook`: a registry holding at most one `PerfController` (start, stop, status) that `hopi-perf` registers. Nothing here starts a thread. |
| `hopi-api` | `HopiPerf`, its config and enum (the consumer API, since the runtime-split plan's stage 8), over the perf runtime registered in `HopiRuntimes`, the registry's perf slot with the starts dropped before it and the last `screen` label, and the runtime's interface `HopiPerfRuntime` (`@HopiInternalApi`) |
| `hopi-perf` | The runtime: the monitors in `dev.koga.hopi.perf.internal`, with the start sequence's perf tool `PerfEntry` (Android, iOS since the runtime-split plan's stage 6, the JVM since stage 8, which `hopi-start`'s `Hopi.start` lists there since stage 10, CPU and memory only) and `ApiPerf`, the runtime behind `HopiPerf`; the iOS native library (`src/nativeInterop/objc`, as built: run-loop observers, main-thread stack walk, `task_info`/`thread_info` reads, the display link); depends on core; targets android, iosArm64, iosSimulatorArm64, jvm; no consumer API (its ABI dumps are empty) |
| `hopi-ui` | Nothing since the runtime-split plan's stage 5: its HUD and Perf tab went with the in-app UI (see "In-app (removed)") |
| `hopi-connect`, `hopi-cli`, `hopi-desktop` | `PerfClient` and decoders, `hopi perf …` / `hopi hangs`, the Perf panel |
| `gradle-plugin` | `hopi { perf { … } }`: `hopi-perf` in debuggable build types and its tool in the tool list |

Why the model lives in core: the bridge and the log sink are core consumers and must show perf
data without depending on `hopi-perf`. This is `RuleEngine`'s split. Why one module and not one per
monitor: zero cost when off comes from lazy loading, not from absent bytes; four runtimes would
add four tools and four registrations for no runtime gain. Core's `perf` package is a few small
classes, loaded only when something touches `PerfHook` with a controller registered, except the
resumed activity's reference on Android (`CurrentActivity`, under Frames), which core's start
writes whatever tools are listed.

## Consumer API

```kotlin
public object HopiPerf {                                         // hopi-api, package dev.koga.hopi.perf
    public fun start(block: HopiPerfConfig.() -> Unit)          // start the monitors set true; apply the settings set
    public fun stop(vararg monitors: HopiMonitor)                // none named: stop all
    public val running: Set<HopiMonitor>
    public fun screen(name: String?)                             // label for "what was on screen"
}
public enum class HopiMonitor { Frames, Hangs, Cpu, Memory }
public class HopiPerfConfig internal constructor() {
    public var frames: Boolean = false
    public var hangs: Boolean = false
    public var hangThresholdMs: Long = 250                       // 100..10_000
    public var hangHeartbeat: Boolean = false                    // Android: heartbeat instead of the Looper printer (see Hangs)
    public var cpu: Boolean = false
    public var memory: Boolean = false
    public var sampleIntervalMs: Long = 1_000                    // CPU and memory, 250..10_000
}
```

Swift: `HopiPerf.shared.start { $0.frames = true; $0.hangs = true }`, where the framework exports
`hopi-api` (in every configuration, as for `HopiRules`). A monitor set true starts as owner
`code`; false leaves it as it is, never a stop. The builder records which of the three settings
the block set, and the runtime applies only those, merged under its lock: a setting left unset
keeps the value in force (the plugin's `perf { }`, a remote `perf.start`'s), where before stage 8
of the runtime-split plan every call reset all of them to their defaults. Invalid values write a
notice line and start nothing (Swift cannot catch); `hopi-api` validates nothing. Its defaults are
literals, since `hopi-api` depends on nothing: `PerfDefaultsTest` (`hopi-perf`'s JVM tests)
compares them with `PerfLimits`, and `HopiMonitor`'s entries with `PerfMonitor`'s, in order. The
runtime behind the API is `hopi-perf`'s `ApiPerf`, which implements `hopi-api`'s
`@HopiInternalApi` `HopiPerfRuntime` and only forwards, to `ApiPerfCalls` (see "How the monitors
find the app"); nothing throws to the caller. The start's configuration holds what is not a
monitor's: `agentBridge` and `summaryLog` are global (`hopi { }`, `Hopi.start { }`), so
`summaryLog = false` also silences perf's rate-limited lines (its notices are written whatever it
says), and `remoteControl` is perf's own part of it (`perf { remoteControl }` on Android,
`perfRemoteControl` on iOS), set at start only.

**Calls before the runtime registers.** There is no queue (the runtime-split plan's E4.4). A
`start` runs its block, is counted by `hopi-api`'s registry and returns; perf's tool, when its
start registers the runtime, writes one notice line with the count in perf's channel (logcat tag
`HopiPerf`, os_log category `perf`), whatever `summaryLog` says: `HopiPerf: <n> starts made before
Hopi started were dropped`. An iOS Debug binary also writes one line to the system log at the
first dropped start (`NSLog`), since there the start is a call the app makes and may be missing.
`stop` does nothing, `running` is empty, and `screen` keeps one label, the last, which the
registration applies (re-read until two reads agree, so a label set meanwhile is not lost). A
start with invalid caps registers nothing, so `HopiPerf` stays inert until a valid start runs the
tools.

**How the monitors find the app.** Through perf's tool only: no `HopiPerf` call creates or
registers the runtime. The tool registers a `PerfController` in core's `PerfHook` (one object, no
thread) and `ApiPerf` in `hopi-api`'s registry. On Android, perf's tool `PerfEntry` does it when
the plugin put `hopi-perf` in the variant: the plugin's generated configuration, a Java class (not
resources; see android-capture.md, "Auto-start"), lists `new PerfEntry(autoStart,
hangThresholdMs, hangHeartbeat, remoteControl)` in its tool list, and the provider's start
sequence starts it like any tool, through a direct reference (since the runtime-split plan's
stage 4, 2026-10-28; before, the provider loaded it with one `Class.forName` when the generated
class said `perfPresent`). Without the perf block the list does not name it, so no perf class is
looked up or loaded, and `HopiPerf` does nothing. So a debug app with the plugin is remotely
controllable without code, and no monitor class is loaded until a monitor starts: the tool runs on
the main thread, and a debug build runs from its APK, where ART loads every class a method names
when it verifies the class, so the backend the tool creates (`AndroidBackend`) names no monitor
class and forwards to the monitors' holder (`AndroidMonitors`), created at the first start, as
`ApiPerf` forwards to `ApiPerfCalls`. `hopi-perf` still ships a consumer R8 keep rule for
`PerfEntry` (harmless: R8 keeps a directly referenced class anyway). The start passes the
`Application` (frames and the hang monitor's input bracket need each activity's `Window`, memory
events need `registerComponentCallbacks`), and core's start keeps the resumed activity in
`CurrentActivity` through one `ActivityLifecycleCallbacks` the provider registers whatever tools
are listed (no thread, no timer), so a frames or hangs start attaches to it at once. Without the
`Application` hangs run with the printer only (no input bracket), CPU and memory samples run, and
frames and memory events report themselves unavailable with a notice; never `ActivityThread`
reflection. On iOS nothing runs until `hopi-start`'s `Hopi.start` starts perf's iOS tool
`PerfEntry` (listed unless its `perf` is false, with its `perfRemoteControl`), which creates the
controller with the default settings and no bridge of its own (the start's `agentBridge` starts
it); a launch argument `-hopiPerf frames,hangs` is read by the tool's first start (one
`NSProcessInfo` lookup) so an agent can measure a cold start: `xcrun simctl launch <udid> <id>
-hopiPerf hangs`. Work before the start runs (pre-main, `+load`, static initializers, the start of
`didFinishLaunching`) is not covered. On the JVM a tool of the same shape, CPU and memory only,
is listed by `hopi-start`'s `Hopi.start` unless its `perf` is false (since stage 10 of the
runtime-split plan; `hopi-perf`'s own tests start it directly), with its log lines on stderr; a JVM
process that does not call `Hopi.start` registers no runtime, so there `HopiPerf` does nothing.

**Gradle DSL** (Android, generated into the app's configuration class like the other settings, as
the perf tool's constructor arguments; off by default):

```kotlin
import dev.koga.hopi.gradle.HopiMonitor   // the plugin's type, not a default import of a build script

hopi {
    perf {
        enabled = false          // true: hopi-perf in debuggable variants only, its tool in the tool list
        autoStart(HopiMonitor.Hangs, HopiMonitor.Frames)   // started by the perf tool at start; default none
        hangThresholdMs = 250
        hangHeartbeat = false    // true: keep another tool's Looper printer, pay the heartbeat's wakes
        remoteControl = true     // false: the bridge's perf.start, perf.stop and a tail's start are refused
    }
}
```

## Frames (FPS and jank)

**Android.** While on: one `HandlerThread` `hopi-perf-frame` at nice 5, the store's choice (not
`THREAD_PRIORITY_BACKGROUND`, which on many devices also moves the thread to the background cgroup,
where it would starve exactly when frames are janky), and
`Window.addOnFrameMetricsAvailableListener(listener, handler)` on each started activity's window,
tracked with `ActivityLifecycleCallbacks` registered only while on: attached on start, removed on
stop (not on pause: in multi-window and picture-in-picture a paused activity is visible and still
draws, so several windows can be attached at once; the listener state is per window). Library
minSdk 24 is exactly FrameMetrics' floor, so there is no pre-24 path.

Window pitfalls, each handled, none silent:

- **Decor view.** `addOnFrameMetricsAvailableListener` calls `getDecorView()`, which *creates* the
  decor on a window that has none: Hopi attaches only when `window.peekDecorView() != null`, else
  at the activity's next start, so it never changes the app's window.
- **Software rendering.** A window without `FLAG_HARDWARE_ACCELERATED` (or a decor without a
  hardware renderer) never reports metrics; the framework only logs a warning. Hopi checks the flag
  and reports that window as `"unmeasured":"software"` in `perf.status` instead of silently
  showing 0 fps.
- **Removal.** `removeOnFrameMetricsAvailableListener` throws for a listener the window does not
  hold; Hopi removes only what it attached (its own per-window record), so a failed attach can
  never crash the app at stop.
- **Other windows.** Compose `Dialog`, `ModalBottomSheet`, `DropdownMenu`/`Popup`, `PopupWindow`,
  toasts and the IME are separate windows Hopi does not see (there is no public API to find them).
  While one is in front, the activity window below reports only its own frames, often none: the
  frames figure then reads "idle", and `perf.status` says `"windows":"activities only"`. A
  `SurfaceView`/`TextureView` producer (video, maps, camera, GL) is outside HWUI's frames as well.

Attaching: there is no public API for the activity already started, and the perf monitors keep no
lifecycle listener while off (the purer reading of "off is free"). The way around it is core's:
its start keeps a `WeakReference` to the resumed activity in `CurrentActivity` (one
`ActivityLifecycleCallbacks` for the process, a field write on each resume and pause; no thread, no
timer), and a start attaches to that activity at once, with one main-thread message (a start may
come from any thread). Until that message ran, `perf.status` says `"attaching":true` beside
`"attached":false`, and the CLI and the desktop viewer show no hint: the attach is on its way. It
is a count of the messages in flight, not a flag, since a frames stopped and started again has
two, and the first one's end must not clear the second's; it is reported only while frames runs.
With no resumed activity (the app in the background) a remote `perf.start` attaches frames at the
next activity start, `perf.status` says `"frames":{"attached":false,"attaching":false}` until
then, and the CLI says so plainly: "frames attach when an activity next starts" (an older app
sends no `attaching`, read as false). The provider's `autoStart` (it runs before any activity)
attaches at the first start. Until the runtime-split plan's stage 5 the in-app launcher wrote the
same reference; core's start is its only writer since.

The framework copies each frame's metrics on the RenderThread and posts one message per rendered
frame to Hopi's handler, so the thread wakes once per frame the app draws and **never while the app
draws nothing**. `dropCountSinceLastInvocation` counts *metrics reports* the framework discarded
because Hopi's handler fell behind, not frames the app dropped; it goes to a `metricsLost` counter
(a non-zero value means the figures undercount and says so). Frames the app missed are inferred
from `TOTAL_DURATION` against the deadline and from each frame's wait to start (below), never from gaps between frames (an idle app draws nothing and misses nothing). Per
frame the listener reads `TOTAL_DURATION`, the stage durations (input, animation, layout/measure,
draw, sync, command issue, swap; GPU on API 31+), `FIRST_DRAW_FRAME` and, on API 26+, the intended
and actual vsync timestamps (on 24 and 25 those metrics do not exist: the frame is bucketed by
`System.nanoTime()` at delivery) into primitive fields: no allocation. Every metric is guarded by
`SDK_INT`, never by catching (`getMetric` of an unknown id is not an error on every release).

Deadline: `FrameMetrics.DEADLINE` on API 31+ (it follows variable refresh rates per frame); on 24
to 30, `1e9 / display.refreshRate` from the activity's own display (`Activity.getDisplay()` on 30,
`windowManager.defaultDisplay` below), re-read on `DisplayManager.DisplayListener.onDisplayChanged`,
registered only while on and only below API 31 (on adaptive-refresh devices it fires on every rate
switch, which 31+ does not need). Classification: **janky** when the frame's own time is over
`2 x deadline` (JankStats' default; a frame just over the deadline on an emulator is noise), **slow** when over
the deadline, **frozen** over 700 ms (Android vitals' definition). Frames whose `FIRST_DRAW_FRAME`
is 1 are counted apart (first frame of a window).

As built (stage 4, after the emulator showed it): a frame's **own time** is `TOTAL_DURATION` less
the wait before it started, `VSYNC_TIMESTAMP - INTENDED_VSYNC_TIMESTAMP` (Choreographer moves a
frame that waited for the main thread to a later vsync), and a frame that started late is held to
the `DEADLINE` of the last frame that started on time. Read from the intended vsync, a late frame
carries the wait in `TOTAL_DURATION` and, on API 31+, in `DEADLINE` too (the later frame
timeline's, several vsyncs long: 50 to 67 ms seen): 30 frames that each blocked 100 ms read as 1
janky frame and 29 frames of 200 ms "within" their deadline. Now they read as 31 janky frames of
their own work (the animation stage's median 102 ms). The wait itself shows as missed vsyncs (the
wait in display intervals, rounded; until 2026-11 they were gaps between frames, which counted idle
time: 110 to 420 a second on an idle phone), and
a long one as a hang. Below API 26 (no vsync metrics) the frame is its `TOTAL_DURATION`.

**Per-second buckets, no timer.** Frames are summed into the current second's bucket. Only a
second strictly in the past is ever closed and written as a point: by the first frame of a later
second, by the sampler's tick when CPU or memory is on, or when a reader (`perf.snapshot`, a tail)
asks. The current second is shown to a reader provisionally and never written, so a
frame later in the same second can never open a second point for it. Never by a timer of the
frames monitor. A second in which nothing drew therefore has no point unless the sampler or a
reader wrote a zero one once it was past, and consumers treat a missing second as "nothing
drawn".

**iOS.** There is no per-frame metrics API. Two tiers:

1. **Main-thread stalls (free, the default).** The hang monitor's `CFRunLoopObserver`s (below)
   already timestamp each main run-loop unit of work. One busier than twice the frame deadline is
   reported as a stall of that length (the same 2x rule as Android's janky): "main thread busy
   84 ms", not "dropped frames", because the app may not have wanted to draw. The deadline is
   `1 / UIScreen.maximumFramesPerSecond` read once on the main queue at start, capped at 60 Hz
   unless the app's Info.plist sets `CADisableMinimumFrameDurationOnPhone` (without it a ProMotion
   iPhone gives the app 60 Hz, and a 120 Hz deadline would report every 9 ms unit as a stall).
   Turning frames on without hangs installs the same observers without the watchdog. Cost: three C
   callbacks per run-loop iteration (below), nothing while the main thread sleeps; the C callback
   enters Kotlin only for a unit over the stall threshold, to record it.
2. **Display-link cadence (only on explicit request).** A `CADisplayLink` on the main run loop,
   created only while a viewer explicitly asks for it: a bridge session tailing perf with
   `"fps":true` (the in-app HUD's and Perf tab's "cadence" toggle did too, until the runtime-split
   plan's stage 5). It measures the display link's callback cadence, not frames the app drew: it fires
   every vsync whether the app draws or not, so it only adds "missed vsyncs" (timestamp gaps over
   `1.5 x (targetTimestamp - timestamp)`), which tier 1 already sees as stalls. Cost: one
   main-thread callback per vsync (60 or 120 per second) of a few µs (to verify), **including while
   the app is idle**, and a live display link keeps a ProMotion/LTPO panel from dropping to its
   idle refresh rate: a power cost on a device even when the app draws nothing (the simulator
   cannot show it; device-only, open). Its `preferredFrameRateRange` is left at the default (a
   lower one would make the cadence meaningless). It targets a weak proxy (a display link retains
   its target), is invalidated when the last viewer goes, and pauses on
   `UIApplicationDidEnterBackground`.

**Compose Multiplatform.** Android: Compose draws through the RenderThread, so FrameMetrics sees
it like any view. iOS: CMP renders with Metal from its own display link, and its frame work runs
inside main run-loop iterations, so tier 1 sees it; with CMP's parallel rendering on, the Metal
encoding moves off the main thread and tier 1 sees only the main-thread part (composition, layout,
recording). A spike checks one CMP-specific alternative:
the presented-time handler of each `MTLDrawable` (`addPresentedHandler`) on CMP's layer, which
would give true presentation intervals with no idle wake; adopted only if it needs no swizzling of
system classes. Nothing of Hopi draws in the app's window since the in-app HUD and Perf tab went
(below, "Not measuring itself").

**"What was on screen."** Each janky frame and stall records a screen label: `HopiPerf.screen(…)`
if the app set one, else the resumed activity's class name (Android) or nothing (iOS). The label
is cut to 64 chars; the app owns what it passes (a route name, never user data). A single-activity
CMP app reads as `MainActivity` throughout unless it calls `screen(…)` on navigation.

## Hangs

**Threshold** 250 ms by default (`hangThresholdMs`, 100..10 000). A hang is one main-thread unit
of work (an Android message, an iOS run-loop iteration) running longer than the threshold.

**Android: printer-armed watchdog.** `Looper.getMainLooper().setMessageLogging(printer)` while
on, set from a message posted to the main thread (one at start, one at stop: `mLogging` is a plain
field the looper reads per message). The printer tells dispatch start (`>`) from end (`<`) by the
first character and publishes the state as **one packed 64-bit atomic**: a 24-bit sequence number
and the 40-bit `SystemClock.uptimeMillis()` start (0 when idle), so the watchdog never reads a
start from one message with the sequence of another. The watchdog thread `hopi-perf-hang` (kernel
thread names are cut at 15 bytes, so every Hopi thread name fits: `hopi-perf-hang`,
`hopi-perf-frame`, `hopi-perf-samp`) runs at the default priority, not nice 5: it is asleep almost
always, and at a lower priority a saturated CPU, which is when hangs happen, would delay detection.
The watchdog:

- sleeps with no timeout while the main thread is idle;
- is released by the printer only when it was asleep with no timeout (one atomic read per
  message, one semaphore release per idle-to-busy transition, the store's `WakeSignal`);
- when awake, sleeps until the packed state's start `+ threshold`; on waking it compares the sequence number: the
  same message still running is a hang; otherwise it sleeps until the current message's deadline,
  or, when the main thread is idle, dozes one more threshold before sleeping untimed (so an
  animation's short idle gaps cost at most 1 / threshold wakes per second, 4 at 250 ms, not one
  per frame), unless no unit began since the one it watched: then it sleeps untimed at once, so a
  lone short unit costs two wakes, not three;
- under a remote lease it still parks before its idle sleep, which the lease's expiry only bounds:
  an idle-to-busy transition wakes it as without a lease.

**False hangs.** Before reporting, the watchdog checks that it woke on time: a wake later than its
deadline by more than one threshold means the whole process was stopped (the cached-app freezer,
a debugger that suspended all threads, the device sleeping between the two clock reads), so the
running message's start is moved to the late wake's time instead of reporting a hang that was the
freeze: a hang that really goes on after the thaw is reported one threshold later, with the frozen
span as `frozenMs`. A watchdog starved for over a threshold at the default priority would take the
same path (a late report, never a false one).
While `Debug.isDebuggerConnected()` (one native call per detection, not per message) a detection
is recorded as `"debugger":true`, takes no stack and is left out of counts and summaries (a
breakpoint on the main thread with "suspend thread" is not a hang).

On a hang it takes `Looper.getMainLooper().thread.stackTrace` (ART suspends the main thread at a
suspend point for the walk; a thread in native code is walked without waiting; tens of µs to a few
ms, to verify, recorded in the event as `captureUs`; it allocates the `StackTraceElement`s on the
watchdog thread, only on a hang), then **re-reads the packed state**: if the sequence changed during
the capture, the message ended and the stack belongs to another one, so it is discarded (the event
says `"stackMissed":true` when no stack survives). It captures again at 2x and 4x the threshold
while the same message runs: at most 3 stacks of 64 frames per hang. ART returns an empty array
when the suspension times out; that is recorded, not retried. The event is written and its log line
emitted **at detection** (an ANR may kill the process before the end), and completed with the total
duration when the message ends. Each capture pauses the main thread; frames whose time overlaps a
capture are flagged `hopiPause` (below, "Not measuring itself").

Cost the platform charges: with a printer set, `Looper.loop` builds two strings per message
(`">>>>> Dispatching to " + target + " " + callback + ": " + what` and the `<<<<<` one), on the
main thread, whatever the printer does. `msg.callback.toString()` is the app's: a lambda's is
`Object.toString` (class name and identity hash), but a coroutine resumed on `Dispatchers.Main` is
a `DispatchedContinuation` whose `toString` includes the continuation's `toString`, which reads the
`@DebugMetadata` annotation reflectively ("Continuation at …"). So the per-message cost depends on
what the app posts: about 1 to 3 µs for a plain message, possibly tens of µs for a coroutine
resume, to verify; and a few hundred bytes of garbage per message (at 300 messages per second,
about 100 KB/s, which raises the app's young-GC count: the memory monitor's GC series is not
independent of the hang monitor and carries a `perturbed` flag while the printer is set). **Gate:** the spike
measures the median and p99 over a realistic mix (empty messages, `View.post` lambdas, coroutine
resumes on `Dispatchers.Main`, a Compose animation); if the mix costs more than 3 µs per message
on the emulator, hangs switch to a heartbeat (the watchdog posts an empty message every
`threshold / 2` while on, 8 wakes per second at 250 ms on each of the two threads, 16 in all,
including idle), and the budget table changes accordingly. `Looper.setObserver` (API 29) is
hidden API and not used.

**Input dispatch, the gap that matters for Compose.** Input events reach the main thread through a
native looper fd callback inside `MessageQueue.next()`, not a `Message`, so the printer brackets
none of it. Only `MOVE`/`HOVER_MOVE` batches run inside the Choreographer's frame message; `DOWN`
and `UP` are dispatched immediately. A View's `performClick` is posted, so a slow `OnClickListener`
is a message and seen, but Compose runs pointer-input handlers, `clickable`'s `onClick` and gesture
detectors **synchronously inside `dispatchTouchEvent`**: for a CMP app most "the button froze"
hangs would be invisible to the printer. `IdleHandler`s run inside `next()` as well. So, while
hangs is on and the `Application` is known, Hopi wraps each started activity window's
`Window.Callback` (delegating every method, the same `ActivityLifecycleCallbacks` as frames):
`dispatchTouchEvent`, `dispatchKeyEvent` and `dispatchGenericMotionEvent` mark busy on entry and
idle on exit **only when the packed state was idle on entry** (a batched `MOVE` inside a frame
message is already bracketed), costing what the printer costs per message (two atomic stores, and
the semaphore release on an idle-to-busy transition while the watchdog sleeps untimed), no
strings. At stop the wrapper is
removed only if it is still the window's callback (AppCompat or another SDK may have wrapped it
since); otherwise it stays as a pass-through behind a volatile flag, and a later start walks
the chain through Hopi's own wrapper class only, finds it and re-enables it instead of wrapping
twice. Without the `Application`
(a start that passed none), the gap stands and `perf.status` says
`"input":false`. **Other windows stay a gap**: a Compose `Dialog`, `ModalBottomSheet` or `Popup`
(and any other `Dialog` or `PopupWindow`) has its own window whose touches go through its own
callback, which no public API lets Hopi find and wrap, so an `onClick` that blocks inside a dialog
is not a hang (no message is involved either). `perf.status` says so, `"inputWindows":"activities
only"` (`"all (heartbeat)"` with `hangHeartbeat`, which sees any main-thread block whatever
dispatched it); the heartbeat is the remedy for an app whose slow work runs in dialogs. The spike's second **gate**: a 600 ms block in a Compose `onClick` must be one
hang with a stack naming it; if the bracket cannot achieve that, the heartbeat is the design for
hangs, not the fallback.

**Start and stop during a hang.** The printer is set and cleared by a posted main-thread message,
which a hung main thread does not run. So neither waits on main: `perf.stop` stops the watchdog,
the bracket and the frames listener's accounting at once and replies; the printer is cleared when
main next runs a message (until then it only publishes state nobody reads). A `perf.start` during
a hang observes from the hang's end; the hang already running is seen only by FrameMetrics (as
one long frame, once it draws) and, if it becomes an ANR, by the next run's `previousAnr`. The
reply says `"printer":"pending"` until the message ran.

**One printer per looper.** `setMessageLogging` replaces whatever printer was set, and there is no
public getter, so Hopi cannot see or chain a printer another tool set (BlockCanary, Matrix, an ANR
SDK): starting hangs displaces it (the start reply and the notice line say so, once), and a tool
that sets its own later displaces Hopi. When frames is also on, Hopi detects displacement (frames
arrive while its printer's sequence does not move) and reports `"displaced":true` in status; with
frames off a displaced printer cannot be detected and status says `"displaced":null`. At stop,
Hopi clears the printer only if it is still its own: the stop message it posts to the main thread
checks that its printer saw that very message's dispatch (`>`), and only then sets `null`, so a
stop never removes another tool's printer. The heartbeat is also the remedy for an app that needs
another printer: `hangHeartbeat = true` (config and DSL).

**The previous run's ANR.** When the hang monitor starts on API 30+, its watchdog thread (never
the caller's, which may be the main thread at startup) calls
`getHistoricalProcessExitReasons(packageName, 0, 1)`: the single most recent exit of this
package. Only if that one is `REASON_ANR` does it stream `getTraceInputStream()`, stopping after
the `"main"` thread's section (at most 16 KB; the trace of every thread can be megabytes), and
keep it as a `previousAnr` event with the record's timestamp. An older ANR behind a later normal
exit is not the previous run's and is not shown. It is never re-read in that process and never
persisted.

**iOS: run-loop observers plus watchdog, in C.** Two `CFRunLoopObserver`s on the main run loop
for `kCFRunLoopCommonModes` (tracking mode included), written in C so a callback costs no
Kotlin/Native thread-state switch. Two, because an observer has one `order` and the ends must
bracket everyone else's work: a **begin** observer at the lowest order for `AfterWaiting |
BeforeTimers` (a unit starts on waking, and every iteration that loops without sleeping starts a
new unit at `BeforeTimers`, so a main thread that keeps processing short sources without ever
sleeping is not one long false hang), and an **end** observer at the highest order for
`BeforeWaiting`, so Core Animation's commit (order 2000000, also on `BeforeWaiting`) counts as
busy. `BeforeSources` adds nothing and is not observed: three callbacks per iteration that sleeps.
Each callback publishes the same packed state as Android's (sequence and `mach_absolute_time`
start, one 64-bit atomic store). A nested run loop (a callout that spins `CFRunLoopRunInMode`)
reports its own iterations, so the outer callout reads as idle while the inner loop waits: right,
since the main thread is responsive then. GCD blocks on the main queue (Kotlin's `Dispatchers.Main`
on iOS) are serviced inside an iteration and are covered. The watchdog is a C pthread
`hopi-perf-hang` at QoS user-initiated, with the same states, the same oversleep check and the same
post-capture sequence re-read as Android's (untimed `semaphore_wait` while idle,
`semaphore_timedwait` to the deadline while busy, one doze). A detection while a debugger is
attached (`sysctl` `KERN_PROC_PID`, `P_TRACED`, one call per detection) is recorded as
`"debugger":true`, without a stack. The main thread's mach port
(`pthread_mach_thread_np(pthread_main_thread_np())`, which adds no port reference) and its stack
bounds (`pthread_get_stackaddr_np`, `pthread_get_stacksize_np`) are read once in `start()`, from
any thread.

Stack capture, signal-free: `thread_suspend(main)`, `thread_get_state(ARM_THREAD_STATE64)`, read
through `arm_thread_state64_get_pc/lr/fp` (on arm64e those fields are signed), a frame-pointer walk
into a preallocated array of 64 addresses, `thread_resume`. The walk reads a frame directly only
while fp is inside the main thread's stack bounds, 16-byte aligned and strictly increasing (which
also ends any cycle in a corrupt chain); anything else ends the walk, or is read with
`vm_read_overwrite` (one system call each, so not per frame by default). Each saved fp has the
Swift async-frame flag (bit 60) cleared before the bounds check. Every return address, not just the
first lr, is stripped of pointer-auth bits **with an explicit mask** (`& 0x0000000FFFFFFFFF`, as
KSCrash and Sentry do): `ptrauth_strip` compiles to nothing in an arm64 (non-arm64e) build such as
the app's, while system libraries' saved return addresses may be signed. Between suspend and
resume nothing allocates, locks, logs or enters Kotlin (the main thread may hold the malloc or dyld
lock, or be inside the Kotlin/Native runtime). The watchdog is a pure C pthread, in native state
while parked, so the Kotlin/Native GC's stop-the-world never waits on it; it enters Kotlin (and is
attached to the runtime) only after `thread_resume`, to record the event. The event is recorded
and its log line written with raw addresses first; once the hang's unit ended, `dladdr` names
each address on the watchdog thread (Kotlin frames read as `kfun:…` in a debug build), the stack's
log line is written again with the names and the event completed with them. `dladdr` takes the
dyld lock, so a main thread hung inside dyld (an image load, `+load`) delays the names, never the
detection; and naming waits for the unit's end because, while the unit still ran, the lock held
against the main thread finishing it (lazy binding on a first launch) made a 300 ms hang read up to
419 ms (stage 5). Only return addresses leave the walk; stack contents (which may hold user
data) are never copied out or logged. Never `backtrace()` (current thread only), never a signal to
the main thread. Expected suspension: under 100 µs for 64 frames read directly, to verify.

## CPU

Sampled, so stated: one sampler thread `hopi-perf-samp`, shared with memory (one wake per
interval for both), `sampleIntervalMs` 1000 by default, parked with a deadline, started only while
CPU or memory is on.

- **Process total, every sample.** Android `Process.getElapsedCpuTime()` (a clock call, no file
  I/O, to verify on API 24 and 35; millisecond resolution, so 0.1% of a core at 1 s); iOS
  `task_info(MACH_TASK_BASIC_INFO)` terminated times plus `TASK_THREAD_TIMES_INFO` live times (not
  the deprecated 32-bit `TASK_BASIC_INFO`). Reported as % of one core (top's convention) with the
  core count.
- **Hopi's own share, every sample.** Each `hopi-perf-*` thread adds its own CPU time
  (`Debug.threadCpuTimeNanos()`, iOS `thread_info` of itself: one call) to a counter just before it
  parks, so the sampler reads Hopi's share without opening any file. `hopi-store` and
  `hopi-bridge*` are not in it; the per-thread list shows them.
- **Per-thread top N, only while watched.** Opening every `/proc/self/task/<tid>/stat` costs one
  open and read per thread (an app has 50 to 150 threads; milliseconds per scan, to verify), so it
  runs only while a bridge session tails perf with `"threads":true` (the desktop Perf panel's
  thread list) or a `perf.snapshot` asks for `threads`. Names from the same `stat` line
  (`comm`, which the kernel cuts at 15 bytes: the list shows them so, and Hopi's own are
  recognised by the `hopi-` prefix). The scan's own CPU lands on the sampler and is labelled. iOS: `task_threads`, then per thread `thread_info(THREAD_BASIC_INFO)` (the kernel's
  own decayed `cpu_usage`, plus user and system time) and `THREAD_EXTENDED_INFO` for the name;
  every thread port is `mach_port_deallocate`d and the array `vm_deallocate`d in the same call (the
  classic leak), tested. Top 10 by default.

Decided: the process total is not gated on a viewer (5 µs per second keeps the history an agent
reads after a scenario); the per-thread scan is, because it is the expensive part.

## Memory

Every sample (µs each, to verify): Java heap (`Runtime.totalMemory() - freeMemory()`, max),
native heap (`Debug.getNativeHeapAllocatedSize()`: `mallinfo()`, which walks the allocator's
arenas or Scudo's per-thread caches under their stats locks, so it can briefly contend with the
app's allocations; measured on API 29 (jemalloc) and 35 (Scudo)), RSS from `/proc/self/statm`
(kept open and re-read from offset 0 with `Os.pread` into a reused `ByteArray`: no open per
sample), GC count and blocking-GC time (`Debug.getRuntimeStat("art.gc.gc-count")`,
`"art.gc.blocking-gc-time"`); iOS `task_info(TASK_VM_INFO).phys_footprint` (what jetsam counts),
`os_proc_available_memory()` (headroom, iOS 13+, below the sample app's 16.0 target; weak-linked
behind `__builtin_available` should the library's minimum ever be lower) and the Kotlin/Native
heap when the runtime reports it. A sample allocates a little and boundedly (the two
`getRuntimeStat` strings); per-frame and per-run-loop work allocates nothing.

Never periodic: PSS. `Debug.getPss()` reads `smaps_rollup` where present (API 29+ kernels), and
`Debug.getMemoryInfo()` walks the full `smaps` (it needs per-mapping categories): milliseconds to
hundreds of milliseconds on a large app, to verify. Both hold the kernel's mm lock of the process
while they read, so app threads that page-fault or `mmap` meanwhile stall: a PSS measurement is
self-interference by nature. They run only on `perf.snapshot --pss`, on the sampler thread, at
most once per 10 s, and frames and stalls during it are flagged `hopiPause`. `ActivityManager.getProcessMemoryInfo` is not used (rate-limited by the system on API
29+: repeated calls within a few minutes return the cached first result).

Events, OS-provided and free, registered only while on and only when the `Application` is known
(Android): `ComponentCallbacks2.onTrimMemory` (level) and `onLowMemory`. On API 34+ the system no
longer sends the `TRIM_MEMORY_RUNNING_*` levels (only `UI_HIDDEN` and `BACKGROUND`), so in the
foreground an Android 14+ app gets no pressure event at all, and `perf.status`'s notice says so
instead of implying "no pressure". iOS: `UIApplicationDidReceiveMemoryWarningNotification` and a
`DISPATCH_SOURCE_TYPE_MEMORYPRESSURE` source (warn, critical) on a utility queue, cancelled at
stop.

## Data model (`dev.koga.hopi.core.perf`)

- **Series**: one ring per series of primitive arrays (`LongArray` timestamps, `FloatArray` or
  `LongArray` values), 1200 points each (20 min at 1 Hz), no boxing, no allocation per point;
  single writer per series (its monitor's thread), readers copy under a per-series lock that is
  uncontended in practice: a real mutex, never a bare spin (a JVM `ReentrantLock`; on iOS an
  `os_unfair_lock`, which lends the main thread's priority to a low-QoS holder), because the main
  thread takes the frame and stall locks. A series' arrays and an event ring's slots are allocated
  at its first write, so a monitor that never ran holds none of them. Series: `frames` (per second: frames, slow, janky, frozen, worst ms,
  missed vsyncs, `metricsLost`), `cpu` (process %, Hopi's own %), `memory` (Java heap, native
  heap, RSS or footprint, available). On iOS the `frames` series is empty unless tier 2 runs (tier
  1 produces stall events, not frame counts), and `perf.snapshot` and the CLI say so rather than
  print 0 fps.
- **Events**: separate rings per kind so a jank flood never evicts a hang: jank frames 1000,
  stalls (iOS tier 1) 1000, hangs 100 (each at most 3 stacks of 64 frames, at most 16 KB),
  memory events 200, previous ANR 1. Every event gets a monotonic `seq` (process-wide).
- **Summaries since start**, constant memory: frame-time histogram (1 ms buckets to 200 ms, 10 ms
  to 1 s, one overflow) for p50/p90/p99/max, counts and percentages; hang count, total and worst;
  CPU average and max; memory min, max, last. Computed when asked.
- Written straight from the monitor's thread (no hop); a write wakes nothing unless a listener is
  registered (a bridge `perf.tail`), which gets the event synchronously and must only `trySend`,
  as the store's listeners do. Listeners subscribe by grain: a tail takes closed per-second
  buckets and events only, never per-frame writes (the in-app HUD and Perf tab did the same, until
  the runtime-split plan's stage 5).
- Frames overlapping a stack capture or a PSS measurement are flagged `hopiPause` and left out of
  summaries (and counted apart, so the exclusion is visible). The `inspector` flag, set while the
  in-app inspector was open, went with it (the runtime-split plan's stages 5 and 8); the bridge's
  `excluded` count, unchanged in shape, counts only frames that overlapped a Hopi pause.
- Strings from the app are bounded: screen labels 64 chars, thread names 64 chars, stack frames
  512 chars each, inside the per-hang 16 KB.

Bounded total: about 120 KB of series plus at most 1.6 MB of hang stacks, allocated only as the
monitors write (with every monitor off: none, the controller's start included).

## Surfaces

### Log lines

A perf line never carries `"id"` (agents skip lines without `id` as transactions; an old agent
must not read perf as traffic): `{"hopi":1,"perf":"<kind>","seq":N,…}`. Android writes them under
a tag of their own, `HopiPerf` (so `adb logcat -s Hopi:I` is unchanged), iOS under category `perf`
of the same subsystem, the JVM to stderr. Same byte budgets (3999 / 1000). On iOS the documented
predicate `subsystem == "dev.koga.hopi"` does include perf lines (the category is not filtered),
so the absent `id` is the guard there, as it already is for notice lines; an agent that wants HTTP
only adds `AND category == "http"`.

```json
{"hopi":1,"perf":"hang","seq":12,"state":"ongoing","ms":250,"screen":"CheckoutActivity","stack":["dev.koga.hopi.sample.PerfLab.hang(PerfLab.kt:41)","…"]}
{"hopi":1,"perf":"hang","seq":12,"state":"ended","ms":612}
{"hopi":1,"perf":"jank","sec":1729850000,"frames":57,"slow":9,"janky":4,"frozen":0,"worstMs":118,"screen":"…"}
{"hopi":1,"perf":"memory","event":"trim","level":15}
{"hopi":1,"perf":"sample","cpu":12.5,"javaHeapMb":42.1,"nativeHeapMb":18.0,"rssMb":201.3}
{"hopi":1,"perf":"notice","suppressed":N}
```

Rate limits: a jank line at most once per second, only for a second with a janky frame; hangs and
memory events always, within a hard cap of 5 perf lines per second (each is rare by nature; the
excess is counted in a `notice`); a `sample` line every 10 s while CPU or memory is on (a stated
cost: one `Log.i`/`os_log` call, tens of µs, every 10 s, on the sampler thread; `summaryLog =
false` removes it). Stacks on a line are the top frames that fit the budget (iOS: 1000 bytes is
about 6 frames, and the `ongoing` line carries raw addresses when the names are not yet resolved,
see Hangs); the full stacks are on the bridge.

### Bridge

`hello` gains `"perf":true` when a controller is registered, `false` otherwise, and
`"perfControl":true` when it allows `remoteControl`; both absent on an older app, read as false. `protocol`
stays 1. `perf.*` commands are accepted only after `hello` on the connection; `hopi-connect` sends
`perf.start`/`perf.stop` only after it verified `hello` (never pipelined, the rules rule). They
parse with core's bounded tree parser. An older `hopi-connect` ignores the unknown `hello` fields,
as it did when `"rules"` was added (a `hopi-connect` test pins it with a recorded new `hello`).

```json
{"cmd":"perf.start","monitors":["frames","hangs"],"hangMs":250,"intervalMs":1000,"ttlSec":600}  // → {"type":"perf.status",...}
{"cmd":"perf.stop","monitors":["frames"]}            // none named: all → {"type":"perf.status",...}
{"cmd":"perf.status"}                                 // → {"type":"perf.status","platform":"android","available":[...],"running":{"hangs":{"thresholdMs":250,"since":…,"expiresAt":…,"displaced":false},…}}
{"cmd":"perf.snapshot","series":["cpu","memory"],"points":120,"events":true,"threads":true,"pss":false,"fullStacks":false}  // → series, summaries, events (stacks cut to 8 frames unless fullStacks), then {"type":"end"}
{"cmd":"perf.event","seq":12}                         // → the full event (all stacks) | NOT_FOUND
{"cmd":"perf.tail","since":0,"samples":true,"fps":false,"threads":false,"start":["hangs"]}  // → backlog events since seq, then live events and samples until disconnect
```

Remote starts have a lease: `ttlSec` 600 by default, at most 3600, `0` only with an explicit
`--no-expiry`; when it ends the monitor stops. The lease is **no thread and no periodic check**:
its expiry is one deadline folded into the timed wait of a perf thread that already runs (the hang
watchdog's or the sampler's), or one delayed message on the frames `HandlerThread` when only frames
is leased (iOS: one `dispatch_after` on a utility queue), rescheduled on renewal: one wake per
lease. A `perf.tail` with `"start"` stops what it started when the session ends. So a CLI that dies
never leaves a monitor running for more than 10 minutes.

**Owners.** A monitor runs while at least one owner holds it: `code` (started in code or by the
provider's `autoStart`, no lease), `lease` (the latest `perf.start`'s expiry; a second
`perf.start` extends it to the later of the two), and one entry per `perf.tail` session that
started it. A lease expiring or a tail session ending removes only its own owner, so it never stops
a monitor that code or another client also holds. `perf.stop` is explicit control and removes
every owner (a code-started monitor included: remote control means control; `remoteControl = false`
refuses it). A `perf.start` with parameters (`hangMs`, `intervalMs`) for a monitor already running
applies them only if they were given, and the reply reports the values in force. `perf.status`
lists each monitor's owners.

`perf.snapshot`'s `points` is 1..1200 and `events` at most 1000 per kind; `perf.tail` uses the
existing bounded per-session queue and `SLOW_CLIENT` rule. A hang is updated in place under its
seq (later stacks, its end), so a tail sends a hang's later versions even at or below its `since`
(any other event at or below it the client has), `SLOW_CLIENT`'s `since` stays below a hang it
sent as ongoing and not as ended, and the desktop's reopen resumes below a hang it shows as ongoing
(counting a re-sent jank or stall once); the desktop viewer opens it on a second
connection next to its `tail` (two of the eight bridge slots). Errors: `UNSUPPORTED` when `hello`
said false, a monitor the platform lacks (hangs on the JVM), or a start or stop with
`remoteControl = false`; `LIMIT` for a value out of range.

### CLI

```text
hopi perf start  --app <id> [--frames] [--hangs [--hang-ms 250]] [--cpu] [--memory] [--interval 1s] [--for 10m | --no-expiry]
hopi perf stop   --app <id> [--frames] [--hangs] [--cpu] [--memory]
hopi perf status --app <id> [--json]
hopi perf snapshot --app <id> [--json] [--threads] [--pss]
hopi perf tail   --app <id> [--json] [--since SEQ] [--samples] [--fps] [--threads] [--start hangs,frames]
hopi hangs       --app <id> [--json] [--since SEQ]          # every hang with its full stacks (perf.snapshot, fullStacks)
```

The usual target options apply. Exit 4 when the app's Hopi has no perf (as rules). `status` prints
one line per monitor (`hangs  on  250 ms  expires in 9m12s  3 hangs`); `snapshot` prints the
summaries and the top threads; `hangs` prints each hang's duration, screen and stacks.

### Desktop viewer

A Performance screen (⌘P), in place of the Network one, over `PerfClient`: monitor toggles (with
the lease shown), charts drawn with Compose `Canvas` and no chart library (frames per second with
janky bars, CPU %, memory lines, requests as bars), the hang list with stacks (copy as text),
memory events as markers on the memory chart.

Every chart reads one time scale (`PerfPanelState.domain`), so a pixel column is one moment in all
of them, with hangs and stalls drawn across them. Dragging over a chart selects a stretch and holds
the timeline still; the side then sums up that stretch alone: CPU peak and average, memory, frames,
hangs, stalls, requests, and who used the CPU. A hang's Inspect selects the seconds around it, at
any distance back. Copy gives the selection as text. All of it is the viewer's: the app adds
nothing, the 20 minutes it keeps are the limit.

The per-thread list is scanned only while this screen is open (`perf.tail` with `threads`). Each
line follows its sample, which dates it, and the viewer keeps what each thread used since its last
report (`RemoteThreadTick`), from the cumulative `cpuMs`: never from `percent`, which on iOS is the
kernel's decayed average and flattens a peak. A thread that leaves the app's top 10 comes back with
everything it used meanwhile, spread over that time (`RemotePerf.threadsBetween`). So a stretch
when the screen was closed has charts (the app's rings) but no threads, and says so. On iOS only
the threads that name themselves and the main thread have names; a GCD worker shows as its tid (its
queue's label is not read). The app still picks its top 10 by `percent`, so on iOS a short burst on
an idle worker can miss the list at that tick and come back spread over the time away.

### In-app (removed)

The in-app HUD (one line over the app, toggled by a long press on the launcher button) and the
in-app screen's Perf tab (monitor switches, summaries, sparklines, the thread list, "Measure PSS",
hangs with stacks) were built in this design's stage 3 (see "As built") and removed with the whole
in-app UI in stage 5 of docs/design/api-runtime-split-plan.md (D7'). The desktop viewer's Perf
panel and the CLI are the surfaces.

## Not measuring itself

- Hopi's threads are named `hopi-perf-*` (and `hopi-store`, `hopi-bridge*`); the per-thread list
  labels them, and the CPU series carries Hopi's own share separately.
- Hopi draws nothing in the app's window: the desktop viewer and the CLI are the surfaces (until
  the runtime-split plan's stage 5, frames drawn while the in-app inspector was open were flagged
  and left out, and the HUD's own redraws were bounded).
- No monitor posts work of its own to the main thread while it runs: the main-thread costs are
  the ones the platform calls Hopi for (Android lifecycle callbacks and the input bracket, the iOS
  run-loop observers, iOS tier 2's display link) plus one message at the hang monitor's start and
  one at its stop; the heartbeat would post, and says so. Per-frame and per-run-loop work allocates
  nothing. Not GC-free, stated: the Looper printer's framework strings (about 100 KB/s at
  60 fps, Hangs), a sample's two small strings, a hang's `StackTraceElement`s; the memory monitor's
  GC series carries a `perturbed` flag while the printer is set.
- Every stack capture and PSS measurement records its pause (`captureUs`, `pssMs`), and frames
  overlapping one are flagged `hopiPause` and left out of summaries.
- Overhead is measured from outside the app (`/proc/<pid>/task/*/stat` and `voluntary_ctxt_switches`
  over adb, gfxinfo framestats, `ps -M` on the simulator), never by Hopi itself.

## Performance budget (targets, each to verify)

| Monitor | Off | On: threads | On: wakes | On: cost |
| --- | --- | --- | --- | --- |
| any, module present | 0 threads, 0 listeners, 0 wakes; Android startup: with `perf { enabled = true }` the generated tool list constructs `PerfEntry` (a direct reference, no lookup, never a miss) | | | |
| `autoStart` (Android) | | | | thread creation at provider init (≤ 0.5 ms per thread, on the main thread at startup, measured) |
| frames, Android | 0 | `hopi-perf-frame` | 1 per rendered frame, 0 while nothing draws | ≤ 5 µs on Hopi's thread per frame, 0 allocation; ≤ 1 µs RenderThread copy; lifecycle callbacks on the main thread per activity transition only |
| frames, iOS tier 1 | 0 | none | 0 extra (main run-loop iterations only) | 3 C callbacks per iteration, ≤ 0.5 µs together; a Kotlin call per stall only |
| frames, iOS tier 2 (explicit toggle only) | 0 | none | 60 or 120 main-thread callbacks per second, idle included; keeps a ProMotion panel off its idle rate | ≤ 3 µs per callback; power cost device-only, open |
| hangs, Android | 0 | `hopi-perf-hang` (default priority) | 0 while the main thread sleeps; ≤ 1 per threshold while busy, plus 1 per idle-to-busy (a lone short unit: 2, its begin's and its deadline's); a remote lease bounds the idle sleep, never blinds it | framework printer strings per message, mix-dependent (gate: 3 µs median over the realistic mix; coroutine resumes may cost more), ~300 B garbage per message; Hopi ≤ 0.2 µs per message; input bracket 2 atomic writes per input event; stack ≤ 2 ms pause per capture |
| hangs, Android heartbeat (gate or `hangHeartbeat`) | 0 | `hopi-perf-hang` | 2 per `threshold / 2` (watchdog and main), idle included: 8 per second on each at 250 ms, 16 in all | one empty main-thread message per tick |
| hangs, iOS | 0 | `hopi-perf-hang` | same as Android's printer path: every main run-loop wake is an idle-to-busy transition, so ≤ 1 per main-thread wake (the simulator's main run loop wakes about 6 times a second with nothing to do, in a build without the monitors too) | ≤ 0.5 µs per run-loop iteration (shared with tier 1); stack ≤ 100 µs suspension |
| CPU | 0 | `hopi-perf-samp` (shared) | 1 per interval (1 s) | process total ≤ 5 µs per sample; Hopi's share: one thread-CPU call per Hopi thread park; thread scan only while watched, ≤ 5 ms Android (100 threads), ≤ 0.5 ms iOS |
| memory | 0 | same sampler | same wake | ≤ 50 µs per sample Android (`mallinfo` included), ≤ 5 µs iOS, two small strings; PSS on demand only, stalls faulting app threads while it reads |
| remote lease | 0 | none of its own | 1 at expiry | rides an existing perf thread's timed wait |
| summary log | 0 | none of its own | none of its own | one log call per 10 s while CPU or memory is on; jank and hang lines as events occur, ≤ 5 per second |
| all on, app idle | | 3 Android, 2 iOS | ≤ 1 per second (sampler), Android and iOS (tier 2 off), plus on iOS the hang watchdog's ≤ 1 per main-thread wake | ≤ 0.1% of one core |

## Release exclusion

Since the runtime-split plan's stage 8 (2026-10-29) a release build holds `hopi-api`'s `HopiPerf`,
which does nothing without a runtime, and never `hopi-perf`.

- `hopi-api`'s ABI dumps hold the consumer API (`HopiPerf`, `HopiMonitor`, `HopiPerfConfig`);
  `hopi-perf`'s are empty, so a public declaration outside `dev.koga.hopi.perf.internal` shows
  there. `hopi-api`'s JVM tests assert no `hopi-core` and no `hopi-perf` on their classpath, and
  `PerfDefaultsTest` (`hopi-perf`) compares `hopi-api`'s literal defaults and `HopiMonitor`'s
  entries with the runtime's.
- The Gradle plugin adds `hopi-perf` to debuggable build types only, with `perf { enabled = true }`
  (TestKit-checked: the debug APK has it, the release APK none), and lists `PerfEntry` in the
  generated tool list (a Java class, not resources). Since the runtime-split plan's stage 9
  (2026-10-29) a module that names `hopi-perf` itself fails its release build in the plugin's
  release guard, and an iOS Release framework whose classpath holds it fails in the iOS one
  (ARCHITECTURE, "Release guards"); nothing swaps it.
- `:sample:androidApp:verifyReleaseHasNoRuntime` (since stage 9; the two release checks before it):
  the release classpath holds no `hopi-perf` and its Hopi classes are the jars' of `hopi-api` and
  the thin `hopi-ktor`; no type of
  `dev.koga.hopi.perf.internal` or `dev.koga.hopi.core` is defined or referenced; no Hopi
  class references `addOnFrameMetricsAvailableListener` or `setMessageLogging`; the release APK has
  no generated configuration and no provider. `verifyDebugCapture` adds the positive control
  (`PerfEntry`, its keep rule and the listener reference present).
- `:sample:shared:verifyIosReleaseExcludesCapture`: without the flag, no `_hopi_perf_` symbol, no
  `kfun:dev.koga.hopi.perf.internal` or `kfun:dev.koga.hopi.core.perf` symbol, no
  `HopiPerfCadence` class, no `hopi-perf-hang` string; with it, all present. Since stage 9 the
  Kotlin family covers every symbol kind of every runtime package (`_k…:dev.koga.hopi.perf.internal`
  and `.core` among them) and the sampler's thread name `hopi-perf-samp` joins the strings. `hopi-api`'s
  `dev.koga.hopi.perf` symbols (`HopiPerf`, `HopiMonitor`, `HopiPerfConfig`) are expected either
  way: every framework exports `hopi-api`.

## Security and privacy

Read-only toward the app's data except starting and stopping monitors, which are write commands
behind the verified `hello` and `remoteControl`. Stacks hold class, method and file names, never
values; thread names and screen labels may carry whatever the app put in them (bounded, never
read from user content by Hopi). Nothing is persisted (the previous ANR is read from the OS, not
written, and only its main-thread section is kept); nothing leaves the device except over the
existing authenticated bridge. Stack capture reads only the app's own main thread, in its own
process, and keeps only return addresses, never the stack's contents. The `-hopiPerf` launch
argument and the Gradle `autoStart` exist only in debug builds (a release build has no runtime).

## Architecture contract changes

Modules table (the pair, core's `perf` package), Consumer API (`HopiPerf`, the DSL), Agent access
(perf log lines, `hello.perf`, `perf.*` commands, the CLI), UI (HUD, Perf tab), Desktop (Perf
panel), "Idle cost is zero" (the hang monitor's watchdog, the sampler and the remote-start lease
deadline as the three stated, opt-in exceptions to "no timers"; the lease has no thread of its
own; iOS tier 2's display link and the Android heartbeat as the two opt-in exceptions to "nothing
wakes while the app is idle"), the release checks.

## Tests

- **Core, JVM, fake clock**: the watchdog state machine (no hang under the threshold, one at it,
  stacks at 1x/2x/4x, untimed sleep after one doze, wakes counted for a busy/idle pattern, a
  message that ends during capture discards the stack, an oversleep restarts instead of reporting,
  a debugger detection takes no stack, nested input bracket inside a message); the packed state
  (sequence wrap-around, start and sequence never torn); frame classification (60/90/120 Hz, API
  31 deadline, first frame, `metricsLost`, bucket closing without a timer, no two points for one second); owners (lease expiry
  and tail end never stop a monitor code or another client holds, `perf.stop` removes all); histogram percentiles;
  ring bounds by count and bytes; per-kind eviction; rate limiter; log lines (no `id`, budgets,
  stack cut at a frame boundary); bridge `perf.*` (hello flag, before-hello, not pipelined,
  `UNSUPPORTED`, `LIMIT`, lease expiry, session-scoped stop, tail backlog/live seam, `SLOW_CLIENT`).
- **Idle**: module present and nothing started: no `hopi-perf-*` thread, no listener, 0 wakes;
  after `stop`: threads gone, listeners removed, window callbacks removed when on top and
  otherwise disabled (a restart re-enables, never stacks, them), the printer cleared only when
  it was Hopi's (a printer set by "another tool" after Hopi's survives the stop). No monitor class
  is loaded once perf's tool started (`NoMonitorClassTest`, a child-first classloader on the JVM;
  HotSpot evidence only: on Android a debug build runs from its APK and ART loads every class a
  method names when it verifies the class, so the Android backend names none, checked with `javap`
  and an emulator probe).
- **API** (`hopi-api`'s JVM and iOS tests, `hopi-perf`'s JVM tests): with no runtime every call is
  inert, the block runs, only `start` is counted and `screen` keeps its label; over a fake runtime
  each call hands over what it should (the monitors set true, each setting set and null when not);
  over the real one a block applies only what it sets, the start's `summaryLog` decides perf's lines
  and `remoteControl` comes from the tool, two dropped starts give one notice, and a label set
  before the registration reaches the controller; an iOS Debug binary writes its line once, at the
  first dropped start.
- **hopi-connect, hopi-cli, hopi-desktop**: decoders, argument parsing, rendering, exit 4, the
  panel composed against a fake session.
- **Android host tests**: provider registration with and without `hopi-perf` (without: no
  `Class.forName` call at all), resources parsed; `Window.Callback` wrapper delegates every method
  and is removed only when on top; perf's tool takes the four `perf { }` values and the start's
  `summaryLog`; a frames start with a resumed activity says `attaching` while frames runs (the
  posted block's end is checked on a device, since a host `Handler` runs nothing).
- **iOS simulator tests** (`:hopi-perf:iosSimulatorArm64Test`): a 600 ms block on the main thread
  is one hang whose stack contains the test's function; a 150 ms one is none; a main thread that
  loops without sleeping through 1 s of 5 ms sources is no hang; the frame walk survives a corrupt
  fp (out of bounds, misaligned, cyclic) and a Swift async frame, and strips signed return
  addresses; `task_threads` leaks no port over 10 000 scans (`mach_port_names`
  count); footprint grows by an allocated, touched 64 MB.

## Device verification plan

One device at a time; `./gradlew --stop` before booting; everything stopped afterwards. The
sample gains a Perf lab screen and a self-test host target like the rules one (`--es perfVerify
all` on Android, `-verifyPerf` on iOS) that injects each case and checks the monitor against an
outside reference:

| Injection | Expectation | Reference |
| --- | --- | --- |
| 30 frames with a 100 ms main-thread sleep in a frame callback; also 20 and 50 ms | ≥ 28 janky frames (100 ms), worst within ±10 ms; 20 ms frames slow, not janky at 60 Hz (iOS: 50 and 100 ms units are stalls, 20 ms none) | gfxinfo framestats (Android), the stall list (iOS) |
| main-thread sleep 200, 300, 1000, 3000 ms; a 600 ms busy loop | 200: none; others one hang each, duration ±20 ms, stack names `PerfLab.hang`/`PerfLab.burn` | the injected duration |
| 600 ms busy loop inside a Compose `onClick` (Android: input dispatch, not a message; iOS: a touch source) | one hang, stack names `PerfLab.clickBurn` (the input-bracket gate) | the injected duration |
| the app sent to the background mid-hang and frozen (`am freeze`-style cached state on Android, suspension on iOS), then resumed | no hang for the frozen time | the injection log |
| frames on, a Compose `Dialog` shown for 5 s | `perf.status` reports the activity-only coverage; no fake jank | gfxinfo |
| 2 threads `perf-burn-1/2` at 100% for 5 s | process ≈ 200% ±15%, both threads at the top | `/proc/<pid>/stat` over adb, `ps -M` |
| 64 MB Java allocation; 64 MB native (touched) | Java heap +64 MB ±5%; RSS / footprint +64 MB ±10% | `dumpsys meminfo`, `footprint`/`vmmap --summary` |
| `am send-trim-memory <pkg> RUNNING_CRITICAL` and `BACKGROUND` (the level API 34+ still sends); simulator memory warning | one memory event each | the command |

Evidence under `build/evidence/perf/`, results under `benchmark/perf/<date>/`.

## Performance plan

With the existing harness method (`abn.sh` arms, alternating blocks, host load under 3, guest
load under 2.5, `paired.py`):

- **Arms**: noop; real, nothing started; each monitor alone; all on.
- **Idle 60 s**, launcher visible: CPU ticks and `voluntary_ctxt_switches` per thread. Gate:
  nothing started is identical to noop (0 ticks, 0 wakes on any Hopi thread); each monitor on
  within its table row.
- **Scrolling** the sample's list (identical flings, gfxinfo): janky % and UI-thread p50/p95 with
  frames and hangs on against off, inside the off-to-off spread.
- **Microbenchmarks on device**: 10 000 main-looper messages with and without the printer, as
  the realistic mix (empty messages, `View.post` lambdas, coroutine resumes on `Dispatchers.Main`,
  a running Compose animation), median and p99, plus the GC count over the run (the 3 µs gate);
  the input bracket per event; run-loop observer cost per iteration; display link callback cost; one
  `thread_get_state` stack capture; one Android `stackTrace` of main at depth 30 and 200; one CPU
  sample, one thread scan, one memory sample, one PSS.
- Results, including targets not met, go into "As built" and the roadmap's open list.

## Implementation order

1. Spike: printer per-message cost over the realistic mix on the emulator; the Compose `onClick`
   hang through the input bracket (both gates decide printer or heartbeat); iOS frame walk and
   suspension time; CMP `MTLDrawable` presented handler.
2. Core model, JSON, log sink, bridge commands, with their JVM tests.
3. `hopi-perf`/`-noop` API, parity, Gradle plugin substitution and DSL, release checks.
4. Monitors: hangs, frames, CPU/memory (Android, then iOS C library).
5. Surfaces: connect and CLI, in-app HUD and Perf tab, desktop panel.
6. Perf lab, device verification, the performance plan, "As built".

## Risks

- **The printer's cost, a displaced printer, or input-dispatch hangs** — two gates above; the
  heartbeat is designed and selectable (`hangHeartbeat`).
- **Stack capture on iOS** — a fault in the walk or a lock held by the suspended thread; mitigated
  by the stack-bounds check (with `vm_read_overwrite` beyond it), nothing allocating, locking or
  entering Kotlin while suspended, and symbolication after resume; stress-tested on the simulator.
- **Misattributed stacks** — a message that ends during capture; the post-capture sequence check
  discards them.
- **False hangs** from a debugger, the cached-app freezer or iOS suspension; the oversleep check and
  the debugger check.
- **CADisplayLink keeping the display at full rate** on ProMotion; tier 2 runs only on an explicit
  toggle, and its power cost needs a device to measure.
- **Android 14+ trim levels** — no foreground pressure event exists there; stated in the UI.
- **Emulator numbers** (SwiftShader frames, VM-exit futex wakes) are upper bounds; a phone run is
  open, as for capture.
- **ART stack walks on deep stacks** may pause the main thread for milliseconds; bounded by 3
  captures per hang and recorded.

## As built

### Stage 1: core and Android (2026-10-25)

Built: core's `dev.koga.hopi.core.perf` (model, rings, histogram, per-second frame buckets,
`BusyState`, `HangDetector`/`HangWatchdog`, owners, `PerfController`/`PerfHook`, `PerfJson`,
`PerfLogSink`, `emitPerfLogLine` on all three platforms), the six `perf.*` bridge commands and the
`hello` flags, `hopi-perf`/`hopi-perf-noop` (API above, ABI dumps, `:checkNoopParity`,
`DefaultsParityTest`), the Android monitors (frames, hangs with printer, input bracket, heartbeat
and the previous ANR, CPU with Hopi's share and the per-thread scan, memory with RSS, GC stats,
trim events and on-demand PSS), the JVM backend (CPU and memory), the provider hand-off, the
Gradle DSL and twin substitution, and the release and debug APK checks.

Tests (host only): `PerfModelTest`, `HangDetectorTest` (fake clock: thresholds, 1x/2x/4x, doze then
untimed, discarded capture, oversleep, debugger, heartbeat, wake only from untimed),
`HangWatchdogTest` (real thread: a 700 ms stall is one hang with three stacks naming the stalled
method, 50 ms none, an idle main thread wakes nothing), `PerfCommandTest`, `PerfBridgeSessionTest`
(hello flags, hello first, `UNSUPPORTED`, `LIMIT`, start/stop/status with a lease, snapshot with
stacks cut to 8 frames, `perf.event`, tail backlog/live seam with a hang's end, session-scoped
release), `HopiPerfTest` (JVM: install starts no thread, one sampler for CPU and memory, lease
expiry keeps a code owner, tail owners), the noop tests, `InputBracketCallbackTest` (every
`Window.Callback` method delegated, only the three dispatches bracketed, a throwing handler still
ends the bracket), the provider's lookup tests (no `Class.forName` without the perf block), the
plugin's render and DSL tests, and the sample's release/debug checks.

Deviations from the design, and why:

- **No spike yet.** The two gates (printer cost over the realistic mix, the Compose `onClick` hang
  through the input bracket) need the emulator and are open; the printer path is built as the
  default and the heartbeat is built and selectable (`hangHeartbeat`), so either outcome needs no
  redesign.
- **Generated class, not resources.** The design says the plugin writes `perf.present=true` into
  the provider's generated resources; the provider actually reads a generated
  `HopiGeneratedConfig` class (android-capture.md, "Auto-start"), so the perf block is four more
  constructor arguments of it (`perfPresent`, `perfAutoStart` as a bit set, `perfHangThresholdMs`,
  `perfHangHeartbeat`). The provider loads `PerfEntry` only when `perfPresent` and calls it through
  core's `PerfBootstrap` interface (one `Class.forName` hit, one constructor, one call). (History:
  since the runtime-split plan's stage 4, `PerfEntry` is a tool the generated tool list constructs,
  and `PerfBootstrap` is gone; see "How the monitors find the app".)
- **The plugin's `HopiMonitor`.** `autoStart(HopiMonitor.Hangs, …)` takes the plugin's own enum
  `dev.koga.hopi.gradle.HopiMonitor` (same entries, same order): the build script cannot see
  `hopi-perf`'s.
- **`HopiPerf.stop` removes every owner**, as `perf.stop` does (the design states it for
  `perf.stop` only).
- **No `HopiLauncher` shortcut.** The launcher's activity reference (frames attach at once on a
  remote start) belongs with the in-app surfaces; until then a remote `perf.start` of frames
  attaches at the next activity start and says so (`attached:false`, a notice). The `inspector`
  flag exists in the model and nothing sets it yet.
- **PSS on the caller's thread.** `perf.snapshot` with `pss` measures on the bridge reader thread
  (never the main thread), not on the sampler's; still at most once per 10 s and recorded as a
  Hopi pause.
- **Android `available` memory is null.** The only source (`ActivityManager.getMemoryInfo`) is a
  binder call per sample; the column is for iOS's `os_proc_available_memory()`.
- **iOS registers, offers nothing.** `hopi-perf`'s iOS target compiles (its klib dump is part of
  the parity check) and registers a controller with no available monitor, so `hello.perf` is true
  and a start is `UNSUPPORTED` with the reason; the C library is stage 2.

Open (stage 1): the spike's two gates and every number in the budget table (device verification
plan and performance plan unchanged); `Window.Callback` install/uninstall on a real window (the
host tests cover the wrapper, not `Window`); displaced-printer detection (counts every 120 frames)
unmeasured; the previous-ANR read on a device that has one.

### Stage 2: iOS monitors (2026-10-25)

Built: the native library `hopi-perf/src/nativeInterop/objc/hopi_perf.{h,m}` (compiled per iOS
target and embedded in the `perf` cinterop klib, package `dev.koga.hopi.perf.internal.native`,
like `hopi-network`'s URLSession library, core's when this was built), and `IosBackend`,
`IosHangMonitor`, `IosSampler` in iosMain:

- **Observers and busy state (C).** Two `CFRunLoopObserver`s on the main run loop for the common
  modes: begin (order `LONG_MIN`) on `AfterWaiting | BeforeTimers | Exit`, end (order `LONG_MAX`) on
  `BeforeWaiting`. They publish the packed state of `BusyState` (same 24/40-bit layout, starts in
  `CLOCK_UPTIME_RAW` milliseconds) as a C11 atomic, and signal the watchdog only on an idle-to-busy
  transition while it is parked and at a hang's end. Installed while frames or hangs runs, removed
  at stop.
- **Frames tier 1.** The observers time each unit in nanoseconds; a unit over twice the deadline
  calls one Kotlin function on the main thread after the state turned idle (so recording it is
  never counted as the app's work), which records a `stall`. The deadline is
  `1 / UIScreen.maximumFramesPerSecond`, read on the main queue (a hop when started elsewhere),
  capped at 60 Hz without `CADisableMinimumFrameDurationOnPhone`; 60 Hz until read.
- **Frames tier 2.** A `CADisplayLink` in Objective-C (`HopiPerfCadence`, created and invalidated
  on the main queue, paused in the background), running only while frames runs and
  `PerfController.watchCadence` counts a viewer; core's bridge counts one per `perf.tail` with
  `"fps":true` for that session's life. Each callback writes one "frame" of the callback interval
  against the vsync period, with missed vsyncs (gaps over 1.5 periods). The HUD toggle is stage 3.
- **Hangs.** `IosHangMonitor` runs core's `HangWatchdog`/`HangDetector` unchanged over
  `NativeBusy` (a `MainBusy` over the C state) on an `NSThread` `hopi-perf-hang` at the
  user-initiated QoS. Capture: `thread_suspend`, `thread_get_state(ARM_THREAD_STATE64)`, pc, lr and
  a frame-pointer walk from fp between sp and the stack's top (16-byte aligned, strictly
  increasing, async bit 60 cleared, every return address masked with `0x0000000FFFFFFFFF`) into a
  preallocated array, `thread_resume`; lr is inserted as the second frame unless the walk's first
  return address is lr. The stack is recorded and logged as raw addresses, then named with
  `dladdr` (`image symbol + offset`; return addresses looked up at `addr - 1`). A debugger
  (`sysctl` `P_TRACED`) is checked once per detection.
- **CPU and memory.** `hopi-perf-samp` (utility QoS, a dispatch semaphore with a deadline):
  `MACH_TASK_BASIC_INFO` plus `TASK_THREAD_TIMES_INFO` for the process total, Hopi's share from
  each perf thread's `CLOCK_THREAD_CPUTIME_ID` before it parks, `TASK_VM_INFO.phys_footprint` as
  the resident column and `os_proc_available_memory()` as available. The per-thread list
  (`task_threads`, `THREAD_BASIC_INFO`, `THREAD_IDENTIFIER_INFO`, `THREAD_EXTENDED_INFO`; every
  port deallocated and the array freed in the same call) uses the kernel's decayed `cpu_usage`,
  so a first scan has percentages too. Memory adds the `UIApplicationDidReceiveMemoryWarning`
  observer (`warning`) and a `DISPATCH_SOURCE_TYPE_MEMORYPRESSURE` source on a utility queue
  (`pressure` with `warn` or `critical`), both removed at stop.
- **Lease with frames alone**: one `dispatch_after` on a low-priority global queue, superseded by a
  generation number on every lease change (a `dispatch_after` cannot be cancelled).
- **Launch argument** `-hopiPerf frames,hangs`, read once by the first `install`.
- **Release check.** The sample's iOS framework now exports `hopi-perf` (or its noop) and
  `verifyIosReleaseExcludesCapture` also requires, without the flag, no `_hopi_perf_` symbol, no
  `kfun:dev.koga.hopi.perf.internal` or `kfun:dev.koga.hopi.core.perf` symbol, no
  `HopiPerfCadence` class and no `hopi-perf-hang` string (UTF-16, a Kotlin literal); with it, all
  of them. Run both ways: noop all 0 / absent; real 79 `_hopi_perf_` symbols, 491 perf Kotlin
  symbols, 24 class symbols, the string present.

Tests (`:hopi-perf:iosSimulatorArm64Test`, the main run loop driven from the test's main thread):
`IosHangTest` (nothing before a start and after a stop: no `hopi-perf-*` thread, no observer; a
600 ms block is one ended hang of 550 to 900 ms whose first stack names the blocking function; 150
ms is none; a main thread that never sleeps through 1 s of 5 ms timer callbacks is none; an idle
main thread sleeping 2 s wakes the watchdog at most 3 times; an 80 ms unit is one stall and a 5 ms
one none; stopping hangs keeps the observers while frames runs), `StackWalkTest` (a sound chain,
max, out of bounds, misaligned, a cycle, a self link, the async flag, signed return addresses, a
zero return address), `IosSamplingTest` (process CPU of a 200 ms burn, footprint +64 MB ±10% for a
touched 64 MB block, no port leak over 10 000 thread scans, one sampler for CPU and memory listed as
Hopi's, memory warnings only while memory runs, a frames-only lease expiring with no thread, the
display link only while frames runs and a viewer asks, the launch argument). Core: `HangDetectorTest`
gains raw-then-named stacks, `PerfBridgeSessionTest` the `fps` viewer count.

Measured (simulator, iPhone on the iOS 27.1 SDK, debug test binary, one run): a capture of a 35-frame
main stack suspended the main thread 65 and 73 µs (target ≤ 100 µs). Every other iOS figure in
the budget table is still to measure.

Deviations from the design, and why:

- **The watchdog is a Kotlin thread, not a C pthread.** It runs core's `HangWatchdog` and
  `HangDetector` unchanged (one state machine for both platforms, fake-clock tested), reading the
  C-owned state through the new `MainBusy` interface. What the design wanted from a C pthread
  holds: it parks inside a C call (native state), so the Kotlin/Native GC never waits on it, and
  the suspend-walk-resume is one C call, so nothing enters Kotlin while the main thread is
  suspended. A GC another thread starts during a capture waits for the resume (≤ 100 µs).
- **One semaphore per watchdog.** A stopped watchdog that has not woken yet must not share a wake
  with the next one, so each start creates its own; the observers signal the attached one under an
  `os_unfair_lock` taken only on their rare signalling paths. It has two owners, the thread and the
  stop (whose wake can land after the thread saw the stop and ended), and the second release frees
  it.
- **`kCFRunLoopExit` begins a unit.** The design names only `AfterWaiting`, `BeforeTimers` and
  `BeforeWaiting`. Without `Exit`, a run loop that returns after waking (a timeout, a stop) left the
  state busy or idle at random for the code that runs after it (a nested loop's caller, which is
  busy work); with it, that code is a unit of its own until the next sleep.
- **Two `ongoing` lines per iOS detection** (same `seq`): raw addresses at once, then the named
  stack, so a main thread hung inside dyld (which `dladdr` waits for) delays the names, never the
  detection. Agents key on `seq`.
- **No `vm_read_overwrite`.** The walk reads only between sp and the stack's top and ends there;
  an async frame record on the heap ends the walk cleanly instead of being followed.
- **Native library in `src/nativeInterop/objc`**, not `src/nativeInterop/c`: the display link, the
  notification observers and `UIScreen` need Objective-C; the hot paths are plain C functions in
  the same file. `pthread_main_thread_np` is exported by libSystem but declared only in a private
  header, so the library declares it.
- **Not sampled on iOS**: the Kotlin/Native heap and GC count (the only source, `GC.lastGCInfo`, is
  experimental runtime API); `available` is null on the simulator (`os_proc_available_memory()`
  returns 0 without a jetsam limit). `hangHeartbeat` does nothing on iOS (a notice says so). PSS
  is refused with a reason.
- **Screen label**: only `HopiPerf.screen(…)` on iOS (no activity fallback), as designed.

Open (stage 2): the CMP `MTLDrawable` presented-handler spike; the observer cost per run-loop
iteration, the display-link callback cost, a CPU sample, a thread scan and a memory sample on the
simulator and a device; tier 2's power cost on a ProMotion device; a hang while the app is
suspended and resumed (the oversleep path on a real suspension); memory pressure events (the
simulator cannot raise them on demand); a CMP app on the simulator (`-hopiPerf hangs`). Under a
debugger every detection is `debugger:true` without a stack, by design: running from Xcode, which
is the usual iOS loop, shows hangs without stacks; the stacks need a run without the debugger
(`simctl launch`, or Xcode's "Debug executable" off).

### Stage 3: surfaces (2026-10-25)

Built, every surface reading the model only while shown and none with a timer:

- **`hopi-connect`.** `PerfClient` over a verified `BridgeClient`, like `RuleClient`: it checks
  `hello.perf` first and sends nothing to an app without it (exit 4), and nothing pipelined with
  `hello`; `start`, `stop`, `status`, `snapshot` (every line before `end`), `event`, `tail`.
  Decoders: `RemotePerfStatus` (and `RemotePerfMonitor`, its details kept as JSON),
  `RemotePerfEvent`, `RemoteFrameSecond`, `RemotePerfSample`, `RemotePerfSeries` (null values as
  NaN), `RemotePerfSummary`, `RemotePerfThread`. `RemotePerf`, a `RemoteSession`'s perf side, is
  the desktop's live model (below). The ABI dump is updated (it also drops the replay entries the
  working tree had already removed).
- **CLI.** `hopi perf start|stop|status|snapshot|tail` and `hopi hangs`, as designed, with the
  flags checked before connecting (`PerfArgs`: `--hang-ms` needs `--hangs`, `--interval` needs
  `--cpu` or `--memory`, `--for` 1s..60m rounded up to seconds, never with `--no-expiry`; `--start`
  names checked). `--json` prints the bridge's lines; human output is `PerfRender` (monitor table
  with owners and the lease's remaining time, summaries, the newest 20 events, the thread table,
  PSS; `hangs` prints every stack with its 1x/2x/4x label). An unattached Android frames monitor
  prints the design's hint. `perf tail` turns a `SLOW_CLIENT` into the `--since` to resume from.
- **Desktop.** A Perf panel (⌘P, menu Perf) beside the list: a switch per monitor with its owners
  (a lease with its end time), charts drawn with `Canvas` (frames per second with janky bars, CPU %,
  memory resident solid and Java heap dashed, memory events as dashed ticks; the last five minutes),
  the recent memory events, the per-thread list behind a switch, and the hangs (expand for the
  stacks, copy as text).
- **In-app.** A Perf button in the list's top bar (only with a controller registered) opening a
  Perf tab: monitor switches (`PerfOwner.Code`), the HUD switch, the iOS cadence switch, summaries,
  sparklines of the last 60 points, the per-thread list, "Measure PSS", memory events, hangs with
  stack detail. The HUD in `HopiLauncher`, toggled by a long press on the button or the tab. The
  inspector flag (`PerfData.inspectorOpen`) is set while `HopiScreen` is composed. The launcher's
  activity hand-off (core's androidMain `PerfLauncher`, a weak reference written on the launcher's
  first composition only when a controller is registered, cleared on dispose): a frames start
  attaches to that window at once with one main-thread message and no notice, and a hangs start
  brackets its input the same way.
- **Counted per-thread scan.** `PerfRuntimeController.watchThreads` is now counted, like
  `watchCadence` (stage 2 found it was a flag: a tail ending stopped the scan another viewer still
  showed).

Tests (host): `PerfClientTest` in `hopi-connect` (an app without perf refused before any perf line,
start/stop/status decoded with the owner and settings the app received, `remoteControl = false`,
every part of a snapshot, a full-stack event and `NOT_FOUND`, a tail's backlog then live lines and
the release of what it held, `RemotePerf` streaming only while watched, backfill, a second monitor
switched on without the first restarting, hangs updated in place, threads, off as `perf.stop`, the
last watcher's close releasing the monitors, a viewer of an app without perf sending nothing),
`PerfCliTest` (parsing, every usage error, exit 4 without perf and without remote control,
start/status/stop end to end with the lease, snapshot rendering, `hangs` with full stacks and
`--since`, a tail holding its `--start` monitor until the app goes away), `PerfPanelUiTest`
(nothing asked before ⌘P, backfill, a switch started as a tail owner, a hang row expanded, threads,
closing the panel releasing everything, an app without perf, `remoteControl = false`),
`HudMeterTest` (common: fps while drawing, idle, the sampler's idle second, unchanged text
publishing nothing, iOS stalls and cadence, jank events ignored), `PerfUiTest` (no Perf button and
a plain long press without a controller; the tab's code start, hangs, threads scan only while
shown, PSS, the inspector flag; the HUD toggled by a long press without recomposing the launcher,
its text after a sample, no listener once hidden), `ThreadWatchersTest` (the counted scan).

Deviations from the design, and why:

- **Desktop switches hold monitors through the panel's own `perf.tail` (`start`), not a lease.**
  A lease would need the viewer to renew it on a timer or let it lapse after 10 minutes of
  watching; a tail owner lives exactly as long as the panel, dies with the viewer, and costs no
  wake on either side. Changing the set reopens the stream (new first, old closed after, so a
  monitor both hold never restarts). Off is `perf.stop`, which removes every owner, as designed.
- **Three connections while the Perf panel is open**, not two: `RemoteSession`'s tail and request
  connections stay as they are, and the `perf.tail` gets its own, opened only while the panel is
  open. The app's eight slots allow it.
- **`perf status` makes a second request** (a one-point snapshot) for the counts the design's
  status line shows, since `perf.status` carries none; `--json` prints the `perf.status` line only.
- **The HUD's figures went stale** after a monitor stopped: it updated only when an item arrived,
  and a stop publishes none, so it kept the last values (forever once nothing ran; frozen CPU and
  MB beside live fps; "idle" when frames stopped). Fixed after stage 5, still with no timer: the
  controller announces its running set to listeners and the HUD drops a stopped monitor's figures
  ("In-app" above; `HudMeterTest`, `PerfHudTest`, `HopiPerfTest`).
- **No HUD tooltip**: mobile has none. The HUD's own cost is stated in the Perf tab's note and in
  ARCHITECTURE.md.
- **The cadence switch lives in the Perf tab**, since the HUD takes no touch; the display link runs
  only while the HUD is composed with the switch on.
- **`hopi-ui-noop` is unchanged**: its launcher draws only the app, so there is nothing to
  long-press and no HUD; `:checkNoopParity` holds since the public API did not change.

Open (stage 3): every surface on a device (stage 4), the HUD's redraw cost measured against the
"at most one per second, none while idle" statement, and the launcher hand-off on a real activity
(host tests cover neither a real `Window` nor `LocalContext`).


### Stage 4: sample, device verification, overhead (2026-10-25)

Built: the sample's **perf lab** and **bench** (`sample/shared`, `PerfLab.kt`, `PerfBench.kt`, see
ARCHITECTURE.md, "Sample app"), `benchmark/perf/verify.py`, `bench.py` and `idle.py`, results in
`benchmark/perf/2026-10-25/`. The lab uses the public API only (`HopiPerf.start`, `stop`,
`screen`), so it runs against the noop as well; the host reads Hopi's record over the bridge
(`hopi perf snapshot --json`, `hopi hangs --json`) and checks it against the injections and its
own references. Android: `--es perfVerify all[,heartbeat]`, `--es perfBench <spec>`; iOS:
`-verifyPerf`, `-perfBench <spec>`; `iOSApp.init` now calls `HopiPerf.shared.install` before the UI.

Measured on the API 35 emulator (API 35, arm64 emulator on an Apple M2, SwiftShader rendering,
60 Hz, debug build, guest load under 2) and on the iPhone 17 simulator (iOS 27.0, Debug, host load
under 4). No physical device yet; emulator and simulator numbers are not device numbers.

**Device verification, Android (printer, the default): 28 of 28 checks pass**
(`android-verify-printer.json`).

| Injection | Hopi | Reference |
| --- | --- | --- |
| 30 frames blocked 100 ms in `withFrameNanos` | 31 janky, animation stage median 102.3 ms (frames up to 151 ms with SwiftShader's render) | gfxinfo framestats: 29 to 30 over two deadlines |
| 30 frames blocked 50 ms | 31 janky, animation stage median 52.6 ms | gfxinfo: 30 |
| 30 frames blocked 20 ms | 3 janky (4 on the heartbeat run): slow, not janky, as designed | gfxinfo: 30, because it counts from the intended vsync and SwiftShader's 15 to 40 ms render makes each frame wait for the previous one |
| main-thread sleep 200 ms | no hang | |
| sleep 300, 1000, 3000 ms; spin 600 ms | one hang each: 303, 1002, 3003, 601 ms (within 0 to 3 ms of the time measured inside the unit), stacks naming `PerfLab.hang` / `PerfLab.burn` | the lab's own timing |
| 600 ms spin inside a Compose `onClick`, tapped with `adb shell input tap` | one hang of 603 to 605 ms, stack naming `PerfLab.clickBurn`, in all four runs: **the input-bracket gate passes** | |
| Compose `Dialog` for 5 s | at most 1 janky frame; `perf.status` says `"windows":"activities only"` | |
| 2 threads spinning 5 s | 197.7 % of one core | `/proc/<pid>/stat`: 199.2 % |
| 64 MB `ByteArray`, touched | Java heap +63.9 MB, resident +63.9 MB | `dumpsys meminfo` Java Heap +63.8 MB |
| 64 MB bitmap pixels (native heap), touched | native heap +64.0 MB, resident +61.4 MB | `dumpsys meminfo`'s App Summary did not show it under Native Heap (-2.8 MB); not resolved |
| `am send-trim-memory RUNNING_CRITICAL`, Home, `BACKGROUND` | trim 15, 20 (UI hidden), 40 | the commands |

Stack capture (ART's `Thread.getStackTrace` of main) paused 134 µs to 1.75 ms, median 230 to
580 µs per run, within the 2 ms budget. The lab starts its monitors from code after the activity
is up, and frames attached to it at once (`"attached":true`, one window, janky frames within 2 s):
the launcher's activity hand-off works on a real activity, as does the input bracket on a real
window (the click burn above).

**The printer's gate: not met.** Main-thread units posted one after the other, 10 000 per run,
3 runs per launch, 3 blocks with arms alternating; median of the 9 runs' medians of the gap between
unit starts (`android-bench-arms.ndjson`, `android-bench-heartbeat.ndjson`):

| Unit | noop | real, nothing started | frames | hangs (printer) | all four | hangs (heartbeat) |
| --- | --- | --- | --- | --- | --- | --- |
| empty `Runnable` | 1.21 µs | 1.25 µs | 1.25 µs | 7.46 to 7.58 µs (+6.2 to +6.4) | 7.58 µs | 1.25 µs (+0.04) |
| new lambda (`View.post`'s shape) | 1.46 µs | 1.46 µs | 1.46 µs | 7.67 to 7.83 µs (+6.2 to +6.5) | 7.75 µs | 1.38 µs (+0) |
| `yield()` on `Dispatchers.Main` | 3.29 µs | 2.96 µs | 3.00 µs | 32.3 to 32.8 µs (+29.0 to +29.8) | 32.3 µs | 3.21 µs (+0.12) |

With the printer set the GC ran 3 to 4 times per 10 000 plain messages and 15 to 16 times per
10 000 coroutine resumes (0 in every other arm), and p99 rose from 1.8 to 11.9 to 16.7 µs (plain)
and from 5.0 to 77 to 85 µs (coroutine). The cost is the framework's: `Looper` builds the
dispatch string (`msg.target`, `msg.callback`'s `toString`, for a coroutine
`DispatchedContinuation`'s, which names the continuation's source position) before it calls any
printer, so no printer of Hopi's can make it cheaper. Hopi's own share is not separable from it
here. The target was 3 µs median over the realistic mix: 2x over on plain messages, 10x on
coroutine resumes.

**The heartbeat, measured as the alternative** (`android-verify-heartbeat.json`, 22 of 26), before
its cadence was fixed: nothing per message (above), 8.1 wakes per second in all while the app is
idle (the watchdog 239 and the main thread 245 in 60 s: 4 each, one beat per threshold where the
design says one per `threshold / 2`) and 90 ms of watchdog CPU per minute. It missed the 300 ms
hang and read 1001 ms as 853 ms and 601 ms as 578 ms. A unit is seen only from the next beat, so a
hang reads short by up to one beat period; the 148 ms under-read of the 1001 ms hang is more than
the 125 ms (`threshold / 2`) the design allows, so it was the defect, not the granularity: after
each beat the watchdog slept to that beat's deadline, one threshold, before posting the next (a
beat's end wakes nothing). Fixed after stage 5 (`HangDetectorTest`): until a unit is a hang the
watchdog looks again within `threshold / 2` (the end of a beat, or of a tap's dispatch, wakes
nothing), so it beats every `threshold / 2` (8 wakes per second on each thread at 250 ms, 16 in
all), a hang reads at most 125 ms short, and only one shorter than 1.5 thresholds can be missed
(2 before). Re-measured on the emulator on 2026-10-27 (`benchmark/perf/2026-10-27/`, verdicts
written before the data in `session-verdicts.md`): idle, the watchdog woke 470 to 471 times and
the main thread 475 to 477 times in 60 s (7.8 to 7.9 per second each, 3 runs), with 110 to 160 ms
of watchdog CPU per minute. In two lab runs the 1 s hang read 917 and 938 ms against 1003 and
1001 ms measured in the unit, the 3 s hang 2950 and 2949 ms against 3002 and 3004, and the 600 ms
burn 556 and 561 ms against 601, each within 125 ms; the 300 ms hang (1.2 thresholds) was missed
both times, which the bound allows (22 and 21 of 26: `verify.py`'s ±20 ms duration checks are the
printer's bound, and the 20 ms jank step read 4 janky frames in one run, as on 2026-10-25). The
click burn stayed exact, 605 and 604 ms (the input bracket does not depend on the printer). The
printer run of the same session: 28 of 28 (hangs 311, 1004, 3001 and 602 ms, the click burn
603 ms).

**Verdict: the printer stays the default** and the heartbeat stays the opt-in
(`hangHeartbeat`) for an app that needs another tool's printer. The printer is exact (0 to 5 ms),
costs nothing while idle and only while hangs runs, and its cost is the framework's own; the
heartbeat would trade it for idle wakes on two threads and hangs missed near the threshold. The
printer's cost is now stated as measured (ARCHITECTURE.md, README). Changing the default is the
owner's call, not this stage's. Open: a cheaper busy/idle signal than the printer (the public SDK
offers none; `Looper.setObserver` is hidden API).

**Idle, 60 s after a 10 s settle, every thread's CPU and voluntary context switches from
`/proc/<pid>/task/*`** (`android-idle.ndjson`):

| Arm | Hopi's threads | Main thread |
| --- | --- | --- |
| noop build, nothing started | none | 0 wakes |
| real, nothing started | 0 CPU, 0 wakes | 0 wakes |
| hangs (printer) | 0 CPU, 0 wakes (the watchdog sleeps untimed) | 0 wakes |
| frames | 0 CPU, 0 wakes (nothing draws) | 0 wakes |
| hangs (heartbeat, before its cadence fix) | `hopi-perf-hang` 239 wakes, 90 ms | 245 wakes, 10 ms |
| hangs (heartbeat, after it: 2026-10-27, 3 runs, `benchmark/perf/2026-10-27/`) | `hopi-perf-hang` 470 to 471 wakes, 110 to 160 ms | 475 to 477 wakes, 10 to 20 ms |
| CPU and memory | `hopi-perf-samp` 60 wakes, 70 ms | 0 wakes |
| all four | `hopi-perf-samp` 60 wakes, 70 ms | 0 wakes |
| CPU alone, memory alone (120 s each) | `hopi-perf-samp` 119 wakes, 100 ms each | 0 wakes |

"Off is free" holds: nothing started is identical to the noop build. The sampler wakes exactly once
per interval, but a tick costs about 0.8 to 1.2 ms of CPU in a debug build (budget: 5 µs for CPU,
50 µs for memory), whichever of the two runs, so the cost is in the shared tick (recording,
publishing, the 10 s log line), not in a data source; most likely interpreted code, since a debug
app's sampler runs once a second and never gets hot enough for the JIT. All four on and idle cost
0.12 % of one core (budget 0.1 %). Open, not fixed in this stage.

**Device verification, iOS simulator: 20 of 21 checks pass** (`ios-verify-iphone17-ios27.0.json`;
the click burn is a button there, not driven, and iOS overhead was not benched).

| Injection | Hopi |
| --- | --- |
| 30 run-loop units blocked 100 / 50 / 20 ms in `withFrameNanos` | 30 stalls (worst 111 ms) / 30 (worst 61 ms) / none |
| sleep 200 ms | no hang |
| sleep 1000, 3000 ms; spin 600 ms | 1005, 3008, 600 ms, stacks naming `PerfLab#hang` / `PerfLab#burn` |
| sleep 300 ms | one hang, but of 376 and 388 ms in the two runs: the run-loop unit went on 75 to 86 ms after the block; not explained (open) |
| 2 threads spinning 5 s | 199.9 % (`ps -o cputime`: 201.4 %); 163 % in a first run on a host at load 40, the simulator's first-boot daemons |
| 64 MB `ByteArray`; 64 MB `nativeHeap`, touched | resident +63.6 MB; +64.0 MB |
| posted `UIApplicationDidReceiveMemoryWarningNotification` | one `warning` event |

Stack capture suspended the main thread 52 to 430 µs (budget 100 µs: met in the first run, 52 to
169 µs, not in the second, 69 to 430 µs). On the first launch after install, showing the Compose
`Dialog` held the main thread for 1510 ms, which Hopi reported as a hang on screen `PerfLab`: a real
one (CMP's first dialog on iOS), not seen again on the next launch.

Deviations from the design, and why:

- **Frame classification (Android)** measures a frame from the vsync it was drawn for and holds a
  late frame to the last on-time frame's deadline (see "Frames"), because the emulator showed
  `TOTAL_DURATION` against `DEADLINE` count 1 janky frame of 30. `FrameMetrics` is final, so this
  is verified on the emulator and not host-tested.
- **The "20 ms frames are slow, not janky" expectation** depends on the render time. It held (3
  and 4 janky of 30) by Hopi's own-time rule; gfxinfo's own count does not, on SwiftShader.
- **The lab's native allocation on Android is a bitmap's pixels**, not a direct `ByteBuffer`
  (ART allocates those in its non-movable Java space; the first run showed native heap +0).
- **No frozen-process case**: freezing the app mid-hang (the cached-app freezer, iOS suspension) is
  not injected by the lab.

Open (stage 4): a physical Android phone and iPhone; iOS overhead (observer cost per run-loop
iteration, display link, sampler) with `bench.py --platform ios`; the click burn and the
memory-pressure source on iOS; the sampler's per-tick cost; a cheaper hang signal than the
printer; the 300 ms iOS reading; the `dumpsys meminfo` native-heap discrepancy; the CMP
`MTLDrawable` spike; the HUD's redraw cost measured on a device.

### Stage 5: review fixes and re-verification (2026-10-26)

A review of stages 1 to 4 and a device pass found the defects below; each is fixed with a test, and
the device checks were rerun on the API 35 emulator (debug, `-Phopi.enabled=true`) and the iPhone 17
simulator (iOS 27.0, Debug). Evidence: `build/evidence/perf/2026-10-26b/`.

| Defect | Fix | Test | On the device |
| --- | --- | --- | --- |
| Under any remote lease (a CLI `perf start` holds one of 600 s; the timer is the earliest of every monitor's) the idle hang watchdog dozed until the lease's end without parking, so `begin` did not wake it: a hang that started meanwhile was missed | the watchdog parks before every idle sleep; the lease only bounds it | `LeasedHangWatchdogTest` (fails without the fix: "a 500 ms unit under a lease was not detected") | `benchmark/perf/lease_check.py`: the 600 ms click burn under a CLI lease on hangs, and with hangs code-owned and only CPU leased: no hang with the stage 4 APK, 603 ms with the fix |
| CPU started while memory kept the sampler running divided all the CPU since the sampler started by one interval | `CpuRate`, shared by both samplers: a tick with CPU off forgets the baseline, the first tick back is a new baseline | `CpuRateTest` | first CPU point after 20 s of memory alone: 6.7 % before (the app idles at 0.1 %), 0.1 % after |
| `perf.tail` dropped every live event at or below `since`, a hang's later versions too: a stream resumed from the highest seq (the desktop's reopen, a CLI resume after `SLOW_CLIENT`) never got the end of a hang in progress | hang versions pass whatever `since`; `SLOW_CLIENT`'s `since` stays below a hang sent as ongoing; `RemotePerf` resumes below a hang it shows as ongoing and counts a re-sent jank or stall once | `PerfBridgeSessionTest` (two), `PerfClientTest.aReopenDuringAHangKeepsItsEndAndCountsOnce` | |
| Android input to a `Dialog`, sheet or popup window is not bracketed, while status said `"input":true` only | no public API reaches those windows: stated, `"inputWindows":"activities only"` (`"all (heartbeat)"` with the heartbeat), "Input dispatch" above | `HangDetailsTest` | status reads it (lease check output) |
| A lone short main-thread unit cost the watchdog three wakes (its begin's, its deadline's, the idle doze's) | no idle doze when no unit began since the one watched: two | `HangDetectorTest.aLoneShortUnitSkipsTheIdleDozeAndABurstKeepsIt` | iOS idle unchanged (+4.7 to +4.9 context switches/s against +4.8): the simulator's units are microseconds long, already one wake each (below) |
| The Perf tab formatted every retained hang's stacks (up to 1.6 MB of text) and rebuilt every row on each second, sample or event | stacks formatted when a row is opened; an unchanged hang keeps its row | `PerfPaneModelTest` | |
| `PerfLock` was a bare spin the main thread takes behind lower-priority readers (priority inversion, read as the app's jank) | a `ReentrantLock` on the JVM and Android, an `os_unfair_lock` on iOS (also the iOS runtime lock) | `PerfLockTest` (JVM and simulator) | |
| `PerfData` allocated its rings (about 125 KB) at install, every monitor off | a ring's arrays at its first write | `PerfModelTest.ringsAreAllocatedAtTheirFirstWriteOnly` | |
| iOS: a 300 ms hang read 391 to 419 ms on a first launch | stacks are named with `dladdr` only once the unit ended (iOS hangs, above). The lab's new `mainBackMs` and `GC.lastGCInfo` fields ruled out the rest of the main-queue drain (a block posted behind the injected one ran within 1 ms) and a GC (none ran during it; pauses under 1 ms); a build that named nothing read 302 ms twice on fresh installs | `HangDetectorTest.rawStacksAreRecordedAndLoggedFirstAndNamedOnlyOnceTheUnitEnded` | 302, 302, 300 ms on three fresh installs (stage 4 and the first pass of this one: 391, 403, 419); 21 of 21 checks, twice; log lines 18 of 18 with the named ongoing line |

**The sampler's tick, measured exactly.** `/proc/<pid>/task/<tid>/schedstat` (nanoseconds, not
10 ms ticks), 60 s idle: `hopi-perf-samp` used 64 to 68 ms per minute with CPU and memory on;
waiting with `LockSupport.parkNanos` instead of a timed `Semaphore.tryAcquire` brought it to 61 ms
(-5 %). So a tick costs about 1.0 ms in the debug build against the 5 and 50 µs budget, still
**over**. A debuggable app cannot be AOT-compiled (`cmd package compile -m speed -f` leaves it at
`verify`), and a once-a-second loop never gets hot enough for the JIT, so it runs in ART's
interpreter; a `simpleperf` profile was too perturbed to rank its parts. All four monitors on and
idle: process +57.7 ms per minute over nothing started (schedstat), 0.096 % of one core (0.10 to
0.13 % by ticks): at the 0.1 % line.

**Android idle, after the fixes** (2 blocks of 60 s, `android-overhead.ndjson`): nothing started
0.067 % of a core for the whole process, Hopi's threads 0 CPU and 0 wakes; hangs 0 and 0 (the
watchdog parks untimed), main thread 0 wakes; CPU and memory, and all four: `hopi-perf-samp` 60
wakes and 60 to 70 ms per minute, every other Hopi thread 0. Verification 28 of 28 (hangs 301,
1003, 3004, 600 ms, the click burn 605 ms), log lines 20 of 20.

**iOS idle, after the fixes** (`ios-idle.ndjson`, 2 blocks of 60 s; `ps` and `top`): nothing
started 50 ms per minute and 6.6 context switches per second (the noop build: 55 ms, 6.35); hangs
50 to 70 ms and 11.3 to 11.5 per second; all four 80 ms and 12.5 to 12.8 per second. Each wake of
the main run loop is an idle-to-busy transition, which wakes a parked watchdog (the design's one
wake per idle-to-busy); on the simulator the main run loop wakes about 6 times a second in an app
that does nothing, so "0 while idle" holds only for a main thread that sleeps, and the budget rows
now say "≤ 1 per main-thread wake". Fewer would need a timer armed on the main thread per unit,
which moves cost onto the thread being measured. Open: the main loop's wake rate on a device.

Stack pauses in this stage's runs: Android 152 µs to 5.4 ms, median 676 µs over 11 captures (one
over the 2 ms budget, the third capture of the 1000 ms hang; stage 4: 136 µs to 1.75 ms); iOS 47 µs
to 1.49 ms, median 176 µs over 137 captures, 56 within the 100 µs budget.

Open (stage 5): the sampler's tick in the interpreter; iOS stack suspension (above, budget 100 µs)
and Android's 5.4 ms outlier; a physical phone and iPhone; the printer's 3 µs gate (the framework's
cost, unchanged); `copy outside the lock` for the readers (the mutex already removes the inversion).
