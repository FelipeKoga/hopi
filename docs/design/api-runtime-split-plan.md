# Plan: automatic capture only, no noop twins, per-tool runtimes (handoff)

Status: plan, 2026-10-27. Nothing in it is built. Written for the agent that will execute it.
It supersedes `docs/design/api-runtime-split.md` wherever the two differ (that first proposal kept a
Ktor plugin and a public `HopiUrlSession`; both go away here).

How to use this document:

1. Section 1 is the state of the repository when the plan was written.
2. Section 4 (evaluation) comes first. It was planned but not run. Its answers go into section 9,
   then the open decisions of section 5 are revisited with the owner.
3. The stages of section 6 run in order. Each stage ends with `./gradlew check` green and its
   "Done when" list satisfied.

Legend: **(verified)** was read in the code on 2026-10-27; **(to verify)** is an assumption that
section 4 must confirm or refute before the stage that depends on it.

---

## 1. State of the repository (2026-10-27)

- Written on top of commit `1c81938`. The commit that adds this plan also repositions the project
  as "devtools for KMP and CMP" instead of "HTTP inspector": `README.md` (introduction),
  `docs/ARCHITECTURE.md` (introduction), `docs/ROADMAP.md` (first lines and a pointer to the
  proposal), `hopi-cli/src/main/kotlin/dev/koga/hopi/cli/CliArgs.kt` (usage text),
  `hopi-desktop/build.gradle.kts` and `hopi-ui/build.gradle.kts` (descriptions),
  `hopi-ui/src/commonMain/kotlin/dev/koga/hopi/ui/HopiScreen.kt` (KDoc), and adds
  `docs/design/api-runtime-split.md` (the first proposal, superseded). `:hopi-cli:test` passed
  after the CLI change.
- Published library modules today (13): `hopi-core`, `hopi-ktor`, `hopi-ktor-noop`, `hopi-ui`,
  `hopi-ui-noop`, `hopi-urlsession`, `hopi-urlsession-noop`, `hopi-android`, `hopi-android-noop`,
  `hopi-rules`, `hopi-rules-noop`, `hopi-perf`, `hopi-perf-noop`; plus the included build
  `gradle-plugin/` (`hopi-instrument`, `hopi-gradle-plugin`, plugin id `dev.koga.hopi`).
  Not published: `hopi-connect`, `hopi-cli`, `hopi-desktop`, `sample:*`. (verified,
  `settings.gradle.kts`)
- The evaluation pass (section 4) was started as eight parallel readers and cancelled before any
  result. Nothing in section 9 is filled yet.

## 2. Decisions

| # | Decision | Source |
| --- | --- | --- |
| D1 | Hopi is a set of devtools for KMP and CMP apps (network, rules, performance, more tools later), not only an HTTP inspector. | Owner. Docs already updated (section 1). |
| D2 | HTTP is captured automatically, as DebugSwift does. The app never installs a per-client SDK: Ktor's OkHttp and Android engines are recorded by the Android bytecode capture, Ktor's Darwin engine by the iOS URLSession capture. **Delete `hopi-ktor` and `hopi-ktor-noop`.** Ktor CIO (and any engine that does not go through OkHttp, `HttpURLConnection` or URLSession) is documented as not recorded. | Owner ("the user must not need a Ktor or an iOS SDK"); deleting rather than keeping the plugin optional is the recommendation the owner asked this plan for. |
| D3 | No `-noop` twin modules. A release build still links no Hopi logic and no `hopi-core`. | Owner. |
| D4 | One tiny API module that does nothing until a runtime registers (no dependency, release-safe, optional), and one runtime module per tool that only debug builds link. | Owner (asked for this plan). |
| D5 | Android: zero code. The `dev.koga.hopi` plugin adds the runtimes to debuggable variants only; a manifest provider starts them. | Owner. |
| D6 | iOS: one call from Swift inside `#if DEBUG` (e.g. `Hopi.shared.start { $0.agentBridge = true }`). The public `HopiUrlSession` API goes away. | Owner. |
| D7 | The in-app launcher appears by itself (Android: overlay per activity; iOS: a window of its own). `HopiLauncher { }` and `HopiScreen` leave the consumer path; code that wants to open the inspector calls `HopiInspector.open()` from the API module. | Owner. |
| D8 | `HopiAndroid` (`isStarted`, `okHttpInstrumented`) becomes internal. | Owner. |
| D9 | Defense in depth: the plugin fails the build when a runtime lands in a non-debuggable variant; the Android runtime refuses to start when the app is not `FLAG_DEBUGGABLE`. | Owner. |

Goals that every stage is judged against: **scalable** (a new tool is one runtime module, a few API
declarations, one DSL entry), **import only what is needed**, **secure** (nothing in release, no
network exposure, secrets redacted), **fast** (no cost added per request; zero cost when off).

## 3. Target architecture

### 3.1 Modules

| Today | After | Linked by |
| --- | --- | --- |
| `hopi-rules` + `hopi-rules-noop` | consumer API in `hopi-api`; the rule engine already lives in `hopi-core`, which registers it | `hopi-api`: the app, optional, every build. Engine: debug only |
| `hopi-perf` + `hopi-perf-noop` | consumer API (start, stop, running, screen labels) in `hopi-api`; runtime `hopi-perf` (monitors, native library) | runtime: debug only, opt-in |
| `hopi-ui` + `hopi-ui-noop` | runtime `hopi-ui` (screens, automatic launcher, desktop panes); `HopiInspector.open()` in `hopi-api` | runtime: debug only, on by default |
| `hopi-android` + `hopi-android-noop`, `hopi-urlsession` + `hopi-urlsession-noop` | runtime `hopi-network` (Android OkHttp and `HttpURLConnection` capture; iOS URLSession capture) | debug only, on by default |
| `hopi-ktor` + `hopi-ktor-noop` | deleted (D2) | |
| `hopi-core` | `hopi-core`: store, bridge, redaction, log sink, rule engine, the start sequence and the tool registry; depends on `hopi-api` | debug only |
| (none) | `hopi-api`: no dependency; `HopiRules` and its builders, `HopiPerf` (subset), `HopiInspector`, the registry the runtimes fill, `@HopiInternalApi` (moved here, same fully qualified name) | the app, optional |

Thirteen published library modules become five (`hopi-api`, `hopi-core`, `hopi-network`,
`hopi-perf`, `hopi-ui`). Names are question Q2 (section 5); `hopi-api` is recommended over `hopi`
because a project `:hopi` inside the root project `hopi` reads ambiguously in Gradle paths and the
name says what the module is (an API that does nothing alone).

Whether the iOS URLSession capture (today in `hopi-core`'s `iosMain`, with its native library built
by `hopi-core/build.gradle.kts`) moves into `hopi-network` is evaluation item E2.6. Moving it makes
`hopi-core` the base only; keeping it is less work. Recommended: move, unless E2.6 finds a blocker.

### 3.2 What the consumer writes

Android, the application module:

```kotlin
plugins {
    id("com.android.application")
    id("dev.koga.hopi") version "0.1.0"
}

hopi {                                   // optional
    agentBridge = true                   // the CLI and the desktop viewer can connect
    perf { enabled = true; autoStart(HopiMonitor.Hangs) }   // adds hopi-perf (debug only)
    // network and ui are on by default: network { enabled = false } / ui { launcher = false }
}
```

No Hopi line in any Kotlin file. Debuggable variants get the runtimes and the provider; others get
nothing (no API module either, unless the app's code names `hopi-api`).

KMP shared module (iOS), until the plugin supports KMP modules (stage 10):

```kotlin
// shared/build.gradle.kts
val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false  // Xcode Debug passes it
val v = "0.1.0"
kotlin {
    sourceSets {
        // Only if shared code calls HopiRules, HopiPerf or HopiInspector; harmless in release.
        commonMain.dependencies { implementation("dev.koga.hopi:hopi-api:$v") }
        if (hopiEnabled) {
            iosMain.dependencies {
                implementation("dev.koga.hopi:hopi-network:$v")
                implementation("dev.koga.hopi:hopi-ui:$v")
                // implementation("dev.koga.hopi:hopi-perf:$v")
            }
            // The Swift-visible entry, only in Debug builds (see 3.4, decision O1)
            iosMain { kotlin.srcDir("src/iosHopi/kotlin") }
        }
    }
}
```

Swift:

```swift
@main
struct MyApp: App {
    init() {
    #if DEBUG
        Hopi.shared.start { $0.agentBridge = true }   // first thing: requests resumed before it are not recorded
    #endif
    }
}
```

Android in a KMP project: the shared module must never add a runtime to `androidMain`; the
application module's plugin does that per variant (the plugin's build-time guard, D9, fails a
release variant that resolves one).

