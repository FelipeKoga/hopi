package dev.koga.hopi.gradle

import dev.koga.hopi.gradle.HopiModules.API
import dev.koga.hopi.gradle.HopiModules.NETWORK
import dev.koga.hopi.gradle.HopiModules.PERF
import dev.koga.hopi.gradle.HopiModules.START
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.AbstractNativeLibrary
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBinary
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.Family

/**
 * `io.github.felipekoga.hopi` in a Kotlin Multiplatform module whose iOS framework an app links; every Kotlin
 * Gradle plugin type the plugin names is here, loaded only once `org.jetbrains.kotlin.multiplatform`
 * is applied. A KMP module has no build types, and an iOS target's Debug and Release binaries link
 * one compilation with one dependency set, so the choice is the Gradle invocation's ([IosBuild]: a
 * Debug build gets Hopi's runtimes, any other none), read from providers, so the configuration
 * cache stores it as an input and a switch from Debug to Release configures again:
 *
 * - every build: `hopi-api` (`HopiRules`, `HopiPerf`) as `api` of `commonMain`, exported from
 *   every iOS framework, so Swift and shared code call it in every configuration (without a
 *   runtime they return `-1`, `false` or an empty list, or do nothing);
 * - a Debug build ([HopiExtension.iosRuntime]) of a module that declares a runtime as
 *   `debugImplementation` (`hopi-network`, `hopi-perf`, as an Android app does; the plugin creates
 *   that configuration in a module without an Android target): `hopi-start` as `api` of `iosMain`,
 *   exported from every iOS framework; it brings the runtimes and core, and the generated
 *   `HopiSetup` starts the tools of the declared ones;
 * - every build: `HopiSetup` generated into `iosMain` ([GenerateHopiSetup]): in a Debug build its
 *   `start()` runs `Hopi.start` with the `hopi { }` block, in any other it does nothing, so Swift
 *   calls `HopiSetup.shared.start()` the same way in every configuration;
 * - every Release binary of a Kotlin/Native target: the iOS release guard before its link
 *   ([HopiIosReleaseGuard]), whatever `enabled` says.
 *
 * The module's Android target gets `hopi-api` alone (an Android app applies the plugin itself);
 * JVM targets and CocoaPods' configuration property are not handled.
 */
internal object HopiKmp {
    private const val DEBUG_IMPLEMENTATION = "debugImplementation"

    /** The task the guard of [releaseCheckLink] exempts while it is requested by name. */
    private const val RELEASE_CHECK = "verifyIosReleaseExcludesCapture"

    /** A build-script value naming the link [RELEASE_CHECK] consumes (a Gradle property of that name exempts nothing). */
    private const val EXEMPT_LINK = "hopiIosReleaseGuardExemptLink"

