# Plan: automatic capture only, no noop twins, per-tool runtimes (handoff)

Status: plan, 2026-10-27; re-planned 2026-10-28 after the evaluation (stage 1). Stages 0 and 1 are
done; nothing of the target is built yet. Written for the agent that will execute it. Section 10
holds the owner's later decisions and the executing agent's provisional ones, and section 3.9 the
target they produce; both override the older text of sections 2 and 3 where they differ.
It supersedes `docs/design/api-runtime-split.md` wherever the two differ (that first proposal kept a
Ktor plugin and a public `HopiUrlSession`; this plan first removed the plugin, then D2' kept a thin
one).

How to use this document:

1. Section 1 is the state of the repository when the plan was written.
2. Section 4 (evaluation) came first. Its answers are in `docs/design/runtime-split-evaluation/`,
   summarized in section 9; the decisions they changed are in section 10.
3. The stages of section 6 run in order. Each stage ends with the base check of section 7 green,
   its "Done when" list satisfied, one commit and a push (section 6, rule 1). Section 10's table
   records where execution stands.

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
  result. (It ran to completion in stage 1, 2026-10-27 and 28; section 9.)

## 2. Decisions

The table is the plan as first written. D2 and D7 are superseded by D2' and D7', and D6's Swift guard
by P3 (section 10); section 3.9 has the resulting target.

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

Sections 3.1 to 3.8 are the target as first written and keep its reasoning; the stage numbers they
cite are the old ones. Section 3.9 is the target after stage 1 and wins wherever the two differ: the
thin Ktor plugin stays (D2'), there is no in-app UI (D7'), and the iOS entry is a module of its own
(O1 = A).

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
- (Superseded by D7': no `HopiInspector`.) **Inspector**: `dev.koga.hopi.HopiInspector` with `open()` (shows the in-app screen if the UI
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
  qualified name; `hopi-core` gets `api(project(":hopi-api"))`. (Superseded by P2: the name becomes
  `dev.koga.hopi.api.HopiInternalApi`, because both release checks forbid every class under
  `dev.koga.hopi.core`, cross-check problem 4.)

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
  - (Superseded: O1 is A, section 10, for consumer simplicity; D waits for the plugin to generate
    the file, O8. E2 found D feasible.)
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

Superseded by D7': there is no in-app UI, so nothing below is built. Kept as the record of the design
that was dropped; E6 evaluated it.

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
  what the app calls). Under D2' the thin `hopi-ktor` is allowed too (P1). The sample's release
  build is not minified, so its check sees every class of the allowed modules
  (`sample/androidApp/build.gradle.kts:33-39`). `sample/shared`'s `verifyIosReleaseExcludesCapture` (verified,
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

Superseded by D2': the plugin stays, so none of these losses happens on Android or the JVM. What
still needs work is in 3.9: the plugin cannot keep its injected store (item 5), the capability text
for a mocked 3xx is already wrong for Ktor clients (item 3, E1), and on iOS the thin plugin is inert,
so item 1 must be checked there (E1 R3). E1 also found losses this list missed: the `HttpCache`
no-store guard exists only in the plugin, uninstrumented OkHttp would go unrecorded, and the JVM's
platform sources would be empty.

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

### 3.9 Target after stage 1 (wins over 3.1 to 3.8)

The owner decisions D2', D7' and D10 and the provisional decisions P1 to P5 (section 10) give this
target. Stage numbers are those of the new section 6.

**Modules.** Thirteen published library modules become six.

| Module | Holds | Targets | Linked by |
| --- | --- | --- | --- |
| `hopi-api` | rules API (`dev.koga.hopi.rules.*`, same names as today), the perf API subset (`dev.koga.hopi.perf.*`), the registry and `@HopiInternalApi` (`dev.koga.hopi.api.*`, P2); no dependency | android, jvm, iosArm64, iosSimulatorArm64 | the app, every build, optional |
| `hopi-ktor` | the thin Ktor client plugin `Hopi`, `HopiConfig`, the hooks interface the runtime implements (P1); depends on `hopi-api` and `ktor-client-core` only | the same four | apps whose code installs it, every build |
| `hopi-core` | store, bridge, redaction, log sink, rule engine, the start sequence and `HopiTool`, the Android provider and `HopiConfigValues` (packages `dev.koga.hopi.android.*`, P4), current-activity tracking (P5) | the same four | debug only |
| `hopi-network` | Android OkHttp and `HttpURLConnection` capture; iOS URLSession capture and its native library; the Ktor adapter (android and jvm, P1) | the same four | debug only, on by default |
| `hopi-perf` | monitors, native library, `-hopiPerf` | android, jvm, iOS | debug only, opt-in |
| `hopi-start` | `Hopi.start { }` with its own configuration class (O1 = A, P3) | iosArm64, iosSimulatorArm64; jvm from stage 10 | iOS Debug frameworks (exported); JVM apps |
| `hopi-ui` | the desktop viewer's screens | jvm | `hopi-desktop`; not published (D7') |

Gone: the six noop twins, `hopi-rules`, `hopi-android`, `hopi-urlsession`, the in-app launcher,
`HopiScreen`, the HUD and `HopiInspector`.

**What the consumer writes.**

- Android application module: the plugin and an optional `hopi { }` block as in 3.2, without
  `ui { }`; no Hopi line in Kotlin. Shared code that installs the Ktor plugin keeps doing so; in a
  release build the plugin finds no runtime and installs nothing.
- KMP shared module, until the plugin supports KMP modules (O8). Option A links every tool into the
  Debug framework; each stays off until started.

  ```kotlin
  val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false  // Xcode Debug passes it
  kotlin {
      listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
          target.binaries.framework {
              export("dev.koga.hopi:hopi-api:$v")                       // HopiRules and HopiPerf from Swift
              if (hopiEnabled) export("dev.koga.hopi:hopi-start:$v")    // Hopi.start
          }
      }
      sourceSets {
          commonMain.dependencies { api("dev.koga.hopi:hopi-api:$v") }
          if (hopiEnabled) iosMain.dependencies { api("dev.koga.hopi:hopi-start:$v") }
      }
  }
  ```

- Swift, with `HOPI` added to the Debug configuration's compilation conditions only when
  `HOPI_ENABLED` is on (P3; `#if DEBUG` would break the Debug build made with `HOPI_ENABLED=false`
  for the A/B arm):

  ```swift
  init() {
  #if HOPI
      Hopi.shared.start { $0.agentBridge = true }   // first thing: requests resumed before it are not recorded
  #endif
  }
  ```

- JVM desktop app (stage 10): `hopi-start` and `hopi-ktor`; `Hopi.start { }` in `main` before any
  `HttpClient` is built.

**Start and registration.** One start sequence in `hopi-core`, `start(config, tools)`: it registers the
rules runtime first (before the caps check and whatever `rules` says, so `HopiRules` works as today,
E4 2.3), presets the store's caps and redaction, starts the summary log sink and the bridge, then
starts each tool. Android: the provider in `hopi-core` (P4) checks `FLAG_DEBUGGABLE` (D9), then
calls it with the tool list the plugin generates (stage 4). iOS: `Hopi.start` in `hopi-start` names
the network and perf tools (stage 6). JVM: tests call the start sequence directly; desktop apps call
`Hopi.start` (stage 10). A call made before its runtime registered returns `-1`, `false` or an empty
list, and the runtime writes one notice line with the count when it registers (E4.4, provisional).

**The thin Ktor plugin (P1).** `hopi-api` cannot name Ktor types, so its registry keeps an opaque Ktor
slot; `hopi-ktor` declares the hooks interface (Ktor types) and casts the slot's value to it. At
install the plugin reads the slot once: hooks present, it hands them its builder; none, it installs
nothing. The recording code that is in `hopi-ktor` today (`Exchange`, `RequestCapture`,
`BodyCapture`, `TeedBody`, `Rules`) moves into `hopi-network` (android and jvm, compiled against Ktor
and `hopi-ktor` as compileOnly), whose network tool registers the hooks only when the plugin class is
loadable. On Android the adapter keeps today's behaviour: it defers to platform capture (one record
per request), records itself the requests Ktor decodes (`X-Hopi-Pass-Through`) and keeps the
`HttpCache` guard. On the JVM it is the recorder. On iOS there is no adapter: the plugin is inert
and URLSession capture records the Darwin engine, as today's `cover` already does. A client built
before the start sequence ran stays unrecorded.

**Release safety.** Unchanged in principle (3.6). The allowed set in release builds is `hopi-api`
and `hopi-ktor`; nothing of `dev.koga.hopi.core`, of the Android capture packages or of a runtime,
with no exemption (P2). The checks are renamed and the iOS release guard and the plugin's
build-time guard are added in stage 9.

**Still open in the target.** Under D2' the plugin's injected store (`HopiConfig.store`) cannot stay,
because `hopi-ktor` no longer sees `hopi-core`; the tests that set it (`hopi-ktor`'s own and, by
reflection, the Android host scenarios) move to the adapter's test seam in stage 10, and
`CliBridgeTest` calls the JVM `Hopi.start`. The capability text says a
mocked 3xx is not followed, which is wrong for Ktor clients today (E1.5): fixed in the separate commit
before stage 2. Whether Ktor's `HttpCache` stores a platform mock (E1 R5, E4.5) and whether URLSession
hands over decoded bodies for a request that names its own `Accept-Encoding` (E1 R3) are run first in
stage 10.

---

## 4. Evaluation to run first (what I would evaluate)

Each item says what to answer, where to look, and which decision or stage it feeds. Items marked
**[run]** need a build, a simulator or a device; the others only reading. Every answer goes into
section 9 with file paths and line numbers, and anything not verified is labelled as such.

Answered in stage 1, read-only: `docs/design/runtime-split-evaluation/E1.md` to `E8.md`, with a
cross-check (section 9). The stage numbers in the item titles below are the old ones; the
**[run]** items now sit at the start of the new stage that needs them (section 6).

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

Where these stand after stage 1 (section 10): Q2 is six modules plus the unpublished `hopi-ui`; O1
is A; O3, O4 and O5 are settled by D2' (`"ktor"` stays, the plugin keeps decoding what Ktor
decodes, JVM apps record through it); O6 is void (D7'); O7 is narrowed: E7 found that `hopi-core`
has no ABI validation today, so "as today" was wrong; validation covers the modules a consumer
compiles against (`hopi-api`, `hopi-ktor`, `hopi-start`), and the runtimes keep what they have,
since they are linked only as one set of one version; O8 stays later. The table's stage numbers are
the old ones.

## 6. Stages

Re-planned in stage 1 (2026-10-28) from the evaluation (`docs/design/runtime-split-evaluation/`),
the owner decisions D2', D7' and D10 and the provisional decisions P1 to P5 (section 10). Paths
are repository-relative. Line numbers are at `e1ef40a`; they move as soon as a stage edits a file,
so the cited code is the key and the number is a pointer. Short names:

- **PLG**: `gradle-plugin/hopi-gradle-plugin/src/main/kotlin/dev/koga/hopi/gradle/HopiPlugin.kt`.
- **FT**: `gradle-plugin/hopi-gradle-plugin/src/functionalTest/kotlin/dev/koga/hopi/gradle/HopiPluginFunctionalTest.kt`.
  `e1ef40a` moved its lines after about :36 by +10 and its companion object by +16; the numbers
  below are HEAD's (verified).
- **E1 … E8, cross-check**: the files of `docs/design/runtime-split-evaluation/`. E8's worklist
  uses the old stage numbers; its "Stage 7" is split here into stages 4, 6 and 9.

Rules for every stage:

1. **One commit per stage, then push.** A stage ends with one commit (stage 2: a `fix:` commit,
   then a `docs:` commit) with the base check of section 7 green and its "Done when" satisfied,
   its row in section 10 updated, then `git push` of `feat/runtime-split`. The next stage starts
   after the push.
2. **Contract first.** Update `docs/ARCHITECTURE.md` first for any contract the stage changes, in
   the same commit. The docs step of each stage names E8's rows ("Per-stage worklist").
3. **[run] experiments at the start of the stage that needs them**, before its code is written.
   Commands and results go into section 9. A result that refutes an assumption re-plans the stage
   in this file first. Checks that need the stage's own code are under "Verify". The E2 probe
   (`E2.md`, "Experiment setup") lives in the scratchpad, never in the repository.
4. **ABI dumps.** `./gradlew :<module>:updateKotlinAbi` for every module whose public API changes,
   committed with the change; while a twin pair exists, both sides change together
   (`:checkNoopParity`). `checkKotlinAbi` runs in each module's `check` (KGP 2.4.20, bytecode only;
   E7 X1 confirms it in stage 3, and stage 3 wires it where it does not).
5. **Same-commit edits when a module or task is deleted or renamed.** Each stage's "Same commit"
   list is complete for that stage. The places to check:
   - `settings.gradle.kts:35-53`, the `include` lines (verified);
   - root `build.gradle.kts`: `noopTwins` (:109-116), `publishedModules` (:244-258),
     `publishedCinterops` (:259), the comment at :239-242 (verified). A pair or key that names a
     missing project fails `check` while Gradle builds the task graph (cross-check 36);
   - PLG `RUNTIME`, `RUNTIME_NOOP`, `PERF`, `PERF_NOOP` (:162-165), `TWINS` (:166), the KDoc
     :12-25 (verified). `runtime()` falls back to `dev.koga.hopi:<name>:0.1.0` when the project is
     missing (:134-135, verified). This build then fails to resolve it (no `mavenLocal()`,
     `settings.gradle.kts:19-30`, verified), but a consumer with `mavenLocal()` silently gets the
     stale artifact: `~/.m2/repository/dev/koga/hopi` holds every module of today at 0.1.0, the six
     `*-noop` included (verified). So each constant changes in the stage that deletes its project;
   - the sample's sets, both run by `check` (`sample/androidApp/build.gradle.kts:347-351`): the
     release set `:125` (`expectedProjects`, compared with `!=`) and the debug set `:228-229`
     (`required`, `containsAll`, no `-noop` project) (verified);
   - FT: the published stand-ins `:157`, the shared fixture `:227-228`, the real/noop loop
     `:245-250`, the `hopi-android` stand-ins `:26-28`, `:86`, `:134`, `:142-146`, `:183`, `:285`,
     `:424-481`, the constant `PROVIDER` `:531` and the stand-in `CONFIG_VALUES` `:534-541`
     (verified); the functional test's input file `gradle-plugin/hopi-gradle-plugin/build.gradle.kts:116-118`
     (verified);
   - `sample/shared/build.gradle.kts` lines that name the module (:21, :24, :28, :83-86, :94-99,
     :116-120, verified);
   - `README.md:560-564` (the base check) and section 7: one missing task path fails the whole
     invocation (cross-check problem 14);
   - the module's `api/` dumps.
