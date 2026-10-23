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

### Later candidates (from DebugSwift's feature set)
- Console / log capture (app logs alongside HTTP).
- WebSocket and SSE inspection for Ktor.
- Mock / edit-and-replay requests from the inspector or the CLI.
- Desktop viewer (Compose Desktop) over the same bridge protocol.
- App info: build, device, environment, feature flags.
- Physical iOS device support for the CLI.
