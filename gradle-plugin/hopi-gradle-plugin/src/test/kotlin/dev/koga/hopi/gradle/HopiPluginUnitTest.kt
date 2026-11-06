package dev.koga.hopi.gradle

import java.io.File
import java.net.URL
import java.net.URLClassLoader
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
        val engine = "io/ktor/client/engine/android/AndroidClientEngine"
        for (pattern in listOf("io.ktor.**", "io/ktor/**", "io.ktor.client.engine.android.*", "io/ktor/client/engine/*/AndroidClientEngine", "**")) {
            assertTrue(excludes(pattern, engine), pattern)
        }
        for (pattern in listOf("io.ktor.*", "com.vendor.pinned.**", "io/ktor/client/engine/okhttp/**", "io.ktor.client.engine.android.Other")) {
            assertEquals(false, excludes(pattern, engine), pattern)
        }
    }

    /**
     * The iOS build of a Gradle invocation: `-Phopi.enabled` when set, else Xcode's environment as
     * the Kotlin plugin reads it (`CONFIGURATION` exactly, ignoring case; `KOTLIN_FRAMEWORK_BUILD_TYPE`
     * only for another configuration name; nothing without `CONFIGURATION`), else no runtime.
     */
    @Test
    fun theIosBuildIsThePropertyElseXcodesConfigurationAsKotlinReadsIt() {
        fun debug(property: String?, configuration: String?, fallback: String? = null): Boolean {
            var read = false
            val result = IosBuild.debug(property, configuration) { read = true; fallback }
            val expectRead = property == null && configuration != null && configuration.lowercase() !in setOf("debug", "release")
            assertEquals(expectRead, read, "KOTLIN_FRAMEWORK_BUILD_TYPE read for $property/$configuration")
            return result
        }
        assertTrue(debug("true", null))
        assertTrue(debug("TRUE", "Release"), "the property wins over Xcode")
        assertFalse(debug("false", "Debug"), "the no-runtime Debug arm")
        assertFalse(debug(null, null), "outside Xcode: no runtime")
        assertFalse(debug(null, null, "debug"), "no CONFIGURATION: the fallback is not read, as Kotlin does")
        assertTrue(debug(null, "Debug"))
        assertTrue(debug(null, "debug"))
        assertFalse(debug(null, "Release"))
        assertFalse(debug(null, "Release", "debug"), "the fallback never overrides Release")
        assertTrue(debug(null, "Staging", "Debug"), "a custom configuration takes the fallback")
        assertFalse(debug(null, "Staging", "release"))
        assertFalse(debug(null, "Staging"), "a custom configuration without the fallback: none (Kotlin fails its embed task)")
        assertFalse(debug(null, "Debug-Staging", "nope"))
        val failure = runCatching { IosBuild.debug("yes", null) { null } }.exceptionOrNull()
        assertEquals("-Phopi.enabled must be true or false, not 'yes'", failure?.message)
    }

    private fun setupTask(runtime: Boolean): GenerateHopiSetup {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("generateHopiSetup", GenerateHopiSetup::class.java) { task ->
            task.runtime.set(runtime)
            task.maxEntries.set(42)
            task.maxTotalBytes.set(4096L)
            task.maxBodyBytes.set(1024L)
            task.summaryLog.set(false)
            task.agentBridge.set(true)
            task.rules.set(false)
            task.redactedHeaders.set(listOf("X-Api-Key", "Quote\"d\\\$x"))
            task.redactedQueryParameters.set(listOf("token"))
            task.redactionDisabled.set(true)
            task.perf.set(true)
            task.perfRemoteControl.set(false)
        }.get()
    }

    @Test
    fun theDebugSetupStartsHopiWithTheBlock() {
        val source = setupTask(runtime = true).render()
        assertTrue("package dev.koga.hopi.setup\n" in source, source)
        assertTrue("public object HopiSetup {" in source, source)
        assertTrue("    public fun start() {\n        dev.koga.hopi.start.Hopi.start {\n" in source, source)
        for (line in listOf(
            "maxEntries = 42", "maxTotalBytes = 4096L", "maxBodyBytes = 1024L", "summaryLog = false", "agentBridge = true",
            "rules = false", "perf = true", "perfRemoteControl = false", "unsafeDisableRedaction()",
            """redactHeaders("X-Api-Key", "Quote\"d\\\${'$'}x")""", """redactQueryParameters("token")""",
        )) {
            assertTrue("            $line\n" in source, "$line in $source")
        }
    }

    @Test
    fun theReleaseSetupDoesNothingAndNamesNoHopiType() {
        val source = setupTask(runtime = false).render()
        assertTrue("public object HopiSetup {" in source, source)
        assertTrue("    public fun start() {\n    }\n" in source, source)
        assertFalse("dev.koga.hopi.start" in source, source)
        assertFalse("agentBridge" in source, source)
    }

    @Test
    fun theIosReleaseGuardsFailureSaysWhatToDo() {
        val runtimes = listOf("project ':hopi-start'", "project ':hopi-core' (via project ':hopi-start')")
        val text = HopiIosReleaseGuard.message(":shared:linkReleaseFrameworkIosArm64", runtimes, releaseCheckLink = false)
        assertTrue(text.startsWith("Hopi's iOS release guard: :shared:linkReleaseFrameworkIosArm64 links a Release binary"), text)
        assertTrue("on its classpath: project ':hopi-start', project ':hopi-core' (via project ':hopi-start')." in text, text)
        assertTrue("-Phopi.enabled=false" in text, text)
        assertFalse("verifyIosReleaseExcludesCapture" in text, text)
        assertTrue(HopiIosReleaseGuard.message(":shared:l", runtimes, releaseCheckLink = true).endsWith("for its positive control."))
    }

    @Test
    fun theReleaseGuardRefusesExactlyHopisRuntimes() {
        for (path in listOf(":hopi-core", ":hopi-network", ":hopi-perf", ":hopi-start")) {
            assertTrue(HopiRuntimeGraph.isRuntime(ProjectId(path)), path)
        }
        // A runtime of another project path, and the modules that are no runtime (the `-noop`
        // modules the runtime-split plan deleted, 2026-10-28 and 29, among them).
        for (path in listOf(":lib:hopi-core", ":hopi-api", ":hopi-ktor", ":hopi-ktor-noop", ":")) {
            assertFalse(HopiRuntimeGraph.isRuntime(ProjectId(path)), path)
        }
        // A KMP root and the platform modules it redirects to.
        for (module in listOf("hopi-core", "hopi-network-android", "hopi-start-iossimulatorarm64", "hopi-perf-jvm", "hopi-network-iosarm64")) {
            assertTrue(HopiRuntimeGraph.isRuntime(ModuleId(GROUP, module)), module)
        }
        // Exact names, not prefixes: the `-noop` modules the runtime-split plan deleted (2026-10-28
        // and 29), which Maven Local may still hold at 0.1.0, are no runtime.
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
        assertEquals("io.github.felipekoga.hopi:hopi-network:0.1.0 (via project ':sdk')", HopiRuntimeGraph.refused(app).first())
        assertEquals(listOf("io.github.felipekoga.hopi:hopi-network:0.1.0"), HopiRuntimeGraph.toRemove(app))

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
        assertTrue(text.startsWith("io.github.felipekoga.hopi: variant freeQa is not debuggable"), text)
        assertTrue("declare project ':hopi-network' as debugImplementation, not as implementation or qaImplementation" in text, text)
    }

    @Test
    fun applyingItOutsideAnAndroidApplicationFails() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java-library")
        project.pluginManager.apply(HopiPlugin::class.java)
        val failure = runCatching { (project as org.gradle.api.internal.project.ProjectInternal).evaluate() }.exceptionOrNull()
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "apply io.github.felipekoga.hopi to the Android application module" in it }, messages.toString())
    }

    @Test
    fun aKmpModuleNeedsTheKotlinGradlePlugin220() {
        // Major, minor and patch: 2.2.0's pre-releases and a later patch's pass.
        for (version in listOf("2.2.0", "2.2.0-RC2", "2.2.20-Beta1", "2.2.21", "2.4.20", "3.0.0", "2.2")) {
            assertFalse(KotlinPluginFloor.isBelowFloor(version), version)
        }
        for (version in listOf("2.1.21", "2.1.0", "2.0.21", "1.9.24", "1.8.22", "2.1.255-SNAPSHOT")) {
            assertTrue(KotlinPluginFloor.isBelowFloor(version), version)
        }
        val message = KotlinPluginFloor.message(":shared", "2.1.21")
        assertTrue("needs the Kotlin Gradle plugin 2.2.0 or newer in a Kotlin Multiplatform module" in message, message)
        assertTrue(":shared applies org.jetbrains.kotlin.multiplatform 2.1.21: " in message, message)
        val unknown = KotlinPluginFloor.message(":shared", null)
        assertTrue(":shared applies org.jetbrains.kotlin.multiplatform of a version too old to report itself" in unknown, unknown)
    }

    /**
     * A Kotlin plugin whose classes the plugin's loader does not resolve to the same ones (declared in
     * the module while io.github.felipekoga.hopi is in the root or buildSrc) is refused with the same-scope rule,
     * not a version: a child loader of the plugin's sees them, another copy or a loader without them
     * does not. The TestKit test covers it in a build.
     */
    @Test
    fun aKotlinPluginTheHopiPluginCannotSeeIsRefusedWithTheScopeRule() {
        val name = KotlinPluginFloor::class.java.name
        val ours = KotlinPluginFloor::class.java.classLoader
        val platform = ClassLoader.getPlatformClassLoader()
        URLClassLoader(emptyArray<URL>(), ours).use { child ->
            URLClassLoader(arrayOf(KotlinPluginFloor::class.java.protectionDomain.codeSource.location), platform).use { copy ->
                assertTrue(KotlinPluginFloor.sameClass(name, ours, ours))
                assertTrue(KotlinPluginFloor.sameClass(name, ours, child), "a child scope's loader")
                assertFalse(KotlinPluginFloor.sameClass(name, ours, copy), "another copy of the class")
                assertFalse(KotlinPluginFloor.sameClass(name, platform, ours), "a loader without the class")
            }
        }
        val message = KotlinPluginFloor.scopeMessage(":shared")
        assertTrue(message.startsWith("io.github.felipekoga.hopi cannot see the classes of the Kotlin Gradle plugin that :shared applies"), message)
        assertTrue("declare org.jetbrains.kotlin.multiplatform and io.github.felipekoga.hopi in the same plugins { } block" in message, message)
        assertFalse("too old" in message, message)
    }
}

private const val GROUP = "io.github.felipekoga.hopi"

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
