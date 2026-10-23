# Hopi

Hopi is a real-time HTTP inspector for Kotlin Multiplatform and Compose Multiplatform apps on
Android and iOS that use the Ktor client. Every request the app makes shows up in three places:

- an in-app screen (list, detail, cURL, share) behind a small floating button,
- one NDJSON line per request in logcat or the iOS unified log,
- a host CLI, `hopi`, whose `--json` output is a stable contract for AI coding agents.

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