    fun configure(project: Project, extension: HopiExtension) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        extension.iosDebug = IosBuild.debug(project.providers)
        // The runtimes the module declares, as an Android app does: debugImplementation, which a
        // module without an Android target gets from the plugin, as a list to read, never resolved.
        val declared = project.configurations.findByName(DEBUG_IMPLEMENTATION) ?: project.configurations.create(DEBUG_IMPLEMENTATION) {
            it.isCanBeResolved = false
            it.isCanBeConsumed = false
            it.description = "Hopi's runtimes for the iOS Debug frameworks (io.github.felipekoga.hopi)."
        }
        fun declares(module: String): Provider<Boolean> = project.provider {
            declared.dependencies.any { dependency ->
                dependency.name == module && (dependency.group == HopiModules.GROUP || dependency is ProjectDependency)
            }
        }
        val network = declares(NETWORK)
        val perf = declares(PERF)
        extension.iosDeclared = network.zip(perf) { n, p -> n || p }
        val start: Provider<List<Dependency>> = extension.iosRuntime.map { on ->
            if (on) listOf(HopiModules.dependency(project, START)) else emptyList()
        }
        val generate = project.tasks.register("generateHopiSetup", GenerateHopiSetup::class.java) { task ->
            task.description = "Generates HopiSetup, which starts Hopi with the hopi { } block in a Debug build and does nothing in any other."
            task.runtime.set(extension.iosRuntime)
            task.maxEntries.set(extension.maxEntries)
            task.maxTotalBytes.set(extension.maxTotalBytes)
            task.maxBodyBytes.set(extension.maxBodyBytes)
            task.summaryLog.set(extension.summaryLog)
            task.agentBridge.set(extension.agentBridge)
            task.rules.set(extension.network.rules)
            task.redactedHeaders.set(extension.redactedHeaders)
            task.redactedQueryParameters.set(extension.redactedQueryParameters)
            task.redactionDisabled.set(extension.redactionDisabled)
            task.network.set(network)
            task.perf.set(perf)
            task.perfRemoteControl.set(extension.perf.remoteControl)
            task.outputDirectory.set(project.layout.buildDirectory.dir("generated/hopi/iosMain/kotlin"))
        }
        kotlin.sourceSets.matching { it.name == "commonMain" }.configureEach { sourceSet ->
            project.dependencies.add(sourceSet.apiConfigurationName, HopiModules.notation(project, API))
        }
        kotlin.sourceSets.matching { it.name == "iosMain" }.configureEach { sourceSet ->
            sourceSet.kotlin.srcDir(generate.flatMap { it.outputDirectory })
            project.configurations.getByName(sourceSet.apiConfigurationName).dependencies.addAllLater(start)
        }
        val checkRequested = project.gradle.startParameter.taskNames.any { it == RELEASE_CHECK || it.endsWith(":$RELEASE_CHECK") }
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach { target ->
            if (target.konanTarget.family == Family.IOS) {
                target.binaries.withType(Framework::class.java).configureEach { framework ->
                    framework.export(HopiModules.notation(project, API))
                    project.configurations.getByName(framework.exportConfigurationName).dependencies.addAllLater(start)
                }
            }
            target.binaries.matching { it.buildType == NativeBuildType.RELEASE }.configureEach { binary ->
                guard(project, binary, checkRequested)
            }
        }
    }

    /**
     * `hopiReleaseGuard<LinkTask>` before the link of the Release [binary]: its input is the
     * runtimes on the binary's compile classpath (its compilation's, which the target's Debug
     * binary shares, so it holds every runtime the invocation adds) and, for a framework, its export
     * configuration, mapped from their resolution results when the task's inputs are read, never
     * while projects are configured. Read when the task is created, after the build script ran, as
     * is the exempt link the script names.
     */
    private fun guard(project: Project, binary: NativeBinary, checkRequested: Boolean) {
        val link = binary.linkTaskName
        val path = "${if (project.path == ":") "" else project.path}:$link"
        val configurations = listOfNotNull(binary.compilation.compileDependencyConfigurationName, (binary as? AbstractNativeLibrary)?.exportConfigurationName)
        val guard = project.tasks.register("hopiReleaseGuard${link.replaceFirstChar(Char::uppercaseChar)}", HopiIosReleaseGuard::class.java) { task ->
            task.group = LifecycleBasePlugin.VERIFICATION_GROUP
            task.description = "Fails $link when the Release binary's classpath holds one of Hopi's runtimes."
            task.linkPath.set(path)
            val exemptLink = exemptLink(project)
            task.releaseCheckLink.set(link == exemptLink)
            task.exempt.set(checkRequested && link == exemptLink)
            // The compile classpath always exists; an export configuration holds api dependencies
            // only (the Kotlin plugin refuses any other export), so one that does not exist hides nothing.
            for (name in configurations) {
                val configuration = project.configurations.findByName(name) ?: continue
                task.runtimes.addAll(configuration.incoming.resolutionResult.rootComponent.map(HopiRuntimeGraph::refused))
            }
        }
        binary.linkTaskProvider.configure(Action<Task> { it.dependsOn(guard) })
    }

    private fun exemptLink(project: Project): String? {
        val extra = project.extensions.extraProperties
        return if (extra.has(EXEMPT_LINK) && !project.providers.gradleProperty(EXEMPT_LINK).isPresent) extra.get(EXEMPT_LINK) as? String else null
    }
}

/**
 * The Kotlin Gradle plugin a KMP module needs: [FLOOR], because Hopi's iOS libraries are built by
 * Kotlin 2.2.21 at klib ABI 2.2.0, and Kotlin/Native skips a library whose ABI is newer than its
 * own. [HopiPlugin] checks it before [HopiKmp] loads any other type of the Kotlin plugin. First,
 * that this plugin's classes see the applied Kotlin plugin's: they do not when `io.github.felipekoga.hopi` is
 * declared in a parent scope (the root's `plugins { }`, buildSrc) and the Kotlin plugin in the
 * module's, and every call into it would fail ([scopeMessage]). Then the version, from
 * `Project.getKotlinPluginVersion()`, which the Kotlin plugin has had since 1.x (1.8.22 and 1.9.24
 * have it); one that lacks it counts as older.
 */
