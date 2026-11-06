package dev.koga.hopi.gradle

import com.android.build.api.AndroidPluginVersion
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
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * `io.github.felipekoga.hopi` in an Android application module (docs/design/android-capture.md); every AGP
 * type the plugin names is here, loaded only once `com.android.application` is applied:
 *
 * - the app chooses the tools by naming their runtimes, debug only, as LeakCanary's users do:
 *   `debugImplementation("io.github.felipekoga.hopi:hopi-network:<v>")` and, for the monitors,
 *   `hopi-perf`. The plugin adds no runtime: each brings core (`api`), which the generated
 *   configuration compiles against. A build type that names none gets nothing from the plugin;
 * - each debuggable variant whose runtime configuration declares a runtime gets the `hopi { }`
 *   block generated into it as the start configuration, its tool list naming the network tool
 *   when `hopi-network` is declared and perf's when
 *   `hopi-perf` is; declared means in this module's dependencies (a runtime only another module
 *   brings starts no tool and instruments nothing);
 * - with the network tool, each debuggable variant has OkHttp's `RealCall` and every
 *   `java.net.URL` connection call site of the app and all of its dependencies rewritten (AGP
 *   instrumentation, scope ALL); other variants are not instrumented at all;
 * - each variant that is not debuggable gets the release guard, `hopiReleaseGuard<Variant>`
 *   ([HopiReleaseGuard]): before the variant's build it fails when the runtime classpath holds
 *   `hopi-core`, `hopi-network`, `hopi-perf` or `hopi-start` (`implementation` where
 *   `debugImplementation` was meant);
 * - each debuggable build type holds every Hopi module it resolves (`hopi-api`, `hopi-ktor`, core
 *   and the runtimes) at this plugin's version: core's rules runtime and hopi-perf's runtime
 *   implement internal interfaces of `hopi-api`, the network tool implements `hopi-ktor`'s hooks
 *   interface, and a mismatch would fail at run time.
 *
 * `hopi-core`'s manifest declares the provider that starts the generated tools, so the app adds no code.
 *
 * AGP 8.0 is the oldest it supports. The plugin compiles against AGP 8.3's API, whose
 * `Component.debuggable` and `lifecycleTasks` it calls from 8.3 on only, in [debuggable] and
 * [runBeforePreBuild] (BytecodeFloorTest holds every other reference to AGP 8.0.2's API); below 8.3
 * a variant is as debuggable as its build type, and the guard is a dependency of the variant's
 * preBuild task.
 */
internal object HopiAndroid {
    private const val KTOR_ANDROID_ENGINE = "io/ktor/client/engine/android/AndroidClientEngine"

    /** The oldest AGP the plugin supports. */
    private val FLOOR = AndroidPluginVersion(8, 0)

    /** The AGP that added `Component.debuggable` and `Variant.lifecycleTasks`. */
    private val VARIANT_LIFECYCLE_API = AndroidPluginVersion(8, 3)

    fun configure(project: Project, extension: HopiExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        val agp = components.pluginVersion
        if (agp < FLOOR) {
            throw GradleException(
                "io.github.felipekoga.hopi needs the Android Gradle plugin 8.0 or newer, and ${project.path} applies " +
                    "${agp.major}.${agp.minor}.${agp.micro}: update com.android.application",
            )
        }
        val variantLifecycleApi = agp >= VARIANT_LIFECYCLE_API
        // Debuggable is a build-type property; finalizeDsl is the last point constraints can be declared.
        val realByBuildType = mutableMapOf<String, Boolean>()
        val debuggableByBuildType = mutableMapOf<String, Boolean>()
        components.finalizeDsl { dsl: ApplicationExtension ->
            for (buildType in dsl.buildTypes) {
                debuggableByBuildType[buildType.name] = buildType.isDebuggable
                val real = extension.enabled.get() && buildType.isDebuggable
                realByBuildType[buildType.name] = real
                // Debuggable build types only: the others resolve no runtime, which their guard checks.
                if (real) alignVersions(project, buildType.name)
            }
        }
        components.onVariants(components.selector().all()) { variant ->
            // A variant with no build type the DSL declared counts as not debuggable: it gets the guard.
            val debuggable = if (variantLifecycleApi) debuggable(variant) else debuggableByBuildType[variant.buildType] == true
            val real = extension.enabled.get() && debuggable
            val expected = realByBuildType[variant.buildType]
            if (expected != null && expected != real) {
                throw GradleException(
                    "variant ${variant.name} is ${if (debuggable) "" else "not "}debuggable, unlike its build type " +
                        "${variant.buildType}; io.github.felipekoga.hopi gave it ${if (expected) "Hopi's runtimes" else "no runtime"}",
                )
            }
            if (!debuggable) guard(project, variant, variantLifecycleApi)
            if (!real) return@onVariants
            val network = declares(project, variant, NETWORK)
            val perf = declares(project, variant, PERF)
            if (!network && !perf) {
                project.logger.warn(
                    "io.github.felipekoga.hopi: ${project.path} variant ${variant.name} declares no Hopi runtime, so Hopi does nothing " +
                        "there: add debugImplementation(\"$GROUP:$NETWORK:$HOPI_VERSION\") (and $PERF for the monitors)",
                )
                return@onVariants
            }
            if (network) instrument(extension.network, variant)
            generateConfig(project, extension, variant, network, perf)
        }
    }

    /** AGP 8.3's `Component.debuggable`: called from AGP 8.3 on only, so older AGPs never link it. */
    private fun debuggable(variant: ApplicationVariant): Boolean = variant.debuggable

    /** The bytecode transform and its excludes: debuggable variants that declare `hopi-network` only. */
    private fun instrument(network: HopiNetworkExtension, variant: ApplicationVariant) {
        variant.instrumentation.transformClassesWith(HopiClassVisitorFactory::class.java, InstrumentationScope.ALL) { parameters ->
            parameters.okhttp.set(network.okhttp)
            parameters.urlConnection.set(network.urlConnection)
        }
        variant.instrumentation.excludes.addAll(network.excludedClasses)
    }

    /**
     * The generated start configuration, every debuggable variant: its tool list names the network
     * tool when `hopi-network` is declared and perf's when `hopi-perf` is.
     */
    private fun generateConfig(project: Project, extension: HopiExtension, variant: ApplicationVariant, networkPresent: Boolean, perfPresent: Boolean) {
        val network = extension.network
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val generate = project.tasks.register("generate${name}HopiConfig", GenerateHopiConfig::class.java) { task ->
            task.networkPresent.set(networkPresent)
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
            task.perfPresent.set(perfPresent)
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
     * task's inputs are read; it runs before `pre<Variant>Build`, so before any compile or dex task
     * of the variant ([runBeforePreBuild]).
     */
    private fun guard(project: Project, variant: ApplicationVariant, variantLifecycleApi: Boolean) {
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
        runBeforePreBuild(project, variant, guard, variantLifecycleApi)
    }

    /**
     * Makes [task] run before the variant's `pre<Variant>Build`: through AGP 8.3's `lifecycleTasks`
     * from 8.3 on (called there only, so older AGPs never link it); below 8.3, as a dependency of the
     * task of that name, which AGP registers after the variant callbacks.
     */
    private fun runBeforePreBuild(project: Project, variant: ApplicationVariant, task: TaskProvider<*>, variantLifecycleApi: Boolean) {
        if (variantLifecycleApi) {
            variant.lifecycleTasks.registerPreBuild(task)
            return
        }
        val preBuild = "pre${variant.name.replaceFirstChar(Char::uppercaseChar)}Build"
        project.tasks.configureEach { candidate -> if (candidate.name == preBuild) candidate.dependsOn(task) }
    }

    /**
     * Whether [variant]'s runtime configuration declares the Hopi runtime [module] (this module's
     * dependencies, every configuration it extends, not what they bring transitively): the
     * coordinates, or this repository's project of that name.
     */
    private fun declares(project: Project, variant: ApplicationVariant, module: String): Boolean =
        variant.runtimeConfiguration.allDependencies.any { dependency ->
            when (dependency) {
                is ProjectDependency -> dependency.name == module && HopiModules.isProject(project, module)
                else -> dependency.group == GROUP && dependency.name == module
            }
        }

    /**
     * Holds every Hopi module [buildType] resolves at this plugin's version ([HOPI_VERSION]): the
     * app names `hopi-api`, `hopi-ktor` and the runtimes itself, at whatever version it typed, and
     * they implement each other's internal interfaces, so a mismatch would fail at run time. A
     * constraint, so it adds nothing to a build that never names a module; none for this
     * repository's projects, which are all one version.
     */
    private fun alignVersions(project: Project, buildType: String) {
        val configuration = "${buildType}Implementation"
        for (module in listOf(API, KTOR, CORE, NETWORK, PERF)) {
            if (HopiModules.isProject(project, module)) continue
            project.dependencies.constraints.add(configuration, "$GROUP:$module") { constraint ->
                constraint.version { it.strictly(HOPI_VERSION) }
                constraint.because("io.github.felipekoga.hopi: every Hopi module of a build is the plugin's version, $HOPI_VERSION")
            }
        }
    }
}
