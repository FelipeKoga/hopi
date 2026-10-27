# Design: one public API and a pluggable runtime, instead of noop twins

Status: superseded (2026-10-27) by `docs/design/api-runtime-split-plan.md` wherever the two differ:
the plan deletes the Ktor plugin and the public `HopiUrlSession` that this proposal kept. Kept for
its reasoning (why twins, options considered).

Former status: proposal (2026-10-27). Nothing built. It changes principle 1 of `docs/ARCHITECTURE.md`
("every consumer-facing artifact has a `-noop` twin"), so the architecture doc is changed with the
first stage, before the code.

Today every consumer-facing module has a twin with the same public API that does nothing, and the
build picks one of the two: the Gradle plugin per Android variant, a Gradle property in a KMP
shared module. This design keeps what the twins guarantee (release builds hold no Hopi logic and
no `hopi-core`) and drops the twins: each public API is declared once, in a small module that does
nothing on its own, and the real behaviour lives in runtime modules that only debug builds add and
that plug themselves into that API.

## Why

What the twins buy, and what they cost.

They let app code that calls Hopi (`install(Hopi)`, `HopiLauncher { }`, `HopiUrlSession.start()`,
`HopiRules`, `HopiPerf`) compile in every build, while a release build links code that does
nothing and names no `hopi-core` class. A KMP shared module has no build types and an iOS framework
links the same klibs for Debug and Release, so the choice has to be a dependency swap.

