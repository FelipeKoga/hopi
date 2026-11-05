# Hopi

Hopi is a debug-only devtool for Android, iOS (Kotlin Multiplatform) and JVM apps: network
capture with mock, fail, delay and edit rules, plus performance monitors, viewed in a desktop
viewer and a CLI.

[![CI](https://github.com/FelipeKoga/hopi/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/FelipeKoga/hopi/actions/workflows/ci.yml?query=branch%3Amain)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.felipekoga.hopi/hopi-core)](https://central.sonatype.com/artifact/io.github.felipekoga.hopi/hopi-core)

> [!WARNING]
> Hopi is experimental, and it is developed entirely with AI (Claude Code). Its APIs can change
> between versions, and it is for debug builds only.

![The desktop viewer: the request list as tonal cards, the selected GET and its detail with the Overview, Request and Response tabs](docs/images/viewer.png)

| Rules | Performance |
| --- | --- |
| ![The Rules panel: two rules with their switches, Update and Remove](docs/images/rules.png) | ![The Perf panel: the monitors' switches and the frames, CPU and memory charts](docs/images/perf.png) |

## Features

- **Network capture**: every OkHttp and `HttpURLConnection` request of an Android app and every
  URLSession request of an iOS process, third-party SDKs and Ktor's engines included; on the JVM,
  the Ktor clients that install Hopi's plugin.
- **Rules**: mock a response, fail a request the platform's own way, delay it, or edit the request
  or the response, in the app's memory for the session only and never weakening its TLS or pinning.
- **Performance monitors**: frames, hangs with the main thread's stack, CPU and memory, each off
  until started.
- **Desktop viewer** (Compose Desktop, macOS first): requests and their detail side by side, Rules
  and Perf panels, cURL, HAR and NDJSON export.
- **CLI**, `hopi`: list, tail, get, cURL, rules and monitors from a terminal; its `--json` output is
  a stable contract for AI coding agents.
- **Log stream**: one NDJSON line per request in logcat or the iOS unified log, with no setup.
- **Redaction** on every channel: `Authorization`, `Proxy-Authorization`, `Cookie` and
  `Set-Cookie` by default, plus the headers and query parameters you name.

## Quick start

Every artifact has the group `io.github.felipekoga.hopi` and the version of `hopi.version` in
[`gradle.properties`](gradle.properties) (`0.1.0`); use one version for every Hopi artifact. Hopi
is published to Maven Central from its first tagged release on, the Gradle plugin
`io.github.felipekoga.hopi` included (it is not on the Gradle Plugin Portal), so `mavenCentral()`
goes in both repository lists of `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Until that first release is out, run `./gradlew publishToMavenLocal` in a clone of this repository
and put `mavenLocal { content { includeGroup("io.github.felipekoga.hopi") } }` first in both lists
([from source](docs/GUIDE.md#from-source)).

Declare the plugin in the same `plugins { }` scope as AGP and the Kotlin plugin. The snippets below
show the module's scope; where the root `build.gradle.kts` declares them with `apply false`, add
`id("io.github.felipekoga.hopi") version "0.1.0" apply false` there and write
`id("io.github.felipekoga.hopi")`, with no version, in the module.

### Android app

In the application module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("io.github.felipekoga.hopi") version "0.1.0"
}

hopi {
    agentBridge = true          // let the CLI and the desktop viewer connect (default false)
    perf { enabled = true }     // register the performance monitors, none started (default false)
}
```

That is the whole setup. Debug builds record every OkHttp and `HttpURLConnection` request, the
libraries' included, with no code to call; release builds get no runtime. Add
`io.github.felipekoga.hopi:hopi-api:0.1.0` only if your code calls `HopiRules` or `HopiPerf`.
More: [Android](docs/GUIDE.md#android-every-okhttp-and-httpurlconnection-request-automatically).

### KMP app on iOS

In the KMP shared module whose framework the iOS app links:

```kotlin
plugins {
    kotlin("multiplatform")
    id("io.github.felipekoga.hopi") version "0.1.0"
}

hopi {
    agentBridge = true
    perf { enabled = true }
}
```

Then one line in Swift, first thing at launch, the same in every configuration and with no `#if`:

```swift
HopiSetup.shared.start()
```

The plugin generates `HopiSetup`. In a Debug build `start()` records every URLSession request of
the process, SDKs and Ktor's Darwin engine included; in a Release build it does nothing and the
framework holds no runtime. The `embedAndSignAppleFrameworkForXcode` build phase needs no change.
A KMP app on both platforms also applies the plugin to its Android application module, as above.
More: [iOS](docs/GUIDE.md#ios-every-urlsession-request-automatically).

### JVM app with Ktor

```kotlin
dependencies {
    implementation("io.github.felipekoga.hopi:hopi-ktor:0.1.0")
    implementation("io.github.felipekoga.hopi:hopi-start:0.1.0")   // only where recording is wanted
}
```

```kotlin
import dev.koga.hopi.ktor.Hopi
import dev.koga.hopi.start.Hopi as HopiStart

fun main() {
    HopiStart.start { agentBridge = true }   // first, before any HttpClient is built
    val client = HttpClient(engine) { install(Hopi) }
    // …
}
```

The JVM records the requests of the Ktor clients that install the plugin. It has no release guard,
so name `hopi-start` only in the builds that should record; the guide keeps it behind a Gradle
property: [JVM](docs/GUIDE.md#jvm-compose-desktop-the-ktor-plugins-requests).

### Open the viewer and the CLI

Building from this repository is the only way to get them today: neither is published. Both reach
the app over its bridge, so the app needs a debug build with `agentBridge = true`.

The desktop viewer: run it from the repository, or build a `.dmg` (unsigned, about 65 MB with its
own Java runtime). Pick a device, enter the app's id, and connect.

```bash
./gradlew :hopi-desktop:run
./gradlew :hopi-desktop:packageDmg   # hopi-desktop/build/compose/binaries/main/dmg/Hopi-1.0.0.dmg
```

The CLI: build it with `./gradlew :hopi-cli:installDist`; the binary is
`hopi-cli/build/install/hopi-cli/bin/hopi`.

```bash
hopi devices --json                        # reachable targets
hopi list --app <applicationId or bundle id> --json --limit 20
hopi tail --app <id> --json                # live, one line per event, until interrupted
hopi mock add --app <id> --match 'GET */users/*' --status 500   # a mock rule
```

Every command, the rules and the monitors:
[Log stream and CLI](docs/GUIDE.md#log-stream-and-cli-for-ai-agents).

## Requirements

| | Minimum | Tested up to |
| --- | --- | --- |
| Kotlin | 2.2.0 for Android and JVM apps, 2.2.21 for iOS | 2.4.20 |
| Gradle | 8.0, on JDK 17 | 9.7.1 |
| Android Gradle plugin | 8.0, with `compileSdk = 34` and `minSdk = 24` | 9.4.0 |
| KMP module with an Android target | `com.android.kotlin.multiplatform.library` (AGP 8.2.0) | the sample only, on AGP 9.4.0 |
| Ktor, for `hopi-ktor` | 3.3.3 | 3.5.2 |
| Xcode | the one your app's Kotlin requires | 27.1, the only one tested |

Versions between the two columns are not tested. Every caveat (`.set()` before Gradle 8.2, the one
`plugins { }` scope, older AGP 8 versions' warnings, the klib ABI) is in the guide's
[Requirements](docs/GUIDE.md#requirements).

## How it works

- **No per-client setup.** On Android the Gradle plugin rewrites, at build time and in debuggable
  variants only, OkHttp's call class and every `java.net.URL` connection call site of the app and
  of every library it depends on; on iOS one start records every URLSession request of the process.
- **No Hopi runtime in release builds.** Only debug builds link the runtimes, never through
  `#if DEBUG`, and the release guards (Android variants, iOS Release links) fail a release build
  that would link one. What a release build can hold is the inert `hopi-api` and `hopi-ktor` your
  code names.
- **Zero idle cost.** No timers, no polling, no request of its own, and a rule check of one volatile
  read per request while no rule exists; a performance monitor that is off has no thread, listener
  or timer.
- **Nothing inside the app.** Hopi shows what it sees in the device log and, over a device-local
  bridge, in the CLI and the desktop viewer. Rules live in the app's memory, and a restart clears
  them.

The design contract: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Documentation

- [Guide](docs/GUIDE.md): each platform's setup, the `hopi { }` options, rules, performance
  monitors, the CLI and the viewer, measurements, troubleshooting, and building.
- [Architecture](docs/ARCHITECTURE.md): the design contract, the wire protocol and the `--json`
  shapes.
- [Roadmap](docs/ROADMAP.md): what is built and what is planned.
- [Releasing](docs/RELEASING.md): the Maven Central release.
- [Consumer fixtures](compat/README.md): the apps that test the requirements above.

## Development

JDK 17, Gradle through `./gradlew`; CI runs on macOS with Xcode 27.1. The base check, one Gradle
invocation, which GitHub Actions runs for every push and pull request:

```bash
./gradlew :hopi-api:jvmTest :hopi-core:jvmTest :hopi-ktor:jvmTest :hopi-ui:jvmTest :hopi-network:jvmTest \
  :hopi-cli:test :hopi-connect:test :hopi-desktop:test \
  :hopi-perf:jvmTest :hopi-perf:testAndroidHostTest check \
  :sample:androidApp:assembleDebug :sample:androidApp:verifyReleaseHasNoRuntime :hopi-network:testAndroidHostTest \
  :sample:shared:verifyIosReleaseExcludesCapture :gradle-plugin:hopi-gradle-plugin:functionalTest :checkPublication
```

To try a change in an app, publish every artifact and the plugin to Maven Local, then add
`mavenLocal()` as [Quick start](#quick-start) says:

```bash
./gradlew publishToMavenLocal
```

The toolchain, the publication check, the consumer matrix, the sample app and the benchmarks:
[Building](docs/GUIDE.md#building) and [Sample app](docs/GUIDE.md#sample-app).

## Credits

Inspired by [DebugSwift](https://github.com/DebugSwift/DebugSwift), [Pulse](https://github.com/kean/Pulse), [Chucker](https://github.com/ChuckerTeam/chucker), [Flipper](https://github.com/meta/flipper), [Pluto](https://github.com/androidPluto/pluto), [netfox](https://github.com/kasketis/netfox) and [Wormholy](https://github.com/pmusolino/Wormholy).

## License

Hopi is licensed under the Apache License, Version 2.0: see [`LICENSE`](LICENSE).
`hopi-ui`'s Open Runde fonts are under the SIL Open Font License 1.1, with
`OPEN_RUNDE_LICENSE.txt` beside them in `hopi-ui/src/jvmMain/resources/dev/koga/hopi/ui/font/`.
