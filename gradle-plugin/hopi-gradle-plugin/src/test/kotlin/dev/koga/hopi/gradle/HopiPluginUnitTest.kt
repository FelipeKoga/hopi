package dev.koga.hopi.gradle

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ComponentSelectionReason
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.testfixtures.ProjectBuilder

class HopiPluginUnitTest {
    @Test
    fun theRuntimeVersionIsTheRepositorysVersion() {
        val properties = Properties().apply { File(System.getProperty("hopi.repositoryProperties")).inputStream().use(::load) }
        assertEquals(properties.getProperty("hopi.version"), HOPI_VERSION)
    }

    private fun generateTask(network: Boolean, perf: Boolean): GenerateHopiConfig {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("generate", GenerateHopiConfig::class.java) { task ->
            task.networkPresent.set(network)
            task.okhttp.set(false)
            task.urlConnection.set(true)
            task.maxEntries.set(42)
            task.maxTotalBytes.set(1024L)
            task.maxBodyBytes.set(512L)
            task.summaryLog.set(true)
            task.agentBridge.set(true)
            task.redactedHeaders.set(listOf("X-Api-Key", "Quote\"d"))
            task.redactedQueryParameters.set(listOf("token"))
            task.redactionDisabled.set(false)
            task.rules.set(false)
            task.perfPresent.set(perf)
            task.perfAutoStart.set(HopiPerfExtension.bits(setOf(HopiMonitor.Hangs, HopiMonitor.Frames)))
            task.perfHangThresholdMs.set(300L)
            task.perfHangHeartbeat.set(true)
            task.perfRemoteControl.set(false)
        }.get()
    }

    @Test
    fun theConfigClassPassesTheBlockAndTheToolListToTheBaseConstructor() {
        val source = generateTask(network = true, perf = true).render()
        assertTrue("extends dev.koga.hopi.android.internal.HopiConfigValues" in source, source)
        assertTrue("42, // maxEntries" in source, source)
        assertTrue("1024L, // maxTotalBytes" in source, source)
        assertTrue("512L, // maxBodyBytes" in source, source)
        assertTrue("true, // summaryLog" in source, source)
        assertTrue("true, // agentBridge" in source, source)
        assertTrue("false, // redactionDisabled" in source, source)
        assertTrue("""new String[] { "X-Api-Key", "Quote\"d" }, // redactHeaders""" in source, source)
        assertTrue("""new String[] { "token" }, // redactQueryParameters""" in source, source)
        // Each tool with its own settings.
        assertTrue("new dev.koga.hopi.android.internal.NetworkTool(false, true, false), // okhttp, urlConnection, rules" in source, source)
        // Frames and Hangs: bits 0 and 1, the order PerfEntry decodes; then the heartbeat and remote control.
        assertTrue(
            "new dev.koga.hopi.perf.internal.PerfEntry(3, 300L, true, false) // perf { }: autoStart, hangThresholdMs, hangHeartbeat, remoteControl" in source,
            source,
        )
        assertTrue(source.indexOf("NetworkTool(") < source.indexOf("PerfEntry("), source)
    }

    @Test
    fun withoutThePerfBlockTheToolListNamesTheNetworkToolOnly() {
        val source = generateTask(network = true, perf = false).render()
        assertTrue("new dev.koga.hopi.android.internal.NetworkTool(false, true, false) // okhttp, urlConnection, rules\n        );" in source, source)
        assertFalse("PerfEntry" in source, source)
    }

    @Test
    fun withTheNetworkOffTheToolListNamesPerfOnly() {
        val source = generateTask(network = false, perf = true).render()
        assertFalse("NetworkTool" in source, source)
        assertTrue("""new String[] { "token" }, // redactQueryParameters""" in source, source)
        // The perf tool is the last argument: no comma after it.
        assertTrue(
            "new dev.koga.hopi.perf.internal.PerfEntry(3, 300L, true, false) // perf { }: autoStart, hangThresholdMs, hangHeartbeat, remoteControl\n        );" in source,
            source,
        )
    }

    @Test
    fun withNoToolTheRedactionListIsTheLastArgument() {
        // Network off and perf off: the start still runs (the rules runtime, the bridge), with no tool.
        val source = generateTask(network = false, perf = false).render()
        assertFalse("NetworkTool" in source, source)
        assertFalse("PerfEntry" in source, source)
        assertTrue("""new String[] { "token" } // redactQueryParameters""" + "\n        );" in source, source)
    }

