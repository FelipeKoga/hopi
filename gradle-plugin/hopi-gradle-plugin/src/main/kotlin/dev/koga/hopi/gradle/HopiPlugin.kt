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
 * - each debuggable build type gets the real runtime (`hopi-android`), every other one its noop twin;
 * - each debuggable variant has OkHttp's `RealCall` and every `java.net.URL` connection call site of
 *   the app and all of its dependencies rewritten (AGP instrumentation, scope ALL), and the
 *   `hopi { }` block generated into it; other variants are not instrumented at all;
 * - with `selectTwins` (the default), each variant resolves `hopi-ktor`/`hopi-ui`/`hopi-rules` to the real module
 *   when debuggable and to the noop twin otherwise, whichever the app's modules name.
 *
 * The runtime starts itself from a provider of its own manifest, so the app adds no code.
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
        const val RUNTIME = "hopi-android"
        const val RUNTIME_NOOP = "hopi-android-noop"
        val TWINS = listOf("hopi-ktor" to "hopi-ktor-noop", "hopi-ui" to "hopi-ui-noop", "hopi-rules" to "hopi-rules-noop")
    }
}