6. **Fast gate, then the full run.** Before the base check (43.5 min in stage 0) run
   `./gradlew check --dry-run`. It fails fast on a missing project or task ("Project with path
   ':hopi-x' could not be found", "Cannot locate tasks"). Its limit: whether it resolves the variant
   classpaths under the configuration cache is unverified, so a green dry run is no proof against a
   module-set failure (a deleted noop still named by a plugin constant, a stale set at
   `sample/androidApp/build.gradle.kts:125`); only the full run is.
7. **Resolved graph at each boundary** (E7 X3): `./gradlew :sample:androidApp:dependencies
   --configuration releaseRuntimeClasspath`, the same with `debugRuntimeClasspath`, and
   `./gradlew :sample:shared:dependencies` with and without `-Phopi.enabled=true`. The Hopi
   projects listed must match the sets of section 7 for that stage (release: equal; debug: a
   superset).
8. **Grep gates exclude dated history**: benchmark result directories (`benchmark/*/2026-*`),
   passages under a dated history note (E8's "history" rows), `docs/design/api-runtime-split.md`,
   this plan and the evaluation files. Form: `git grep -n -i -e <term> [-e <term> …] -- .
   ':!benchmark/*/2026-*' ':!docs/design/api-runtime-split.md' ':!docs/design/api-runtime-split-plan.md'
   ':!docs/design/runtime-split-evaluation'`. One `-e` per term, not an `-E` alternation: with git
   2.47.1, `git grep -l -i -E 'noop|twin|parity'` lists none of the tracked `.pyc` files, while
   `git grep -l -i -e noop -e twin -e parity` lists 4 of them (verified). Each hit left is removed
   or sits under a history note.
9. **Machine.** 8 GB of RAM and a nearly full disk: one Gradle build at a time, none while
   `xcodebuild` runs (its build phase runs Gradle, `sample/iosApp/scripts/capture-swift-test.sh:4-5`,
   verified), and about 2 GB free before any run that links the Release framework (section 10).

### Stage 0. Preflight (done)

Done 2026-10-27 and 28 at `e8583b8`: the owner decisions are in section 10; the base check of
section 7 is green (43.5 min; the Release framework link needs about 2 GB free); `e1ef40a` fixed
the functional tests' daemon teardown.

### Stage 1. Evaluation and re-plan (done in this commit)

E1 to E8 and `cross-check.md` answer section 4, read-only (nothing was built or run). This commit
rewrites sections 3.9 and 6 to 10 for D2', D7', D10 and P1 to P5. Where each stage-order problem of
`cross-check.md` is carried:

1. Section 6 not re-planned for section 10: this commit.
2. The old stage 3 broke the build between its steps: void. D2' keeps the Ktor plugin, so no stage
   deletes `PlatformCapture`, the `ktor` source or the defer machinery.
3. Hard-coded module sets: rule 5, and stages 5, 7, 8, 9 and 10.
4. `hopi-api` on a release classpath trips `dev/koga/hopi/core/`: P2 in stage 3, checked in stage 7.
5. `HopiPerfConfig`'s internal constructor and `PerfEntry`: stage 4 (`PerfEntry` becomes a tool),
   stage 8 (the constructor).
6. No iOS perf start between the perf stage and the entry: the entry (stage 6) comes before perf
   (stage 8).
7. D2' had no stage text: stage 10 (P1).
8. The noop constants and `TWINS`: rule 5, and stages 5, 7, 8, 9 and 10.
9. Deleting `hopi-android` and `hopi-urlsession`: stage 9, "Same commit".
10. The URLSession library move and `publishedCinterops`: stage 9, step 1 and "Same commit".
11. What `check` compiles of the iOS entry, and the `HOPI_ENABLED=false` arm: P3 (a module that
    `check` compiles) and the `HOPI` Swift condition, stage 6.
12. The UI removal drops the only `PerfLauncher` writer: P5 in stage 4, before stage 5; stage 5
    also drops `hopi-ui` from `publishedModules` and its per-target dumps.
13. Grep gates hit history and live scripts: rule 8, stage 11.
14. The base check names deleted tasks: rule 5 and section 7's table.
15. The edge inventory, with the stage that cuts or moves each edge (`cross-check.md:40`; a list,
    not a problem): rule 5 and each stage's "Same commit" list.

Done when: every E item has an answer or a [run] experiment placed in a stage below; every blocker
of the E files that still applies is carried by a stage; the owner has the provisional decisions.

### Stage 2. Contract documents

Goal: the contract documents describe the target, after a separate fix of the redirect wording.

First ([run]): none.

Steps:

1. **Fix commit** (`fix:`; code and its docs only). `Capabilities.NOT_FOLLOWED`
   (`hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/Capabilities.kt:21-22`) is attached to
   every source but `KTOR` (`:51-56`) and says "the client does not follow it" (verified). Ktor's
   OkHttp, Android and Darwin engines turn the stack's own redirects off by default, and a Hopi mock
   sits above the stack, so Ktor's `HttpRedirect` (and `Auth` for a 401) follows a mocked 3xx on
   `okhttp`, `urlconnection` and `urlsession` records (E1.5: bytecode for OkHttp and Android;
   `hopi-urlsession/src/iosTest/kotlin/dev/koga/hopi/urlsession/KtorDarwinTest.kt:84-102`, verified,
   for Darwin; the host and device runs are R4 and R6 in stage 10). New text (E1.5): "a mocked 3xx, 401 or 407
   reaches the client as the final response on this source: OkHttp, HttpURLConnection and URLSession
   do not follow it; a client above them (Ktor's HttpRedirect, Auth) still does". The same change in
   `docs/design/rules.md:178-185` and `:1096-1097`, `README.md:383-385` and
   `docs/ARCHITECTURE.md:1006-1009` (verified). The tests name the constant,
   not its text (`hopi-ui/src/commonTest/kotlin/dev/koga/hopi/ui/rules/RuleRowsTest.kt:34`,
   `hopi-ui/src/jvmTest/kotlin/dev/koga/hopi/ui/rules/RulesPaneUiTest.kt:35`, verified).
2. **Docs commit** (`docs:`; no code).
   - ARCHITECTURE, E8 rows A1, A2, A4 to A6: a "Migration in progress" note after
     `docs/ARCHITECTURE.md:12` that points to this plan; principle 1 (`:16-19`, verified) rewritten:
     no twins; the optional `hopi-api` does nothing alone; only debug builds link the runtimes; a
     release build may link `hopi-api` and the thin `hopi-ktor`, nothing else (P1); the plugin's
     build-time guard and the iOS release guard refuse a runtime in a release build; the Android
     runtime refuses to start when the app is not debuggable; Hopi leaves a release build by not
     being linked, never by `#if DEBUG`. The Modules table marked with the stage that changes each
     row. The heading `## Consumer API (what the noop twins mirror)` (`:74`, verified) becomes
     "Consumer API".
   - ROADMAP, rows M1, M2, M4, M5: the rule for every new tool (`docs/ROADMAP.md:9-11`, verified)
     becomes a runtime module, off by default and opt-in, its API in `hopi-api`, a DSL entry in the
     plugin; its pointer to the superseded proposal goes to this plan. The surfaces (`:17-18`,
     verified) become the realtime log line, the bridge and CLI, and the desktop viewer (D7').
   - `docs/design/api-runtime-split.md:3-5` (verified): the status line says "the plan deletes the
     Ktor plugin and the public `HopiUrlSession` that this proposal kept", false since D2'. It
     becomes: the plan removes the public `HopiUrlSession` that this proposal kept and keeps a thin
     Ktor plugin (D2'). E8 F1 ("nothing to do") was read at `e8583b8`, before D2'.

Same commit: nothing is deleted or renamed.

Verify: fix commit: `./gradlew :hopi-core:jvmTest :hopi-ui:jvmTest`, then the base check. Docs
commit: `git show --stat HEAD` lists only `.md` files.

Done when: `NOT_FOLLOWED` and the two docs name the client-side follow-up; the contract describes
the target; the docs commit changes no code.

### Stage 3. The API module

Goal: a published `hopi-api` with no dependency, an empty registry with the opaque Ktor slot
reserved, and `@HopiInternalApi` under its new name (P2).

First ([run]):

1. **E7 X1** (is `checkKotlinAbi` in `check`?): `./gradlew :hopi-connect:check :hopi-rules:check
   --dry-run | grep -E 'checkKotlinAbi|allTests|testAndroidHostTest'`. Absent: today only
   `NoopParityCheck`'s `dependsOn(":$real:checkKotlinAbi", ":$noop:checkKotlinAbi")`
   (`build.gradle.kts:128`, verified) reaches it, so a module in no pair (`hopi-api` here,
   `hopi-start` in stage 6, `hopi-network` in stage 9) and a module whose pair goes (`hopi-perf` in
   stage 8, `hopi-ktor` in stage 10) would have its dump unchecked. This stage's commit then adds
   `tasks.named("check") { dependsOn("checkKotlinAbi") }` to every ABI-validated module, `hopi-api`
   included, and `hopi-start` and `hopi-network` get it when they are created.
2. **E7 X2** (are host tests in `check`?): `./gradlew :hopi-android:check :hopi-android-noop:check
   :hopi-perf:check --dry-run | grep testAndroidHostTest`. Present: the explicit entries of section 7
   are redundant (they stay). Absent: `:hopi-android-noop:testAndroidHostTest` has never run in the
   base check; record it.
3. **E7 X3** baseline of the resolved graphs (rule 7), recorded in section 9.
4. **E4 X1** (after step 1 below creates the module): with
   `abiValidation { filters { exclude.annotatedWith.add("dev.koga.hopi.api.HopiInternalApi") } }`,
   `./gradlew :hopi-api:updateKotlinAbi :hopi-api:checkKotlinAbi`, then grep the three dumps for the
   registry's name (`HopiRuntimes`, step 3). Present: the filter does not work on members; exclude
   by name instead (as `hopi-android/build.gradle.kts:21-28` does, verified).

Steps:

1. Create `hopi-api/`: no dependency; `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`;
   `explicitApi()`; `abiValidation` with the filter of E4 X1; `optIn` for its own annotation and
   for `kotlin.concurrent.atomics.ExperimentalAtomicApi` (as `hopi-core/build.gradle.kts:17`,
   verified); `description` set before `gradle/publishing.gradle.kts` is applied.
2. **P2**: move `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/HopiInternalApi.kt:1-19`
   (verified) to `hopi-api/src/commonMain/kotlin/dev/koga/hopi/api/HopiInternalApi.kt` as
   `dev.koga.hopi.api.HopiInternalApi`; its KDoc (`:3-6`, verified, names `hopi-ktor` and `hopi-ui`)
   names `hopi-api` and `hopi-ktor`. One mechanical rewrite: 91 tracked files outside the docs name
   `dev.koga.hopi.core.HopiInternalApi` (git grep, verified), among them the `optIn.add(...)` of ten
   build scripts (`hopi-android`, `hopi-cli`, `hopi-connect`, `hopi-core`, `hopi-desktop`,
   `hopi-ktor`, `hopi-perf`, `hopi-rules`, `hopi-ui`, `hopi-urlsession`, verified). No file of
   package `dev.koga.hopi.core` uses the annotation by its simple name: besides `HopiInternalApi.kt`,
   only `hopi-core/src/commonTest/kotlin/dev/koga/hopi/core/Fixtures.kt` and
   `hopi-core/src/jvmTest/kotlin/dev/koga/hopi/core/CoreGiantBodyBenchmark.kt` declare that package,
   and neither names it (verified). So rewriting the fully qualified name covers all 91 files.
   `hopi-core` gets `api(project(":hopi-api"))`.
3. **The registry**, `HopiRuntimes` (E4's name, provisional), in `dev.koga.hopi.api` (P2),
   `@HopiInternalApi`: one write-once field per runtime kind, read with one volatile read, each an
   `AtomicReference` of `kotlin.concurrent.atomics` (E4 2.3). Only one field now: the opaque Ktor slot, an `Any?` (provisional; P1 allows a marker
   interface). A second registration returns `false`, and the caller writes the notice (hopi-api
   has no log sink, E4 2.3). A `@HopiInternalApi resetForTests()`. Not hidden from Objective-C yet:
   nothing exports `hopi-api` before stage 7; stage 6 settles the hiding (E2.4).
4. **Tests** (`hopi-api/src/jvmTest`): no `hopi-core` on the test runtime classpath and no core class
   loadable (the assertion of `HopiRulesNoopTest.hopiCoreIsNotOnTheRuntimeClasspath`, E4 5.4); the
   registry is write-once and resets.
5. **ABI**: `./gradlew :hopi-api:updateKotlinAbi`. No dump names `HopiInternalApi` today (git grep
   over `*/api/*`: 0 hits, verified), so P2's "the marker appears in ABI dumps" is (to verify): run
   `checkKotlinAbi` on every ABI-validated module and commit every dump that changes.
6. **Docs**: README install table (E8 R5), ARCHITECTURE A4 and A5 (the annotation's new name, the
   `hopi-api` row) and A13 (204-206: "no `hopi-core` on the test classpath" becomes `hopi-api`'s
   test, step 4).

Same commit:

- `settings.gradle.kts`: `include(":hopi-api")`.
- `build.gradle.kts:244-258`: `"hopi-api" to allTargets`. Required: `hopi-core`'s Gradle module
  file and POM now name `hopi-api`, and `PublicationCheck` fails on a `dev.koga.hopi` dependency not
  published here (`build.gradle.kts:198-205` for the module file, `:216-217` for the POM, verified).
- If E7 X1 found `checkKotlinAbi` outside `check`: the `dependsOn` of "First" item 1 in the build
  script of every ABI-validated module.
- `README.md:560-564` and section 7: add `:hopi-api:jvmTest`.
- The sample's sets: no edit. `:hopi-api` joins the debug classpath through `hopi-core`, which
  `containsAll` tolerates (`sample/androidApp/build.gradle.kts:229`, verified); release has no core,
  so no `hopi-api` yet.

Verify: base check; `:checkPublication` lists `hopi-api`'s 21 files (E7 1). No device check.

Done when: `hopi-api` publishes; no file outside dated history names
`dev.koga.hopi.core.HopiInternalApi` (rule 8); everything green.

### Stage 4. Start sequence and tools in core, Android and JVM tests

Goal: one start sequence in `hopi-core` starts a list of tools; on Android the provider, moved to
`hopi-core` (P4), calls it with the generated tool list; caps, bridge and current-activity tracking
(P5) are set at start; D9's run-time check.

First ([run]):

1. **E3.6-a** (baseline): `./gradlew :sample:androidApp:verifyReleaseUsesNoop`, then
   `sample/androidApp/build/reports/hopi/release-uses-noop.txt`. Expected: 5 noop projects and 31
   Hopi classes (`README.md:493` and `docs/ARCHITECTURE.md:302` say 13, verified; E3 correction 1).
   Another count goes into section 9.
2. **E3.6-b** (a runtime added by hand, today): add `releaseImplementation(project(":hopi-android"))`
   to `sample/androidApp/build.gradle.kts:66-77` (verified), run `./gradlew
   :sample:androidApp:assembleRelease :sample:androidApp:verifyReleaseUsesNoop`, then
   `git checkout -- sample/androidApp/build.gradle.kts`. Expected: a duplicate `HopiAndroid` class,
   an accidental protection that ends in stage 9. Either way stage 9's guard is required.
3. **E5 X3** (before): `./gradlew :sample:androidApp:installDebug`, `adb logcat -c`,
   `adb shell am start -W -n dev.koga.hopi.sample/.MainActivity`, `adb logcat -d -s Hopi:I HopiPerf:I`,
   `hopi-cli/build/install/hopi-cli/bin/hopi perf status --app dev.koga.hopi.sample`; then again with
   `perf { enabled.set(true); autoStart(dev.koga.hopi.gradle.HopiMonitor.Hangs) }` at
   `sample/androidApp/build.gradle.kts:63` (verified). The fully qualified name, because the script
   does not import `HopiMonitor` (`:1-9`, verified) and item 4 runs after this one.
   Expected: the provider line "android capture started (… perf N us)", `hello.perf` true, hangs
   running with owner `code`. The same after the stage (Verify).
4. **E3.3** (`HopiMonitor` import): the app script of FT `publishedCoordinatesAreSwappedPerVariant`
   (`:232-237`, verified) with `hopi { perf { enabled = true; autoStart(HopiMonitor.Hangs) } }`,
   without and then with `import dev.koga.hopi.gradle.HopiMonitor` as its first line;
   `./gradlew :gradle-plugin:hopi-gradle-plugin:functionalTest --tests '*publishedCoordinatesAreSwappedPerVariant*'`.
   "Unresolved reference" without the import: `README.md:410` (verified) and plan 3.2 need the
   import (stage 13).
5. **E3.4** (host-test feasibility): whether `ApplicationInfo().apply { flags = 0 }` works under
   `isReturnDefaultValues` (`hopi-android/build.gradle.kts:45-48`, verified); if not, the decision
   function takes the flags as an `Int` (provisional). A scratch host test in `hopi-android` (the
   digest's `:hopi-network` task does not exist before stage 9):
   `./gradlew :hopi-android:testAndroidHostTest --tests '*Debuggable*'`.

Steps:

1. **Core API** (`commonMain`, `@HopiInternalApi`): `HopiTool { fun start(context) }` and
   `start(config, tools)` with a core configuration (store caps, redaction, `summaryLog`,
   `agentBridge`). Behaviour, so iOS uses the same sequence in stage 6 (E2.2): `enabled = false`
   does nothing and writes nothing; invalid caps write one notice whatever `summaryLog` says
   (provisional: iOS's rule, E2.2; Android writes it only with `summaryLog`,
   `hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/AndroidCapture.kt:120-124`,
   verified), install nothing and do not use up the first call; the first valid call wins and a later
   different one writes one notice; nothing throws to the caller; tools start in list order. Tests in
   `commonTest` with a fake tool, including both notices (no test covers them today, E2.2).
2. **Caps and bridge at start** (E3 3): a `@HopiInternalApi` preset of the default store's caps that
   does not create the store (its constructor starts the `hopi-store` thread,
   `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/store/HopiStore.kt:263-265`, verified);
   `HopiStore.default` and `configureDefault` (`:765-779`, verified) use it, so a first reader (the
   Ktor plugin's installation, a rule's `from(id)`, the bridge) no longer fixes the caps. With
   `agentBridge` the bridge starts at start, on a background thread (provisional), not at the first
   recorded request (`AndroidCapture.kt:87-101`, verified). Redaction and the summary sink are
   installed once (today `CaptureInstallation.kt:36-48`, verified).
3. **P4**: move `HopiInitProvider`, `HopiConfigValues`, `PerfConfig` and `GeneratedConfig`
   (`hopi-android/src/androidMain/kotlin/dev/koga/hopi/android/internal/HopiInitProvider.kt:13-187`,
   verified) to `hopi-core/src/androidMain/kotlin/dev/koga/hopi/android/internal/`, and the provider
   declaration (`hopi-android/src/androidMain/AndroidManifest.xml:7-11`, verified) to a new
   `hopi-core/src/androidMain/AndroidManifest.xml`. The packages `dev.koga.hopi.android.internal`
   and `.generated` stay: the rewriter never rewrites `dev/koga/hopi/android/` (E3 2d). `hopi-core`
   gets consumer keep rules for both packages (today `hopi-android/consumer-rules.pro:3-4`,
   verified) through `optimization { consumerKeepRules }` as `hopi-android/build.gradle.kts:41-44`
   (verified) (provisional: a copy of the two lines). `HopiConfigValues` keeps only the global
   settings; its `AndroidCapture.Settings` (`HopiInitProvider.kt:139-151`, verified) becomes the core
   configuration of step 1.
4. **Provider** (D9 run time, E3 4): its first statement (`HopiInitProvider.kt:21`, verified) checks
   `FLAG_DEBUGGABLE` through a companion function; absent or no `ApplicationInfo`: one notice line,
   always (E3 4 text), and nothing else. Then one `Class.forName` of the generated class (`:174`,
   verified) and the start sequence with its tool array. No generated class: one notice and nothing
   started (provisional, E3 2g; today it starts with defaults, `:180-181`). The start line keeps its
   prefix "android capture started" (`:32-37`, verified), which E5 X3 reads.
5. **Generated tool list** (E3 2): `GenerateHopiConfig.render()`
   (`gradle-plugin/hopi-gradle-plugin/src/main/kotlin/dev/koga/hopi/gradle/GenerateHopiConfig.kt:54-79`,
   verified) passes the global settings to the base constructor and an array of tools, each with its
   own settings: `new dev.koga.hopi.android.internal.NetworkTool(okhttp, urlConnection, rules)` (name
   provisional) and, with `perf { enabled = true }`,
   `new dev.koga.hopi.perf.internal.PerfEntry(autoStart, hangThresholdMs, hangHeartbeat)`. The
   reflective perf boot goes: `bootPerf` (`HopiInitProvider.kt:83-104`, verified), `perfPresent`, and
   `PerfBootstrap` (`hopi-core/src/androidMain/kotlin/dev/koga/hopi/core/perf/PerfBootstrap.kt:12-22`,
   verified), whose `bit()` moves next to its decoder in `hopi-perf`. Generation stays limited to
   debuggable variants (PLG:68, :83, verified). PLG:53-56 (verified) also adds `hopi-core` (a constant
   `CORE`) to each debuggable build type: javac compiles the generated class inside the app and
   every runtime takes core as `implementation` (E3 2a). Side effect accepted (E3 2b): core's
   `api(libs.kotlinx.coroutines.core)` (`hopi-core/build.gradle.kts:47`, verified) reaches the app's
   debug compile classpath.
6. **Network tool** in `hopi-android`: wraps `AndroidCapture.start(settings, okHttp, okHttpWanted,
   urlConnection)` (`AndroidCapture.kt:113-146`, verified) and the `RealCall` lookup `okHttpState`
   (`HopiInitProvider.kt:59-81`, verified), which stays in `hopi-android` because it returns an
   `AndroidCapture.OkHttp`.
7. **Perf tool**: `PerfEntry`
   (`hopi-perf/src/androidMain/kotlin/dev/koga/hopi/perf/internal/AndroidBackend.kt:263-276`,
   verified) implements `HopiTool`, keeps its name and package (so
   `sample/androidApp/build.gradle.kts:273` stays), takes the `Application` from the start context
   (today `:266`), and calls `PerfRuntime` itself (`:267`), never the public `HopiPerf.install`, so
   stage 8 can remove `install`. Its keep rule `hopi-perf/consumer-rules.pro:3` (verified) stays
   (harmless; E5 3.3: a directly referenced class is kept anyway, unverified). Android only here;
   stage 6 adds the iOS one and stage 8 the JVM one (provisional).
8. **P5**: core's Android start registers one `ActivityLifecycleCallbacks` that keeps a weak
   reference to the resumed activity, the value `PerfLauncher` holds today
   (`hopi-core/src/androidMain/kotlin/dev/koga/hopi/core/perf/PerfLauncher.kt:15-31`, verified).
   `AndroidBackend.attachLauncherActivity` (`AndroidBackend.kt:188-197`, verified) reads it, so frames
   and hangs attach at once after stage 5 removes the launcher, today the only writer
   (`hopi-ui/src/androidMain/kotlin/dev/koga/hopi/ui/platform/Platform.android.kt:91-95`, called from
   `hopi-ui/src/commonMain/kotlin/dev/koga/hopi/ui/HopiLauncher.kt:104`, verified). Registered
   whatever tools are listed (provisional); it runs only on lifecycle events, no thread and no timer.
   `PerfLauncher.kt:7-13` ("no lifecycle listener while every monitor is off") is reworded.
9. **JVM and iOS**: JVM tests call `start(config, tools)` directly; no JVM entry before stage 10.
   iOS does not change in this stage: `sample/iosApp/iosApp/iOSApp.swift:10-24` (verified) keeps
   `HopiUrlSession.shared.start` and `HopiPerf.shared.install` until stage 6.
10. **Sample caps** (E3 3): after step 2 the provider's preset wins over the caps that
    `--el maxTotalBytes` fed to the Ktor plugin
    (`sample/androidApp/src/main/kotlin/dev/koga/hopi/sample/MainActivity.kt:101-108`,
    `sample/shared/src/commonMain/kotlin/dev/koga/hopi/sample/SampleHttp.kt:64-75`, verified), and
    `--el maxBodyBytes` never reached OkHttp capture. So `hopi { }`
    (`sample/androidApp/build.gradle.kts:57-64`, verified) reads `sample.maxBodyBytes` and
    `sample.maxTotalBytes` Gradle properties (defaults 256 KiB and 16 MiB). The `--el` extras stay
    for the plugin's own records only, with their KDoc saying so (`MainActivity.kt:31-39`, verified)
    (provisional). `hopi-desktop/src/test/kotlin/dev/koga/hopi/desktop/DeviceViewerUiTest.kt:49`
    (precondition) and `:87` (`--el maxBodyBytes`) (verified) become "debug build installed with
    `-Psample.maxBodyBytes=8388608`"; `benchmark/device/scripts/giant.py:7-8,36-48` and
    `benchmark/device/scripts/ui_hold.py:11,39-42` (verified) replace `--cap` and `--store` by a
    build and install on Android. iOS keeps its launch arguments
    (`sample/iosApp/iosApp/iOSApp.swift:15-16`, verified).
11. **Docs**: ARCHITECTURE (the start sequence, the provider's module),
    `docs/design/android-capture.md` "Auto-start" (cited by `GenerateHopiConfig.kt:15`, verified),
    `docs/design/perf-monitors.md` "How the monitors find the app" (cited by
    `HopiInitProvider.kt:85`, verified). Both design docs say the DSL is generated into resources;
    it is a Java subclass (E3 correction 10).

Same commit:

- The constructor's lockstep sites (E3 2e): `GenerateHopiConfig.kt:58-75` (verified); the moved
  `HopiConfigValues`; `gradle-plugin/hopi-gradle-plugin/src/test/kotlin/dev/koga/hopi/gradle/HopiPluginUnitTest.kt:17-53`
  (verified); the host-test stand-in
  `hopi-android/src/androidHostTest/kotlin/dev/koga/hopi/android/generated/HopiGeneratedConfig.kt:6`
  (verified; `hopi-perf` is not on that test classpath, so it lists the network tool or a fake);
  `hopi-android/src/androidHostTest/kotlin/dev/koga/hopi/android/GeneratedConfigTest.kt:55-68`
  (verified), which becomes "the provider starts exactly the listed tools and loads no class but the
  generated one"; FT `CONFIG_VALUES` (`:534-541`), written at `:183` and `:459` (verified).
- FT stand-ins: the plugin now adds `hopi-core`, and `runtime()` falls back to
  `dev.koga.hopi:hopi-core:0.1.0` without a `:hopi-core` project (PLG:134-135, verified), which the
  fixtures do not publish. So a `hopi-core` stand-in joins the "apps" fixture (`:285`, next to
  `:424-481`) and the published stand-ins (`:157`), holding `HopiConfigValues`, the tool interface and
  the provider (today in the `hopi-android` stand-in: the manifest `:432-441`, `HopiConfigValues`
  `:459`, `HopiInitProvider` `:461-478`, verified). The generated class names tool classes, so the
  network tool joins both `hopi-android` stand-ins, and `PerfEntry` with the generated constructor
  joins the published `hopi-perf` stand-in (the fixture enables perf, `:237`, verified).
- PLG:53-56 (above); PLG KDoc :12-25.
- `hopi-android/src/androidMain/AndroidManifest.xml`: the provider leaves it.
- `sample/androidApp/build.gradle.kts`: no edit. `PROVIDER` (:183, :271), `RULE_ENGINE` (:272) and
  `PERF_ENTRY` (:273) keep their names (verified); `:228` already requires `:hopi-core`.
- `README.md:560-564` and section 7: no task changes (the provider's host tests stay in
  `hopi-android`'s, which can reach core's public `@HopiInternalApi` classes; provisional).

Verify: base check (incl. `:hopi-android:testAndroidHostTest`, `:hopi-perf:testAndroidHostTest`,
FT). [run]: E5 X3 after (same expectations); `--es rulesVerify all` unchanged; E3.3's body cap:
`./gradlew :sample:androidApp:installDebug -Psample.maxBodyBytes=8388608`, `adb reverse tcp:8765
tcp:8765`, the dev server, `./gradlew :hopi-desktop:test --tests '*DeviceViewerUiTest*'
-Phopi.desktop.device=android`, then "5 MB body opens" true in
`build/evidence/desktop/android-checks.jsonl` at the repository root (`hopi-desktop/build.gradle.kts:52`,
verified; `-Phopi.desktop.evidence=<dir>` writes it elsewhere); the bridge is up before any request
(`hopi list --app dev.koga.hopi.sample` right after launch); `python3 benchmark/perf/idle.py
--seconds 60 --settle 10 --arms off=none,all=all`: `off` still shows `hopiCpuUs` 0 and `hopiWakes`
0 with P5's callbacks.

Done when: the provider in `hopi-core` starts exactly the generated tools; a non-debuggable
`ApplicationInfo` starts nothing and writes one notice (host test); perf registers as a tool on the
emulator; everything green.

### Stage 5. Remove the in-app UI (D7')

Goal: no in-app UI: `hopi-ui` holds only the desktop viewer's screens, JVM only and unpublished;
`hopi-ui-noop` is deleted.

First ([run]): none. E6.2 and E6.3 (overlay and window spikes) are moot under D7'. Precondition:
stage 4's P5 tracking is in place.

Steps:

1. **Sample**: `sample/shared/src/commonMain/kotlin/dev/koga/hopi/sample/SampleApp.kt:47` (import),
   `:81` (KDoc) and `:91-95` (the `HopiLauncher { }` wrap) (verified). The hosts
   `MainViewController.kt` and `MainActivity.kt` need no change (E6.1).
2. **`hopi-ui`**: keeps the KMP plugin with `jvm()` only (`hopi-ui/build.gradle.kts:43`), so the task
   stays `:hopi-ui:jvmTest` (provisional). Remove the plugin
   `alias(libs.plugins.android.kotlin.multiplatform.library)` (`:6`) with the `android { }` block it
   configures (`:36-41`), `iosArm64()` and `iosSimulatorArm64()` (`:44-45`), the publishing script
   (`:12`), and `abiValidation` (`:17-29`) with `hopi-ui/api/` (O7: `hopi-ui` is neither a module a
   consumer compiles against nor a runtime) (provisional); the `description` (`:11`) says "the
   desktop viewer's screens" (all verified). Delete `src/androidMain`, `src/iosMain`,
   `HopiLauncher.kt`, the HUD (`perf/PerfHud.kt`), the in-app perf tab (`perf/PerfPane.kt`) and rules
   list (`rules/RulesPane.kt`), the `HopiScreen` composables, and `PerfLauncherAnchor`, whose only
   caller is `HopiLauncher.kt:104`: its `expect` (`src/commonMain/kotlin/dev/koga/hopi/ui/platform/Platform.kt:34`)
   and jvm `actual` (`src/jvmMain/kotlin/dev/koga/hopi/ui/platform/Platform.jvm.kt:21`) (verified).
   `HopiScreen.kt` keeps what the desktop panes import (`DetailState`, `Toast`, `ToastHost`,
   `rememberDetailState`, `rememberDetailActions`, `rememberListModel`:
   `hopi-ui/src/jvmMain/kotlin/dev/koga/hopi/ui/desktop/HopiPanes.kt:29-48`, verified).
   `hopi-desktop/build.gradle.kts:26` (verified) stays.
3. **Tests**: delete both `LauncherLayoutTest` copies (E6.6 correction), `LauncherUiTest`, and the
   tests of the HUD, the in-app perf tab and the in-app rules list; a test of shared list, detail or
   rule code is retargeted to `HopiPanes` (`HopiPanesUiTest`) when the panes use that code, otherwise
   deleted (provisional). The idle-frame and "not drawn under" guarantees go with the in-app UI.
4. **Texts that name the launcher, the HUD or the in-app screen**: `AndroidBackend.kt:69` ("or open
   the Hopi launcher") and `:183`, `PerfLauncher.kt:7-13`, `:19` and `:27`,
   `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/perf/PerfData.kt:81` (the `inspector` frame
   flag, set while `HopiScreen` is open: nothing sets it now; it stays, always false, provisional),
   `hopi-cli/src/main/kotlin/dev/koga/hopi/cli/PerfRender.kt:19-21` (the KDoc and the hint
   `FRAMES_HINT`), the same hint in `hopi-desktop/src/main/kotlin/dev/koga/hopi/desktop/PerfPanel.kt:199`,
   and the KDoc link `[dev.koga.hopi.ui.HopiScreen]` in
   `hopi-ui/src/jvmMain/kotlin/dev/koga/hopi/ui/desktop/HopiPanes.kt:118` (all verified). Also
   (git grep, verified): the HUD as a listener in the KDoc of `PerfController.kt:173` and `:191`,
   `PerfData.kt:25` and `PerfEvents.kt:5` (all in `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/perf/`);
   `hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/HopiPerf.kt:11` ("later, the in-app screen"),
   `hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/internal/PerfRuntime.kt:242` ("the in-app Perf
   tab") and `hopi-perf/src/jvmTest/kotlin/dev/koga/hopi/perf/HopiPerfTest.kt:126` ("the HUD");
   `hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/Hopi.kt:42` ("for the in-app screen");
   `hopi-connect/src/test/kotlin/dev/koga/hopi/connect/RemoteSessionTest.kt:258`;
   `hopi-desktop/build.gradle.kts:25`; the comment `sample/iosApp/iosApp/iOSApp.swift:18-20` (the
   in-app Perf tab, the HUD and the launcher; stage 6 rewrites `:7-24`); and the sample's caption
   "Read the result in Hopi's Perf tab" (`sample/shared/src/commonMain/kotlin/dev/koga/hopi/sample/PerfLab.kt:315`).
5. **Device scripts** that tap "Open Hopi" lose their target:
   `benchmark/device/scripts/ab.sh:18`, `inspector.sh:24`, `post.sh:11`, `reopen.sh:9,11` (verified).
   Delete them (provisional); their results stay as dated history.
6. **Docs**: E8 rows R5 (86, 88), R6 (115), R9, R16, R24; A4 (42-43), A9, A13 (194-204), A19
   (sets), A35, A56, A62 (1789), A67 (1960), A69 to A73, A80 (2248), A81; M7; PM3, PM6, PM8; RU18
   (863-864).

Same commit:

- `settings.gradle.kts:39`; `build.gradle.kts:111` (the ui pair) and `:248-249` (`hopi-ui` and
  `hopi-ui-noop`: both leave `publishedModules`); the comment `:239-242` (verified).
- PLG:166 loses `"hopi-ui" to "hopi-ui-noop"`; KDoc :19-20 (verified).
- `gradle-plugin/hopi-gradle-plugin/src/main/kotlin/dev/koga/hopi/gradle/HopiExtension.kt:23-26`
  (the KDoc names `hopi-ui`, verified).
- `sample/androidApp/build.gradle.kts:125` drops `:hopi-ui-noop`; `:228` drops `:hopi-ui` (verified).
- `sample/shared/build.gradle.kts:95` goes (verified).
- FT: nothing; no line names `hopi-ui` (verified by grep).
- `README.md:560-564` and section 7: drop `:hopi-ui-noop:jvmTest`.
- Delete `hopi-ui-noop/` (8 files with its dumps, verified) and `hopi-ui/api/`.

Verify: base check; `:hopi-ui:jvmTest`, `:hopi-desktop:test`. [run]: `--es perfVerify all` with
`python3 benchmark/perf/verify.py build/evidence/perf` on the emulator; `hopi perf start frames
--app dev.koga.hopi.sample` right after launch answers without "frames attach when an activity next
starts" (`AndroidBackend.kt:69`, verified), proving P5 on the single-activity sample; `-verifyPerf`
on the simulator; the desktop viewer against the emulator (`DeviceViewerUiTest`,
`-Phopi.desktop.device=android`).

Done when: no `HopiLauncher`, `HopiScreen` composable or HUD outside dated history (rule 8);
`hopi-ui` builds for the JVM only and is not published; perf frames attach with no launcher.

### Stage 6. iOS entry `hopi-start` (P3)

Goal: Swift starts Hopi with one call, `Hopi.shared.start { … }`, from a published, Debug-only,
exported module that names the tools and runs stage 4's start sequence.

First ([run]):

1. **E2.5** (the entry's configuration type): the E2 probe, `./gradlew -I $P/init.gradle.kts
   :e2probe:linkDebugFrameworkIosSimulatorArm64 -Pe2.variant=entry`, the greps of `E2.md`
   ("Experiments") and `xcrun --sdk iphonesimulator swiftc -typecheck …`. Purpose: confirm that a
   configuration class the entry's module declares reaches Swift plainly and that the closure form
   type-checks. A failed typecheck changes the entry's shape before code is written; P3's own class
   with primitive members stands either way. The probe's `src/entry/Probe.kt` (`E2.md:280`, read at
   `e8583b8`, before P2) starts with `@file:OptIn(dev.koga.hopi.core.HopiInternalApi::class)`, which
   no longer resolves: write `@file:OptIn(dev.koga.hopi.api.HopiInternalApi::class)` (P2, stage 3).
   `hopi-core` reaches `hopi-api` through its `api` edge, so the rest of the probe stands.
2. **E2.4** (export leakage and hiding): the probe with `-Pe2.variant=export`, then `=hidden`
   (`E2.md`). Purpose: how the `@HopiInternalApi` declarations of `hopi-api` (exported in every build
   from stage 7) and of `hopi-start` stay out of Swift's header. The hidden variant compiles and
   hides: `HidesFromObjC` on `HopiInternalApi`. Otherwise `@HiddenFromObjC` per declaration.
3. **E2.8 step 3** (today's failure mode, also E5 X2's baseline):
   `./gradlew :sample:shared:verifyIosReleaseExcludesCapture -Phopi.enabled=true`, then without the
   flag; record both `found` lines.
4. **E5 X4** (before): the Debug build (section 7's command), `xcrun simctl launch --console-pty
   booted dev.koga.hopi.sample -hopiPerf hangs`, `hopi perf status --app dev.koga.hopi.sample`; then
   `-verifyPerf` with `python3 benchmark/perf/verify.py build/evidence/perf --platform ios` (21 of 21).
   The same after the stage.
5. **E4 X5** (optional): whether `kotlin.native.Platform.isDebugBinary` tells Debug from Release
   (commands in `E4.md`, "Experiments"); true only in Debug lets `hopi-api`'s `iosMain` warn once
   about a forgotten start (section 8 risk; stage 7, step 2 uses the result).

Steps:

1. **`hopi-start/`**: `iosArm64`, `iosSimulatorArm64` (jvm in stage 10); package
   `dev.koga.hopi.start` (provisional). `public object Hopi { val isStarted; fun start(); fun
   start(configure: HopiStartConfig.() -> Unit) }` (`isStarted` because `CaptureTests.swift` asserts
   one). `public class HopiStartConfig` with primitive members only (E2.5): `enabled`, `agentBridge`,
   `summaryLog`, `rules`, `maxEntries`, `maxTotalBytes`, `maxBodyBytes`, `redactHeaders(vararg)`,
   `redactQueryParameters(vararg)`, `unsafeDisableRedaction()` (today's `HopiUrlSessionConfig`,
   appendix B), plus `perf` (default true: register the perf runtime, start nothing) (provisional).
   `implementation` (not exported), in `commonMain` so that the jvm target of stage 10 takes the
   same edges: `hopi-core` and `hopi-perf` (the iOS network tool is in core until stage 9, step 2).
   `explicitApi()`, ABI validation (klib dump, and `check` depending on `checkKotlinAbi` if E7 X1
   found it absent, stage 3), `description` and publishing.
2. **Tools on iOS**: the network tool wraps today's URLSession start
   (`hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/urlsession/UrlSessionCapture.kt:115-159`,
   verified; E2.2): target, hooks, `RuleEngine.recorderStarted`, `UrlSessionRules.enable`, in that
   order after the start sequence's store (E2.2). It sits in
   `hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/urlsession/`, beside `UrlSessionCapture.kt`, and
   moves with it in stage 9, step 2 (provisional). Not in `hopi-urlsession`: `hopi-start` names the
   tool, so it is public, and `hopi-urlsession` validates its ABI with no filter
   (`hopi-urlsession/build.gradle.kts:16-18`) against a noop that holds only `HopiUrlSession` and
   `HopiUrlSessionConfig`, so `:checkNoopParity`, and with it `check`, would fail; the sample also
   exports `hopi-urlsession` until stage 9 (`sample/shared/build.gradle.kts:83`), which would put the
   class into Swift's header. Core has no ABI validation and is not exported (`:120`) (all
   verified). The perf tool: an iOS `PerfEntry` in `hopi-perf` `iosMain` (same name and package,
   provisional) that calls `PerfRuntime.install` and its once-only `-hopiPerf` read
   (`hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/internal/PerfRuntime.kt:287-302`, verified),
   never the public `HopiPerf.install`; the bridge starts from the start sequence, not from perf
   (`:296`).
3. **Start on iOS**: stage 4's sequence with E2.2's rules (step 1 of stage 4); the notice texts of
   `UrlSessionCapture.kt:325-329` (verified) are kept.
4. **Sample build**: in `sample/shared/build.gradle.kts`, `iosMain` gets
   `if (hopiEnabled) api(project(":hopi-start"))` and the framework block (`:77-87`, verified) gets
   `if (hopiEnabled) export(project(":hopi-start"))`. `hopi-core` stays on the iOS compile classpath
   with the flag (`:120`, for the probe). `export(urlSessionModule)`/`api(urlSessionModule)`
   (`:83`, `:119`) stay until stage 9 and `export(perfModule)`/`api(perfModule)` (`:86`, `:99`) until
   stage 8 (verified) (provisional). Their comment `:80-82` ("Swift calls HopiUrlSession.shared.start()
   itself (iOSApp.swift)", verified) loses that clause: Swift calls `Hopi.shared.start`.
5. **Swift** (E2.1): `sample/iosApp/iosApp/iOSApp.swift:7-24` (verified): `HopiUrlSession.shared.start
   { … }` and `HopiPerf.shared.install { … }` become one `Hopi.shared.start { config in … }` (agent
   bridge and the two cap launch arguments) inside `#if HOPI`;
   `sample/iosApp/iosAppCaptureTests/CaptureTests.swift:14-16` (verified) become
   `Hopi.shared.start()` and `Hopi.shared.isStarted`, inside `#if HOPI` (provisional), and its doc
   comment `:7` ("which called `HopiUrlSession.shared.start()`", verified) names the new call;
   `ThirdPartySDK.swift:5` (a comment); `CaptureVerification.swift` unchanged (it calls only
   `HopiRules.shared`, E2.1).
6. **`project.yml`** (E2.1 1.4, option a): in Debug, `HOPI_ENABLED: "true"`,
   `HOPI_SWIFT_CONDITION_true: HOPI` and `SWIFT_ACTIVE_COMPILATION_CONDITIONS: "$(inherited)
   $(HOPI_SWIFT_CONDITION_$(HOPI_ENABLED))"`, so Swift and the build phase
   (`sample/iosApp/project.yml:75`, verified) read one setting. The condition must reach the test
   target `iosAppCaptureTests` too, which has its own `settings` block (`:82-100`, verified):
   without it the `#if HOPI` in `CaptureTests.swift` compiles its assertion out and the suite passes
   empty. Set it on both targets (or at project level, to verify in XcodeGen's schema). A Release
   build with `HOPI_ENABLED=true` then fails to compile, the safe side. Comments `:69-72`, `:80-81`
   (verified). Then `xcodegen generate` (the `.pbxproj` is generated and checked in, E2.1).
7. **Hiding** per E2.4's result. `hopi-start`'s exported API names no core or runtime type.
8. **Docs**: E8 rows R12 to R15, the iOS parts of A16 to A21, US2 and US6 (the iOS start).

Same commit:

- `settings.gradle.kts`: `include(":hopi-start")`; `build.gradle.kts:244-258`:
  `"hopi-start" to listOf("iosArm64", "iosSimulatorArm64")` (13 files, E7 1; `expectedPublication`
  takes a target list, `:277-289`, verified).
- `sample/shared/build.gradle.kts`, `sample/iosApp/project.yml` and the regenerated project, the two
  Swift files (above).
- `README.md:560-564` and section 7: no change (`hopi-start`'s iOS compile and tests run in `check`).
- The iOS release check (`sample/shared/build.gradle.kts:126-195`, verified): no edit. Without the
  flag the Release framework has no `hopi-start`; its families do not count `dev.koga.hopi.start`.

Verify: base check; `./gradlew :sample:shared:linkDebugFrameworkIosSimulatorArm64 -Phopi.enabled=true`,
then `Shared.h` declares `Hopi` and `HopiStartConfig` and no `Hopi_core…` type from `hopi-start`.
[run]: **E7 X5**: `xcodebuild -project sample/iosApp/iosApp.xcodeproj -scheme iosApp -configuration
Debug -destination 'platform=iOS Simulator,name=iPhone 17' HOPI_ENABLED=false build` compiles; the
Release build compiles; E5 X4 after; `-thirdPartySDK`; `-verifyRules`;
`sample/iosApp/scripts/capture-swift-test.sh 'platform=iOS Simulator,name=iPhone 17'`; the desktop
viewer against the simulator (`-Phopi.desktop.device=ios`); E2.8 step 3 again: without the flag
every count is 0, as before; with it every family is non-zero; record the new counts beside the
baseline. With the flag the Release framework also links `hopi-start`, the start sequence and the
tools, because one framework block serves both build types (`sample/shared/build.gradle.kts:77-87`,
verified), so its counts (the core count first) differ from those of "First" item 3.

Done when: Swift makes one start call; Debug, Debug with `HOPI_ENABLED=false`, and Release compile;
`-verifyPerf` 21 of 21; the capture tests pass.

### Stage 7. Rules into the API

Goal: `HopiRules` and its builders live in `hopi-api` with the same names; core implements the rules
runtime and registers it first at start. The first stage with `hopi-api` on release classpaths.

First ([run]):

1. **E7 X4 / E4 X3** (on a scratch branch): `api(project(":hopi-api"))` in `sample/shared`
   `commonMain`, `:hopi-api` added to `sample/androidApp/build.gradle.kts:125`, then
   `./gradlew :sample:androidApp:verifyHopiSelection` and
   `./gradlew :sample:shared:verifyIosReleaseExcludesCapture`, or `xcrun nm -m
   sample/shared/build/bin/iosSimulatorArm64/releaseFramework/Shared.framework/Shared | grep
   'dev.koga.hopi.core'`. Under P2 both pass: the annotation is in `dev.koga.hopi.api`. A
   `dev/koga/hopi/core/` type or a `kfun:dev.koga.hopi.core` symbol means something of core leaked
   into `hopi-api`: fix it first (no exemption, P2).
2. **E4 X1**, if stage 3 could not settle it.

Steps:

1. **API** (`hopi-api` `commonMain`, `dev.koga.hopi.rules.*`, appendix B; the real and noop dumps are
   identical, E4.1): `HopiRules`, `HopiRuleOptions`, `HopiMock`, `HopiDelay`, `HopiEdit`,
   `HopiRequestEdit`, `HopiResponseEdit`, `HopiRuleInfo` (`@HopiInternalApi` constructor),
   `HopiFailure`, `HopiSource` (`Ktor` stays, D2'). The builders keep every setting, the
   function-call ones included (the noop drops those, E8 F7), and expose them through about 19
   read-only `@HopiInternalApi` getters (E4.3 names); no validation in the API.
2. **Runtime interface**: `@HopiInternalApi interface HopiRulesRuntime` (E4 2.3) and the registry's
   `rules` field. `HopiRules` runs the block, reads the field once, then delegates or returns `-1`,
   `false` or an empty list. An add while no runtime is registered is counted (section 10, E4.4).
   A forgotten start registers nothing, so no runtime notice reports it (E4 correction on the
   section 8 risk). If E4 X5 (stage 6) found `Platform.isDebugBinary` true only in Debug,
   `hopi-api`'s `iosMain` writes one line on the first `HopiRules` call made with no runtime
   registered, in a Debug binary only (E4: one `NSLog` line per process). Otherwise section 8's row
   "A forgotten iOS `start` call" keeps only the README step and the sample.
3. **Core**: `internal object ApiRules : HopiRulesRuntime` in
   `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/rules/`, with today's body of
   `hopi-rules/src/commonMain/kotlin/dev/koga/hopi/rules/HopiRules.kt:35-119` (verified; E4 2.3).
   `ApiRules.register()` is idempotent and writes one notice with the count of dropped adds. The
   start sequence calls it first, before the caps check and whatever `rules` says (E4 2.3), on
   Android (provider), iOS (`hopi-start`) and in the JVM tests.
4. **`from(id)` fix** (E4 2.3): the runtime looks the record up without creating the store (a
   `@HopiInternalApi HopiStore.defaultOrNull`) and answers "mock.fromTransaction: no transaction n";
   today `HopiStore.default.get` creates it with default caps (`HopiStore.kt:766-767`, verified).
5. **Tests** (E4 5.4): `HopiRulesTest` (6 tests) becomes
   `hopi-core/src/jvmTest/kotlin/dev/koga/hopi/core/rules/ApiRulesTest.kt`, registering in
   `@BeforeTest`; `HopiRulesNoopTest` moves to `hopi-api`'s jvmTest, its read-back test extended to
   the getters. New: delegation to a fake runtime; adds counted only while none is registered; a
   second registration refused; core's notice carries the count.
6. **Sample**: `sample/shared/build.gradle.kts:23-24` (`rulesModule`), `:84` (`export`) and
   `:96-97` (`api`) (verified) become `api(project(":hopi-api"))` in `commonMain` and
   `export(project(":hopi-api"))` in every configuration (Swift calls `HopiRules.shared` unguarded,
   E2.1). `MainActivity.kt:13-14` (verified) keeps its imports (same names).
7. **Versions** (E4 2.3): `hopi-api` comes from the app and `hopi-core` from the plugin at
   `HOPI_VERSION`; a mismatch fails at run time. The plugin adds a constraint that aligns
   `dev.koga.hopi:hopi-api` with `HOPI_VERSION` on debuggable build types (provisional).
8. **Docs**: E8 rows R5 (91-92), R6 (116-117), R19 (353, 368); A4 (48-49), A12, A19 (sets, F3),
   A42, A51, A66; M12; RU2, RU16, RU20, RU22.
   Document the behaviour change: a rule added before start is dropped with one notice (E4 4). The
   plan's 3.7 "one volatile read, nothing else" is not exact for rules: the block runs and bodies
   are copied (E4 correction 4).

Same commit:

- Delete `hopi-rules/` and `hopi-rules-noop/` with their `api/` dumps.
- `settings.gradle.kts:44-45`; `build.gradle.kts:114` (the rules pair) and `:252-253`; the comment
  `:239-242` (the rules twins) (verified).
- PLG:166 loses the rules pair; KDoc :19-20 (verified).
- FT: `:157` drops `hopi-rules` and `hopi-rules-noop` and adds `hopi-api`; `:228` becomes
  `api("dev.koga.hopi:hopi-api:$version")`; `:245-250` drops the rules pair (the other pairs stay
  until their stages) and asserts `hopi-api`'s marker in debug and release; KDoc `:142-147`
  (verified).
- `sample/androidApp/build.gradle.kts:125`: `:hopi-rules-noop` becomes `:hopi-api`; `:228`: drop
  `:hopi-rules`, add `:hopi-api` (provisional) (verified).
- `README.md:560-564` and section 7: no change (no rules task is listed).
- `hopi-api`'s dumps (`updateKotlinAbi`).

Verify: base check; `verifyHopiSelection` and `verifyIosReleaseExcludesCapture` pass with
`hopi-api` in release. [run]: **E4 X2**: `./gradlew :sample:shared:linkDebugFrameworkIosSimulatorArm64
-Phopi.enabled=true`, then grep `Shared.h` for
`HopiRulesRuntime|HopiRuntimes|HopiPredicate|ruleSources|headerSets|bodyBytes|fromTransactionId`
(the registry's name of stage 3 included, as in `E4.md` X2); any match: hide per declaration
(stage 6's choice did not reach them). **E4 X6**: the dev server, `--es rulesVerify all` on the
emulator and `-verifyRules` on the simulator pass as before; one `HopiRules.mock` before start
(Android: in an `Application` constructor; iOS: before `Hopi.shared.start`) returns `-1` and gives
exactly one notice from the runtime (on an iOS Debug binary, plus step 2's line when E4 X5 added it).

Done when: `hopi-rules` and its noop are gone; the self-tests pass on both platforms; the release
checks pass with `hopi-api` and no exemption.

### Stage 8. Perf into API and runtime

Goal: `HopiPerf`'s consumer subset lives in `hopi-api`; `hopi-perf` is a runtime that registers
through its tool; `install` and `hopi-perf-noop` go.

First ([run]): none new. Stage 6 recorded the iOS baseline (E2.8 step 3, E5 X2).

Steps:

1. **API** (E5.2): `hopi-api` gets `HopiPerf` (`start`, `stop`, `running`, `screen`), `HopiMonitor`
   and `HopiPerfConfig` with the start block's settings (`frames`, `hangs`, `cpu`, `memory`,
   `hangThresholdMs`, `hangHeartbeat`, `sampleIntervalMs`), defaults as literals. `agentBridge` and
   `summaryLog` leave the block for the global configuration (`hopi { }`, `HopiStartConfig`); a
   global `summaryLog = false` now silences perf lines too (document it). `remoteControl` moves to
   perf's own configuration (`perf { remoteControl }`, `HopiStartConfig`) (provisional: E5's
   reason, it gates only perf commands; the plan said global).
2. **Constructor fix** (cross-check problem 5): `HopiPerfConfig` keeps its internal constructor
   (`hopi-perf/src/commonMain/kotlin/dev/koga/hopi/perf/HopiPerf.kt:50`, verified) in `hopi-api`. The
   runtime never builds one: `PerfRuntime` takes `PerfSettings` directly; each `PerfEntry` tool builds
   `PerfSettings` (today `AndroidBackend.kt:267-272`, verified); `HopiPerf.start` hands the block's
   set values to the registered perf runtime through a `@HopiInternalApi` interface in `hopi-api`.
   A JVM `PerfEntry` joins the Android (stage 4) and iOS (stage 6) ones, in
   `hopi-perf/src/jvmMain/kotlin/dev/koga/hopi/perf/internal/` beside `JvmBackend.kt` (verified),
   with the same name and package: it builds `PerfSettings`, registers the controller in `PerfHook`
   and the perf runtime in the registry. Without it nothing starts perf on the JVM once `install`
   goes (step 6, and the JVM entry of stage 10).
3. **Only what was set applies** (E5.2): today each install or start re-applies all five settings
   with defaults for the unset ones (`PerfRuntime.kt:293-295`, verified). The builder records which
   were set; the runtime applies only those.
4. **Registration**: the perf tool registers the controller in `PerfHook` and the perf runtime in the
   registry; `HopiPerf.start` no longer installs the runtime (`PerfRuntime.kt:304-305`, verified). A
   `start` before registration is dropped and counted, with one notice at registration, as for rules
   (provisional); the pending screen label stays, one value as today (`:270-271`, `:325-328`,
   verified). No `HopiPerf.isAvailable` (provisional): the sample's real/noop label comes from the
   network status of stage 9.
5. **Remove** `HopiPerf.install` (`HopiPerf.kt:20-23`, verified) and the Android
   `HopiPerf.install(Application, …)` (no caller, E5 1.3). Texts that name it (all verified):
   `AndroidBackend.kt:25-26` (which also names `hopi-android`'s provider, in core since stage 4) and
   `:63`; `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/perf/PerfController.kt:201`;
   `HopiPerf.kt:49`, the KDoc of `HopiPerfConfig`, which moves to `hopi-api`; "HopiPerf not
   installed" in `hopi-core/src/commonMain/kotlin/dev/koga/hopi/core/bridge/BridgeSession.kt:838`,
   `hopi-connect/src/main/kotlin/dev/koga/hopi/connect/PerfClient.kt:106` and
   `hopi-desktop/src/main/kotlin/dev/koga/hopi/desktop/PerfPanel.kt:125` (E5 1.3).
6. **Tests**: `hopi-perf`'s tests start the perf tool in `@BeforeTest` (`HopiPerfTest` the JVM
   `PerfEntry` of step 2) instead of `HopiPerf.install`
   (`hopi-perf/src/jvmTest/kotlin/dev/koga/hopi/perf/HopiPerfTest.kt:38,96,116,129,168`;
   `hopi-perf/src/iosTest/kotlin/dev/koga/hopi/perf/internal/IosHangTest.kt:30`;
   `IosSamplingTest.kt:115`) and instead of the self-installing `start` (`HopiPerfTest.kt:46,70,84`;
   `IosSamplingTest.kt:76,99,124`) (verified). `HopiPerfNoopTest` moves to `hopi-api`'s jvmTest.
   `DefaultsParityTest` is replaced by a `hopi-perf` jvmTest that compares `hopi-api`'s literal
   defaults with `PerfLimits` and `HopiMonitor`'s order with `PerfMonitor`'s (E5.2). New (E5.4): no
   monitor class is loaded after the tool starts.
7. **Plugin**: PLG:54-56 add `hopi-perf` to debuggable build types only; `PERF_NOOP` (:165) and the
   perf pair of :166 go; KDoc :19-20 (the `selectTwins` bullet names `hopi-perf`) and :21-22
   (verified). `HopiPerfExtension.kt:9-14` (verified) loses "`hopi-perf-noop` in the others". The
   DSL gains `remoteControl` (step 1), so the generated `PerfEntry` constructor and its lockstep
   sites of stage 4 change with it.
8. **Sample**: `sample/shared/build.gradle.kts:26-28`, `:85-86` and `:98-99` (verified) go: `HopiPerf`
   comes from the exported `hopi-api`, and `hopi-start` names the perf tool, so Kotlin/Native keeps
   `hopi-perf` (cross-check problem 6). The iOS check's KDoc (`:131-134`, verified) stops expecting
   the noop's `HopiPerf`; `hopi-api`'s `kfun:dev.koga.hopi.perf.HopiPerf…` stays allowed (family 6
   matches only `.perf.internal` and `.core.perf`, `:163`, verified).
9. **Docs**: E8 rows R5 (93-94), R22, R23; A4 (50-51), A19 (sets), A52 to A55, A63; M3; PM1 to
   PM5, PM7, PM9, PM10 ("As built" untouched); `docs/ARCHITECTURE.md:1056-1058` (verified) names
   `HopiPerf.install` and a 120 KB model allocated at install, both stale (E5.4).

Same commit:

- Delete `hopi-perf-noop/` (its `DefaultsParityTest`, `HopiPerfNoopTest`, `realPerfJvm`, E5 3.5) and
  its dumps; regenerate `hopi-perf/api` (the consumer API leaves it) and `hopi-api/api`.
- `settings.gradle.kts:47`; `build.gradle.kts:115` (the perf pair) and `:255`; comment `:239-242`
  (verified).
- PLG (step 7).
- FT: `:157` drops `hopi-perf-noop`; `:245-250` drops the perf pair (debug has the perf marker,
  release none); KDoc `:142-147`; the published `hopi-perf` stand-in's `PerfEntry` constructor
  follows step 7 (verified lines).
- `sample/androidApp/build.gradle.kts:125` drops `:hopi-perf-noop` (verified).
- `README.md:560-564` and section 7: drop `:hopi-perf-noop:jvmTest`.

Verify: base check incl. `:hopi-perf:jvmTest :hopi-perf:testAndroidHostTest`. [run]: `--es perfVerify
all` with `verify.py`; `-verifyPerf` (21 of 21); E5 X3 and E5 X4 again; **E5 X5** (off is free):
Android `./gradlew :sample:androidApp:assembleDebug -Psample.hopiCapture=false
-Psample.applicationIdSuffix=.noop`, installed beside the normal debug build, then `python3
benchmark/perf/idle.py --seconds 60 --settle 10 --arms noop=none@dev.koga.hopi.sample.noop,off=none,all=all`;
iOS `python3 benchmark/perf/ios_idle.py --arms off=none,all=all --idle 60 --settle 10 --blocks 2` with
the Debug build and with a `HOPI_ENABLED=false` Debug build. `off` equals the no-runtime arm.

Done when: no `HopiPerf.install` outside dated history; perf works on both platforms through its
tool; off is free on both.

### Stage 9. Network runtime `hopi-network` and release guards

Goal: one network runtime merges `hopi-android` and `hopi-urlsession` (and core's URLSession library),
with D8, the renamed release checks and both build-time guards (D9).

First ([run]):

1. **E2.8 step 1** (dry runs): Xcode's `CONFIGURATION` (Debug, Release, a custom one) mapped to the
   link task `embedAndSignAppleFrameworkForXcode` selects, and one dependency set for both build
   types under `-Phopi.enabled=true` (commands in `E2.md`, "Experiments"). Another mapping changes
   the guard's hook.
2. **E2.8 step 2** (XCFramework): the probe's `-m :e2probe:assembleE2ProbeXCFramework` lists both
   links: one invocation links both build types.
3. **E2.3** (dead-code elimination, runs 1 to 4 in `E2.md`): whether unreferenced Kotlin and native
   archive members of a dependency land in a static, dynamic, Debug or Release framework. It decides
   whether `nm` on the framework can see a runtime that was only on the classpath; the guard is
   required either way. Run 2's `src/ref` (`E2.md:279`) imports `dev.koga.hopi.perf.HopiPerf`, which
   lives in `hopi-api` since stage 8 and only delegates. The probe depends on `:hopi-perf` alone
   (`E2.md:269`), whose edge to core, and so to `hopi-api`, is `implementation`
   (`hopi-perf/build.gradle.kts:66`, verified): the import does not resolve unless stage 8 gave
   `hopi-perf` an `api` edge to `hopi-api`, and even then run 2 would reference no runtime code. So
   at this stage `src/ref` references the runtime itself, the iOS perf tool of stage 6
   (`dev.koga.hopi.perf.internal.PerfEntry`), under
   `@file:OptIn(dev.koga.hopi.api.HopiInternalApi::class)`; the probe adds
   `implementation(project(":hopi-api"))` when `hopi-perf` does not export it; the perf Kotlin count
   (`E2.md:312`) uses `_k[a-z]+:dev\.koga\.hopi\.perf\.internal`, which `hopi-api`'s own `HopiPerf`
   does not match.
4. **E5 X1** (optional; writes `~/.m2`): `./gradlew publishToMavenLocal`, then a throwaway project
   outside the repository with `hopi-perf` from Maven Local (commands in `E5.md` 5): what an
   unreferenced Maven runtime costs a Debug framework.

Steps (in this order):

1. **Split the hit counter first** (E2.6, cross-check problem 10): core keeps a small native library,
   `hopi_hits.{h,m}` with its own cinterop (`hits.def`) and compile and archive tasks: `HopiUSHits`,
   its claim and the four C functions (`hopi-core/src/nativeInterop/objc/hopi_urlsession.m:193-216`,
   `:1846-1856`, `hopi_urlsession.h:96-111`, verified; E2.6), renamed `hopi_hits_*` so that
   `_hopi_us_` means capture only (provisional). `hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/rules/HitCounter.ios.kt:3-8`
   (verified) changes its imports; `NativeHitCounter.handle` (`:24-26`, verified), `RuleHits.counter`
   and `RuleEngine.republishAfterExhaustion` become `@HopiInternalApi` public (E2.6). The moved rule
   code calls the counter through the C functions (E2.6 step 2).
2. **`hopi-network/`**: `android`, `iosArm64`, `iosSimulatorArm64` (jvm in stage 10); ABI validation
   with the `internal.**` filter; `description` and publishing. Android: everything of `hopi-android`
   (hooks, interceptor, wrappers, recorders, the network tool, `consumer-rules.pro`), packages kept
   (E3 2d). iOS: `UrlSessionCapture.kt`, `UrlSessionRules.kt` and the rest of `hopi_urlsession.{h,m}`
   from core, with the `urlsession` cinterop (package renamed, for example
   `dev.koga.hopi.network.internal.urlsession`) and `hopi-core/build.gradle.kts:33-41`, `:60-96`,
   `:98-113` (verified: the cinterop, `urlSessionMinIos`, `urlSessionShim()`, `checkUrlSessionMinIos`
   and its `check` wiring); core's hits library uses the same `15.0` (provisional). The iOS network
   tool of stage 6 moves here with `UrlSessionCapture.kt`. If E7 X1 found `checkKotlinAbi` outside
   `check`, the module's `check` depends on it (stage 3).
3. **Test edges move** (cross-check problem 9, item 7): `hopi-android/build.gradle.kts:45-48`,
   `:58-70` and `:74-105` (verified: host-test options, `hopi-instrument`, `hopi-ktor` (kept, D2'),
   the Ktor engines, the OkHttp jar sets) and the tests; `hopi-urlsession/build.gradle.kts:34-44`,
   `:54-57`, `:61-97` (verified: the fixtures cinterop, Darwin and `hopi-ktor`, `-Phopi.simulator`)
   and the tests, with the iOS tests of the moved library. The build comment of
   `benchmark/urlsession/ab.m:6-10` (verified) follows the moved `.m` paths.
4. **D8**: `HopiAndroid` becomes internal. The sample reads a `@HopiInternalApi` network status in
   the registry instead (started, `okHttpInstrumented`), written by the Android network tool and
   absent without a runtime (E3.7 option a; provisional: option b, a `src/debug` reader, would not
   compile in the `-Psample.hopiCapture=false` arm, which has no runtime). `MainActivity.kt:100`,
   `:152`, `:169`, `:260` (verified) keep their output byte for byte (`capture: started=…
   okHttpInstrumented=…` and the `verify` done line), so the readers
   (`benchmark/android-capture/run.py:118,208-211`, `benchmark/android-capture/e2e/check.py:109-110,226-229`,
   `benchmark/android-capture/e2e/drive.py:73`, `benchmark/android-capture/idle.py:48`, verified;
   `benchmark/urlsession/e2e/` has a `check.py` and a `drive.py` of its own) keep working.
5. **Plugin** (D9 build time, E3.1): `RUNTIME` (PLG:162) becomes `hopi-network`; :53 adds nothing to
   other build types; `RUNTIME_NOOP` (:163) goes (verified). The guard: a task per non-debuggable
   variant whose input is `variant.runtimeConfiguration.incoming.resolutionResult.rootComponent`
   (the sample's pattern, `sample/androidApp/build.gradle.kts:279-292,326-327`, verified), resolved
   inside the task (the configuration-cache reuse assertion, FT:98-101, verified), failing on the
   projects or `dev.koga.hopi:` coordinates `hopi-core`, `hopi-network`, `hopi-perf`, `hopi-start`;
   `hopi-api` and `hopi-ktor` allowed (P1); wired before the variant's build (AGP task name to
   verify). DSL (E3.3): `network { enabled; okhttp; urlConnection; rules; excludeClasses }`, store and
   bridge settings at the top; `enabled = false` (`HopiExtension.kt:14`, verified) now means "no
   variant gets anything"; `network { enabled = false }` turns off the transform and the network tool,
   while core's provider still starts perf (P4); generation split from instrumentation (E3 2f). The
   plugin's description (`gradle-plugin/hopi-gradle-plugin/build.gradle.kts:30`, verified).
6. **Android release check** (E3.6): `verifyReleaseUsesNoop` (`sample/androidApp/build.gradle.kts:301-304`)
   and `verifyHopiSelection` (`:308-311`, the one `check` runs at `:348`) (verified) become one
   `verifyReleaseHasNoRuntime`: the release set equals {`:hopi-api`, `:hopi-ktor-noop`} until stage 10;
   `HopiGraph.projectsIn` (`:287`) also reports `dev.koga.hopi` module components; the APK's Hopi
   classes equal those jars' classes (unminified); forbidden packages (`:180`) become
   `dev/koga/hopi/core/`, `dev/koga/hopi/android/`, `dev/koga/hopi/perf/internal/`,
   `dev/koga/hopi/ui/`, `dev/koga/hopi/network/`, `dev/koga/hopi/urlsession/`,
   `dev/koga/hopi/start/`, with no exemption (P2); the manifest declares no `dev.koga.hopi.`
   component; hook callers, negative controls and perf APIs unchanged.
7. **iOS release check** (E2.7): `VerifyIosCaptureSelection` (`sample/shared/build.gradle.kts:126-195`,
   verified) keeps families 1 to 3, 5, 7, 8 and 9 (`:158-170`); families 4 and 6 become one pattern
   over every symbol kind, `_k[a-z]+:dev\.koga\.hopi\.(core|network|urlsession|android|ui|perf\.internal|start)[.#]`;
   it adds `hopi-perf-samp` (UTF-16LE), no `SharedHopi` or `SharedSampleCaptureProbe` in the Release
   header, no `_OBJC_CLASS_$_SharedHopi` (exact name), no `Hopi_core…` and no registry type in the
   header; the positive control also requires `_hopi_hits_`. Allowed: `hopi-api`'s symbols and
   exported classes, and `dev.koga.hopi.ktor` (P1).
8. **iOS release guard** (E2.8): every `KotlinNativeLink` with a Release build type depends on a check
   that reads the link's resolved components lazily and fails on `:hopi-core`, `:hopi-network`,
   `:hopi-perf`, `:hopi-start` or their `dev.koga.hopi:` coordinates, naming the link, the runtime and
   the fix; exempt only when a requested task name ends with `verifyIosReleaseExcludesCapture`;
   configuration-cache safe; in a script plugin that `sample/shared` applies
   (`gradle/ios-release-guard.gradle.kts`, provisional).
9. **`hopi-start`** gains `implementation(project(":hopi-network"))` in `commonMain`: the iOS
   network tool lives there now (step 2).
10. **Docs**: E8 rows R5, R6, R9 to R15, R21, R26, R29, R33; A4 (44-47), A5 (63-67), A8, A10, A11,
    A16 to A21, A28, A29, A34 to A36, A40, A49, A50, A53, A60, A62 to A65, A67, A68, A74, A80; M6,
    M8, M9, M12, M15; AC2, AC3, AC9, AC12 to AC15, AC17; US2, US6 to US9; RU12, RU19, RU22; F13 (the
    heading cited at `sample/shared/build.gradle.kts:14` and `sample/iosApp/project.yml:70`,
    verified); `docs/design/urlsession-capture.md:350-352` (verified) is stale (E2.3).

Same commit. The seven items of cross-check problem 9 first:

1. `gradle-plugin/hopi-gradle-plugin/build.gradle.kts:116-118` points at
   `../../hopi-network/consumer-rules.pro`, which FT:431 copies (verified); a missing input fails
   `functionalTest`, hence `check` (`:124`; root `:143`).
2. FT stand-ins follow `RUNTIME` (PLG:162, the plugin picks the project by name, PLG:134-135):
   `hopi-android` becomes `hopi-network` at `:26-28` and `:142-146` (KDoc, which also drop
   `hopi-android-noop`), `:183` and `:424-479`. `hopi-android-noop` goes everywhere: `:157` drops it
   and renames `hopi-android` to `hopi-network`; the `:245` pair `"hopi-android" to
   "hopi-android-noop"` becomes a debug-only check (debug has the `hopi-network` marker, release has
   none), as in stage 8, because without `RUNTIME_NOOP` the release APK gets no runtime and the
   loop's `release has $noop` (`:248`) would fail; `:285` drops it together with its stand-in
   `:480-481` and renames `hopi-android`. `:86` and `:134` invert (release and qa define no runtime
   class) (verified). New tests: the guard (E3.6-c's three fixtures) and `network { enabled = false }`
   (E3.5).
3. The packages `dev.koga.hopi.android.internal` and `.generated` are kept, so `HookDescriptors.kt`,
   `HopiRewriter.kt` and `sample/androidApp/build.gradle.kts:180`, `:183`, `:239`, `:271` (verified at
   the sample's lines) keep their names.
4. `MainActivity.kt:12`, `:100`, `:152`, `:169`, `:260` (D8, step 4).
5. Every type the generated class names is on the debuggable compile classpath: `hopi-core`, added by
   the plugin since stage 4, and `hopi-network` (`RUNTIME`); javac compiles the class inside the app
   (`GenerateHopiConfig.kt:58`, verified).
6. `verifyReleaseUsesNoop` and `verifyHopiSelection` become one task:
   `sample/androidApp/build.gradle.kts:301-311`, `:319-336`, `:347-348` (verified); `README.md:542-544`
   (verified) names it.
7. The test edges of step 3.

Then problem 10 and rule 5:

- Step 1's split, and `build.gradle.kts:259` becomes `hopi-core` to `oslog` and `hits`,
  `hopi-network` to `urlsession`, `hopi-perf` to `perf`; otherwise `checkPublication` expects a core
  `-cinterop-urlsession.klib` (`expectedPublication`, `:277-289`) (verified).
- Delete `hopi-android/`, `hopi-android-noop/`, `hopi-urlsession/` (its jvm target goes with it, E7
  2b) and `hopi-urlsession-noop/` (`realUrlSessionJvm`, `HopiUrlSessionNoopTest`), with their four
  `api/` directories; create `hopi-network/api/` (android, klib).
- `settings.gradle.kts:40-43` go, `hopi-network` joins; `build.gradle.kts:112-113` (two pairs) and
  `:250-251`, `:256-257` go, `"hopi-network" to listOf("android", "iosArm64", "iosSimulatorArm64")`
  joins; the comment `:239-242` (`hopi-android(-noop)`) (verified).
- PLG:162-163 (step 5); KDoc :15 (`hopi-android` and "its noop twin") (verified).
- `sample/androidApp/build.gradle.kts:125` drops `:hopi-android-noop`; `:228`: `:hopi-android`
  becomes `:hopi-network`; the comment `:67-71` (verified).
- `sample/shared/build.gradle.kts:20-21`, `:80-83` and `:119` (`urlSessionModule`) go (verified).
- `README.md:560-564` and section 7: drop `:hopi-urlsession:jvmTest :hopi-urlsession-noop:jvmTest
  :hopi-android:testAndroidHostTest`, add `:hopi-network:testAndroidHostTest`, rename
  `:sample:androidApp:verifyReleaseUsesNoop` to `:sample:androidApp:verifyReleaseHasNoRuntime`, list
  `:sample:shared:verifyIosReleaseExcludesCapture`.

Verify: base check; `:hopi-network:testAndroidHostTest`; the iOS tests of `hopi-network` and
`hopi-core` (in `check`). [run]: **E3.6-c**: `releaseImplementation(project(":hopi-network"))` in
the sample, `assembleRelease` fails with the guard's message before any dex task, `assembleDebug`
passes, revert; the functional test (`--tests '*NonDebuggable*'`). **E3.6-d**: on a scratch branch
with the guard unwired, install that release APK, `adb logcat -c`, launch, `adb logcat -d -s Hopi`:
one "not debuggable" notice, no "android capture started". **E2.8 step 4**: the guard fails
`linkReleaseFrameworkIosSimulatorArm64 -Phopi.enabled=true` before linking; the positive control
passes; the plain check passes with the new list; the probe's Release XCFramework fails with the
guard applied; the Xcode Release app's `nm` finds no runtime symbol; the `HOPI_ENABLED=false` Debug
build compiles (commands in `E2.md`). **E5 X2** after: the guard names `hopi-perf`. **E1 R7** (a
giant-body benchmark in `hopi-network`'s Android host tests, 1 to 50 MB, caps 256 KiB to 16 MiB;
`gradle/giant-body-benchmark.gradle.kts` learns `testAndroidHostTest`) and **E1 R8**
(`benchmark/urlsession/e2e/ab.sh` with `-maxBodyBytes 8388608 -maxTotalBytes 33554432`): never
measured past 1 MiB. The sample on both platforms (`-thirdPartySDK`, `capture-swift-test.sh`), both
capture A/Bs.

Done when: the four modules are gone; one network runtime records on both platforms; the release
checks pass under their new names; both guards fail their negative cases.

### Stage 10. Thin Ktor plugin (P1)

Goal: `hopi-ktor` links into every build with no recording logic and no core; the recorder is an
adapter in `hopi-network`; `hopi-ktor-noop` goes; JVM apps start with `Hopi.start`.

First ([run]) (E1 4.4 and 5; the memo: R1 to R5 first):

1. **compileOnly in a shared android and jvm source set** (P1): a scratch `hopi-network` source set
   compiled against `ktor-client-core` and `hopi-ktor` as `compileOnly`. Fails: the same sources go
   into both `androidMain` and `jvmMain` (P1's fallback).
2. **R1** (host): with a test that installs no plugin, Ktor's OkHttp engine and the JDK connection
   with `ContentEncoding` record the server's gzip bytes (`1f 8b`, `Content-Encoding: gzip`);
   `./gradlew :hopi-network:testAndroidHostTest --tests 'dev.koga.hopi.android.AndroidCaptureTest'`.
   Confirmed: the plugin keeps recording the requests that name their own `Accept-Encoding` (D2').
   Decoded: that engine needs no pass-through.
3. **R2** (device): Android's own `HttpURLConnection` decodes and drops `Content-Encoding` only when it
   asked for gzip itself (E1 4.4 commands). A gzip body without the header breaks `heldCodings`.
4. **R3** (simulator): URLSession hands over decoded bodies when the request names its own
   `Accept-Encoding`, plain and through Ktor Darwin with `ContentEncoding` (add
   `libs.ktor.client.encoding` to the iOS tests); `./gradlew :hopi-network:iosSimulatorArm64Test
   --tests '*UrlSessionCaptureTest*'`. `1f 8b` in the record: this stage adds decoding for
   `urlsession` records (P1).
5. **R4** (host): rule semantics on Ktor's OkHttp and Android engines without the plugin: a mocked 302
   followed by `HttpRedirect` (confirms stage 2's wording), a 401 under `Auth`, each fail's exception
   class, `HttpTimeout` against a delay, a body edit under `ContentEncoding`, a 500 under
   `expectSuccess`; the scenarios in `hopi-network/src/androidHostTest/kotlin/hopitest/RuleScenarios.kt`,
   `./gradlew :hopi-network:testAndroidHostTest --tests 'dev.koga.hopi.android.AndroidRulesTest'`.
6. **R5** and **E4 X4** (`HttpCache`, Android and iOS): a cacheable platform mock, the rule removed,
   a second request. The mock body again: core's rules add `Cache-Control: no-store` to platform
   mocks and edits (P1); the real body: nothing to add. Android: the scenario in the same
   `RuleScenarios.kt`, the same command as R4. iOS: `HttpClient(Darwin) { install(HttpCache) }` in
   `UrlSessionRulesTest`, `./gradlew :hopi-network:iosSimulatorArm64Test --tests '*UrlSessionRulesTest*'`.
   (The E files' commands name `:hopi-android` and `:hopi-urlsession`, deleted in stage 9.)

Steps:

1. **`hopi-ktor`** (published; four targets): `hopi-ktor/build.gradle.kts:38` (verified)
   `implementation(project(":hopi-core"))` becomes `implementation(project(":hopi-api"))`; `api` on
   `ktor-client-core` stays (`:37`). It holds the `Hopi` plugin, `HopiConfig` (per-client settings),
   `HopiLogSink` and `@HopiInternalApi interface HopiKtorHooks` (P1) with Ktor types. At install the
   plugin reads the registry's Ktor slot once: hooks registered, it hands them the plugin builder;
   none, it installs nothing (release: no interceptor).
2. **Adapter**: `Exchange.kt`, `RequestCapture.kt`, `BodyCapture.kt`, `TeedBody.kt`, `Rules.kt`,
   `Rules.android.kt`, `Rules.jvm.kt` and the `Recorder` of `Hopi.kt` (from `:72`) (verified) move to
   `hopi-network`, into the shared android and jvm source set of "First" item 1; `Rules.ios.kt` goes.
   The network tool writes the slot only when the plugin class loads. `hopi-network` gains a `jvm`
   target holding only this adapter. The `HttpCache` guard
   (`hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/Rules.kt:232-240`, verified) stays in the
   adapter for the plugin's own responses. The Android defer and pass-through stay (D2').
   `hopi-network/consumer-rules.pro` (moved in stage 9) gains `-dontwarn io.ktor.**` and
   `-dontwarn dev.koga.hopi.ktor.**`, as `hopi-android/consumer-rules.pro:9-10` does for the
   `compileOnly` OkHttp (`hopi-android/build.gradle.kts:56`) (verified): R8 meets the adapter's
   references to missing classes in a minified debuggable variant of an app without Ktor (that R8
   fails on them is to verify). Plus a `-keep` for the class the network tool probes by name, or the
   tool references that class directly inside `try`/`catch (NoClassDefFoundError)`, so R8 cannot
   rename what a string names. No check covers this: the functional test's minified debuggable app
   (FT:349) uses stand-ins, and the sample's debug build is not minified (verified).
3. **iOS**: no adapter; `install(Hopi)` is inert; URLSession capture records the Darwin engine as
   today (the plugin defers to it with `cover`, `UrlSessionCapture.kt:143`, verified). That `cover`
   has no reader on iOS any more (the adapter is android and jvm only): it goes, with its constant
   and comment (E1 1.4: `:94-95`, `:139-142`) (provisional). R3's result decides decoding. The
   plugin no longer records on iOS, so
   `hopi-core/src/iosMain/kotlin/dev/koga/hopi/core/rules/PlatformSources.ios.kt:3-4` (verified:
   `KTOR` and `URL_SESSION`) drops `KTOR`, and the KDoc `Capabilities.kt:67-73` (verified) follows;
   a no-source response edit then reads inactive on iOS, and the caveat of
   `docs/design/rules.md:853-856` (verified) goes (E4 2.4, E8 F12). Document that a Ktor engine
   other than Darwin (CIO) is not recorded on iOS.
4. **JVM entry** (P3): `hopi-start` gains `jvm`; `Hopi.start { }` runs the start sequence with the
   network tool (the Ktor adapter) and, with `perf` (default true), the JVM perf tool of stage 8,
   step 2 (CPU and memory only) (provisional). Document: a client created before the start
   sequence ran stays unrecorded (Android starts in the provider; iOS and JVM call `Hopi.start`
   first).
5. **Tests**: hopi-ktor's jvmTest files (`HopiPluginTest`, `RulesTest`, `TeedBodyTest`,
   `BoundedBufferTest`, `PlatformCaptureDeferTest`, `TestSupport`, `HopiOverheadBenchmark`,
   `KtorGiantBodyBenchmark`, verified) move to `hopi-network`'s jvmTest and build the adapter on a
   test-owned store (today `HopiConfig.store`, E1). `hopi-ktor` keeps a jvmTest: with no hooks the
   plugin installs nothing, and no core class is loadable. `hopi-network` applies
   `gradle/giant-body-benchmark.gradle.kts` (as `hopi-ktor/build.gradle.kts:54`), and its jvmTest
   takes `hopi-ktor`'s jvmTest dependencies (`:40-48`) and the `benchmark/fixtures` source directory
   (`:50`), which `KtorGiantBodyBenchmark` imports (`dev.koga.hopi.bench.*`) (verified).
   - The Android host scenarios that install the plugin (moved in stage 9, step 3): the helper
     `hopi(store, decode)` of `hopi-network/src/androidHostTest/kotlin/hopitest/Scenarios.kt`
     (`:463-475` at `e1ef40a`) sets the store through `HopiConfig`'s internal setter by reflection
     (`setStore$`, `:472`), which this stage removes, and the thin plugin installs nothing without
     hooks. Replace that
     call with the adapter's test seam: register the adapter's hooks, bound to the scenario's store,
     in the registry's Ktor slot before `install(Hopi)`, and reset the slot after each scenario.
     `AndroidCaptureTest.kt:477-495` keeps its assertions: one `ktor` record, decoded, no
     pass-through header on the wire. The iOS tests that install the plugin
     (`KtorDarwinTest.kt:33-35`, `UrlSessionRulesTest.kt:604-614`) assert `URL_SESSION` records and
     pass with an inert plugin (all verified).
   - `hopi-cli`'s `CliBridgeTest` keeps `install(Hopi)` and `MockEngine` and calls the JVM
     `Hopi.start` first: `hopi-cli/build.gradle.kts:41` keeps `testImplementation(project(":hopi-ktor"))`
     (the test imports `dev.koga.hopi.ktor.Hopi` and `HopiLogSink`, `CliBridgeTest.kt:9-10`, and
     `hopi-start` does not bring `hopi-ktor`, which `hopi-network` takes as `compileOnly`) and gains
     `testImplementation(project(":hopi-start"))` (provisional; E1.2's store fixture is the
     alternative). The plugin reads the slot once, at install, and `client` is a companion-object
     `val` (`:401-421`), built when the class initializes, before any instance or `@BeforeTest`: the
     companion object calls the entry's `start { agentBridge = false }` before it builds `client`
     (the test starts its own bridge, `:57`), or `client` becomes `by lazy`, first used after the
     start (verified). The entry and the plugin are both named `Hopi`, so the file imports one under
     an alias, for example `import dev.koga.hopi.start.Hopi as HopiStart`.
6. **Sample**: `sample/shared/build.gradle.kts:94` (verified) becomes
   `implementation(project(":hopi-ktor"))` in every build; `SampleHttp.kt:68-73` keeps
   `install(Hopi)`; its `start()` KDoc (`:79-82`, verified) changes: the bridge is up from the start
   sequence.
7. **Docs**: README install (the optional Ktor plugin, per-client settings, the client-before-start
   consequence, the JVM entry, whose snippet imports one of the two `Hopi` names under an alias as
   in step 5), ARCHITECTURE's `hopi-ktor` sections, `docs/design/rules.md` (the Ktor
   rows), the defer passages where P1 changes them. E8's rows for the old stage 3 (removing the
   plugin) are reviewed under D2': the deletion rows are void; a passage that describes the plugin
   correctly stays.

Same commit:

- Delete `hopi-ktor-noop/` (`DefaultsParityTest`, `HopiNoopTest`, `realKtorJvm` at
  `hopi-ktor-noop/build.gradle.kts:43-65`, verified) and its dumps; regenerate `hopi-ktor/api`,
  `hopi-network/api` (with `jvm`) and `hopi-start/api` (with `jvm`).
- `settings.gradle.kts:37`; `build.gradle.kts:110` (the last pair; `noopTwins` is empty) and `:247`;
  the comment `:239-242` (the ktor twins) (verified); in `publishedModules`, `hopi-network` becomes
  `allTargets` and `hopi-start` adds `jvm`.
- PLG:166: the ktor pair goes, `TWINS` is empty (the code goes in stage 11); KDoc :19-20 (the
  `selectTwins` bullet names `hopi-ktor`) (verified).
- `hopi-network/consumer-rules.pro` (step 2).
- FT: `:157` drops `hopi-ktor-noop` and keeps `hopi-ktor`; `:227` becomes
  `api("dev.koga.hopi:hopi-ktor:$version")`; `:245-250` asserts the `hopi-api` and `hopi-ktor` markers
  in debug and release and no runtime marker in release; KDoc `:142-147`, `:255` (verified).
- `sample/androidApp/build.gradle.kts:125`: `:hopi-ktor-noop` becomes `:hopi-ktor`; `:228` keeps
  `:hopi-ktor` (verified).
- `README.md:578` (verified): `:hopi-ktor:jvmGiantBodyBenchmark` becomes
  `:hopi-network:jvmGiantBodyBenchmark`. `README.md:560-564` and section 7: drop
  `:hopi-ktor-noop:jvmTest`, add `:hopi-network:jvmTest`.
- `gradle/libs.versions.toml:41` (`ktor-client-mock`, verified) stays (the moved tests and
  `CliBridgeTest` use it).

Verify: base check incl. `:hopi-ktor:jvmTest :hopi-network:jvmTest`; the release set is exactly
{`:hopi-api`, `:hopi-ktor`}. [run]: **E1 R6** (devices): add `mock-302` to the sample's rules
self-test, `--es rulesVerify all` and `-verifyRules`: `ktor-okhttp` and `ktor-darwin` see 200 after
the 302, `sdk-okhttp` and `sdk-urlconnection` see 302; record the fail exception types. The Android
capture A/B: no per-request regression. `HopiOverheadBenchmark` in `:hopi-network:jvmTest`: the
plugin's cost through the hooks.

Done when: `hopi-ktor` names no core type; a release client installs no interceptor; the release APK
holds only `hopi-api` and `hopi-ktor` classes; a JVM app records through `Hopi.start`.

### Stage 11. Remove the remaining twin machinery

Goal: no parity check, no substitution, and no `noop`, `twin` or `parity` outside dated history.

First ([run]): count the hits of step 7's grep before the edits. (If E7 X1 found `checkKotlinAbi`
outside `check`, stage 3 already wired it into every ABI-validated module.)

Steps:

1. **Root build**: `build.gradle.kts:24-136` (the class, the `inline`/`const` rule, `noopTwins`, the
   registrations, `checkNoopParity`) and `:139` go; the comments `:2` and `:239-242` are reworded;
   `base`, `:138`, `:140-143` and `:146-329` stay (root `check` also carries the included-build
   checks and `checkPublication`) (verified).
2. **Plugin**: `selectTwins` (`HopiExtension.kt:23-27`, default `:79`), its call site PLG:69-72,
   `selectTwins()` PLG:113-131 and `TWINS` PLG:166 go; KDoc PLG:12-25 (verified). FT: the test
   `publishedCoordinatesAreSwappedPerVariant` swaps nothing any more: rename it (provisional
   `publishedCoordinatesArePerVariant`) and reword `:20-31` and `:141-148` (verified).
3. **Benchmarks** (cross-check problem 13, E7 4): in the 16 live scripts (`ios_hold.sh` went with
   the in-app inspector in stage 5)
   (`benchmark/android-capture/{run.py,abtable.py,e2e/check.py,e2e/run.sh}`,
   `benchmark/device/scripts/{ios-ab.sh,ios-matrix.sh,ios.py,matrix.sh,matrix2.sh,paired.py}`,
   `benchmark/perf/{bench,idle}.py`, `benchmark/urlsession/e2e/{ab.sh,abtable.py,check.py,run.sh}`,
   verified) the noop arm becomes the no-runtime arm, label `nohopi` and install suffix `.nohopi`
   (provisional: `off` is taken, it means "runtime linked, nothing started" in
   `benchmark/perf/idle.py:12` and `bench.py:16`, verified). "Twin" for the other platform's script
   is reworded. The status-line readers (`benchmark/android-capture/run.py:118,208-211`,
   `benchmark/android-capture/e2e/check.py:109-110,226-229`, `benchmark/android-capture/e2e/drive.py:73`,
   `benchmark/android-capture/idle.py:48`, verified) follow the arm names. The iOS
   `HOPI_ENABLED=false` arm (`benchmark/urlsession/e2e/run.sh`, `ab.sh`, `abtable.py`, `check.py`),
   which compiles since stage 6, is relabelled the same way.
4. **Bytecode**: delete all 7 tracked `__pycache__/*.pyc`. 4 of them hold a term (`abtable`, `run`,
   `e2e/check`, `perf/idle`), which an `-E` alternation does not find: with git 2.47.1,
   `git grep -l -i -E 'noop|twin|parity'` lists none of them, while `git grep -l -i -e noop -e twin
   -e parity` and `-E 'noop'` alone list the 4 (verified). Add `__pycache__/` to `.gitignore`
   (absent today, verified).
5. **Sample texts**: `sample/androidApp/build.gradle.kts:55-56`, `:158`, `:229` (the `-noop` clause),
   `:299-311` comments; `sample/shared/build.gradle.kts:14-17`, `:48` (verified).
6. **O7 as applied** (section 10's O7: the modules a consumer compiles against; the runtimes keep
   what they have): ABI validation on `hopi-api`, `hopi-ktor` and `hopi-start`; the runtimes
   `hopi-network` and `hopi-perf` keep theirs, with their `internal.**` filters; `hopi-connect` as
   today. `hopi-core` stays exempt, as it has always been (about 830 public declarations, consumed
   only by Hopi modules of the same version, E7 3).
7. **Grep gate** (rule 8): `git grep -n -i -e noop -e twin -e parity` with rule 8's exclusions (one
   `-e` per term, step 4); each hit is removed or sits under a dated history note (E8 F4).
8. **Docs**, ARCHITECTURE first: E8 rows R2, R3, R31, R32; A5 (68-69), A6 (if stage 2 left it), A11
   (158), A13 (the rest), A14, A18 (the substitution text), A35 (any substitution text left), A70
   (2030); M17 (266-267); AC4, AC14 (`selectTwins`), AC16. F4 (the grep gate) is rule 8.

Same commit: `README.md:555-557` (the parity clause, verified); `README.md:560-564` and section 7:
drop `:checkNoopParity`.

Verify: the target base check of section 7; `./gradlew check --dry-run` shows no `checkNoopParity`
and every `checkKotlinAbi`. [run]: the renamed arms run: `benchmark/android-capture/run.py e2e …`,
`benchmark/urlsession/e2e/run.sh`, `python3 benchmark/perf/idle.py --arms
nohopi=none@dev.koga.hopi.sample.nohopi,off=none,all=all`.

Done when: the grep gate is clean; the target base check is green; the benchmark scripts run with
the new labels.

### Stage 12. Physical devices (D10)

Goal: the CLI and the desktop viewer reach a debug app on an iPhone over USB, and on Android phones
over adb.

First ([run]) (none in the E files; D10 came later; on the owner's machine):

1. `xcrun devicectl list devices` with the iPhone connected; how `devicectl` copies a file out of an
   app's data container (subcommand and flags to verify). It decides how `bridge.json` is read.
2. Whether the app's bridge on a device listens on loopback and writes `bridge.json` to
   `<app data container>/Library/Caches/hopi/bridge.json` as on the simulator
   (`docs/ARCHITECTURE.md:1256`, verified; the device behaviour is to verify).
3. The `usbmuxd` protocol (to verify): the socket `/var/run/usbmuxd`, listing devices and connecting
   to a device port.

Steps:

1. `hopi-connect`: a `usbmuxd` client and a `devicectl` reader beside `Simctl.kt` and `Adb.kt`;
   `Targets.openIos` and `iosBridge` (`hopi-connect/src/main/kotlin/dev/koga/hopi/connect/Targets.kt:210-227`,
   verified, simulators only today) gain USB iPhones; the token check is unchanged.
2. Tests against a fake `usbmuxd`: a test-owned Unix-socket server in `hopi-connect`'s tests, run by
   `:hopi-connect:test`; no hardware.
3. CLI and desktop: `--device <udid>` picks a USB iPhone (`Targets.pickDevice`, `:238`, verified); the
   viewer lists USB iPhones.
4. Android hardware: no code expected; adb already covers USB and wireless devices.
5. Docs: README and ARCHITECTURE "Agent access" (targets).

Same commit: nothing is deleted or renamed.

Verify: `:hopi-connect:test :hopi-cli:test :hopi-desktop:test`. [run, owner's hardware]: an iPhone
over USB with the Debug app: `hopi list --app dev.koga.hopi.sample --device <udid>`, `hopi tail`,
`hopi perf status`, the desktop viewer; an Android phone over USB and over wireless adb: the same.

Done when: the fake-`usbmuxd` tests are green; the hardware checks are recorded in section 9, or
listed as not run with the reason.

### Stage 13. Documentation pass

Goal: the docs describe what was built.

First ([run]): none.

Steps:

1. README rewritten around the tools and the install paths: Android (the plugin only), KMP and iOS
   (`hopi-api` plus `hopi-start` under `hopi.enabled`, Swift `#if HOPI`), JVM (`hopi-ktor` plus
   `Hopi.start`). A short "What reaches a release build": Hopi leaves by not being linked, never by
   `#if DEBUG` (3.6.1); what `hopi-api` and `hopi-ktor` leave (their exported Objective-C classes on
   iOS, E2 correction 4); E2.3's static and dynamic results; Debug builds shipped to testers contain
   Hopi. E3.3's import result for `autoStart(HopiMonitor.…)`.
2. ARCHITECTURE's remaining sections; ROADMAP; the design docs' API passages; performance rows
   measured on the plugin or the noops kept with a dated note (E8 R25); the "Migration in progress"
   note goes (A1).
3. The README anchors in use stay or their links change (E8 F14).

Same commit: nothing is deleted or renamed.

Verify: rule 8's gates; each README anchor resolves.

Done when: no passage describes a twin, `HopiUrlSession`, the in-app UI or `selectTwins` as current.

### Stage 14. Final verification and report

Goal: the whole matrix of section 7 green, the new set in Maven Local, and a report to the owner.

First ([run]): none.

Steps:

1. The whole matrix of section 7, the device checks included.
2. `./gradlew publishToMavenLocal` (the root task follows `publishedModules`,
   `build.gradle.kts:321-327`, verified): `hopi-api`, `hopi-core`, `hopi-network`, `hopi-perf`,
   `hopi-ktor`, `hopi-start`, the plugin and `hopi-instrument`; list
   `~/.m2/repository/dev/koga/hopi/<module>/0.1.0/` for each.
3. Note to the owner: `~/.m2/repository/dev/koga/hopi` still holds every `*-noop 0.1.0`, the
   deleted `hopi-android`, `hopi-urlsession` and `hopi-rules`, and `hopi-ui`, no longer published
   since stage 5 (verified today). A consumer with `mavenLocal()` resolves them silently
   (PLG:134-135); deleting them is the owner's call.
4. Measure the release APK's Hopi classes again (`README.md:493`, E8 R25).
5. The report: what changed, what was measured before and after, what was not verified and why;
   the plan's status line says it was executed.

Same commit: nothing is deleted or renamed.

Verify: as step 1.

Done when: the report is with the owner.

### Later (O8). Plugin on KMP modules

Apply `dev.koga.hopi` to a KMP shared module: it adds `hopi-start` and the runtimes to the iOS Debug
build and the `hopi-api` export, so the shared module needs no `if (hopiEnabled)` block. Evaluate
reading Xcode's `CONFIGURATION` during `embedAndSignAppleFrameworkForXcode` instead of
`-Phopi.enabled` (E2.8 step 1's mapping), and map the CocoaPods configuration property (E2.8 table).
Not part of this pass.

## 7. Verification matrix

### Base check today

Kept equal to the base check of `README.md` ("Building"); a stage that adds, deletes or renames a
task path edits both (rule 5). As of stage 3:

```bash
./gradlew :hopi-api:jvmTest :hopi-core:jvmTest :hopi-ktor:jvmTest :hopi-ui:jvmTest :hopi-ktor-noop:jvmTest \
  :hopi-cli:test :hopi-connect:test :hopi-desktop:test :hopi-urlsession:jvmTest :hopi-urlsession-noop:jvmTest \
  :hopi-perf:jvmTest :hopi-perf-noop:jvmTest :hopi-perf:testAndroidHostTest :checkNoopParity check \
  :sample:androidApp:assembleDebug :sample:androidApp:verifyReleaseUsesNoop :hopi-android:testAndroidHostTest \
  :gradle-plugin:hopi-gradle-plugin:functionalTest :checkPublication
```

- One task path that no longer exists fails the whole invocation: a stage that deletes or renames
  one edits `README.md:560-564` and this section in the same commit (rule 5).
- `:checkNoopParity`, `functionalTest` and `:checkPublication` already run inside `check` (root
  `build.gradle.kts:139`, `:143` with `gradle-plugin/hopi-gradle-plugin/build.gradle.kts:124`, and
  `:329`), and so does `verifyIosReleaseExcludesCapture` on macOS
  (`sample/shared/build.gradle.kts:207-209`) (verified). `verifyReleaseUsesNoop` duplicates
  `verifyHopiSelection`, which `check` runs (`sample/androidApp/build.gradle.kts:301-311,347-348`,
  verified). Whether `testAndroidHostTest` is in `check` is unverified (E7 X2, stage 3); the explicit
  entries stay either way.

### Task paths per stage

| Stage | Leaves the base check | Joins, or is renamed |
| --- | --- | --- |
| 3 | | `:hopi-api:jvmTest` |
| 5 | `:hopi-ui-noop:jvmTest` | (`:hopi-ui:jvmTest` stays: KMP with `jvm()` only, provisional) |
| 8 | `:hopi-perf-noop:jvmTest` | |
| 9 | `:hopi-urlsession:jvmTest`, `:hopi-urlsession-noop:jvmTest`, `:hopi-android:testAndroidHostTest` | `:hopi-network:testAndroidHostTest`; `:sample:androidApp:verifyReleaseUsesNoop` renamed `:sample:androidApp:verifyReleaseHasNoRuntime`; `:sample:shared:verifyIosReleaseExcludesCapture` listed |
| 10 | `:hopi-ktor-noop:jvmTest` | `:hopi-network:jvmTest` |
| 11 | `:checkNoopParity` | |

Stages 2, 4, 6, 7 and 12 to 14 change no task path. The sample's sets that `check` asserts
(`sample/androidApp/build.gradle.kts:125` and `:228`) after each stage:

| After | Release set (`:125`, equal) | Debug set (`:228`, contains) |
| --- | --- | --- |
| today | `:hopi-ktor-noop`, `:hopi-ui-noop`, `:hopi-rules-noop`, `:hopi-android-noop`, `:hopi-perf-noop` | `:hopi-android`, `:hopi-core`, `:hopi-ktor`, `:hopi-ui`, `:hopi-rules`, `:hopi-perf` |
| 5 | minus `:hopi-ui-noop` | minus `:hopi-ui` |
| 7 | `:hopi-rules-noop` becomes `:hopi-api` | minus `:hopi-rules`, plus `:hopi-api` |
| 8 | minus `:hopi-perf-noop` | |
| 9 | minus `:hopi-android-noop` | `:hopi-android` becomes `:hopi-network` |
| 10 | `:hopi-ktor-noop` becomes `:hopi-ktor`: {`:hopi-api`, `:hopi-ktor`} | {`:hopi-api`, `:hopi-core`, `:hopi-ktor`, `:hopi-network`, `:hopi-perf`} |

### Target base check after stage 11

```bash
./gradlew :hopi-api:jvmTest :hopi-core:jvmTest :hopi-ktor:jvmTest :hopi-network:jvmTest :hopi-ui:jvmTest \
  :hopi-perf:jvmTest :hopi-perf:testAndroidHostTest :hopi-network:testAndroidHostTest \
  :hopi-cli:test :hopi-connect:test :hopi-desktop:test check \
  :sample:androidApp:assembleDebug :sample:androidApp:verifyReleaseHasNoRuntime \
  :sample:shared:verifyIosReleaseExcludesCapture :gradle-plugin:hopi-gradle-plugin:functionalTest :checkPublication
```

Published then: `hopi-api`, `hopi-core`, `hopi-network`, `hopi-perf`, `hopi-ktor` (android, jvm,
iosArm64, iosSimulatorArm64) and `hopi-start` (iosArm64, iosSimulatorArm64, jvm), plus the plugin
and `hopi-instrument`. `hopi-ui` is JVM only and unpublished. `hopi-start`'s tests run inside
`check` (provisional: no explicit entry).

### Device and simulator checks (all [run])

Android emulator, after `python3 sample/devserver/devserver.py`, `./gradlew
:sample:androidApp:installDebug` and `adb reverse tcp:8765 tcp:8765` (`README.md:507-525`,
verified):

- `adb shell am start -n dev.koga.hopi.sample/.MainActivity --es rulesVerify all`; `--es rulesTargets
  all`.
- `--es perfVerify all` with `python3 benchmark/perf/verify.py build/evidence/perf`; from stage 5,
  `hopi perf start frames --app dev.koga.hopi.sample` attaches at once (P5).
- `python3 benchmark/perf/bench.py build/evidence/perf --arms off=none,hangs=hangs,all=all` and
  `python3 benchmark/perf/idle.py --arms off=none,all=all`, with the no-runtime arm (label `nohopi`
  from stage 11).
- The capture end to end (`benchmark/android-capture/e2e/run.sh`, `check.py`) and the capture A/B
  (`benchmark/android-capture/run.py ab …`).
- The desktop viewer: `./gradlew :hopi-desktop:test --tests '*DeviceViewerUiTest*'
  -Phopi.desktop.device=android`, installed with `-Psample.maxBodyBytes=8388608` from stage 4.
- From stage 9: a release build with a runtime added by hand fails (E3.6-c).

iOS simulator, no Gradle build meanwhile (`README.md:527-544`, verified):

- The Debug build: `xcodebuild -project sample/iosApp/iosApp.xcodeproj -scheme iosApp -configuration
  Debug -destination 'platform=iOS Simulator,name=iPhone 17' build`; from stage 6 the same with
  `HOPI_ENABLED=false` compiles; from stage 9 the Release build's executable holds no runtime symbol
  (E2.8 step 4).
- `xcrun simctl launch booted dev.koga.hopi.sample -thirdPartySDK`; `-verifyRules`; `-verifyPerf` with
  `python3 benchmark/perf/verify.py build/evidence/perf --platform ios`;
  `sample/iosApp/scripts/capture-swift-test.sh 'platform=iOS Simulator,name=iPhone 17'`.
- `python3 benchmark/perf/ios_idle.py --arms off=none,all=all --idle 60 --settle 10 --blocks 2`, with
  the Debug build and a `HOPI_ENABLED=false` Debug build; the URLSession A/B
  (`benchmark/urlsession/e2e/ab.sh`, `benchmark/urlsession/ab.m`).
- The desktop viewer with `-Phopi.desktop.device=ios`.

Physical devices (stage 12, the owner's hardware): an iPhone over USB, Android phones over USB and
over wireless adb: `hopi list`, `hopi tail`, `hopi perf status` and the desktop viewer.

Gone with D7': the "Open Hopi" taps (`benchmark/device/scripts/ab.sh`, `inspector.sh`, `post.sh`,
`reopen.sh`) and the in-app idle-frame and "not drawn under" checks.

## 8. Risks

Rewritten after stage 1; stage numbers are the new section 6's.

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Hopi linked into an iOS Release framework because a runtime was on that link's classpath (`hopi.enabled` left on, one invocation linking both build types, `assembleXCFramework`): DebugSwift #202's failure mode (3.6.1) | high | The iOS release guard (stage 9, E2.8 run first); `verifyIosReleaseExcludesCapture` in `check`; later (O8) the plugin derives the choice from `CONFIGURATION` |
| A runtime reaches an Android release build through a manual dependency | high | The plugin's build-time guard and the `FLAG_DEBUGGABLE` check (D9, stages 4 and 9); the renamed release check |
| A stage leaves `check` red because a hard-coded module set, fixture pair, plugin constant or task path was not edited (each full run takes about 45 min) | high | The same-commit list of section 6; the fast gate `./gradlew check --dry-run`, which cannot see module-set failures, so the full run decides |
| A consumer with `mavenLocal()` silently resolves a deleted noop: `~/.m2/repository/dev/koga/hopi` holds every `*-noop` 0.1.0 and the plugin falls back to `dev.koga.hopi:<noop>:0.1.0` (`HopiPlugin.kt:134-135`) | medium | Each noop constant leaves the plugin in the stage that deletes its module; stage 14 publishes the new set and tells the owner the stale noops are theirs to delete |
| Ktor's `HttpCache` stores a platform mock, or URLSession hands over encoded bodies for a request that names its own `Accept-Encoding` (the thin plugin is inert on iOS) | medium | E1 R3 and R5 run first in stage 10; the fixes are named there |
| compileOnly Ktor in a source set shared by android and jvm does not build (P1) | medium | Tried first in stage 10; fallback: the same sources in androidMain and jvmMain |
| The Debug build made with `HOPI_ENABLED=false` (the A/B arm) stops compiling once Swift calls `Hopi` | medium | Swift's `#if HOPI` tied to `HOPI_ENABLED` (P3); E7 X5 in stage 6 |
| Perf loses its start on iOS, or frames on Android, between stages | medium | iOS keeps `HopiPerf.install` until the entry exists (stage 6); current-activity tracking moves into core (P5, stage 4) before the in-app UI goes (stage 5) |
| Option A links every tool into Debug frameworks, perf's native library included | low | Each tool stays off until started; E2.3 measures it in stage 9; option D, generated by the plugin, later (O8) |
| A client built before the start sequence ran is not recorded (P1) | low | Android starts in the provider, before `Application.onCreate`; the README tells iOS and JVM apps to call `Hopi.start` first |
| A forgotten iOS `start` call: Debug records nothing | low | The README's first iOS step; the sample; a Debug-only line from `hopi-api` on the first call with no runtime, if E4 X5 shows Kotlin/Native can tell Debug from Release (stages 6 and 7) |
| Coordinates change for consumers | low | 0.1.0, Maven Local only; the README states the new coordinates |
| Physical iPhone support cannot be verified without hardware | low | Stage 12 tests the host side against a fake `usbmuxd`; the owner runs the hardware check |
| Wider scope than one pass can hold | medium | Stages are independent commits; each leaves the build green; the owner can stop after any stage |

## 9. Evaluation results

Stage 1 answered every item of section 4, read-only, in `docs/design/runtime-split-evaluation/`
(one file per item, plus a cross-check; that folder's README says at which commit each was read).
Nothing was built or run: every **[run]** item is written there with its commands and sits at the
start of the stage that needs it (section 6). E1 to E6 were written before D2', D7' and D10, so their
passages on deleting the Ktor plugin and on the in-app launcher are superseded where those differ.
Stage numbers below are the new ones.

| Item | What it found that changes the plan | Lands in |
| --- | --- | --- |
| [E1](runtime-split-evaluation/E1.md) Ktor plugin, `ktor` source | Only `CliBridgeTest` installs the plugin on the JVM. Ktor's three engines turn the stack's redirects off, so Ktor's `HttpRedirect` follows a mocked 3xx: `Capabilities.NOT_FOLLOWED` and `docs/design/rules.md:178-185` are wrong for Ktor clients today. Deleting the plugin would also have lost the `HttpCache` no-store guard (`hopi-ktor/src/commonMain/kotlin/dev/koga/hopi/ktor/Rules.kt:232-240`), the recording of Ktor's OkHttp engine when OkHttp is not instrumented, and every platform source on the JVM. Several appendix A lines corrected. | the fix commit before stage 2; stage 10 (P1; R1 to R5 first) |
| [E2](runtime-split-evaluation/E2.md) iOS capture and entry | D is feasible; A is kept (P3). A non-exported type in an exported signature is exported with its members, transitively, so the entry declares its own configuration class. Swift needs a condition tied to `HOPI_ENABLED`, not `DEBUG`. Swift's `HopiRules` needs `hopi-api` as `api` plus `export`, and the registry hidden with `@HiddenFromObjC`. Moving URLSession capture first needs the native hit counter split off (`HitCounter.ios.kt` uses the library). Kotlin/Native drops unreferenced Kotlin even in Debug. The release check should match every `_k*:` symbol kind, and the guard keys on Release `KotlinNativeLink`. | stage 6 (entry; E2.4, E2.5); stage 9 (E2.3, E2.6 to E2.8) |
| [E3](runtime-split-evaluation/E3.md) Android capture, plugin | Provider, `HopiConfigValues` and the generated config belong in `hopi-core` androidMain with their packages (P4); the plugin adds `hopi-core` to debuggable compile classpaths; the tool list is a constructor array; caps are preset and the bridge started at start (today the bridge waits for the first recorded request); the sample's caps come from a Gradle property. The release APK holds 31 noop classes, not 13. The D9 build-time guard must resolve inside a task. | stage 4; stage 9 (D9 build-time guard, release checks) |
| [E4](runtime-split-evaluation/E4.md) Rules | Appendix B is exact. `HopiRules` calls core singletons directly; proposed a `@HopiInternalApi` `HopiRulesRuntime` in `hopi-api`, implemented by core and registered first, with about 19 builder getters hidden with `@HiddenFromObjC`; no queue (E4.4). The moved annotation trips the release check (P2). A `from(id)` caps bug. | stage 3 (P2); stage 7 |
| [E5](runtime-split-evaluation/E5.md) Perf | Blocker of the old order: perf's API cannot move before the start sequence exists, or iOS cannot register perf. Android's `HopiPerf.install(Application)` has no caller. `remoteControl` is never set and gates only perf commands, so it stays perf-scoped; a global `summaryLog` newly silences perf lines (documented); the builder applies only what was set. No test checks that no class loads. | stage 4 (perf as a tool); stage 6 (iOS); stage 8 |
| [E6](runtime-split-evaluation/E6.md) UI, desktop viewer | Written for the automatic launcher, void under D7'. What still holds: the desktop viewer needs only `HopiPanes` and the theme types, so `hopi-ui` keeps `jvm()`; only `SampleApp.kt` calls `HopiLauncher`; both `LauncherLayoutTest` copies go. | stage 5 |
| [E7](runtime-split-evaluation/E7.md) Build infrastructure | `hopi-ktor-noop` can go only after `hopi-ktor` depends on `hopi-api` and a start path registers the hooks (C1). The annotation kept under `dev.koga.hopi.core` trips both release checks (C2). `hopi-core` has no ABI validation (O7). KGP wires `checkKotlinAbi` into `check`, so removing the parity tasks loses no ABI check (bytecode evidence; X1). Every deleting stage must edit the sample's module sets, the functional test and the plugin's constants. `check --dry-run` as a fast gate. | every stage's same-commit list; stages 3, 10, 11 |
| [E8](runtime-split-evaluation/E8.md) Documentation | 213 passages in 8 documents, each with an action and a stage, and a per-stage worklist. The plan never named `verifyHopiSelection` and `verifyDebugCapture`, which pin the module sets. `api-runtime-split.md` already says superseded. The old final grep gate conflicted with dated history. | every stage's docs step; stage 13 |

**Cross-check** ([cross-check.md](runtime-split-evaluation/cross-check.md)): 46 claims re-verified at
`e1ef40a`; 40 confirmed, 5 unclear, 1 refuted.

- Refuted: the functional-test line numbers that E1, E3, E4 and E5 cite moved by +10 at `e1ef40a`;
  section 6 uses HEAD's.
- Unclear, each settled by a **[run]** item: whether an unreferenced runtime's native archive member
  still lands in the framework (E2.3, stage 9); whether a core type named by an entry in the
  consumer's own module is exported the same way (E2.5; under A the entry holds its own class
  anyway); whether Android's own `HttpURLConnection` decodes gzip only when it asked for it (E1 R2,
  stage 10); whether URLSession decodes when the request names its own `Accept-Encoding` (E1 R3,
  stage 10); whether E7's derived order keeps `check` green: three omissions found (the
  `HopiPerfConfig` constructor, `publishedCinterops`, the only `PerfLauncher` writer), carried into
  stages 8, 9 and 4.
- 15 stage-order items (14 problems and the edge inventory), the reason section 6 was rewritten;
  section 6, stage 1, says where each is carried.

### [run] results, by stage

Stage 3 (2026-10-28, at `a6be8e8` plus the stage's edits):

- **E7 X1**: `./gradlew :hopi-connect:check :hopi-rules:check --dry-run` lists
  `:hopi-connect:checkKotlinAbi` and `:hopi-rules:checkKotlinAbi`: KGP puts `checkKotlinAbi` into
  each module's `check`, so no module needs it wired, and removing the parity tasks loses no ABI check.
- **E7 X2**: `./gradlew :hopi-android:check :hopi-android-noop:check :hopi-perf:check --dry-run`
  lists each module's `testAndroidHostTest`: host tests run in `check`, so
  `:hopi-android-noop:testAndroidHostTest` always ran in the base check; the explicit entries of
  section 7 are redundant and stay.
- **E7 X3** (baseline, `dependencies`, Hopi projects only): `:sample:androidApp`
  `releaseRuntimeClasspath`: `:hopi-android-noop`, `:hopi-ktor-noop`, `:hopi-perf-noop`,
  `:hopi-rules-noop`, `:hopi-ui-noop`; `debugRuntimeClasspath`: `:hopi-android`, `:hopi-core`, and
  each twin pair substituted to the real module (`:hopi-ktor`, `:hopi-perf`, `:hopi-rules`,
  `:hopi-ui`). `:sample:shared` `iosSimulatorArm64CompileKlibraries`: without `-Phopi.enabled=true`
  the noops of ktor, perf, rules, ui and urlsession; with it `:hopi-core`, `:hopi-ktor`,
  `:hopi-perf`, `:hopi-rules`, `:hopi-ui`, `:hopi-urlsession`.
- **E4 X1**: with `filters { exclude.annotatedWith.add("dev.koga.hopi.api.HopiInternalApi") }`,
  `hopi-api`'s three dumps hold only the annotation class; `HopiRuntimes` (annotated at class level)
  is in none. The filter works; no name filter is needed.
- **P2's "the marker appears in ABI dumps"** is refuted: after the rename, `./gradlew
  updateKotlinAbi` changed no dump but adding `hopi-api`'s own.

Stage 4 (2026-10-28; "before" at `d26907d`, "after" with the stage's edits; emulator
`Hopi_API_35`, API 35):

- **E3.6-a**: `verifyReleaseUsesNoop`'s report lists the five noop projects on the release
  classpath, 31 Hopi classes in the APK (`README.md:493` and `docs/ARCHITECTURE.md` say 13), 0
  referenced core or capture types and 0 classes calling a hook.
- **E3.6-b**: with `releaseImplementation(project(":hopi-android"))`, `assembleRelease` fails in
  `mergeDexRelease`: "Type dev.koga.hopi.android.HopiAndroid is defined multiple times" (the real
  module and its noop twin). An accidental protection that ends with the twins; stage 9's guard
  replaces it.
- **E3.3**: `autoStart(HopiMonitor.Hangs)` in an app's build script fails without an import
  ("Unresolved reference 'HopiMonitor'") and passes with `import dev.koga.hopi.gradle.HopiMonitor`:
  `README.md:410` and plan 3.2 need the import (stage 13).
- **E3.4**: not run; the debuggable decision takes the flags as an `Int?`, so no host test needs an
  `ApplicationInfo`.
- **E5 X3** before: the default sample writes `android capture started (okhttp instrumented, 13706
  us; config 365 us, okhttp lookup 634 us, start 8916 us, perf 3790 us)`, and `hopi perf status`
  shows remote control on and every monitor off. With `autoStart(Hangs)`: `… 16515 us; config 533
  us, okhttp lookup 611 us, start 6591 us, perf 8778 us`, hangs on at 250 ms with owner `code`,
  one hang reported.
- **E5 X3** after (same emulator; an interleaved A/B against the stage 3 build `d26907d`: 4 rounds
  of 5 cold starts per arm, each install followed by one discarded launch, every launch with
  `--es perfBench 'mode=idle;monitors=none'`, so no request; one emulator run is noisy, so medians):
  - As first built, the provider's line took a median 33.3 ms against 26.4 ms. `config` was 6.1 ms
    (0.26 before): it now constructs the network tool, whose class initialization ran `listOf` and
    loaded Kotlin's collection classes inside the window (the old provider's companion did that
    before the window opened). The start sequence's own steps, outside every part, took 3.5 ms:
    `HopiStore.presetDefault` initialized the store's class on the main thread.
  - Fixed in this stage: the start sequence presets the caps through a holder of their own
    (`DefaultStoreCaps`, which `HopiStore.presetDefault` and `configureDefault` use) and leaves
    the store's class unloaded; `NetworkTool` looks `RealCall` up with two plain calls, no list.
    Then the line took about 2 ms more than before, in every round (round medians +0.3 to +9.3
    ms) and at every quantile (median 29.2 against 27.5 ms; p25 to p75 27.8 to 38.1 against 21.2
    to 32.5): `config` 1.5 ms, `network` 15.9 ms against `okhttp lookup` plus `start` 14.2 ms,
    `perf` 9.1 ms against 8.0 ms (unchanged code), and the start sequence's own steps 1.3 ms,
    mostly creating the `hopi-start` thread and its body (by design). `am start -W`'s TotalTime
    fell by about 20 ms (median 1266 against 1285.5 ms, the p25 to p75 ranges overlapping; first
    build: 1273.5 against 1316.5): with `agentBridge` the store, the summary sink and the bridge are
    made on the `hopi-start` thread, where the sample's first Ktor client made them on the main
    thread in `MainActivity.onCreate`.
  - The default sample writes, for example, `android capture started (okhttp instrumented, 19303
    us; config 1419 us, network 11292 us, perf 5349 us)`; `hopi perf status` shows remote control
    on and every monitor off.
- **The bridge at start**: the app's process started by a broadcast to its exported
  `androidx.profileinstaller.ProfileInstallReceiver` (`am broadcast -f 0x20`), so no activity and
  none of the sample's code that makes a Ktor client: the `hopi-bridge` and `hopi-store` threads
  run, and `hopi list` exits 0 with no record.
- **Rules**: `--es rulesVerify all`: 44 scenarios, 0 failed; the pinned check is ok.
- **E3's body cap**: after `installDebug -Psample.maxBodyBytes=8388608`, `DeviceViewerUiTest`'s
  "5 MB body opens" passes (captured 5242766 of 5242766, not truncated). Its adb reset step failed
  twice the same way: another adb client (two `agent-device mcp` servers ran on the host) started
  the server between the test's `kill-server` and `start-server`, which then failed with "didn't
  ACK". The test now accepts a server that another client started, counts the overhead's POSTs in
  `<evidence>/devserver.ndjson` (it read a stale `build/evidence/desktop/devserver.log`, so each
  block waited out its 3 minutes) and reads the overhead's result line from logcat, where the
  sample now logs it too (the screen shows it below the fold). Then 18 of 18 pass, and 500
  `POST /items` take 2627, 2648 and 2744 ms connected against 2611, 2529 and 2696 ms
  disconnected.
- **idle.py** (`--seconds 60 --settle 10 --arms off=none,all=all`): the off arm's Hopi threads
  (`hopi-store`, `hopi-bridge`) take 0 µs and 0 wakes, and so does the main thread: core's
  current-activity callbacks (P5) wake nothing. With every monitor on, `hopi-perf-samp` takes
  40 ms and 60 wakes in the 60 s.
- **The base check**: the first run lost three Kotlin metadata compilations to `OutOfMemoryError:
  Metaspace` and two iOS tests to timing (`WakeSignalTest.aMarkDuringADozeIsTakenWhenItEnds`, a
  doze measured at 0 ms; `UrlSessionCaptureTest.sessionsAndTasksCreatedBeforeStart`, a task
  resumed before start recorded) with the host 2.8 GB into swap; with fresh daemons the rerun was
  green, those five included.

Stage 5 (2026-10-28, the stage's edits on `32fbbd5`; emulator `Hopi_API_35`, API 35;
simulator `iPhone 17`, iOS 27.0):

- **P5 on the single-activity sample**: launched with no extras, then `hopi perf start --frames`
  1, 2 and 4 s after `am start -W` returned: each reply shows frames attached, with no launcher
  left to write `PerfLauncher`. Three start, stop and start cycles in one process: 1.5 s after each
  start frames are attached, and they count frames (4, 2 janky, after a swipe and a tap). The reply
  to the start itself said "not attached" in 2 of the 3 restarts. The start posts the attach to the
  main thread, and the reply is read before that message runs, so the CLI and the desktop show the
  "frames attach when an activity next starts" hint for an attach that is a message away (stage 8:
  make the reply wait briefly for it, or not show the hint while one is pending). A temporary log in
  core's callbacks showed one `onActivityResumed` and no pause in that window, so `PerfLauncher`
  held the activity all along.
- **Perf lab**: `python3 benchmark/perf/verify.py` on Android: 28 of 28 on a second run. The first
  missed "jank 20 ms x30: slow, not janky" with 5 janky frames where at most 3 are allowed (3 in
  stage 4's run, 2 in the rerun): a timing check on a loaded emulator.
- **Desktop viewer against the emulator**: `DeviceViewerUiTest` 18 of 18 (500 `POST /items`: 2692,
  2570 and 2613 ms connected against 2743, 2738 and 2723 ms disconnected). `DevicePerfPanelUiTest`,
  after the lab and `hopi hangs --json`: 15 of 15 checks (one informational line), the live click
  burn included (hang #84, 601 ms, its stack naming `PerfLab.clickBurn`), now that the test brings
  the app back to the front first; in stage 4 that step found no button.
- **Perf lab on iOS**: `python3 benchmark/perf/verify.py --platform ios` on the Debug build: 21 of
  21 once the freshly erased simulator's first-boot services had settled. The run made while they
  still took the host's CPU missed "jank 20 ms x30: no stall" (8 stalls) and "cpu 2 threads x 5
  s" (57.6 % for 200 %).
- **The rest**: `:sample:shared:compileKotlinIosSimulatorArm64 -Phopi.enabled=true` compiles; the
  base check is green.

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
| Q2 | six published modules: `hopi-api`, `hopi-ktor` (thin), `hopi-core`, `hopi-network`, `hopi-perf`, `hopi-start` (the iOS and JVM entry, P3); `hopi-ui` unpublished (D7') |
| O1 | A: one exported iOS entry module, `hopi-start` (`Hopi.start { }`), over the network and perf runtimes, its configuration a type of its own (P3); D (a Debug-only file in the consumer's module) waits for the plugin to generate it (O8) |
| O3, O4, O5 | settled by D2': `"ktor"` stays, the plugin keeps decoding what Ktor decodes, JVM apps record through it |
| O6 | void (D7') |
| O7 | ABI validation on the modules a consumer compiles against (`hopi-api`, `hopi-ktor`, `hopi-start`); the runtimes keep what they have (`hopi-core` has none, E7); no parity check |
| O8 | yes, later: the plugin on KMP modules is not part of this pass |
| E4.4 | no pending queue in `hopi-api`: a call before a runtime registered returns `-1`, `false` or an empty list; the runtime writes one notice line with the count when it registers |
| D9 | strict as written: no opt-in for non-debuggable variants or iOS Release configurations |
| P1 | D2' realized: `hopi-ktor` depends on `hopi-api` and `ktor-client-core` only and declares a hooks interface with Ktor types; `hopi-api`'s registry keeps an opaque Ktor slot that `hopi-ktor` casts; at install the plugin installs the registered hooks or nothing. Today's recording code moves into `hopi-network` (android and jvm, Ktor and `hopi-ktor` compileOnly), registered only when the plugin class is loadable; `hopi-network` gains a jvm target for it. iOS has no adapter. Release builds may hold `hopi-api` and `hopi-ktor` (3.9) |
| P2 | `@HopiInternalApi` becomes `dev.koga.hopi.api.HopiInternalApi` (about 100 files, mechanical, with every ABI dump it changes) and the registry lives in `dev.koga.hopi.api`; the release checks keep forbidding all of `dev.koga.hopi.core`, with no exemption |
| P3 | the entry module is `hopi-start` (iOS; jvm from stage 10), published with an ABI dump; runtimes as `implementation`; the framework exports `hopi-start` in Debug and `hopi-api` always; Swift's guard is a compilation condition `HOPI` tied to `HOPI_ENABLED` |
| P4 | `HopiInitProvider`, `HopiConfigValues` and the generated config move to `hopi-core` androidMain with their `dev.koga.hopi.android.*` packages; the plugin adds `hopi-core` to debuggable compile classpaths |
| P5 | current-activity tracking moves into core's start, so perf keeps attaching frames and hangs once the in-app UI, today the only `PerfLauncher` writer, is gone |

Stage 0 baseline (2026-10-27 and 28, `e8583b8`): the base check of section 7 is green. The first
full run took 43.5 min and stopped only at `:sample:shared:linkReleaseFrameworkIosSimulatorArm64`
with `ENOSPC` (499 MB left on the disk; with about 2 GB free it links). The rerun then failed in
the plugin's functional tests: their teardown sent `SIGTERM` to every pid named by the ~90 daemon
logs that earlier runs left in the TestKit directory, one of them by then `osanalyticshelper`, and
timed out waiting for it. The teardown now stops only daemons whose log appeared during the test and
whose arguments name Gradle's daemon entry point; 3 of 3 pass in 30 s, no daemon left behind.

| Stage | Status | Commit |
| --- | --- | --- |
| 0. Preflight | done: owner decisions recorded, baseline green | `e1ef40a` |
| 1. Evaluation and re-plan | done: E1 to E8 and the cross-check in `docs/design/runtime-split-evaluation/`; sections 3.9, 6 to 10 rewritten | `27b1714` |
| 2. Contract documents (after a separate fix commit) | done: the redirect wording fixed (`:hopi-core:jvmTest` and `:hopi-ui:jvmTest` green; `PerfUiTest` timed out once waiting 5 s for the PSS line and passed on two reruns, a timing flake in a test stage 5 deletes); ARCHITECTURE principle 1, the migration note and the Modules table's stages; ROADMAP's rule for every new tool and its surfaces; the old proposal's status line | `2e9cce0` (fix), `a6be8e8` |
| 3. The API module | done: `hopi-api` (registry `HopiRuntimes` with the Ktor hooks' slot; no dependency; four targets; published); `@HopiInternalApi` renamed in 91 files (P2); E7 X1 to X3 and E4 X1 in section 9 | `d26907d` |
| 4. Start sequence and tools in core | done: `HopiStart` in core (`StartConfig`, tools with their own settings, the caps preset without creating the store, the installation on a `hopi-start` thread with `agentBridge`); P4's provider in core with D9's run-time check and no start without the generated class; P5's current-activity callbacks; `NetworkTool` and `PerfEntry` as tools; the plugin adds `hopi-core` to debuggable build types and generates the tool list; the sample's caps are build properties (E3's 5 MB check passes); [run] results in section 9 | `32fbbd5` |
| 5. Remove the in-app UI | done: `hopi-ui` holds only the desktop viewer's screens (JVM, unpublished, no ABI dump); `hopi-ui-noop`, the launcher, `HopiScreen`, the HUD and the in-app perf and rules panes deleted; shared-code tests retargeted to `HopiPanes`; the scripts that drove the in-app inspector deleted; P5 shown on the emulator; [run] results in section 9 | (this commit) |
| 6. iOS entry | | |
| 7. Rules into the API | | |
| 8. Perf into API and runtime | | |
| 9. Network runtime and release guards | | |
| 10. Thin Ktor plugin | | |
| 11. Remove the remaining twin machinery | | |
| 12. Physical devices | | |
| 13. Documentation pass | | |
| 14. Final verification and report | | |
| Later (O8). Plugin on KMP modules | deferred | |

Carried in from before the plan: `DeviceViewerUiTest` on Android was partly stale (ROADMAP,
"Desktop viewer"); stage 4 fixed it (18 of 18 with figures, section 9): the build's caps reach
OkHttp capture, and the test's overhead step reads the right dev server log and the result line.

Stage 4 leaves for later stages:

- Accepted consequence of P4: every real module takes `hopi-core` as `implementation`, so an
  Android app that links one without the plugin merges core's provider. Each debug launch pays one
  failed `Class.forName` of the generated class on the main thread and writes the
  no-generated-configuration notice; a non-debuggable build that links a real module writes the
  not-debuggable notice; nothing starts, and a Ktor plugin the app installs records as before
  (ARCHITECTURE, Android capture "Start" and "Selecting real vs noop"; README, "Install").
- Stage 6: `HopiStart` checks the caps on every call, `UrlSessionCapture` only until a call
  succeeded; once iOS starts through `HopiStart`, a later call with invalid caps writes the
  invalid-caps notice, not "already started".
- Stage 9: `hopi-android/consumer-rules.pro:2` still says the provider is the manifest's; reword it
  when `hopi-android` becomes `hopi-network`.
- The sample's `MainActivity` still calls `SampleHttp.start()` so the bridge is up before the
  first request; on Android the start sequence does that now, and iOS needs the call until stage 6.
- The start sequence's notice emitter is `((String) -> Unit)?`, null meaning the platform log, so
  the provider creates no function value on the main thread.
- Stage 5 (its perf checks): `benchmark/perf/verify.py` passes 28 of 28 on this stage's build, and
  `DevicePerfPanelUiTest` after it passes every check but the live click burn: the script's memory
  step leaves the app in the background, so the test's tap finds no button. Done in stage 5: the
  test brings the running task back to the front first.

Stage 5 leaves for later stages:

- Stage 8: the reply to `hopi perf start --frames` can say "not attached", and the CLI and the
  desktop then show the "frames attach when an activity next starts" hint, while the attach is one
  main-thread message away (section 9). Make the reply wait briefly for that message, or leave the
  hint out while an attach is pending.
- Stage 8: `PerfLauncher` keeps its name though no launcher writes it any more (core's
  current-activity callbacks do), and `PerfData.inspectorOpen` stays, always false; rename the one
  and drop the other with the perf API move.
- `hopi-ui`'s `HopiScreen.kt` now holds only the state helpers the desktop panes share; a rename
  can come with any later change there.

---

## Appendix A. Inventory already found (2026-10-27, narrow greps; re-grep in E1.1)

Kept as first written. Stage 1 widened and corrected it: E1's corrections (A.1, A.3, A.4), E7's
tables and the edge inventory at the end of `runtime-split-evaluation/cross-check.md` (item 15)
are the current lists; under D2' the `ktor` source and the defer machinery stay.

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
