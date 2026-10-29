package dev.koga.hopi.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * `plugins { id("dev.koga.hopi") }` in an Android application module (docs/design/android-capture.md):
 *
 * - each debuggable build type gets the real runtime (`hopi-android`) and `hopi-core`, every other
 *   one the runtime's noop twin: javac compiles the generated configuration inside the app, and it
 *   names core's types, which every runtime takes as `implementation` only;
 * - each debuggable variant has OkHttp's `RealCall` and every `java.net.URL` connection call site of
 *   the app and all of its dependencies rewritten (AGP instrumentation, scope ALL), and the
 *   `hopi { }` block generated into it as the start configuration and its tool list; other
 *   variants are not instrumented at all;
 * - with `selectTwins` (the default), each variant resolves `hopi-ktor` to the real module when
 *   debuggable and to the noop twin otherwise, whichever the app's modules name;
 * - each debuggable build type holds `dev.koga.hopi:hopi-api` (`HopiRules` and `HopiPerf`, which the
 *   app's modules name in every build type) at this plugin's version, the version of the
 *   `hopi-core` and `hopi-perf` it adds: core's rules runtime and hopi-perf's runtime implement
 *   internal interfaces of `hopi-api`, and a mismatch would fail at run time;
 * - with `perf { enabled = true }`, each debuggable build type gets `hopi-perf` (the others nothing:
 *   `HopiPerf` is `hopi-api`'s) and the tool list names its tool, which registers the monitors and
 *   starts the `autoStart` ones (docs/design/perf-monitors.md).
 *
 * `hopi-core`'s manifest declares the provider that starts the generated tools, so the app adds no code.
 */
public class HopiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("hopi", HopiExtension::class.java)
        extension.applyDefaults()
        var android = false
        project.pluginManager.withPlugin("com.android.application") {
            android = true
            configure(project, extension)
        }
        project.afterEvaluate {
            if (!android) {
                throw GradleException(
                    "apply dev.koga.hopi to the Android application module (com.android.application) of ${project.path}; " +
                        "it instruments the libraries from there",
                )
            }
        }
    }

    private fun configure(project: Project, extension: HopiExtension) {
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        // Debuggable is a build-type property; finalizeDsl is the last point dependencies can be declared.
        val realByBuildType = mutableMapOf<String, Boolean>()
        components.finalizeDsl { dsl: ApplicationExtension ->
            for (buildType in dsl.buildTypes) {
                val real = extension.enabled.get() && buildType.isDebuggable
                realByBuildType[buildType.name] = real
                project.dependencies.add("${buildType.name}Implementation", runtime(project, if (real) RUNTIME else RUNTIME_NOOP))
                // The generated configuration extends core's HopiConfigValues and lists core's
                // HopiTool: javac needs core on the variant's compile classpath, and no runtime
                // exposes it there.
                if (real) project.dependencies.add("${buildType.name}Implementation", runtime(project, CORE))
                if (real) alignApi(project, buildType.name)
                // The monitors' runtime, debuggable build types only: HopiPerf, which the app calls,
                // is hopi-api's.
                if (real && extension.perf.enabled.get()) project.dependencies.add("${buildType.name}Implementation", runtime(project, PERF))
            }
        }
        components.onVariants(components.selector().all()) { variant ->
            val real = extension.enabled.get() && variant.debuggable
            val expected = realByBuildType[variant.buildType]
            if (expected != null && expected != real) {
                throw GradleException(
                    "variant ${variant.name} is ${if (variant.debuggable) "" else "not "}debuggable, unlike its build type " +
                        "${variant.buildType}; dev.koga.hopi gave it the ${if (expected) "real" else "noop"} runtime",
                )
            }
            if (real) instrument(project, extension, variant)
            if (extension.selectTwins.get()) {
                selectTwins(project, variant.compileConfiguration, real)
                selectTwins(project, variant.runtimeConfiguration, real)
            }
        }
    }

    private fun instrument(project: Project, extension: HopiExtension, variant: ApplicationVariant) {
        variant.instrumentation.transformClassesWith(HopiClassVisitorFactory::class.java, InstrumentationScope.ALL) { parameters ->
            parameters.okhttp.set(extension.okhttp)
            parameters.urlConnection.set(extension.urlConnection)
        }
        variant.instrumentation.excludes.addAll(extension.excludedClasses)
        val name = variant.name.replaceFirstChar(Char::uppercaseChar)
        val generate = project.tasks.register("generate${name}HopiConfig", GenerateHopiConfig::class.java) { task ->
            task.okhttp.set(extension.okhttp)
            // The runtime defers Ktor's Android engine to the wrapper only when that engine's own
            // `URL` calls were rewritten, which an exclude pattern can prevent.
            task.urlConnection.set(
                extension.urlConnection.zip(extension.excludedClasses) { on, excludes -> on && excludes.none { excludes(it, KTOR_ANDROID_ENGINE) } },
            )
            task.maxEntries.set(extension.maxEntries)
            task.maxTotalBytes.set(extension.maxTotalBytes)
            task.maxBodyBytes.set(extension.maxBodyBytes)
            task.summaryLog.set(extension.summaryLog)
            task.agentBridge.set(extension.agentBridge)
            task.rules.set(extension.rules)
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
     * Resolves both directions of each twin pair to the side this variant needs, for this
     * repository's projects and for the published coordinates. Safe because the twins have the same
     * ABI with no inlined body (`:checkNoopParity`).
     */
    private fun selectTwins(project: Project, configuration: Configuration, real: Boolean) {
        val root = project.rootProject
        configuration.resolutionStrategy.dependencySubstitution { substitutions ->
            for ((realName, noopName) in TWINS) {
                val from = if (real) noopName else realName
                val to = if (real) realName else noopName
                if (root.findProject(":$from") != null && root.findProject(":$to") != null) {
                    substitutions.substitute(substitutions.project(":$from")).using(substitutions.project(":$to"))
                        .because("dev.koga.hopi: ${if (real) "debuggable variants get the real module" else "other variants get the noop twin"}")
                }
                substitutions.substitute(substitutions.module("$GROUP:$from")).using(substitutions.module("$GROUP:$to:$HOPI_VERSION"))
            }
        }
    }

    /**
     * Holds `hopi-api` at [HOPI_VERSION] in [buildType], which gets core, and `hopi-perf` with
     * `perf { enabled = true }`, at that version: the app names `hopi-api` itself (it holds
     * `HopiRules` and `HopiPerf`), at whatever version it chose, and core's rules runtime and
     * hopi-perf's runtime implement internal interfaces of it. A constraint, so it adds nothing to
     * a build that never names `hopi-api`; none for this repository's project, which is core's own
     * version.
     */
    private fun alignApi(project: Project, buildType: String) {
        if (project.rootProject.findProject(":$API") != null) return
        project.dependencies.constraints.add("${buildType}Implementation", "$GROUP:$API") { constraint ->
            constraint.version { it.strictly(HOPI_VERSION) }
            constraint.because("dev.koga.hopi: hopi-core $HOPI_VERSION implements hopi-api $HOPI_VERSION's internal interfaces")
        }
    }

    /** This repository's project when it exists (Gradle substitutes only included builds' projects), else the coordinates. */
    private fun runtime(project: Project, name: String): Any =
        project.rootProject.findProject(":$name")?.let { project.dependencies.project(mapOf("path" to it.path)) } ?: "$GROUP:$name:$HOPI_VERSION"

    /**
     * Whether the instrumentation exclude [pattern] (a glob over class names, `*` within one
     * package, `**` across them, written with dots or slashes) matches the class [internalName].
     */
    internal fun excludes(pattern: String, internalName: String): Boolean {
        val glob = pattern.replace('.', '/').removeSuffix("/class")
        val regex = buildString {
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.getOrNull(i + 1) == '*' -> append(".*").also { i++ }
                    c == '*' -> append("[^/]*")
                    c == '?' -> append("[^/]")
                    else -> append(Regex.escape(c.toString()))
                }
                i++
            }
        }
        return Regex(regex).matches(internalName)
    }

    private companion object {
        const val KTOR_ANDROID_ENGINE = "io/ktor/client/engine/android/AndroidClientEngine"
        const val GROUP = "dev.koga.hopi"
        const val CORE = "hopi-core"
        const val API = "hopi-api"
        const val RUNTIME = "hopi-android"
        const val RUNTIME_NOOP = "hopi-android-noop"
        const val PERF = "hopi-perf"
        val TWINS = listOf("hopi-ktor" to "hopi-ktor-noop")
    }
}