internal object KotlinPluginFloor {
    const val FLOOR = "2.2.0"

    /** The Kotlin plugin's class [check] calls, `getKotlinPluginVersion`'s file facade, by name. */
    const val VERSION_FACADE = "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt"

    fun check(project: Project) {
        val applied = project.plugins.findPlugin("org.jetbrains.kotlin.multiplatform")
        if (applied != null && !sameClass(VERSION_FACADE, KotlinPluginFloor::class.java.classLoader, applied.javaClass.classLoader)) {
            throw GradleException(scopeMessage(project.path))
        }
        val found = try {
            project.getKotlinPluginVersion()
        } catch (missing: NoSuchMethodError) {
            null
        }
        if (found == null || isBelowFloor(found)) throw GradleException(message(project.path, found))
    }

    /**
     * Whether [ours] resolves the class [name] to the one [theirs] does. Gradle defines a plugin's
     * generated subclass in its class's loader, so [theirs] can be the applied plugin's.
     */
    fun sameClass(name: String, ours: ClassLoader?, theirs: ClassLoader?): Boolean = load(name, ours) === load(name, theirs)

    private fun load(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (absent: ClassNotFoundException) {
        null
    } catch (absent: LinkageError) {
        null
    }

    fun scopeMessage(path: String): String =
        "io.github.felipekoga.hopi cannot see the classes of the Kotlin Gradle plugin that $path applies, which it runs on: declare " +
            "org.jetbrains.kotlin.multiplatform and io.github.felipekoga.hopi in the same plugins { } block, the root build script's " +
            "(with apply false) or $path's (io.github.felipekoga.hopi in a parent build script or in buildSrc does not see a Kotlin " +
            "plugin declared below it)."

    /** Compares major, minor and patch only, so 2.2.0's own pre-releases and 2.2.20-Beta1 pass. */
    fun isBelowFloor(version: String): Boolean {
        fun numbers(v: String) = v.split('.', '-').take(3).map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for ((found, needed) in numbers(version).zip(numbers(FLOOR))) {
            if (found != needed) return found < needed
        }
        return false
    }

    fun message(path: String, found: String?): String =
        "io.github.felipekoga.hopi needs the Kotlin Gradle plugin $FLOOR or newer in a Kotlin Multiplatform module, and $path applies " +
            "org.jetbrains.kotlin.multiplatform ${found ?: "of a version too old to report itself"}: Hopi's iOS libraries are built by " +
            "Kotlin 2.2.21, and Kotlin/Native skips a library built for a newer Kotlin than its own. Use Kotlin $FLOOR or newer."
}

/**
 * Whether this Gradle invocation is an iOS Debug build, which gets Hopi's runtimes: the Gradle
 * property `hopi.enabled` (`true` or `false`) when it is set; else Xcode's build environment as the
 * Kotlin plugin reads it for `embedAndSignAppleFrameworkForXcode` (`XcodeEnvironment.buildType`):
 * `CONFIGURATION` matched exactly, ignoring ASCII case, to `debug` or `release`, else
 * `KOTLIN_FRAMEWORK_BUILD_TYPE` the same way, and nothing when `CONFIGURATION` is unset; else no
 * runtime. So a build outside Xcode (`./gradlew check`, an XCFramework) has none unless
 * `-Phopi.enabled=true` asks for it.
 */
internal object IosBuild {
    const val PROPERTY = "hopi.enabled"

    fun debug(providers: ProviderFactory): Boolean =
        debug(providers.gradleProperty(PROPERTY).orNull, providers.environmentVariable("CONFIGURATION").orNull) {
            providers.environmentVariable("KOTLIN_FRAMEWORK_BUILD_TYPE").orNull
        }

    /** [frameworkBuildType] is read only when [configuration] is neither `debug` nor `release`, as the Kotlin plugin reads it. */
    fun debug(property: String?, configuration: String?, frameworkBuildType: () -> String?): Boolean {
        if (property != null) {
            return when (property.asciiLowercase()) {
                "true" -> true
                "false" -> false
                else -> throw GradleException("-P$PROPERTY must be true or false, not '$property'")
            }
        }
        if (configuration == null) return false
        val buildType = buildType(configuration) ?: frameworkBuildType()?.let(::buildType)
        return buildType == "debug"
    }

    private fun buildType(value: String): String? = value.asciiLowercase().takeIf { it == "debug" || it == "release" }

    private fun String.asciiLowercase(): String = map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
}
