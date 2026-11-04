package dev.koga.hopi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * The iOS release guard (docs/ARCHITECTURE.md, "Release guards"): `hopiReleaseGuard<LinkTask>`
 * (`hopiReleaseGuardLinkReleaseFrameworkIosSimulatorArm64`), which the plugin puts before the link
 * of every Release binary of a KMP module's Kotlin/Native targets ([HopiKmp]), whatever
 * `hopi { enabled }` says. That covers every way a Release framework is linked: Xcode's Release
 * configuration, a custom one with `KOTLIN_FRAMEWORK_BUILD_TYPE=release`, the Release half of
 * `assemble<Name>XCFramework` (one invocation links both build types, so it fails as a whole while
 * `assemble<Name>DebugXCFramework` links Debug only), CocoaPods' pod frameworks and
 * `link<…>Release<…>` itself. It fails when the binary's classpath holds one of Hopi's runtimes
 * ([HopiRuntimeGraph]): the plugin adds `hopi-start` to a Debug build only, so one found here came
 * from `-Phopi.enabled=true` (or Xcode's Debug configuration) in the invocation that links Release,
 * or from a module that names it. No outputs, so it runs before every such link; no opt-out.
 *
 * One exemption, for a release check's positive control, which links a Release framework with
 * every runtime on purpose: the link the module's build script names in
 * `extra["hopiIosReleaseGuardExemptLink"]` (a build-script value: a Gradle property of that name
 * exempts nothing), and only while `verifyIosReleaseExcludesCapture` is requested by name
 * (`verifyIosReleaseExcludesCapture` or `<path>:verifyIosReleaseExcludesCapture`, no
 * abbreviation): that guard still walks the classpath, logs what it would refuse and passes.
 */
@DisableCachingByDefault(because = "A check with no outputs")
public abstract class HopiIosReleaseGuard : DefaultTask() {
    /** `<project path>:<link task>`, for the failure. */
    @get:Input
    public abstract val linkPath: Property<String>

    /** This guards the link the release check consumes (the exempt link the build script named). */
    @get:Input
    public abstract val releaseCheckLink: Property<Boolean>

    /** The release check's positive control was requested by name and this guards its link. */
    @get:Input
    public abstract val exempt: Property<Boolean>

    /**
     * The runtimes found ([HopiRuntimeGraph.refused]) on the binary's compile classpath and in a
     * framework's export configuration: mapped from their resolution results, so resolved when the
     * task's inputs are read (or when the configuration cache stores the task).
     */
    @get:Input
    public abstract val runtimes: ListProperty<String>

    @TaskAction
    public fun check() {
        // The walk runs for the exempt link too, so every invocation that reaches a guard runs it.
        val found = runtimes.get().distinct()
        if (found.isEmpty()) return
        if (exempt.get()) {
            logger.lifecycle("Hopi's iOS release guard: ${linkPath.get()} is exempt (the release check's positive control, requested by name): ${found.joinToString()}")
            return
        }
        throw GradleException(message(linkPath.get(), found, releaseCheckLink.get()))
    }

    internal companion object {
        fun message(link: String, runtimes: List<String>, releaseCheckLink: Boolean): String =
            "Hopi's iOS release guard: $link links a Release binary with Hopi's runtime on its classpath: ${runtimes.joinToString()}. " +
                "A Release binary must hold none: the dev.koga.hopi plugin adds hopi-start to a Debug build only " +
                "(-Phopi.enabled=true, or Xcode's Debug configuration), so link Release in a build without them (Xcode's " +
                "Release configuration, or -Phopi.enabled=false); name no runtime yourself (a module that names one fails " +
                "here in every build); or link only the Debug binary (link<…>Debug…, assemble<Name>DebugXCFramework)." +
                if (releaseCheckLink) " This is the link verifyIosReleaseExcludesCapture consumes: request that task by its own name for its positive control." else ""
    }
}
