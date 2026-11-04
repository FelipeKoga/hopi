package dev.koga.hopi.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import dev.koga.hopi.gradle.HopiModules.API
import dev.koga.hopi.gradle.HopiModules.CORE
import dev.koga.hopi.gradle.HopiModules.GROUP
import dev.koga.hopi.gradle.HopiModules.KTOR
import dev.koga.hopi.gradle.HopiModules.NETWORK
import dev.koga.hopi.gradle.HopiModules.PERF
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * `dev.koga.hopi` in an Android application module (docs/design/android-capture.md); every AGP
 * type the plugin names is here, loaded only once `com.android.application` is applied:
 *
 * - each debuggable build type gets `hopi-core` and, with `network { enabled }` (the default), the
 *   network runtime (`hopi-network`); every other build type gets no runtime at all. Core is there
 *   whatever the tools: its provider starts the generated configuration, which javac compiles inside
 *   the app and which names core's types, and every runtime takes core as `implementation` only;
 * - each debuggable variant gets the `hopi { }` block generated into it as the start configuration
 *   and its tool list: the network tool with `network { enabled }`, perf's with
 *   `perf { enabled = true }`, possibly none (the start then still registers the rules runtime and
 *   starts the bridge with `agentBridge`; nothing records, the Ktor plugin's recorder being
 *   `hopi-network`'s, which the network tool registers);
 * - with `network { enabled }`, each debuggable variant has OkHttp's `RealCall` and every
 *   `java.net.URL` connection call site of the app and all of its dependencies rewritten (AGP
 *   instrumentation, scope ALL); other variants are not instrumented at all;
 * - each variant that is not debuggable gets the release guard, `hopiReleaseGuard<Variant>`
 *   ([HopiReleaseGuard]), whatever `enabled` says: before the variant's build it fails when the
 *   runtime classpath holds `hopi-core`, `hopi-network`, `hopi-perf` or `hopi-start`, which only a
 *   module naming them can bring there;
 * - each debuggable build type holds `dev.koga.hopi:hopi-api` (`HopiRules` and `HopiPerf`, which the
 *   app's modules name in every build type) and, with `network { enabled }`, `dev.koga.hopi:hopi-ktor`
 *   (the Ktor plugin, which the app's modules may name in every build type) at this plugin's
 *   version, the version of the `hopi-core`, `hopi-network` and `hopi-perf` it adds: core's rules
 *   runtime and hopi-perf's runtime implement internal interfaces of `hopi-api`, the network tool
 *   calls its registry's `registerNetwork` and implements `hopi-ktor`'s hooks interface, and a
 *   mismatch would fail at run time;
 * - with `perf { enabled = true }`, each debuggable build type gets `hopi-perf` (the others nothing:
 *   `HopiPerf` is `hopi-api`'s) and the tool list names its tool, which registers the monitors and
 *   starts the `autoStart` ones (docs/design/perf-monitors.md).
 *
 * `hopi-core`'s manifest declares the provider that starts the generated tools, so the app adds no code.
 */
internal object HopiAndroid {
    private const val KTOR_ANDROID_ENGINE = "io/ktor/client/engine/android/AndroidClientEngine"

    fun configure(project: Project, extension: HopiExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        // Debuggable is a build-type property; finalizeDsl is the last point dependencies can be declared.
        val realByBuildType = mutableMapOf<String, Boolean>()
        components.finalizeDsl { dsl: ApplicationExtension ->
            for (buildType in dsl.buildTypes) {
                val real = extension.enabled.get() && buildType.isDebuggable
                realByBuildType[buildType.name] = real
                // Debuggable build types only: the others get no runtime, which their guard checks.
                if (!real) continue
                val configuration = "${buildType.name}Implementation"
                // The network runtime, with `network { enabled }` (the default).
                if (extension.network.enabled.get()) project.dependencies.add(configuration, HopiModules.notation(project, NETWORK))
                // Whatever the tools: core's provider reads the generated configuration, which
                // extends core's HopiConfigValues and lists core's HopiTool, so javac needs core on
                // the variant's compile classpath, and no runtime exposes it there.
                project.dependencies.add(configuration, HopiModules.notation(project, CORE))
                alignApi(project, buildType.name, extension.network.enabled.get())
                // The monitors' runtime: HopiPerf, which the app calls, is hopi-api's.
                if (extension.perf.enabled.get()) project.dependencies.add(configuration, HopiModules.notation(project, PERF))
            }
        }
        components.onVariants(components.selector().all()) { variant ->
            val real = extension.enabled.get() && variant.debuggable
            val expected = realByBuildType[variant.buildType]
            if (expected != null && expected != real) {
                throw GradleException(
                    "variant ${variant.name} is ${if (variant.debuggable) "" else "not "}debuggable, unlike its build type " +
                        "${variant.buildType}; dev.koga.hopi gave it ${if (expected) "Hopi's runtimes" else "no runtime"}",
                )
            }
            if (real && extension.network.enabled.get()) instrument(extension.network, variant)
            if (real) generateConfig(project, extension, variant)
            if (!variant.debuggable) guard(project, variant)
        }
    }

    /** The bytecode transform and its excludes: `network { enabled }`, debuggable variants only. */
    private fun instrument(network: HopiNetworkExtension, variant: ApplicationVariant) {
        variant.instrumentation.transformClassesWith(HopiClassVisitorFactory::class.java, InstrumentationScope.ALL) { parameters ->
            parameters.okhttp.set(network.okhttp)
            parameters.urlConnection.set(network.urlConnection)
        }
        variant.instrumentation.excludes.addAll(network.excludedClasses)
    }

    /**
     * The generated start configuration, every debuggable variant: its tool list names the network
     * tool with `network { enabled }` and perf's with `perf { enabled = true }`, and may be empty.
     */
    private fun generateConfig(project: Project, extension: HopiExtension, variant: ApplicationVariant) {
        val network = extension.network
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val generate = project.tasks.register("generate${name}HopiConfig", GenerateHopiConfig::class.java) { task ->
            task.networkPresent.set(network.enabled)
            task.okhttp.set(network.okhttp)
            // The runtime defers Ktor's Android engine to the wrapper only when that engine's own
            // `URL` calls were rewritten, which an exclude pattern can prevent.
            task.urlConnection.set(
                network.urlConnection.zip(network.excludedClasses) { on, patterns -> on && patterns.none { excludes(it, KTOR_ANDROID_ENGINE) } },
            )
            task.maxEntries.set(extension.maxEntries)
            task.maxTotalBytes.set(extension.maxTotalBytes)
            task.maxBodyBytes.set(extension.maxBodyBytes)
            task.summaryLog.set(extension.summaryLog)
            task.agentBridge.set(extension.agentBridge)
            task.rules.set(network.rules)
            task.redactedHeaders.set(extension.redactedHeaders)
            task.redactedQueryParameters.set(extension.redactedQueryParameters)
            task.redactionDisabled.set(extension.redactionDisabled)
            task.perfPresent.set(extension.perf.enabled)
            task.perfAutoStart.set(extension.perf.autoStart.map(HopiPerfExtension::bits))
            task.perfHangThresholdMs.set(
                extension.perf.hangThresholdMs.map { ms ->
                    if (ms !in 100L..10_000L) throw GradleException("hopi { perf { hangThresholdMs } } must be in 100..10000, not $ms")
                    ms
                },
            )
            task.perfHangHeartbeat.set(extension.perf.hangHeartbeat)
            task.perfRemoteControl.set(extension.perf.remoteControl)
        }
        val java = variant.sources.java ?: throw GradleException("variant ${variant.name} has no Java sources to add Hopi's configuration to")
        java.addGeneratedSourceDirectory(generate, GenerateHopiConfig::outputDirectory)
    }

    /**
     * The release guard of a variant that is not debuggable ([HopiReleaseGuard]): its inputs are the
     * variant's runtime classpath mapped to the runtimes it holds and the ones to remove, when the
     * task's inputs are read; AGP runs it before `pre<Variant>Build`, so before any compile or dex
     * task of the variant.
     */
    private fun guard(project: Project, variant: ApplicationVariant) {
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val graph = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        val guard = project.tasks.register("hopiReleaseGuard$name", HopiReleaseGuard::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Fails the build when the non-debuggable variant ${variant.name} resolves one of Hopi's runtimes."
            task.variantName.set(variant.name)
            task.buildType.set(variant.buildType ?: variant.name)
            task.runtimes.set(graph.map(HopiRuntimeGraph::refused))
            task.remove.set(graph.map(HopiRuntimeGraph::toRemove))
        }
        variant.lifecycleTasks.registerPreBuild(guard)
    }

    /**
     * Holds `hopi-api` and, with [network] (`network { enabled }`), `hopi-ktor` at [HOPI_VERSION] in
     * [buildType], which gets core (whatever the tools), `hopi-network` with `network { enabled }`
     * and `hopi-perf` with `perf { enabled = true }`, at that version: the app names `hopi-api`
     * itself (it holds `HopiRules` and `HopiPerf`) and may name `hopi-ktor` (the Ktor plugin), at
     * whatever version it chose; core's rules runtime and hopi-perf's runtime implement internal
     * interfaces of `hopi-api`, `hopi-network`'s network tool calls its registry's
     * `registerNetwork`, and its Ktor recorder implements `hopi-ktor`'s internal hooks interface. A
     * constraint, so it adds nothing to a build that never names the module; none for this
     * repository's project of that name, which is core's own version.
     */
    private fun alignApi(project: Project, buildType: String, network: Boolean) {
        val configuration = "${buildType}Implementation"
        if (!HopiModules.isProject(project, API)) {
            project.dependencies.constraints.add(configuration, "$GROUP:$API") { constraint ->
                constraint.version { it.strictly(HOPI_VERSION) }
                constraint.because("dev.koga.hopi: hopi-core $HOPI_VERSION implements hopi-api $HOPI_VERSION's internal interfaces")
            }
        }
        if (network && !HopiModules.isProject(project, KTOR)) {
            project.dependencies.constraints.add(configuration, "$GROUP:$KTOR") { constraint ->
                constraint.version { it.strictly(HOPI_VERSION) }
                constraint.because("dev.koga.hopi: hopi-network $HOPI_VERSION implements hopi-ktor $HOPI_VERSION's internal hooks interface")
            }
        }
    }
}