The release build already ships the noop classes (13 in the sample's release APK). So what the
twins guarantee is "no Hopi logic", not "no Hopi class". A module that declares the API once and
does nothing until a runtime registers gives the same guarantee.

Their cost, as of 1c81938:

| | |
| --- | --- |
| Modules | 6 noop modules next to 6 real ones |
| Code | about 390 lines of noop sources, 540 lines of noop tests, 790 lines of duplicated ABI dumps |
| Checks | `:checkNoopParity` (both sides' JVM, Android and klib dumps compared line by line), three `DefaultsParityTest`s that load the real module in a child-first classloader from a configuration of their own, a `LauncherLayoutTest` kept identical in two modules |
| Constraints | no `inline` function and no `const` in any parity-checked API, since the plugin swaps a twin under code compiled against the other one; every default copied by hand into the noop |
| Every API change | made twice, both dumps updated together |
| Consumer | one `if (hopiEnabled) X else X-noop` per artifact in the shared module, and an app module on top of a KMP module must not also select by build type |

## Goals and non-goals

Goals:

- **The same release guarantee.** A release build links no `hopi-core`, no capture, no monitor,
  no rule engine and no UI beyond the launcher's pass-through layout. The checks that prove it
  today (`verifyReleaseUsesNoop`, `verifyIosReleaseExcludesCapture`, the "no core on the test
  classpath" tests) keep proving it, retargeted.
- **Each public API declared once.** No parity check, no defaults copied by hand, `inline` and
  `const` allowed again.
- **The consumer's code does not change.** Same names, same packages, same signatures. What
  changes is the Gradle file and, on iOS, one line.
- **A missing runtime is safe.** Forgetting the runtime gives a build that compiles and does
  nothing, not a resolution or link error.
- **Free when absent.** With no runtime registered: no hook, no thread, no class of `hopi-core`
  loaded; one null read where an entry point is called, none per request.
- **Unchanged when present.** The request path, the monitors and the bridge are the same code as
  today; the runtime registers the same Ktor hooks on the same builder.

Non-goals:

- Changing what any tool does, the bridge, the CLI or the desktop viewer.
- Automatic start on iOS with no call at all (see "Registration").
- Publishing anywhere but Maven Local.

## Options considered

| Option | What it is | Verdict |
| --- | --- | --- |
| Keep the twins | As today | Rejected: the cost above grows with every tool the roadmap adds. |
| **API + runtime** | The app always depends on API modules that do nothing on their own and never depend on core; runtime modules depend on the API and register implementations. Release: API only. Debug: API and runtime. | **Taken.** |
| No public API at all (LeakCanary's model) | If app code names no Hopi symbol, no twin is needed. Android capture already works like this (the plugin rewrites, a provider starts it). | **Taken where possible, as a later stage**: an automatic launcher (Android: an overlay attached through `ActivityLifecycleCallbacks`; iOS: a window of its own, opened by a shake) would let most apps drop `HopiLauncher`, and Ktor's OkHttp and Darwin engines are already recorded by the platform capture. It cannot remove every call: rules from tests and debug menus, `HopiPerf.screen`, the Ktor plugin on CIO or the JVM, and the iOS start. |
| Gradle variants | Real and noop published as two variants of one coordinate, picked by an attribute | Rejected: shorter Gradle files, every other cost stays. |
| Ship the real module, gate it at run time, let R8 or Kotlin/Native's dead-code removal drop it | | Rejected: a manifest provider keeps the Android code alive, and nothing guarantees that a run-time flag (`Platform.isDebugBinary`) folds to a constant in a Kotlin/Native release binary. Principle 1 asks for a guarantee visible in the build, not one that depends on an optimizer. |

## Modules

| Today | After |
| --- | --- |
| `hopi-urlsession` + `hopi-urlsession-noop` | API (`HopiUrlSession`, `HopiUrlSessionConfig`) in `hopi`; runtime `hopi-urlsession-runtime` (iOS capture) |
| `hopi-android` + `hopi-android-noop` | API (`HopiAndroid`) in `hopi`'s `androidMain`; runtime `hopi-android-runtime` (the hooks, the provider) |
| `hopi-rules` + `hopi-rules-noop` | API (`HopiRules`, the builders, `HopiFailure`, `HopiSource`) in `hopi`; runtime: the rule engine already lives in `hopi-core`, so `hopi-core` registers it |
| `hopi-perf` + `hopi-perf-noop` | API (`HopiPerf`, `HopiPerfConfig`, `HopiMonitor`, `HopiPerf.install(Application)`) in `hopi`; runtime `hopi-perf-runtime` (monitors, native library) |
| `hopi-ktor` + `hopi-ktor-noop` | API `hopi-ktor` (`Hopi`, `HopiConfig`, `HopiLogSink`; depends on ktor-client-core only); runtime `hopi-ktor-runtime` |
| `hopi-ui` + `hopi-ui-noop` | API `hopi-ui` (`HopiScreen`, `HopiLauncher`; compose-runtime and compose-ui only); runtime `hopi-ui-runtime` (the screens, Material, the desktop panes) |
| `hopi-core` | `hopi-core`, now depending on `hopi`; `HopiInternalApi` moves to `hopi` with the same fully qualified name |

From 13 published library modules to 10: three API modules (`hopi`, `hopi-ktor`, `hopi-ui`), the
core and six runtimes. `hopi-ktor` and `hopi-ui` stay separate from `hopi` because they name
third-party types (`ClientPlugin`, `Modifier`): an app that uses neither must not get Ktor or
Compose through `hopi`. Perf keeps a runtime of its own (the roadmap's "each tool is its own
module, off by default"), and so does each later tool; its API joins `hopi`, which stays a module of
declarations and builders.

`hopi` has no dependency at all. Its JVM tests keep the noop modules' assertion: no `hopi-core`
on the test runtime classpath, no core class loadable. `hopi-ktor` and `hopi-ui` keep the same test.

The runtime modules' names are open (see "Open questions"). Since 0.1.0 is published to Maven
Local only, the coordinates may change without a deprecation period.

## Registration

The API modules own a registry that the runtimes fill, annotated `@HopiInternalApi`:

```kotlin
// hopi
@HopiInternalApi
public object HopiRuntime {
    public var urlSession: UrlSessionRuntime? = null   // written once, at registration
    public var rules: RulesRuntime? = null
    public var perf: PerfRuntime? = null
    public var android: AndroidRuntime? = null
}

// hopi-ktor
@HopiInternalApi
public fun interface HopiKtorRuntime {
    public fun install(plugin: ClientPluginBuilder<HopiConfig>)
}

// hopi-ui
@HopiInternalApi
public interface HopiUiRuntime {
    @Composable public fun Screen(onClose: () -> Unit, modifier: Modifier)
    @Composable public fun Launcher(content: @Composable () -> Unit)
}
```

Each field is written before the app's first call and read after it, so a `@Volatile` field (an
`AtomicReference` on native) is enough; a second registration of the same kind is ignored with a
notice line.

The public API reads the registry:

```kotlin
// hopi-ktor: no runtime, no hook, exactly what the noop's empty plugin does today
public val Hopi: ClientPlugin<HopiConfig> = createClientPlugin("Hopi", ::HopiConfig) {
    HopiKtorRuntime.current?.install(this)
}

// hopi-ui: no runtime, the pass-through layout that the noop has today
@Composable
public fun HopiLauncher(content: @Composable () -> Unit) {
    val ui = HopiUiRuntime.current
    if (ui == null) LauncherLayout(content) else ui.Launcher(content)
}

// hopi
public fun mock(match: String, block: HopiMock.() -> Unit = {}): Long =
    HopiMock().apply(block).let { HopiRuntime.rules?.add(match, it) ?: -1 }
```

The runtime's Ktor installer registers the same hooks the plugin body registers today, on the
same `ClientPluginBuilder`, so the per-request code is unchanged. `LauncherLayout` is the one
measure policy both sides use: the runtime's launcher calls it for the app's content, so the
layout parity that `LauncherLayoutTest` checks in two modules today holds by construction.

The configuration and builder classes keep what was set (the noop's builders drop it today) and
expose it to the runtime through `@HopiInternalApi` members. They do not validate: validation stays
in the runtime (core's rule engine, `CaptureInstallation`), so a release build accepts any value,
as the noop does today. Their defaults are declared once, so `DefaultsParityTest` goes away.

### When registration happens

- **Android.** Each runtime's manifest registers it from a content provider, as
  `hopi-android`'s `HopiInitProvider` starts capture today: before `Application.onCreate`, so
  before any call the app makes. A provider per runtime, or one in `hopi-core` that registers
  every runtime on the classpath, is an implementation choice of stage 1.
- **iOS.** Kotlin/Native runs no code of a module until something calls it, and has no
  supported equivalent of a provider (`@EagerInitialization` is documented as a temporary
  solution that will be removed). The app calls the runtime once, first thing, from code that only
  Debug builds compile:

  ```swift
  init() {
  #if DEBUG
      HopiRuntime.shared.install()          // registers every runtime the framework links
  #endif
      HopiUrlSession.shared.start { $0.agentBridge = true }
  }
  ```

  The Debug framework exports the runtime (the Xcode build phase passes `-Phopi.enabled=true`, as
  today); the Release framework does not link it, and `#if DEBUG` keeps Swift from naming it. A
  mismatch fails loudly: a Debug build of a framework without the runtime does not compile.
  Today's iOS apps already make one call first thing (`HopiUrlSession.start()`), so this adds a
  line, not a new requirement.
- **JVM and Kotlin entry points.** The same `install()`, called from Kotlin. A shared module that
  wants the call in common code generates a one-line file from `hopi.enabled` (a body that calls
  the runtime, or an empty one), as `sample/shared` already generates its flag.

Order: an API call made before registration must not be lost silently. `HopiUrlSession.start` and
`HopiPerf.install` keep their configuration as pending in the registry, and `install()` applies
it. A Ktor client created before registration is not recorded (its plugin body ran with no
runtime); `install()` writes a notice line when a Ktor plugin was installed before it, so the
mistake shows in the log.

## Selecting the runtime

Android with the plugin: the plugin adds the runtimes to debuggable variants only (the runtime of
each API module the app resolves, `hopi-perf-runtime` with `perf { enabled = true }`) and nothing
to the others. No dependency substitution, so `selectTwins` goes away, and so does the rule that
an app module above a KMP module must not select by build type.

Android without the plugin: `debugImplementation` of the runtimes.

KMP shared module:

```kotlin
val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.koga.hopi:hopi:$v")          // always: the API
            implementation("dev.koga.hopi:hopi-ktor:$v")
            implementation("dev.koga.hopi:hopi-ui:$v")
            if (hopiEnabled) implementation("dev.koga.hopi:hopi-runtime:$v")   // every runtime
        }
    }
}
```

`hopi-runtime` is an aggregate with no code that depends on every runtime: one line to add, one
to export from the iOS framework. Applying `dev.koga.hopi` to a KMP module so that it adds the
runtimes and the export itself is a later stage.

## Cost

| | Runtime absent (release) | Runtime present (debug) |
| --- | --- | --- |
| Ktor | plugin body: one null read at `HttpClient` creation; no hook, nothing per request | the same hooks as today |
| UI | one read of the registry per launcher composition; the pass-through layout | one interface call per composition, then today's launcher |
| Rules | the block runs on a builder that is then dropped, as in the noop | one interface call per rule call |
| URLSession, perf | null read, configuration kept as pending (a few objects, once) | one interface call per entry point |
| Classes in a release APK | the API classes the app calls (R8 drops the rest), against 13 noop classes today | |

The release checks change wording only: the APK may hold classes of `hopi`, `hopi-ktor` and
`hopi-ui`, never of `hopi-core` or a runtime; the iOS release framework holds no capture, monitor
or runtime symbol.

## What goes away, what is added

Goes away: the six noop modules, their tests and dumps, `:checkNoopParity`, the three
`DefaultsParityTest`s and their `realXJvm` configurations, the second `LauncherLayoutTest`, the
plugin's `TWINS` substitution and `selectTwins`, the ban on `inline` and `const`.

Added: the registry and SPI interfaces (`@HopiInternalApi`), registration in each runtime (a
provider on Android, `HopiRuntime.install()` elsewhere), pending configuration for the entry
points called before registration, the `hopi-runtime` aggregate, and the iOS install line in the
sample and the README. ABI validation stays on the API modules, for the stability of the public
API, without a twin to compare with.

## Risks

- **A forgotten iOS `install()`** gives a Debug build that records nothing. Mitigation: the pending
  configuration of `HopiUrlSession.start` writes a notice line when no runtime registered within
  the first run-loop turn after it.
- **Objective-C export names.** `HopiRuntime` must not collide with another exported name; the
  existing rule (the URLSession entry point is not `Hopi`, because of the Ktor plugin) applies.
- **One extra call per entry point.** Measured with the existing benchmarks (`HopiOverheadBenchmark`,
  the Android capture and perf A/Bs) before and after stage 4; the per-request path is not supposed
  to move.
- **Breaking change for consumers.** Gradle coordinates change (runtime names, `hopi` for the
  API); the Kotlin and Swift API does not, except for the iOS install line.

## Stages

Each stage leaves `./gradlew check`, the release checks and the sample's device runs green.

1. **Docs and `hopi`.** Change principle 1 in `docs/ARCHITECTURE.md` and the roadmap's "Rule for
   every new tool". Create `hopi` with `HopiInternalApi` (moved from core, same FQN) and the
   registry; core depends on it.
2. **Rules.** The smallest API and no third-party type: API into `hopi`, core registers the rule
   engine, `hopi-rules-noop` and `hopi-rules` go away. Proves the pattern end to end, Swift included.
3. **URLSession, Android, perf.** APIs into `hopi`, runtimes renamed, the three noops removed,
   the iOS `install()` and the pending configuration.
4. **Ktor.** Split `hopi-ktor` into API and runtime; overhead benchmark before and after.
5. **UI.** Split `hopi-ui`; `LauncherLayout` shared; the desktop viewer depends on the runtime.
6. **Gradle plugin and sample.** Drop `selectTwins` and the substitution, add runtimes per
   variant, the `hopi-runtime` aggregate, retargeted release checks, README install section.
7. **Later.** The automatic launcher (no `HopiLauncher` call), and `dev.koga.hopi` on KMP modules.

## Open questions

- Runtime module names: `hopi-<tool>-runtime`, `hopi-<tool>-debug`, or a single `hopi-runtime`
  (one module holding every runtime but Ktor and UI)? Per-tool runtimes keep the roadmap's
  "each tool its own module"; a single one keeps the KMP Gradle file at one line without an aggregate.
- Does `HopiAndroid` (`isStarted`, `okHttpInstrumented`) need to stay public? If nothing calls it
  outside Hopi's own tests and the sample, it can become internal to the runtime, and `hopi` keeps
  no Android-only API.
- Should `hopi-ui`'s API move into `hopi` once an automatic launcher exists and `HopiScreen`
  is the only Compose type left?