### 3.3 The API module (`hopi-api`)

No dependency. Kotlin targets: `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`. `explicitApi()`,
ABI validation on. Contents:

- **Rules**, same fully qualified names as today (`dev.koga.hopi.rules.*`), so callers do not
  change: `HopiRules` (`mock`, `fail`, `delay`, `edit`, `list`, `setEnabled`, `remove`, `clear`),
  `HopiRuleOptions`, `HopiMock`, `HopiDelay`, `HopiEdit`, `HopiRequestEdit`, `HopiResponseEdit`,
  `HopiRuleInfo`, `HopiFailure`, `HopiSource`. Changes from today: `HopiSource.Ktor` is removed
  (D2); the builders **keep** what is set (today's noop builders drop it) and expose it to the
  runtime through `@HopiInternalApi` members; they do not validate (validation stays in core's
  engine, so a release build accepts anything, as the noop does today). Without a runtime every
  call returns `-1`, `false` or an empty list, as the noop does today. (Full current API:
  appendix B.)
- **Perf**, `dev.koga.hopi.perf.*`: `HopiPerf.start { }`, `HopiPerf.stop(vararg)`,
  `HopiPerf.running`, `HopiPerf.screen(name)`, `HopiMonitor`, and the start block's settings
  (monitors and thresholds). `HopiPerf.install(...)` and the Android
  `HopiPerf.install(Application, ...)` are removed: registration is the start sequence's job (3.4).
  `agentBridge`, `summaryLog` and `remoteControl` move to the global start configuration. The exact
  split is evaluation item E5.2.
- **Inspector**: `dev.koga.hopi.HopiInspector` with `open()` (shows the in-app screen if the UI
  runtime is registered; does nothing otherwise) and `isAvailable: Boolean`. No Compose type.
  Not named `Hopi`: that name is reserved for the iOS entry object (3.4), and two `Hopi` classes
  in one framework collide in the Objective-C export (the reason `HopiUrlSession` was not called
  `Hopi`, `docs/ARCHITECTURE.md`).
- **Registry** (`@HopiInternalApi`): one object with a field per runtime kind (`rules`, `perf`,
  `inspector`), each written once by the runtime at start and read with one volatile read (an
  atomic reference on native) by the API calls. A second registration of the same kind is ignored
  with a notice line.
- **`@HopiInternalApi`** moves here from
  `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/HopiInternalApi.kt` with the same fully
  qualified name; `hopi-core` gets `api(project(":hopi-api"))`.

Its JVM tests keep the noop modules' assertion: no `hopi-core` on the test runtime classpath, no
core class loadable.

### 3.4 Start and registration

One start sequence in `hopi-core`, shared by every platform:
`start(config, tools)` installs the store (caps, redaction, summary log sink, bridge), registers the
rules runtime in the API registry, then starts each tool it was given. Each runtime module provides
one tool object implementing a small `@HopiInternalApi` interface declared in `hopi-core` (for
example `HopiTool { fun start(context) }`); the tool registers its API runtime (perf, inspector) in
the registry when it starts.

- **Android.** The plugin already generates, per variant, `HopiGeneratedConfig.java`, a subclass
  of `dev.koga.hopi.android.internal.HopiConfigValues` (verified,
  `gradle-plugin/hopi-gradle-plugin/src/main/kotlin/dev/koga/hopi/gradle/GenerateHopiConfig.kt:14,51,58`),
  and the provider `HopiInitProvider` (verified,
  `hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/HopiInitProvider.kt`) starts
  capture from the manifest. The generated class grows a list of the enabled tools, referenced by
  class directly (no reflection, so R8 and class loading stay predictable), and the provider calls
  the start sequence with it. The provider first checks `FLAG_DEBUGGABLE` and, if absent, writes one
  notice line and starts nothing (D9).
- **iOS.** Kotlin/Native runs no code of a module until something calls it, and it drops code that
  nothing reachable references (to verify, E2.3), so the runtimes cannot register themselves. The
  call that Swift makes must reference the tools. Options (decision O1):
  - **A. Aggregate module, exported.** A module (e.g. `hopi-ios`) that depends on every runtime and
    declares `Hopi.start { }`; the framework `export()`s only it. Simple; links every tool in Debug
    whether the app wants it or not (each stays off until started).
  - **C. Tools named from Swift.** `Hopi.shared.start(tools: [HopiNetwork.shared, HopiUi.shared])`;
    each runtime module must be `export()`ed, which puts its public `@HopiInternalApi` declarations
    in the Swift header (to verify, E2.4).
  - **D. Entry in the consumer's own module, Debug only.** A short file in a source directory that
    only `hopi.enabled` builds compile (the sample already does this for
    `src/iosCaptureProbe/kotlin`, verified `sample/shared/build.gradle.kts:116`) declares
    `object Hopi { fun start(configure) }` and calls the start sequence with exactly the tools it
    names. It is exported to Swift because it is the module's own code; no runtime module is
    exported. Stage 10 makes the plugin generate this file from the DSL.
  - **Recommendation: D**, with the template written by hand in stage 7 (the sample is the first
    user) and generated by the plugin in stage 10. It is the only option that links only the tools
    the app chose without exporting runtime internals. Open detail for E2.5: how the Swift closure's
    configuration type reaches the header (a type from a non-exported dependency used in an
    exported signature may be exported without its members; if so, the template declares its own
    small configuration class and copies it). Fallback if D fails the experiment: A.
- **JVM.** Tests call the start sequence directly. The JVM target stays for host tests, the CLI's
  and the desktop viewer's bridge tests and the desktop viewer itself; nothing records HTTP on the
  JVM after D2 (decision O5).

Calls made before the start sequence ran must not be lost silently: `HopiPerf.start` and the rule
calls made before registration keep their arguments as pending in the registry and the runtime
applies them when it registers, or they write a notice line (choose in E4.4).

### 3.5 Automatic launcher (hopi-ui)

- **Android.** The UI tool registers `ActivityLifecycleCallbacks` from the provider's start. For each
  created activity it adds a small `ComposeView` (the button only, never a full-screen view that
  would take the app's touches) to the activity's content frame, with the owners Compose needs
  (`LifecycleOwner`, `SavedStateRegistryOwner`, `ViewModelStoreOwner`) taken from a
  `ComponentActivity` or supplied by Hopi for other activities (E6.2). The inspector opens as an
  activity of its own declared in the runtime's manifest, which keeps back handling, insets and
  touches native. The existing launcher code (drag with positions as fractions in
  `rememberSaveable`, the long-press HUD) is reused for the button.
- **iOS.** The UI tool creates, at start, a `UIWindow` of its own above the app's windows, sized to
  the button (so it takes no touch outside the button), attached to the active `UIWindowScene`,
  hosting a `ComposeUIViewController` with the button; the inspector is presented full screen from
  it. A shake gesture opens the inspector too (config). Window level, key-window behaviour, scene
  changes and keyboard are E6.3, verified on the simulator.
- **Config.** `ui { launcher = true; shake = true }` on Android, the same fields in `Hopi.start` on
  iOS. With the launcher off, the app opens the inspector with `HopiInspector.open()` (a debug menu).
- **Invariants to keep** (today's numbers in `README.md`, "Performance"): 0 frames over 10 s idle
  with the launcher on screen, no recomposition of the launcher while idle, the app not drawn under
  the open screen. Their tests (the `recompositionProbe` of the internal launcher) move with it.

### 3.6 Release safety

Hopi leaves release builds by **not being linked**, never by `#if DEBUG` and never by relying on
dead stripping. 3.6.1 explains why the difference matters.

- **Build time.** Android: the plugin adds runtimes only to debuggable variants, and fails the build
  when any runtime module or `hopi-core` resolves in a non-debuggable variant (for example a manual
  `implementation(...)`). iOS: the Xcode build phase runs Gradle with `-Phopi.enabled=true` only for
  the Debug configuration (verified, `sample/iosApp/project.yml:75-79`), so the Gradle build that
  produces a Release framework has no runtime among its dependencies and does not compile the
  Debug-only entry file. Swift's `#if DEBUG` only lets the app compile against a framework that has
  no `Hopi` symbol; it removes nothing by itself.
- **Release guard (new, iOS).** A Gradle check that fails any Release framework or XCFramework
  link (`linkRelease*`, the release half of `assembleXCFramework`, `embedAndSignAppleFrameworkForXcode`
  with a `CONFIGURATION` other than Debug, the CocoaPods sync task if the consumer uses it) when a
  runtime is on that link's classpath, except in the explicit positive-control run of
  `verifyIosReleaseExcludesCapture`. Without it, one property left on (3.6.1, item 1) puts Hopi in
  an App Store build.
- **Run time.** Android provider: `FLAG_DEBUGGABLE` check (D9). iOS: the call exists only in Debug.
- **Checks.** `sample/androidApp`'s `verifyReleaseUsesNoop` (verified,
  `sample/androidApp/build.gradle.kts:301`) becomes `verifyReleaseHasNoRuntime`: the release APK
  holds no class of `hopi-core` or of any runtime; `hopi-api` classes are allowed (R8 keeps only
  what the app calls). `sample/shared`'s `verifyIosReleaseExcludesCapture` (verified,
  `sample/shared/build.gradle.kts:198`) keeps its intent: the Release framework holds no capture,
  monitor, UI runtime or core symbol.
- **Unchanged:** the bridge listens on device-local transports only and authenticates every
  connection; redaction defaults; nothing persisted.

#### 3.6.1 `#if DEBUG` is not removal (DebugSwift issue #202)

What the issue says (https://github.com/DebugSwift/DebugSwift/issues/202, "Feature: Remove package
for production version", opened 2025-08-01, closed as completed 2026-05-08, read 2026-10-27):
installed with Swift Package Manager, DebugSwift and all its files are "always compiled and linked,
even if we disable it at runtime", which costs binary size, raises security and code-audit concerns
and lengthens compile times. The maintainer answered with a workaround, not a change to the package:
wrap DebugSwift in a separate framework target that only the Debug configuration links (or that the
Release scheme excludes), and guard its import and its `enable()` call with `#if DEBUG`. The issue
page shows no merged change that explains the closure.

Why `#if DEBUG` is not enough (background, not stated in the issue): it removes only the app's own
call sites. A Swift package product is linked in every configuration, since Swift Package Manager
has no per-configuration linking. The linker's dead stripping cannot be relied on to drop it:
Objective-C classes and categories, `+load` methods and static initializers, Swift type metadata
and protocol conformance records stay because the runtime can reach them without a direct call;
`-ObjC` loads every Objective-C object file of a static library; a dynamic product is embedded
whole; resource bundles are copied regardless. Whether a debug tool is in the binary depends on what
is linked, not on what is called.

How Hopi differs:

| | DebugSwift through Swift Package Manager | Hopi (today and after this plan) |
| --- | --- | --- |
| What keeps it out of Release | `#if DEBUG` around the calls; the package stays linked | The runtime is not a dependency of the Release build: per variant on Android; on iOS the Gradle build for Release runs without `hopi.enabled` |
| Role of `#if DEBUG` | The only guard | Lets Swift compile against a Release framework that has no `Hopi` symbol |
| Proof | None in the package | `verifyIosReleaseExcludesCapture` scans the Release framework (`xcrun nm` plus a byte search): no `_hopi_us_` or `_hopi_perf_` symbol, no capture or rule-protocol Objective-C class, no `kfun:dev.koga.hopi.core` symbol, no capture queue label, no private selector name (`_didReceiveData:`, `_didFinishWithError:`), no perf thread name; with the flag it requires all of them, so the check is known to see them (verified, `sample/shared/build.gradle.kts:125-209`, wired into `check` on macOS). Android: the release APK check |

Where Hopi can still fail the same way, and what this plan adds:

1. **A Release link with the runtime on its classpath.** A Kotlin Multiplatform framework's Debug
   and Release binaries link the same compilation and the same dependencies (to verify, E2.8), so
   whatever `hopi.enabled` adds goes into every framework that Gradle invocation links:
   `hopi.enabled=true` left in `gradle.properties` or a CI script, one invocation that links both
   build types, `assembleXCFramework` (it builds both), a CocoaPods or Swift package distribution
   build. The Debug-only entry object is part of the framework's exported API, hence reachable, so
   Kotlin/Native keeps it and everything it calls, and Swift's `#if DEBUG` does not help. This is
   DebugSwift's failure mode. Mitigation: the release guard of 3.6; stage 10 derives the choice
   from Xcode's `CONFIGURATION` instead of a free property.
2. **The API module.** `hopi-api`, when the app uses it, is in Release by design: a few classes, no
   logic, no Objective-C class of its own, no private selector, no resource, no `+load`. An app that
   wants no Hopi symbol at all does not depend on it: neither Android nor iOS needs it to use Hopi.
   For comparison, today's release builds hold the noop twins (13 classes in the sample's release
   APK, `README.md` "Performance"; the noop `HopiPerf` in the iOS framework, which the check above
   expects), so the plan leaves less in Release than the current design does.
3. **Debug builds shipped to testers** (an ad hoc or TestFlight build of the Debug configuration)
   contain Hopi, as intended for Debug. The README says so next to the install steps.
4. **Android, a runtime added by hand** (`implementation("dev.koga.hopi:hopi-network:…")` in the
   app or a library): the plugin's build-time guard (D9) and the release APK check.

### 3.7 Performance

- Per request: the platform capture's cost does not change. Ktor apps lose one capture layer (the
  plugin and its `PlatformCapture.covers` check per request), so their cost can only drop.
