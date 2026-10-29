package dev.koga.hopi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * The build-time release guard (docs/ARCHITECTURE.md, "Release guards"): `hopiReleaseGuard<Variant>`,
 * registered for every variant that is not debuggable, whatever `hopi { enabled }` says, and run
 * before the variant's `pre<Variant>Build`, so before any of its compile or dex tasks. It fails when
 * the variant's runtime classpath holds one of Hopi's runtimes ([HopiRuntimeGraph]): this plugin
 * adds them to debuggable variants only, so one found here was named by a module, by hand or by a
 * library. No outputs, so it runs in every build of the variant; no opt-out.
 */
@DisableCachingByDefault(because = "A check with no outputs")
public abstract class HopiReleaseGuard : DefaultTask() {
    @get:Input
    public abstract val variantName: Property<String>

    /** The variant's build type, whose `<buildType>Implementation` would name a runtime for it. */
    @get:Input
    public abstract val buildType: Property<String>

    /**
     * The runtimes found, each with the components that bring it ([HopiRuntimeGraph.refused]):
     * mapped from the variant's resolution result, so resolved when the task's inputs are read (or
     * when the configuration cache stores the task), never while projects are configured.
     */
    @get:Input
    public abstract val runtimes: ListProperty<String>

    /** The runtimes to remove ([HopiRuntimeGraph.toRemove]), mapped from the same result. */
    @get:Input
    public abstract val remove: ListProperty<String>

    @TaskAction
    public fun check() {
        val found = runtimes.get()
        if (found.isNotEmpty()) throw GradleException(message(variantName.get(), buildType.get(), found, remove.get()))
    }

    internal companion object {
        fun message(variant: String, buildType: String, runtimes: List<String>, remove: List<String>): String =
            "dev.koga.hopi: variant $variant is not debuggable, but its runtime classpath holds Hopi's runtime: " +
                "${runtimes.joinToString()}. Hopi's runtimes belong in debuggable variants only, where this plugin adds them: " +
                "remove ${remove.joinToString()} from $variant (in this module or the one that brings it: name it for debuggable " +
                "build types only, as debugImplementation, not as implementation or ${buildType}Implementation)."
    }
}

/**
 * Hopi's runtimes in a resolved dependency graph, for the release guard. An object, not a function
 * of the plugin: the configuration cache stores the provider mappings that use it.
 */
internal object HopiRuntimeGraph {
    private const val GROUP = "dev.koga.hopi"
    private val RUNTIMES = setOf("hopi-core", "hopi-network", "hopi-perf", "hopi-start")

    /** A KMP root's coordinates redirect to its platform module's (`hopi-network-android`). */
    private val PLATFORM_SUFFIXES = listOf("-android", "-jvm", "-iosarm64", "-iossimulatorarm64")

    /**
     * Whether [id] is one of Hopi's runtimes: the projects `:hopi-core`, `:hopi-network`,
     * `:hopi-perf` and `:hopi-start`, or those modules of `dev.koga.hopi`, with or without a
     * platform suffix. Exact names, not prefixes (a stale `hopi-perf-noop` is not a runtime);
     * `hopi-api` and `hopi-ktor` are not runtimes.
     */
    fun isRuntime(id: ComponentIdentifier): Boolean = isOneOf(id, RUNTIMES)

    /** [id] is the project `:<name>` or the module `dev.koga.hopi:<name>[<platform suffix>]` of one of [names]. */
    private fun isOneOf(id: ComponentIdentifier, names: Set<String>): Boolean = when (id) {
        is ProjectComponentIdentifier -> names.any { id.projectPath == ":$it" }
        is ModuleComponentIdentifier -> id.group == GROUP &&
            (id.module in names || PLATFORM_SUFFIXES.any { suffix -> id.module.endsWith(suffix) && id.module.removeSuffix(suffix) in names })
        else -> false
    }

    /**
     * Each runtime in the graph under [root], in the order a walk from the root meets them, as its
     * display name followed by the components that depend on it, the root left out
     * (`project ':hopi-core' (via project ':hopi-network')`, `dev.koga.hopi:hopi-network:0.1.0`).
     */
    fun refused(root: ResolvedComponentResult): List<String> = runtimesIn(root).map { describe(it, root.id) }

    /**
     * The runtimes to remove, by display name, in the same order: each one that something other
     * than a runtime brings (the variant itself, a library); one that only other runtimes bring
     * (core under `hopi-network`, a KMP root's platform module) goes with them.
     */
    fun toRemove(root: ResolvedComponentResult): List<String> =
        runtimesIn(root).filter { runtime -> broughtBy(runtime).any { !isRuntime(it) } }.map { it.id.displayName }

    private fun runtimesIn(root: ResolvedComponentResult): List<ResolvedComponentResult> {
        val found = ArrayList<ResolvedComponentResult>()
        val visited = HashSet<ComponentIdentifier>()
        visited.add(root.id)
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (component !== root && isRuntime(component.id)) found += component
            for (dependency in component.dependencies) {
                if (dependency is ResolvedDependencyResult && visited.add(dependency.selected.id)) queue.add(dependency.selected)
            }
        }
        return found
    }

    /** The components whose dependencies, not constraints, bring [component]: a constraint brings nothing. */
    private fun broughtBy(component: ResolvedComponentResult): List<ComponentIdentifier> =
        component.dependents.filter { !it.isConstraint }.map { it.from.id }

    private fun describe(component: ResolvedComponentResult, root: ComponentIdentifier): String {
        val via = component.dependents.map { it.from.id }.filter { it != root }.map { it.displayName }.distinct()
        return if (via.isEmpty()) component.id.displayName else via.joinToString(prefix = "${component.id.displayName} (via ", postfix = ")")
    }
}
