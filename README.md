# Hopi

Hopi is a real-time HTTP inspector for Kotlin Multiplatform and Compose Multiplatform apps on
Android and iOS that use the Ktor client. Every request the app makes shows up in three places:

- an in-app screen (list, detail, cURL, share) behind a small floating button,
- one NDJSON line per request in logcat or the iOS unified log,
- a host CLI, `hopi`, whose `--json` output is a stable contract for AI coding agents,
- a desktop viewer (Compose Desktop, macOS first) that shows the same traffic on your screen.

It is built to cost nothing when nobody is looking: no timers, no polling, no global interception,
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
        }
    }
}
```

Debug runs pass `-Phopi.enabled=true` (an Android Studio run configuration, and the Xcode
build phase for the Debug configuration, as in `sample/iosApp`). Anything built without it ships
the noops, which contain no Hopi logic and no `hopi-core`. An Android-only app can select
by build type instead (`debugImplementation` real, `releaseImplementation` noop); an app module on
top of a KMP shared module must not, since the shared module already brings one of the two.

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
reachable. Exit codes: 0 ok, 1 usage, 2 not reachable, 3 not found; errors are one line on stderr.
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

Ctrl replaces ⌘ on Linux and Windows. An export fetches the bodies not opened yet, up to 32 MB
per request and 256 MB in all; larger ones keep their sizes and a comment.

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
| Release APK of the sample built without the flag | 12 Hopi classes (the noop twins), 0 from `hopi-core`; 345 and 97 with `-Phopi.enabled=true` |

The first idle and the 300 KB body figures were taken with the earlier, Android-only version of
the sample; the rest in the phase 3 device acceptance. Physical devices have not been measured yet.

## Sample app

`sample/` is a Compose Multiplatform app (a small shop: item list, detail, a form that POSTs, and a
diagnostics tab that fires one request of every kind the inspector handles) for Android and iOS.

```bash
python3 sample/devserver/devserver.py           # dev server on 127.0.0.1:8765

# Android (emulator): the real Hopi needs the flag
./gradlew :sample:androidApp:installDebug -Phopi.enabled=true
adb reverse tcp:8765 tcp:8765

# iOS simulator: Debug passes the flag itself
open sample/iosApp/iosApp.xcodeproj             # or:
xcodebuild -project sample/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.2' build

# The release APK must ship only the noop twins
./gradlew :sample:androidApp:verifyReleaseUsesNoop
```

## Building

JDK 17, Gradle through `./gradlew` (9.7.1), Kotlin 2.4.20, Compose Multiplatform 1.12.0, Ktor
3.5.2. `./gradlew check` runs the tests (including the iOS simulator tests), the ABI dump checks and
the noop parity check (a real module and its twin must have exactly the same public API).
The giant-body benchmarks are opt-in and slow (minutes): run one module at a time with
`./gradlew :hopi-ktor:jvmGiantBodyBenchmark` (also `:hopi-core:` and `:hopi-ui:`)
or `./gradlew :hopi-cli:testGiantBodyBenchmark`; tables land in `<module>/build/giant-body/`.
