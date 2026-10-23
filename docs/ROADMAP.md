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

### Performance monitors
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

### Later candidates (from DebugSwift's feature set)
- Console / log capture (app logs alongside HTTP).
- WebSocket and SSE inspection for Ktor.
- Mock / edit-and-replay requests from the inspector or the CLI.
- App info: build, device, environment, feature flags.
- Physical iOS device support for the CLI.