    @Test
    fun networkIsOnByDefault() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("hopi", HopiExtension::class.java)
        extension.applyDefaults()
        assertEquals(true, extension.network.enabled.get())
        assertEquals(true, extension.network.okhttp.get())
        assertEquals(true, extension.network.urlConnection.get())
        assertEquals(true, extension.network.rules.get(), "rules on unless refused")
        assertEquals(emptyList(), extension.network.excludedClasses.get())
        extension.network { it.excludeClasses("com.vendor.pinned.**") }
        assertEquals(listOf("com.vendor.pinned.**"), extension.network.excludedClasses.get())
    }

    @Test
    fun perfIsOffByDefault() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("hopi", HopiExtension::class.java)
        extension.applyDefaults()
        assertEquals(false, extension.perf.enabled.get())
        assertEquals(emptySet(), extension.perf.autoStart.get())
        assertEquals(250L, extension.perf.hangThresholdMs.get())
        assertEquals(false, extension.perf.hangHeartbeat.get())
        assertEquals(true, extension.perf.remoteControl.get(), "remote control on unless refused")
        extension.perf { it.autoStart(HopiMonitor.Memory) }
        assertEquals(8, HopiPerfExtension.bits(extension.perf.autoStart.get()))
    }

    @Test
    fun anExcludePatternThatCoversKtorsAndroidEngineIsRecognized() {
        val plugin = HopiPlugin()
        val engine = "io/ktor/client/engine/android/AndroidClientEngine"
        for (pattern in listOf("io.ktor.**", "io/ktor/**", "io.ktor.client.engine.android.*", "io/ktor/client/engine/*/AndroidClientEngine", "**")) {
            assertTrue(plugin.excludes(pattern, engine), pattern)
        }
        for (pattern in listOf("io.ktor.*", "com.vendor.pinned.**", "io/ktor/client/engine/okhttp/**", "io.ktor.client.engine.android.Other")) {
            assertEquals(false, plugin.excludes(pattern, engine), pattern)
        }
    }

    @Test
    fun theReleaseGuardRefusesExactlyHopisRuntimes() {
        for (path in listOf(":hopi-core", ":hopi-network", ":hopi-perf", ":hopi-start")) {
            assertTrue(HopiRuntimeGraph.isRuntime(ProjectId(path)), path)
        }
        // A runtime of another project path, and the modules that are no runtime.
        for (path in listOf(":lib:hopi-core", ":hopi-api", ":hopi-ktor", ":hopi-ktor-noop", ":")) {
            assertFalse(HopiRuntimeGraph.isRuntime(ProjectId(path)), path)
        }
        // A KMP root and the platform modules it redirects to.
        for (module in listOf("hopi-core", "hopi-network-android", "hopi-start-iossimulatorarm64", "hopi-perf-jvm", "hopi-network-iosarm64")) {
            assertTrue(HopiRuntimeGraph.isRuntime(ModuleId(GROUP, module)), module)
        }
        // Exact names, not prefixes: the stale hopi-perf-noop in Maven Local is no runtime.
        for (module in listOf("hopi-perf-noop", "hopi-api", "hopi-ktor", "hopi-ktor-noop", "hopi-ktor-android", "hopi-core-extra", "hopi-network-")) {
            assertFalse(HopiRuntimeGraph.isRuntime(ModuleId(GROUP, module)), module)
        }
        assertFalse(HopiRuntimeGraph.isRuntime(ModuleId("com.other", "hopi-core")))
    }

    @Test
    fun theReleaseGuardNamesWhatBringsEachRuntimeAndRemovesOnlyWhatSomethingElseBrings() {
        // The app names hopi-network, which brings core: core goes with it.
        var app = Component(ProjectId(":app"))
        app.dependsOn(Component(ProjectId(":hopi-network")).dependsOn(Component(ProjectId(":hopi-core"))))
        assertEquals(listOf("project ':hopi-network'", "project ':hopi-core' (via project ':hopi-network')"), HopiRuntimeGraph.refused(app))
        assertEquals(listOf("project ':hopi-network'"), HopiRuntimeGraph.toRemove(app))

        // The app names core as well: both are to be removed.
        app = Component(ProjectId(":app"))
        val core = Component(ProjectId(":hopi-core"))
        app.dependsOn(Component(ProjectId(":hopi-network")).dependsOn(core), core)
        assertEquals(listOf("project ':hopi-network'", "project ':hopi-core'"), HopiRuntimeGraph.toRemove(app))

        // The thin hopi-ktor, in every build, brings no runtime: only hopi-api.
        app = Component(ProjectId(":app"))
        app.dependsOn(Component(ProjectId(":hopi-ktor")).dependsOn(Component(ProjectId(":hopi-api"))))
        assertEquals(emptyList(), HopiRuntimeGraph.refused(app))
        assertEquals(emptyList(), HopiRuntimeGraph.toRemove(app))

        // Published coordinates: a library names the KMP root, which redirects to its platform
        // module, which brings core's root and its platform module; only the root is to be removed.
        app = Component(ProjectId(":app"))
        val sdk = Component(ProjectId(":sdk"))
        app.dependsOn(sdk)
        sdk.dependsOn(
            Component(ModuleId(GROUP, "hopi-network")).dependsOn(
                Component(ModuleId(GROUP, "hopi-network-android")).dependsOn(
                    Component(ModuleId(GROUP, "hopi-core")).dependsOn(Component(ModuleId(GROUP, "hopi-core-android"))),
                ),
            ),
        )
        assertEquals("dev.koga.hopi:hopi-network:0.1.0 (via project ':sdk')", HopiRuntimeGraph.refused(app).first())
        assertEquals(listOf("dev.koga.hopi:hopi-network:0.1.0"), HopiRuntimeGraph.toRemove(app))

        // A constraint brings nothing: core, constrained by the app and brought by hopi-network only, goes with it.
        app = Component(ProjectId(":app"))
        val constrained = Component(ProjectId(":hopi-core"))
        app.dependsOn(Component(ProjectId(":hopi-network")).dependsOn(constrained))
        app.constrains(constrained)
        assertEquals(listOf("project ':hopi-network'"), HopiRuntimeGraph.toRemove(app))
    }

    @Test
    fun theReleaseGuardsFailureSaysWhatToRemove() {
        val text = HopiReleaseGuard.message("freeQa", "qa", listOf("project ':hopi-network'", "project ':hopi-core' (via project ':hopi-network')"), listOf("project ':hopi-network'"))
        assertTrue(text.startsWith("dev.koga.hopi: variant freeQa is not debuggable"), text)
        assertTrue("remove project ':hopi-network' from freeQa (" in text, text)
        assertTrue(text.endsWith("not as implementation or qaImplementation)."), text)
        // No twin is left to select since stage 10: the advice never names selectTwins.
        assertFalse("selectTwins" in text, text)
        assertFalse("selectTwins" in HopiReleaseGuard.message("release", "release", listOf("project ':hopi-network'"), listOf("project ':hopi-network'")))
    }

    @Test
    fun applyingItOutsideAnAndroidApplicationFails() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java-library")
        project.pluginManager.apply(HopiPlugin::class.java)
        val failure = runCatching { (project as org.gradle.api.internal.project.ProjectInternal).evaluate() }.exceptionOrNull()
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "apply dev.koga.hopi to the Android application module" in it }, messages.toString())
    }
}

