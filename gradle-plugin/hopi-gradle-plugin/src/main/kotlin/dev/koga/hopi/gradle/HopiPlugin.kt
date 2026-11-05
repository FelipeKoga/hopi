package dev.koga.hopi.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.util.GradleVersion

/**
 * `plugins { id("io.github.felipekoga.hopi") }`, the whole setup, with an optional `hopi { }` block
 * ([HopiExtension]), in either of two kinds of module:
 *
 * - an Android application module (`com.android.application`; [HopiAndroid],
 *   docs/design/android-capture.md): its debuggable build types get Hopi's runtimes, the
 *   generated start configuration and the instrumentation, its other variants nothing but the
 *   release guard;
 * - a Kotlin Multiplatform module (`org.jetbrains.kotlin.multiplatform`; [HopiKmp]) whose iOS
 *   framework an app links: `hopi-api` in every build, `hopi-start` (which brings the runtimes) in
 *   a Debug build only, a generated `HopiSetup` that Swift calls the same way in every
 *   configuration, and the iOS release guard before every Release link.
 *
 * Each path's code is a class of its own, loaded only once its Gradle plugin is applied, so this
 * plugin loads where AGP is absent (an iOS-only KMP module) or the Kotlin plugin is.
 *
 * The oldest builds it supports: Gradle 8.0, AGP 8.0 ([HopiAndroid]) and, in a KMP module, the
 * Kotlin Gradle plugin 2.2.0 ([KotlinPluginFloor]); an older one fails here with the reason.
 */
public class HopiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val gradle = GradleVersion.current()
        if (gradle.baseVersion < GRADLE_FLOOR) {
            throw GradleException("io.github.felipekoga.hopi needs Gradle ${GRADLE_FLOOR.version} or newer, and this build runs Gradle ${gradle.version}")
        }
        val extension = project.extensions.create("hopi", HopiExtension::class.java)
        extension.applyDefaults()
        var applied = false
        project.pluginManager.withPlugin("com.android.application") {
            applied = true
            HopiAndroid.configure(project, extension)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            applied = true
            // Before HopiKmp loads any of the Kotlin plugin's types, which an older one may lack.
            KotlinPluginFloor.check(project)
            HopiKmp.configure(project, extension)
        }
        project.afterEvaluate {
            if (!applied) {
                throw GradleException(
                    "apply io.github.felipekoga.hopi to the Android application module (com.android.application) or to a Kotlin " +
                        "Multiplatform module (org.jetbrains.kotlin.multiplatform), not to ${project.path}; on Android it " +
                        "instruments the libraries from the application module",
                )
            }
        }
    }

    private companion object {
        val GRADLE_FLOOR: GradleVersion = GradleVersion.version("8.0")
    }
}

/** Hopi's modules, as the plugin adds them. */
internal object HopiModules {
    const val GROUP = "io.github.felipekoga.hopi"
    const val CORE = "hopi-core"
    const val API = "hopi-api"
    const val KTOR = "hopi-ktor"
    const val NETWORK = "hopi-network"
    const val PERF = "hopi-perf"
    const val START = "hopi-start"

    /** This repository's project when it exists (Gradle substitutes only included builds' projects), else the coordinates. */
    fun notation(project: Project, name: String): Any =
        project.rootProject.findProject(":$name")?.let { project.dependencies.project(mapOf("path" to it.path)) } ?: "$GROUP:$name:$HOPI_VERSION"

    fun dependency(project: Project, name: String): Dependency = project.dependencies.create(notation(project, name))

    /** Whether the module [name] is this repository's project rather than the coordinates at [HOPI_VERSION]. */
    fun isProject(project: Project, name: String): Boolean = project.rootProject.findProject(":$name") != null
}

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