- API calls without a runtime: one volatile read, nothing else; no class of `hopi-core` loaded.
- Off is free: a tool not started has no thread, no listener, no timer and no class of its own
  loaded (perf's existing guarantee, `docs/design/perf-monitors.md`), and the API module loads none
  of a runtime's classes.
- Measure before and after the stages that touch the request path (3 and 7) with the existing A/B
  harnesses: `benchmark/android-capture/` and `benchmark/urlsession/`. The README's headline
  per-request figure (+8.4 µs, JVM) was the Ktor plugin's; it becomes history.

### 3.8 What removing the Ktor plugin loses (document it; fix the first item)

1. **Encoded bodies on Android (regression to fix in stage 3).** A request that names its own
   `Accept-Encoding` (Ktor's `ContentEncoding`, or the app) gets the body from OkHttp and
   `HttpURLConnection` still encoded; Ktor decodes it above the engine. Today the Ktor plugin
   records those requests itself, decoded, and asks the platform recorder to skip them through the
   `X-Hopi-Pass-Through` header (verified:
   `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/capture/PlatformCapture.kt:6-40`,
   `hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/HopiOkHttpInterceptor.kt:46`,
   `.../AndroidCapture.kt:131,133`, `.../UrlConnectionRecorder.kt:229-234`). Without the plugin
   those bodies would show as gzip bytes. Fix: record the encoded bytes and decode lazily, off the
   request path, where a body is shown or served (UI, bridge `get`, cURL, HAR): gzip and deflate
   with the platform's own inflater; `br` and unknown encodings shown as "encoded (br), N bytes".
   iOS is believed unaffected: the Darwin engine is covered with `cover`, not
   `coverWithPassThrough` (verified, `hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/urlsession/UrlSessionCapture.kt:143`),
   which suggests URLSession hands over decoded bodies (to verify, E1.4).
2. **Engines not recorded:** CIO (all platforms), and on the JVM every engine. MockEngine is not
   recorded (tests only).
3. **Rules relative to Ktor's own plugins.** With the Ktor plugin, rules applied above Ktor's
   redirect handling, so Ktor followed a mocked 3xx. Rules now apply in the platform stack; whether
   Ktor's `HttpRedirect` still follows a mocked 3xx depends on the engine's own redirect settings
   (to verify, E1.5). Document the observed behaviour.
4. **Per-client settings.** Each `HttpClient` could carry its own redaction, log sink and bridge
   flag; now there is one configuration per process (redaction was already a union).
5. **Test seams:** the plugin's injected store (`HopiConfig.store`, verified
   `hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/HopiConfig.kt:54`) was how several JVM tests
   produced traffic; they need a new fixture (stage 3).

---

## 4. Evaluation to run first (what I would evaluate)

Each item says what to answer, where to look, and which decision or stage it feeds. Items marked
**[run]** need a build, a simulator or a device; the others only reading. Every answer goes into
section 9 with file paths and line numbers, and anything not verified is labelled as such.

### E1. Ktor plugin and the `ktor` source (feeds stage 3, O3, O4)

1. Complete inventory of what deleting `hopi-ktor` touches: every file under `hopi-ktor/` and
   `hopi-ktor-noop/`; every other file that imports `dev.koga.hopi.ktor`, calls `install(Hopi)`,
   uses `PlatformCapture`, `TransactionSource.KTOR`, `HopiSource.Ktor`, the string `"ktor"` as a
   source, or the `X-Hopi-Pass-Through` header. Start from appendix A and re-grep; the appendix
   greps were narrow.
2. For every test that uses the plugin to produce traffic (appendix A), what it asserts and what
   replaces the plugin: the same assertion through a platform engine (Android host tests with Ktor's
   OkHttp and Android engines and no plugin; iOS tests with Ktor's Darwin engine and no plugin), or
   a store fixture for the JVM (CLI, connect, desktop, UI). Check whether core already offers a way
   to write a transaction into a store that tests can use (the recorder entry points the platform
   captures call: `HopiStore.submit`, `submitStart`/`submitEnd` with `DeferredRecord`, verified in
   `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/store/HopiStore.kt:287-299,466`), and design
   the fixture (where it lives: a test-only source set shared through a non-published module, or a
   `@HopiInternalApi` helper; recommend one).
3. What the plugin records that platform capture does not, beyond 3.8: request bodies of Ktor's
   `OutgoingContent` kinds (write-channel, read-channel, byte array, multipart), WebSockets, SSE,
   timing phases, error mapping (`state`), redirects (one record per hop or one per call).
4. Content-Encoding: confirm the Android behaviour of 3.8.1 in the recorders' code and tests;
   confirm from the iOS capture code and tests whether URLSession hands decoded bodies over when the
   request names its own `Accept-Encoding`. Then specify the lazy decoding: where the encoded flag
   and the `Content-Encoding` value are kept, how the per-body cap applies (encoded bytes), where
   decoding runs (UI formatting is already off the main thread; the bridge `get`; cURL; HAR export),
   and the memory bound for a decoded giant body.
5. Rules on Ktor engines without the plugin: for each engine (OkHttp, Android, Darwin) and each
   action (mock including 3xx, fail, delay, edit), what happens. Read the capabilities code
   (`hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/Capabilities.kt`, which mentions the
   Ktor defer at line 71) and the existing tests; **[run]** the sample's rules self-test after
   stage 3 to confirm.
6. Wire compatibility of removing `"ktor"`: bridge protocol (does `hello` carry a protocol version;
   do decoders in `hopi-connect`, the CLI and the desktop viewer reject unknown source strings),
   recorded NDJSON fixtures in tests and under `benchmark/`, the CLI `--source` parser and its error
   text. Recommendation to confirm: remove the value everywhere, keep decoders lenient (an unknown
   source renders as its raw string), and make `--source ktor` a usage error that explains that
   Ktor's engines are recorded as `okhttp`, `urlconnection` or `urlsession`.
7. Benchmarks: which disappear with the module (`HopiOverheadBenchmark`, `KtorGiantBodyBenchmark`,
   the `jvmGiantBodyBenchmark` task wiring), which results under `benchmark/` stay as dated history,
   and whether giant-body capture was ever measured for OkHttp and URLSession (if not, add it to
   stage 3's verification).
8. `gradle/libs.versions.toml`: which Ktor entries remain needed (the sample's client and engines,
   tests that exercise Ktor engines) and which can go.

### E2. iOS capture and the iOS entry (feeds stage 7, O1)

1. Every symbol Swift calls today and how it reaches Swift: `sample/iosApp/iosApp/iOSApp.swift`,
   `ThirdPartySDK.swift`, `CaptureVerification.swift`, `iosAppCaptureTests/CaptureTests.swift`;
   the framework configuration (`binaries.framework`, `export(...)`) in
   `sample/shared/build.gradle.kts`; `sample/iosApp/project.yml` (the build phase that passes
   `-Phopi.enabled=true`, `OTHER_LDFLAGS` weak links).
2. What `HopiUrlSession.start` does, step by step (store, summary sink, bridge, rules protocol class
   registration, session factory hook, the Ktor Darwin cover), and its configuration
   (`HopiUrlSessionConfig`), so the start sequence subsumes it exactly, including the "invalid caps
   write a notice line and start nothing" behaviour and idempotence.
3. Kotlin/Native dead-code elimination: does a dependency's code that nothing reachable references
   end up in the framework binary? **[run]** Build the sample Debug framework with `hopi-perf` as a
   dependency but unreferenced and list its symbols (`nm` on the built framework binary).
4. Export leakage: with a runtime module `export()`ed, which of its declarations appear in the
   generated Objective-C header? **[run]** Inspect the generated header under the sample's
   `build/`.
5. Option D details: whether a configuration class from a non-exported dependency, used in the
   signature of an exported declaration, appears in the header with its members. **[run]** A small
   experiment in the sample. Decide the template's shape from the result.
6. Moving the iOS capture out of `hopi-core`: list the Kotlin files (`hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/urlsession/`),
   the native library sources and the cinterop and build tasks in `hopi-core/build.gradle.kts`
   (verified: tasks described at lines 75, 90, 100), and what else in core depends on them (the
   os_log shim stays in core). Recommend move or keep.
7. Today's iOS release check: what `VerifyIosCaptureSelection` asserts (summarised in 3.6.1), and
   the new assertion list: today's, plus the runtime packages, the start sequence and the UI runtime;
   `hopi-api` symbols allowed.
8. Release exclusion under every iOS integration mode (3.6.1): how each mode decides Debug or
   Release, and how the release guard detects a Release link: `embedAndSignAppleFrameworkForXcode`
   (`CONFIGURATION`), the CocoaPods plugin's sync task, `assembleXCFramework` (both build types in
   one invocation), a plain `linkReleaseFramework*`. Confirm that a framework's Debug and Release
   binaries share one compilation and its dependencies. **[run]** Link a Release framework with
   `-Phopi.enabled=true` outside the positive-control check: the guard must fail the build. Build the
   sample's Release app without the property: `verifyIosReleaseExcludesCapture` must pass with the
   new list.

### E3. Android capture and the Gradle plugin (feeds stages 7 and 9)

1. How the plugin selects runtime or noop today, with line numbers (verified starting points:
   `HopiPlugin.kt` KDoc 15-22, perf and `selectTwins` 54-71, perf values passed to
   `GenerateHopiConfig` 99-107, `selectTwins()` 116-131, constants `RUNTIME_NOOP`, `PERF`,
   `PERF_NOOP`, `TWINS` 163-166), and what each piece becomes.
2. The generated config path end to end: `GenerateHopiConfig` → `HopiGeneratedConfig.java` →
   `HopiConfigValues` → the provider. Is it generated for non-debuggable variants too? How will it
   carry the tool list?
3. The DSL after the change. Today (verified, `HopiExtension.kt`, `HopiPerfExtension.kt`):
   `enabled`, `okhttp`, `urlConnection`, `selectTwins`, `maxEntries`, `maxTotalBytes`,
   `maxBodyBytes`, `summaryLog`, `agentBridge`, `rules`, `redactHeaders`, `redactQueryParameters`,
   `unsafeDisableRedaction`, `excludeClasses`, `perf { enabled, autoStart, hangThresholdMs,
   hangHeartbeat }`. Proposal: drop `selectTwins`; group `okhttp`, `urlConnection`, `rules`,
   `excludeClasses` under `network { enabled = true; ... }`; add `ui { enabled = true; launcher;
   shake }`; keep store and bridge settings at the top. Confirm nothing else reads the removed ones.
4. Where the `FLAG_DEBUGGABLE` check goes in the provider, and the notice line it writes.
5. Every functional test and fixture assertion under `gradle-plugin/hopi-gradle-plugin/src/functionalTest`
   that mentions twins, noops, `hopi-ktor` or `hopi-ui`.
6. What `verifyReleaseUsesNoop` asserts today (class counts, forbidden packages) and the new list.
   **[run]** A release variant with a runtime added by hand must fail the build (D9, 3.6.1 item 4).
7. Who reads `HopiAndroid.isStarted` and `okHttpInstrumented` (sample, tests, docs) before it
   becomes internal.

### E4. Rules (feeds stage 5)

1. The full public API of `hopi-rules` (compare with appendix B, which lists the noop's).
2. How `HopiRules` reaches core today (direct calls into core singletons?), and the interface the
   rules runtime must implement for the API module to delegate to it.
3. Which builder state the runtime needs and how the API exposes it (`@HopiInternalApi` getters or
   one snapshot method per builder).
4. Calls made before registration: queue (bounded) or notice line.
5. Tests of rules that depend on `hopi-ktor` and where they move.

### E5. Perf (feeds stage 6)

1. The full public API of `hopi-perf` and who calls each declaration from app code (the sample's
   `PerfLab`, `PerfBench`) vs from Hopi itself.
2. The API/runtime split of `HopiPerfConfig` (which fields belong to the start block in the API,
   which to the global start configuration).
3. How perf registers today on Android (provider and plugin, `autoStart` bits) and on iOS
   (`HopiPerf.install`, the `-hopiPerf` launch argument), and how both map to the start sequence.
4. The "off is free" tests (no class loaded, no thread, no wake) and how they keep working.
5. Native library linkage when `hopi-perf` is linked only in Debug frameworks.

### E6. UI and the desktop viewer (feeds stage 8)

1. Every consumer call site of `HopiLauncher` and `HopiScreen` (the sample's `SampleApp.kt`,
   `MainViewController.kt`, `MainActivity.kt`, tests, docs).
2. Android overlay: how to obtain the Compose owners for `ComponentActivity` and for other
   activities; insets; activities that are translucent, dialogs, picture-in-picture; process death
   and the saved launcher position; how the inspector activity is declared and themed. **[run]**
   Verify on the emulator: the button on every activity, touches outside it reach the app, 0 frames
   idle (`dumpsys gfxinfo`).
3. iOS window: `UIWindow` level and scene attachment, touches outside the button, the keyboard,
   rotation, multiple scenes on iPad, the shake gesture. **[run]** Verify on the simulator.
4. `HopiInspector.open()`: what the UI runtime registers and how it presents the screen on each
   platform.
5. The desktop viewer's needs from `hopi-ui` after the split (`HopiPanes` in
   `dev.koga.hopi.ui.desktop`, theme accessors); confirm `hopi-ui`'s JVM target stays for it.
6. Tests to delete (the noop's `LauncherLayoutTest`, `HopiNoopUiTest`), move or adapt; the
   recomposition and idle-frame guarantees and how they stay tested.

### E7. Build infrastructure (feeds every stage)

1. Root `build.gradle.kts`: `NoopParityCheck` registrations (verified from line 120),
   `checkNoopParity` (line 132) and its `inline`/`const` rule, `check` wiring (line 139),
   `checkPublication` (line 296) and its expected coordinates; `gradle/publishing.gradle.kts`.
2. Module dependency graph including test-only edges (verified: `hopi-android/build.gradle.kts:67`
   androidHostTest → `hopi-ktor`; `hopi-urlsession/build.gradle.kts:56` iosTest → `hopi-ktor`;
   `hopi-cli/build.gradle.kts:41` testImplementation → `hopi-ktor`;
   `sample/shared/build.gradle.kts:94-95` real/noop Ktor and UI), to order the stages so each leaves
   `check` green.
3. Which `api/` dump directories are deleted and created; which modules keep ABI validation
   (recommendation O7).
4. Every script under `benchmark/` that names a noop module, `hopi-ktor`, `-Phopi.enabled` or a twin.
5. The README's base check command (section "Building") and its replacement after each stage.

### E8. Documentation (feeds every stage and stage 11)

Inventory, with heading and line range, every passage of `README.md`, `docs/ARCHITECTURE.md`,
`docs/ROADMAP.md` and `docs/design/*.md` that describes: the twins, the Ktor plugin
(`install(Hopi)`, `HopiConfig`, `HopiLogSink`, source `ktor`), `HopiUrlSession`, `HopiLauncher` and
`HopiScreen` as consumer API, `HopiAndroid`, `selectTwins`, the `-Phopi.enabled` selection,
`checkNoopParity` and `DefaultsParityTest`, the `inline`/`const` ban, the release checks, principle 1.
For each: delete, rewrite or touch up. Measurement and "As built" passages stay as dated history
with a note, not rewritten.

---

## 5. Open decisions and questions (with recommendations)

| # | Question | Recommendation | Settled by |
| --- | --- | --- | --- |
| Q2 | Module names | `hopi-api`, `hopi-core`, `hopi-network`, `hopi-perf`, `hopi-ui` | Owner |
| O1 | iOS registration | D (entry file in the consumer's module, Debug only; generated by the plugin later), fallback A | E2.3 to E2.5, owner |
| O3 | `"ktor"` on the wire | Remove everywhere; lenient decoders; `--source ktor` is a usage error with an explanation | E1.6 |
| O4 | Encoded bodies (3.8.1) | Keep encoded bytes, decode lazily where shown or served; gzip and deflate | E1.4 |
| O5 | JVM target | Keep for tests, the CLI and the desktop viewer; document that JVM apps are not recorded | E7.2 |
| O6 | Launcher defaults | Launcher on, shake on (iOS), configurable; `HopiInspector.open()` always available | Owner |
| O7 | ABI validation after the twins | Keep it on every published module (as today) for public API stability; no parity | E7.3 |
| O8 | Plugin on KMP modules (stage 10) | Yes, later: adds the runtimes and generates the iOS entry from the DSL; it could read Xcode's `CONFIGURATION` environment variable instead of `-Phopi.enabled` (to verify), which also closes 3.6.1 item 1 | Owner |
| O9 | Commit the 2026-10-27 positioning edits first | Done: committed together with this plan | Owner |

## 6. Stages

Every stage: update `docs/ARCHITECTURE.md` first for any contract it changes; update the ABI dumps
it changes (`./gradlew :<module>:updateKotlinAbi`, both sides of a pair while the pair exists);
run the verification list; fill "Done when". Remove a twin pair from `NoopParityCheck`, from the
plugin's `TWINS` and from `checkPublication` in the same stage that deletes the pair.

### Stage 0. Preflight

1. Settle Q2, O6 and O8 with the owner.
2. **[run]** Baseline: the README base check (section 7) before any change, with pass or fail and
   timings recorded in section 9. The migration needs a green start.

Done when: answers recorded; baseline recorded.

### Stage 1. Evaluation

Run section 4. Write the answers into section 9. Re-plan any stage whose assumption turned out
wrong, in this file, before executing it. Bring O1, O3, O4, O7 back to the owner with the evidence.

Done when: every E item has an answer or an explicit "unverified, because …"; the owner confirmed the
decisions that changed.

### Stage 2. Contract documents

1. `docs/ARCHITECTURE.md`: principle 1 rewritten (no twins: an optional API module that does nothing
   alone, runtimes linked by debug builds only, the build-time and run-time guards); a short
   "Migration in progress" note at the top pointing to this plan; the Modules table and the consumer
   API sections marked with what each stage will change (or rewritten to the target with a note).
2. `docs/ROADMAP.md` "Rule for every new tool": each tool is a runtime module, off by default and
   opt-in, with its API (if any) in `hopi-api`, a DSL entry in the plugin, and its data on every
   surface (in-app, log line, bridge and CLI, desktop).
3. `docs/design/api-runtime-split.md`: status line "superseded by api-runtime-split-plan.md".

Done when: the contract describes the target; no code changed.

### Stage 3. Remove the Ktor plugin

Order inside the stage matters; keep the build green between steps.

1. **Traffic fixture for JVM tests** (E1.2): create it; port `hopi-cli`'s `CliBridgeTest` and any
   other JVM test that installs the plugin (`hopi-connect`, `hopi-desktop`, `hopi-ui` if E1.1 finds
   them) to it. Remove `testImplementation(project(":hopi-ktor"))` from `hopi-cli/build.gradle.kts:41`.
2. **Lazy decoding of encoded bodies** (3.8.1, E1.4): implement and test it in core and in the places
   that show or serve bodies (UI detail formatting, bridge `get`, cURL, HAR export), with a giant-body
   test bounded like the existing ones.
3. **Platform tests without the plugin**: `hopi-android` androidHostTest (`hopitest/Scenarios.kt`,
   `AndroidCaptureTest.kt`) asserts that Ktor's OkHttp and Android engines, with and without
   `ContentEncoding`, are recorded exactly once with the right source and readable bodies; drop
   `implementation(project(":hopi-ktor"))` at `hopi-android/build.gradle.kts:67` (keep
   `ktor-client-android` and `ktor-client-encoding` there). `hopi-urlsession` iosTest
   (`KtorDarwinTest.kt`, `UrlSessionRulesTest.kt`, `TestSupport.kt`) does the same for the Darwin
   engine; drop the dependency at `hopi-urlsession/build.gradle.kts:56`.
4. **Remove the defer machinery**: delete `PlatformCapture` (core) and its uses
   (`HopiOkHttpInterceptor.kt:46-47`, `AndroidCapture.kt:131,133`, `UrlConnectionRecorder.kt:229-234`,
   `UrlSessionCapture.kt:143`, the comment at `Capabilities.kt:71`) and their tests.
5. **Remove the `ktor` source**: `TransactionSource.KTOR`
   (`hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/model/HttpTransaction.kt:103-104`),
   `HopiSource.Ktor` in `hopi-rules` and `hopi-rules-noop` (update both dumps), the rules model,
   capabilities and platform sources, the UI (`TransactionRows`, `DetailModel`, `Text`), the CLI
   (`Render.kt`, `--source` parsing, usage text), the desktop rule forms, `hopi-connect`, and every
   test in appendix A.3; decoders lenient (O3).
6. **Sample**: `SampleHttp.kt` without `install(Hopi)`; `KtorVerify.kt` and `KtorVerify.android.kt`
   become "Ktor's engines are recorded automatically, once, with readable bodies"; `RulesVerify.kt` and
   `MainActivity.kt` drop the Ktor-plugin target (their Ktor scenarios now run through the platform
   sources); `sample/shared/build.gradle.kts:94` no longer names `hopi-ktor`/`hopi-ktor-noop`.
7. **Delete the modules**: `hopi-ktor/`, `hopi-ktor-noop/`; their `include` lines in
   `settings.gradle.kts`; their pair in `NoopParityCheck`; `"hopi-ktor" to "hopi-ktor-noop"` in the
   plugin's `TWINS` (`HopiPlugin.kt:166`) and any functional-test assertion on it; their coordinates
   in `checkPublication`; unused Ktor entries in `gradle/libs.versions.toml` (E1.8).
8. **Docs**: README (install section without `install(Hopi)`, the artifact table, "For AI agents"
   Ktor lines, the rules capability table without the Ktor column, performance rows measured on the
   plugin kept as history with a note), ARCHITECTURE (the `hopi-ktor` consumer API, its store and
   redaction text, its parity text, the per-request budget section), `docs/design/rules.md` and
   `urlsession-capture.md`/`android-capture.md` passages about the Ktor defer.

Verify: base check without the Ktor tasks; `:hopi-android:testAndroidHostTest`; `check` (iOS
simulator tests); **[run]** the sample on the emulator (`rulesVerify all`) and the simulator
(`-verifyRules`), and the Android capture A/B (`benchmark/android-capture/`) to confirm no per-request
regression.

Done when: no file names `dev.koga.hopi.ktor`, `PlatformCapture`, `TransactionSource.KTOR`,
`HopiSource.Ktor` or `X-Hopi-Pass-Through` (grep); Ktor's three engines are recorded once with
readable bodies, `ContentEncoding` included; everything green.

### Stage 4. The API module

1. Create `hopi-api` (no dependency; the four targets; `explicitApi()`; ABI validation;
   `gradle/publishing.gradle.kts`; `description`). Add it to `settings.gradle.kts` and to
   `checkPublication`.
2. Move `HopiInternalApi` into it (same fully qualified name); `hopi-core` gets
   `api(project(":hopi-api"))`.
3. Add the registry (3.3) with no runtime kinds wired yet, and `HopiInspector` (does nothing yet).
4. JVM test: no `hopi-core` on its test runtime classpath, no core class loadable (port from the noop
   tests).

Done when: `hopi-api` publishes in `:checkPublication`; everything green.

### Stage 5. Rules into the API

1. Move the consumer rules API from `hopi-rules` into `hopi-api` (same FQNs), builders keeping their
   values (E4.3).
2. Core implements the rules runtime interface and registers it in the start path that exists at
   this point (today's Android provider in `hopi-android`, `HopiUrlSession.start` on iOS, the JVM
   test entry).
3. Move `hopi-rules`' tests to `hopi-core` (integration) and `hopi-api` (no-runtime behaviour).
4. Delete `hopi-rules/` and `hopi-rules-noop/`, their `include`, parity pair, `TWINS` entry,
   coordinates; the sample's `rulesModule` selection (`sample/shared/build.gradle.kts:24`) becomes a
   plain `hopi-api` dependency; Swift's `HopiRules.shared` comes from the exported `hopi-api`.
5. Docs: README "Rules" code section, ARCHITECTURE `hopi-rules` section, `docs/design/rules.md`.

Verify: base check; **[run]** the sample's rules self-tests on both platforms.

### Stage 6. Perf into API and runtime

1. Move `HopiPerf`'s consumer subset into `hopi-api` (E5.2); the runtime keeps the monitors, the
   native library, the `-hopiPerf` launch argument, and registers its perf runtime when its tool
   starts. Remove `HopiPerf.install(...)` (both variants).
2. Plugin: `perf { enabled = true }` adds `hopi-perf` to debuggable variants and nothing to the
   others (no `hopi-perf-noop`); `autoStart`, `hangThresholdMs`, `hangHeartbeat` unchanged.
3. Delete `hopi-perf-noop/` (its `DefaultsParityTest` and the `realPerfJvm` configuration with it),
   its parity pair, `PERF_NOOP` and the `TWINS` entry, its coordinates; the sample's `perfModule`
   selection (`sample/shared/build.gradle.kts:28`).
4. Docs: README perf section (no `install`; the iOS start configuration), ARCHITECTURE,
   `docs/design/perf-monitors.md` (API section; "As built" untouched).

Verify: base check incl. `:hopi-perf:testAndroidHostTest`; **[run]** `perfVerify all` on the
emulator with `benchmark/perf/verify.py`, `-verifyPerf` on the simulator, and the idle check
(`benchmark/perf/idle.py`) to confirm "off is free".

### Stage 7. Network runtime and the start sequence

1. Create `hopi-network`: Android side from `hopi-android` (hooks, interceptor, wrappers, recorders,
   `HopiInitProvider`, manifest), iOS side from `hopi-urlsession` plus, if E2.6 says so, the
   URLSession capture and its native library from `hopi-core`. `HopiAndroid` becomes internal.
2. The start sequence and the tool interface in `hopi-core` (3.4); the network tool; the Android
   provider calls the start sequence with the generated tool list and checks `FLAG_DEBUGGABLE` first.
3. iOS entry per O1: in the sample, the Debug-only source directory with the `Hopi` entry; Swift
   files (`iOSApp.swift`, `ThirdPartySDK.swift`, `CaptureVerification.swift`, `CaptureTests.swift`)
   call `Hopi.shared.start` inside `#if DEBUG`; `project.yml` unchanged unless E2.1 says otherwise.
4. Plugin: add `hopi-network` (instead of `hopi-android`) to debuggable variants, nothing to the
   others (`RUNTIME_NOOP` gone); the build-time guard (D9); DSL `network { }` (E3.3); generated
   config with the tool list.
5. Delete `hopi-android/`, `hopi-android-noop/`, `hopi-urlsession/`, `hopi-urlsession-noop/`
   (their `DefaultsParityTest`, `realUrlSessionJvm` with them), their pairs, entries, coordinates;
   the sample's `urlSessionModule` selection (`sample/shared/build.gradle.kts:21`).
6. Release checks: `verifyReleaseUsesNoop` → `verifyReleaseHasNoRuntime` (3.6);
   `verifyIosReleaseExcludesCapture` adapted (E2.7); the iOS release guard (3.6, E2.8), with a test
   that a Release link with the runtime on its classpath fails.
7. Docs: README Android and iOS install sections (3.2), ARCHITECTURE (URLSession capture and
   Android capture consumer API, selection section), `docs/design/urlsession-capture.md` and
   `android-capture.md` (consumer API passages).

Verify: base check (renamed tasks); `:hopi-network:testAndroidHostTest`; `check`; the release
checks; **[run]** the sample on both platforms (third-party SDK capture: `-thirdPartySDK`; the Swift
capture tests: `sample/iosApp/scripts/capture-swift-test.sh`), and both capture A/Bs.

### Stage 8. UI runtime and the automatic launcher

1. Implement 3.5 on Android and iOS; register the inspector runtime so `HopiInspector.open()` works.
2. Remove `HopiLauncher` and `HopiScreen` from the public API (keep the composables internal; the
   launcher's layout policy no longer needs a twin); the desktop viewer keeps `HopiPanes`.
3. Plugin: `ui { }` adds `hopi-ui` to debuggable variants (on by default). iOS: the entry template
   names the UI tool.
4. Delete `hopi-ui-noop/` (its `LauncherLayoutTest`, `HopiNoopUiTest`), the pair, entries,
   coordinates; the sample drops its `HopiLauncher { }` wrapping (`SampleApp.kt`,
   `MainViewController.kt`, `MainActivity.kt` per E6.1) and its `hopi-ui` selection
   (`sample/shared/build.gradle.kts:95`).
5. Docs: README (the launcher paragraph, the HUD), ARCHITECTURE `hopi-ui` section.

Verify: base check; `:hopi-ui:jvmTest` (recomposition probe); `:hopi-desktop:test`; **[run]** the
emulator and simulator checks of E6.2 and E6.3, idle frames and CPU as in the README's performance
table.

### Stage 9. Remove the remaining twin machinery

1. Root `build.gradle.kts`: delete `NoopParityCheck`, `checkNoopParity` and its `inline`/`const`
   rule, and the `check` dependency on it (verified lines 120-139).
2. Plugin: delete `selectTwins` (`HopiExtension.kt:27`), the substitution code (`HopiPlugin.kt:116-131`),
   `TWINS`; update the plugin's `description` (`gradle-plugin/hopi-gradle-plugin/build.gradle.kts:30`,
   it still says OkHttp and `HttpURLConnection` only); functional tests updated.
3. Grep for `noop`, `twin`, `Noop`, `parity` across the repository; every remaining hit is either
   history in a dated benchmark result or removed.

Done when: the grep is clean; base check green.

### Stage 10 (later, after O8). Plugin on KMP modules

Apply `dev.koga.hopi` to a KMP shared module: it adds the chosen runtimes to the iOS Debug build
and generates the `Hopi` entry (O1, D) from the DSL, so the shared module needs no hand-written file
and no `if (hopiEnabled)` blocks. Evaluate reading Xcode's `CONFIGURATION` environment variable
during `embedAndSignAppleFrameworkForXcode` instead of `-Phopi.enabled` (to verify).

### Stage 11. Documentation pass

README rewritten around the tools and the two install paths (3.2), with a short "What reaches a
release build" passage: Hopi is removed by not being linked, not by `#if DEBUG` (3.6.1); what
`hopi-api` leaves when used; Debug builds shipped to testers contain Hopi. ARCHITECTURE's remaining
sections; ROADMAP; design docs' API passages; performance table rows that measured the plugin or the
noops kept with a dated note. Remove the "Migration in progress" note.

### Stage 12. Final verification and report

The whole matrix of section 7, then a report to the owner: what changed, what was measured before
and after, what was not verified and why.

## 7. Verification matrix

Base check today (verified, `README.md` "Building"):

```bash
./gradlew :hopi-core:jvmTest :hopi-ktor:jvmTest :hopi-ui:jvmTest :hopi-ktor-noop:jvmTest :hopi-ui-noop:jvmTest \
  :hopi-cli:test :hopi-connect:test :hopi-desktop:test :hopi-urlsession:jvmTest :hopi-urlsession-noop:jvmTest \
  :hopi-perf:jvmTest :hopi-perf-noop:jvmTest :hopi-perf:testAndroidHostTest :checkNoopParity check \
  :sample:androidApp:assembleDebug :sample:androidApp:verifyReleaseUsesNoop :hopi-android:testAndroidHostTest \
  :gradle-plugin:hopi-gradle-plugin:functionalTest :checkPublication
```

Target after stage 9 (adjust names to Q2):

```bash
./gradlew :hopi-api:jvmTest :hopi-core:jvmTest :hopi-ui:jvmTest :hopi-perf:jvmTest :hopi-perf:testAndroidHostTest \
  :hopi-network:testAndroidHostTest :hopi-cli:test :hopi-connect:test :hopi-desktop:test check \
  :sample:androidApp:assembleDebug :sample:androidApp:verifyReleaseHasNoRuntime \
  :sample:shared:verifyIosReleaseExcludesCapture :gradle-plugin:hopi-gradle-plugin:functionalTest :checkPublication
```

Device and simulator checks (README "Sample app"; all **[run]**): `:sample:androidApp:installDebug`,
`adb reverse tcp:8765 tcp:8765`, `--es rulesVerify all`, `--es rulesTargets all`, `--es perfVerify all`
with `benchmark/perf/verify.py`; the iOS Debug build with `xcodebuild`, `-thirdPartySDK`,
`-verifyRules`, `-verifyPerf`, `sample/iosApp/scripts/capture-swift-test.sh`; the dev server
`sample/devserver/devserver.py`. A/B harnesses: `benchmark/android-capture/`, `benchmark/urlsession/`,
`benchmark/perf/`.

## 8. Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Hopi linked into an iOS Release framework because a runtime was on that link's classpath (`hopi.enabled` left on, one invocation linking both build types, `assembleXCFramework`): DebugSwift #202's failure mode (3.6.1) | high | The release guard (3.6, E2.8); `verifyIosReleaseExcludesCapture` in `check`; stage 10 derives the choice from `CONFIGURATION` |
| Encoded bodies on Android after the plugin is gone (3.8.1) | high | Stage 3 step 2 lands before the module is deleted; tests with `ContentEncoding` on both Ktor engines |
| iOS registration option D does not export the configuration type usably (E2.5) | medium | Template declares its own configuration class; fallback A |
| The Android overlay takes the app's touches or breaks insets | medium | Button-sized view only; the inspector in its own activity; emulator checks E6.2 |
| A runtime reaches a release build through a manual dependency | high | Plugin build-time guard; `FLAG_DEBUGGABLE` check; `verifyReleaseHasNoRuntime` |
| A forgotten iOS `start` call: Debug records nothing | low | The README's first iOS step; the sample; a notice when the API is used with no runtime registered |
| Coordinates change for consumers | low | 0.1.0, Maven Local only; the README states the new coordinates |
| Mocked 3xx semantics change for Ktor apps | low | E1.5 measured and documented |
| Wider scope than one pass can hold | medium | Stages are independent commits; each leaves the build green; the owner can stop after any stage |

## 9. Evaluation results

To be filled in stage 1, one subsection per E item, with paths and line numbers, and "unverified,
because …" where needed.

## 10. Progress (kept by the executing agent)

Started 2026-10-27 on the branch `feat/runtime-split` (this plan's commit `c7d9cf2` with
`docs/emulator-figures-2026-10-27` merged). The owner asked to proceed without stopping, so where
the owner has not answered, the recommendations of section 5 are taken as provisional decisions;
the owner can override any of them, and a stage built on one names it.

Owner decisions of 2026-10-28, which replace parts of sections 2 and 3:

| # | Decision |
| --- | --- |
| D2' | **The Ktor plugin stays**, optional. Automatic capture remains the mobile path: an Android or iOS app installs nothing per client. `hopi-ktor` becomes a thin plugin that links into every build and holds no recording logic; it installs its hooks only when the network runtime registered a Ktor recorder (debug builds), so release builds pay nothing. On Android it keeps deferring to platform capture (one record per request), keeps the requests Ktor decodes itself and the `HttpCache` guard; on the JVM (Compose Desktop) it is the recorder; on iOS the Darwin engine stays with URLSession capture. The `ktor` source stays. `hopi-ktor-noop` goes with the other twins. |
| D7' | **No in-app UI.** The desktop viewer and the CLI are the surfaces: no launcher, no `HopiScreen`, no HUD, no `HopiInspector`. `hopi-ui` stays only as the desktop viewer's screens (JVM). |
| D10 | **Physical devices without in-app UI.** Android already connects over `adb` (USB or wireless), to verify on hardware. iPhone over USB: `hopi-connect` reaches the app's loopback bridge through `usbmuxd` and reads its `bridge.json` with `devicectl`; a stage of its own. |

Provisional, taken by the executing agent:

| # | Provisional decision |
| --- | --- |
| Q2 | `hopi-api`, `hopi-core`, `hopi-network`, `hopi-perf`, plus the thin `hopi-ktor`; `hopi-ui` unpublished (D7') |
| O1 | A: one exported iOS entry module (`Hopi.start { }`) over the network and perf runtimes, its configuration a type of its own; D (a Debug-only file in the consumer's module) waits for the plugin to generate it (O8) |
| O3, O4, O5 | settled by D2': `"ktor"` stays, the plugin keeps decoding what Ktor decodes, JVM apps record through it |
| O6 | void (D7') |
| O7 | ABI validation on every published module, no parity check |
| O8 | yes, later: stage 10 is not part of this pass |
| E4.4 | no pending queue in `hopi-api`: a call before a runtime registered returns `-1`, `false` or an empty list; the runtime writes one notice line with the count when it registers |
| D9 | strict as written: no opt-in for non-debuggable variants or iOS Release configurations |

Stage 0 baseline (2026-10-27 and 28, `e8583b8`): the base check of section 7 is green. The first
full run took 43.5 min and stopped only at `:sample:shared:linkReleaseFrameworkIosSimulatorArm64`
with `ENOSPC` (499 MB left on the disk; with about 2 GB free it links). The rerun then failed in
the plugin's functional tests: their teardown sent `SIGTERM` to every pid named by the ~90 daemon
logs that earlier runs left in the TestKit directory, one of them by then `osanalyticshelper`, and
timed out waiting for it. The teardown now stops only daemons whose log appeared during the test and
whose arguments name Gradle's daemon entry point; 3 of 3 pass in 30 s, no daemon left behind.

| Stage | Status | Commit |
| --- | --- | --- |
| 0. Preflight | done: owner decisions recorded, baseline green | (this commit) |
| 1. Evaluation | in progress | |
| 2. Contract documents | | |
| 3. Remove the Ktor plugin | | |
| 4. The API module | | |
| 5. Rules into the API | | |
| 6. Perf into API and runtime | | |
| 7. Network runtime and the start sequence | | |
| 8. UI runtime and the automatic launcher | | |
| 9. Remove the remaining twin machinery | | |
| 10. Plugin on KMP modules | deferred (O8) | |
| 11. Documentation pass | | |
| 12. Final verification and report | | |

Carried in from before the plan: `DeviceViewerUiTest` on Android is partly stale (ROADMAP,
"Desktop viewer"); its 5 MB check needs the sample's body cap to reach OkHttp capture, which stage 3
has to solve anyway once `SampleHttp` loses `install(Hopi)`.

---

## Appendix A. Inventory already found (2026-10-27, narrow greps; re-grep in E1.1)

A.1 Gradle edges to the modules that disappear (verified):

- `hopi-android/build.gradle.kts:67` androidHostTest → `project(":hopi-ktor")` (with
  `ktor-client-android`, `ktor-client-encoding`)
- `hopi-urlsession/build.gradle.kts:56` iosTest → `project(":hopi-ktor")` (with `ktor-client-darwin`)
- `hopi-cli/build.gradle.kts:41` testImplementation → `project(":hopi-ktor")`
- `sample/shared/build.gradle.kts:21` urlsession twin, `:24` rules twin, `:28` perf twin, `:94` Ktor
  twin, `:95` UI twin, `:116` `iosCaptureProbe` source dir, `:120` core when enabled, `:198`
  `verifyIosReleaseExcludesCapture`
- `hopi-ktor-noop/build.gradle.kts:58`, `hopi-perf-noop/build.gradle.kts:53`,
  `hopi-urlsession-noop/build.gradle.kts:53`: the `realXJvm` configurations of the `DefaultsParityTest`s
- Root `build.gradle.kts`: `NoopParityCheck` at 120, `checkNoopParity` at 132, `check` at 139,
  `checkPublication` at 296
- `sample/androidApp/build.gradle.kts:301` `verifyReleaseUsesNoop`

A.2 Files using the Ktor plugin outside `hopi-ktor` (grep `install\(Hopi\)|dev\.koga\.hopi\.ktor`):
`hopi-android/src/androidHostTest/kotlin/hopitest/Scenarios.kt`,
`hopi-urlsession/src/iosTest/kotlin/dev/koga/hopi/urlsession/KtorDarwinTest.kt`,
`hopi-urlsession/src/iosTest/kotlin/dev/koga/hopi/urlsession/UrlSessionRulesTest.kt`,
`hopi-cli/src/test/kotlin/dev/koga/hopi/cli/CliBridgeTest.kt`,
`sample/shared/src/commonMain/kotlin/dev/koga/hopi/sample/SampleHttp.kt`.

A.3 Files naming the `ktor` source (grep `"ktor"|HopiSource\.Ktor|Source\.KTOR|SOURCE_KTOR`):
`hopi-core`: `model/HttpTransaction.kt`, `rules/Capabilities.kt`, `rules/RuleModel.kt`,
`rules/PlatformSources.{android,ios,jvm}.kt`, tests `RuleEngineTest.kt`, `EncodedBodyMockTest.kt`,
`RuleSpecTest.kt`, `CapabilitiesTest.kt`; `hopi-ui`: `detail/DetailModel.kt`,
`list/TransactionRows.kt`, `format/Text.kt`, tests `DetailFormatterTest.kt`, `TransactionRowsTest.kt`,
`RuleActionsTest.kt`, `RuleRowsTest.kt`; `hopi-connect` tests `RemoteSessionRulesTest.kt`,
`RuleClientTest.kt`; `hopi-cli/src/main/kotlin/dev/koga/hopi/cli/Render.kt`; `hopi-desktop` tests
`RulesPanelUiTest.kt`, `DeviceRulesPanelUiTest.kt`, `RuleFormTest.kt`; `hopi-android` test
`AndroidCaptureTest.kt`; `hopi-rules` `HopiRuleBuilders.kt` and `HopiRulesTest.kt`; `hopi-ktor`
(deleted); the sample's `KtorVerify.kt`, `KtorVerify.android.kt`, `SampleHttp.kt`, `MainActivity.kt`.

A.4 The defer and pass-through machinery (verified): `PlatformCapture.kt` (core, commonMain);
`HopiOkHttpInterceptor.kt:46-47`, `AndroidCapture.kt:131,133`, `UrlConnectionRecorder.kt:229-234`
(`hopi-android`); `UrlSessionCapture.kt:143` (core, iosMain); `Exchange.kt:106`, `Hopi.kt:88-89`
(`hopi-ktor`).

A.5 `HopiUrlSession` mentions: `README.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, every
`docs/design/*.md`, `gradle-plugin/hopi-gradle-plugin/src/main/kotlin/dev/koga/hopi/gradle/HopiExtension.kt`,
`sample/iosApp/project.yml`, `sample/iosApp/iosApp/{iOSApp,ThirdPartySDK}.swift`,
`sample/iosApp/iosAppCaptureTests/CaptureTests.swift`, `sample/shared/build.gradle.kts`, and the
`hopi-urlsession`/`hopi-urlsession-noop` sources and tests.

A.6 Swift files of the sample: `sample/iosApp/iosApp/iOSApp.swift`, `ThirdPartySDK.swift`,
`CaptureVerification.swift`, `sample/iosApp/iosAppCaptureTests/CaptureTests.swift`.

## Appendix B. Today's consumer API (from the noop twins, verified)

- `hopi-ktor`: `val Hopi: ClientPlugin<HopiConfig>`; `HopiConfig` (`enabled`, `maxEntries`,
  `maxTotalBytes`, `maxBodyBytes`, `logSink`, `agentBridge`, `rules`, `redactHeaders`,
  `redactQueryParameters`, `unsafeDisableRedaction`); `enum HopiLogSink { Off, Summary }`. All deleted.
- `hopi-urlsession`: `object HopiUrlSession { start(); start(configure); isStarted }`;
  `HopiUrlSessionConfig` (`enabled`, `maxEntries`, `maxTotalBytes`, `maxBodyBytes`, `summaryLog`,
  `agentBridge`, `rules`, `redactHeaders`, `redactQueryParameters`, `unsafeDisableRedaction`).
  Replaced by the start sequence's configuration (3.4).
- `hopi-android`: `object HopiAndroid { isStarted; okHttpInstrumented }`. Internal (D8).
- `hopi-ui`: `@Composable HopiScreen(onClose, modifier)`, `@Composable HopiLauncher(content)`.
  Leave the public API (D7); `HopiInspector.open()` instead.
- `hopi-rules`: `object HopiRules { mock, fail, delay, edit, list, setEnabled, remove, clear }`;
  `HopiRuleOptions` (`label`, `priority`, `times`, `enabled`, `sources`, `query`, `hasQuery`,
  `noQuery`, `matchHeader`, `hasHeader`, `noHeader`, `delay`); `HopiMock` (`status`, `header`,
  `body(String)`, `body(ByteArray)`, `from`); `HopiDelay` (`beforeRequest`, `beforeResponse`);
  `HopiEdit` (`request { }`, `response { }`); `HopiRequestEdit` (`method`, `host`, `port`, `path`,
  `setQuery`, `removeQuery`, `setHeader`, `addHeader`, `removeHeader`, `body` ×2);
  `HopiResponseEdit` (`status`, `setHeader`, `addHeader`, `removeHeader`, `body` ×2); `HopiRuleInfo`
  (`id`, `label`, `enabled`, `priority`, `times`, `hits`, `exhausted`, `match`, `action`,
  `unsupported`); `enum HopiFailure { Timeout, Reset, UnknownHost, Cancel }`;
  `enum HopiSource { Ktor, OkHttp, UrlConnection, UrlSession }`. Moves to `hopi-api` without
  `HopiSource.Ktor`.
- `hopi-perf`: `object HopiPerf { install(block); start(block); stop(vararg); running; screen(name) }`,
  Android `HopiPerf.install(Application, block)`; `enum HopiMonitor { Frames, Hangs, Cpu, Memory }`;
  `HopiPerfConfig` (`frames`, `hangs`, `hangThresholdMs` 250, `hangHeartbeat`, `cpu`, `memory`,
  `sampleIntervalMs` 1000, `remoteControl` true, `agentBridge` false, `summaryLog` true). Split
  between `hopi-api` and the runtime (E5.2).