private const val GROUP = "dev.koga.hopi"

/** A resolved coordinate, displayed as Gradle displays one. */
private class ModuleId(private val groupName: String, private val moduleName: String) : ModuleComponentIdentifier {
    override fun getGroup(): String = groupName
    override fun getModule(): String = moduleName
    override fun getVersion(): String = "0.1.0"
    override fun getModuleIdentifier(): ModuleIdentifier = object : ModuleIdentifier {
        override fun getGroup(): String = groupName
        override fun getName(): String = moduleName
    }
    override fun getDisplayName(): String = "$groupName:$moduleName:0.1.0"
}

/** A project of this build, displayed as Gradle displays one. */
private class ProjectId(private val path: String) : ProjectComponentIdentifier {
    override fun getBuild(): BuildIdentifier = BuildIdentifier { ":" }
    override fun getProjectPath(): String = path
    override fun getBuildTreePath(): String = path
    override fun getProjectName(): String = path.substringAfterLast(':')
    override fun getDisplayName(): String = "project '$path'"
}

/** A component of a resolved graph: each edge is both its source's dependency and its target's dependent, as in Gradle's result. */
private class Component(private val identifier: ComponentIdentifier) : ResolvedComponentResult {
    private val outgoing = LinkedHashSet<Edge>()
    private val incoming = LinkedHashSet<Edge>()

    fun dependsOn(vararg others: Component): Component = apply { for (other in others) link(other, constraint = false) }

    fun constrains(other: Component): Component = apply { link(other, constraint = true) }

    private fun link(other: Component, constraint: Boolean) {
        val edge = Edge(this, other, constraint)
        outgoing += edge
        other.incoming += edge
    }

    override fun getId(): ComponentIdentifier = identifier
    override fun getDependencies(): Set<DependencyResult> = outgoing
    override fun getDependents(): Set<ResolvedDependencyResult> = incoming
    override fun getSelectionReason(): ComponentSelectionReason = throw UnsupportedOperationException()
    override fun getModuleVersion(): ModuleVersionIdentifier? = null
    override fun getVariants(): List<ResolvedVariantResult> = emptyList()
    override fun getDependenciesForVariant(variant: ResolvedVariantResult): List<DependencyResult> = emptyList()
}

private class Edge(private val source: Component, private val target: Component, private val constraint: Boolean) : ResolvedDependencyResult {
    override fun getSelected(): ResolvedComponentResult = target
    override fun getResolvedVariant(): ResolvedVariantResult = throw UnsupportedOperationException()
    override fun getRequested(): ComponentSelector = throw UnsupportedOperationException()
    override fun getFrom(): ResolvedComponentResult = source
    override fun isConstraint(): Boolean = constraint
}
