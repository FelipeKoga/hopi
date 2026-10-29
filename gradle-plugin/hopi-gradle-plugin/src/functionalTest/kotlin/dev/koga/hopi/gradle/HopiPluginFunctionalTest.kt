package dev.koga.hopi.gradle

import dev.koga.hopi.instrument.DexReader
import dev.koga.hopi.instrument.DexReader.MethodRef
import dev.koga.hopi.instrument.HookDescriptors
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * The plugin applied to fixture Android apps, built with AGP by Gradle TestKit
 * (docs/design/android-capture.md, "Tests"): the debug variant is instrumented and gets the network
 * runtime and its provider, the release variant is not touched, gets no runtime and passes its
 * release guard, and an app with no OkHttp builds and is instrumented for its `URL` calls. A
 * flavored app on OkHttp 5 (the `okhttp-android` artifact) with a custom debuggable, minified build
 * type and a custom non-debuggable one has exactly its debuggable variants instrumented and its
 * non-debuggable one guarded. With `network { enabled = false }` a debug build gets no transform and
 * no network runtime but still core, its provider and a generated configuration, whose tool list
 * names perf's tool or nothing. The fixture's `hopi-core`, `hopi-network` and `hopi-perf` are
 * stand-ins with the runtimes' class names (the plugin takes a project of that name over the
 * coordinates): core's holds the provider, its manifest and the generated configuration's base
 * class, `hopi-network`'s the hooks and the network tool the generated class names, `hopi-perf`'s
 * perf's tool; the real runtimes are tested by their own tests. An app with no such projects gets
 * core, the runtime and the Ktor twins by their published coordinates, as a consumer of Maven Local
 * does. A runtime that reaches a release variant fails the build in the release guard, before any
 * dex task.
 */
class HopiPluginFunctionalTest {
    private val fixtures = File(System.getProperty("hopi.fixturesDir"))
    private val testKit = File(System.getProperty("hopi.testKitDir"))

    /** One instance per test: a fixture daemon this test started logs after this. */
    private val started = System.currentTimeMillis()

    @AfterTest
    fun stopTheFixtureDaemon() {
        // The daemons this test started in the TestKit directory, never the developer's: their logs name
        // their pids. Logs of earlier runs stay there and the system reuses pids, so a log counts only when
        // it was created during this test and its pid is still a Gradle daemon.
        val pids = testKit.walkTopDown()
            .filter { it.isFile && it.createdMillis() >= started - 1_000 }
            .mapNotNull { DAEMON_LOG.matchEntire(it.name)?.groupValues?.get(1)?.toLong() }
            .toSet()
        for (pid in pids) {
            ProcessHandle.of(pid).filter { it.isGradleDaemon() }.ifPresent { handle ->
                handle.destroy()
                handle.onExit().get(30, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun debugIsInstrumentedReleaseIsUntouchedAndAnAppWithoutOkHttpWorks() {
        val project = fixture("apps")
        val tasks = arrayOf(
            ":app:assembleDebug", ":app:assembleRelease", ":plain:assembleDebug", ":plain:assembleRelease",
            ":flavored:assembleFreeDebug", ":flavored:assembleFreeStaging", ":flavored:assembleFreeQa",
            ":nonet:assembleDebug", ":notools:assembleDebug",
        )
        val result = runner(project, *tasks).build()
        // The release guard: one per non-debuggable variant, run before its build (it found no
        // runtime); a debuggable variant has none.
        for (guard in listOf(":app:hopiReleaseGuardRelease", ":plain:hopiReleaseGuardRelease", ":flavored:hopiReleaseGuardFreeQa")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(guard)?.outcome, guard)
        }
        for (debuggable in listOf(":app:hopiReleaseGuardDebug", ":flavored:hopiReleaseGuardFreeDebug", ":flavored:hopiReleaseGuardFreeStaging", ":nonet:hopiReleaseGuardDebug")) {
            assertNull(result.task(debuggable), debuggable)
        }
        // Instrumentation is registered for debuggable variants only: no transform task for release.
        assertNotNull(result.task(":app:transformDebugClassesWithAsm"), result.output)
        assertNull(result.task(":app:transformReleaseClassesWithAsm"))
        assertNull(result.task(":plain:transformReleaseClassesWithAsm"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:generateDebugHopiConfig")?.outcome)
        assertNull(result.task(":app:generateReleaseHopiConfig"))

        val debug = DexReader.ofApk(project.resolve("app/build/outputs/apk/debug/app-debug.apk"))
        assertEquals(setOf(REAL_CALL), debug.callersOf(OKHTTP_HOOK).filter { it.startsWith("okhttp3/") }.toSet())
        assertFalse(REAL_CALL in debug.callersOf(INTERCEPTORS))
        assertTrue("com/example/sdk/Sdk" in debug.callersOf(URL_HOOK), debug.invokesByClass["com/example/sdk/Sdk"].toString())
        assertTrue("com/example/app/AppCode" in debug.callersOf(URL_HOOK))
        assertTrue(debug.callersOf(OPEN_CONNECTION).none { !it.startsWith(HookDescriptors.RUNTIME_PACKAGE) })
        assertTrue("dev/koga/hopi/android/generated/HopiGeneratedConfig" in debug.definedClasses)
        assertTrue("dev/koga/hopi/android/internal/HopiInitProvider" in debug.definedClasses)
        assertTrue(PROVIDER in mergedManifest(project, "app", "debug"))

        val release = DexReader.ofApk(project.resolve("app/build/outputs/apk/release/app-release-unsigned.apk"))
        assertEquals(emptyList(), release.referencedTypes.filter { it.startsWith("dev/koga/hopi/android/internal/") || it.startsWith("dev/koga/hopi/android/generated/") })
        assertTrue(REAL_CALL in release.callersOf(INTERCEPTORS))
        assertTrue("com/example/sdk/Sdk" in release.callersOf(OPEN_CONNECTION))
        assertTrue("com/example/app/AppCode" in release.callersOf(OPEN_CONNECTION))
        // No runtime at all: the fixture's release variant names no Hopi module.
        assertEquals(emptyList(), release.definedClasses.filter { it.startsWith("dev/koga/hopi/") }, "release defines no Hopi class")
        assertFalse(PROVIDER in mergedManifest(project, "app", "release"))

        // No OkHttp anywhere in this app: it builds, its URL call goes to the hook, and no okhttp3 class exists.
        val plain = DexReader.ofApk(project.resolve("plain/build/outputs/apk/debug/plain-debug.apk"))
        assertTrue(plain.definedClasses.none { it.startsWith("okhttp3/") })
        assertTrue("com/example/plain/PlainCode" in plain.callersOf(MethodRef(HookDescriptors.URL_HOOKS, "openStream", "(Ljava/net/URL;)Ljava/io/InputStream;")))
        val plainRelease = DexReader.ofApk(project.resolve("plain/build/outputs/apk/release/plain-release-unsigned.apk"))
        assertTrue(plainRelease.referencedTypes.none { it.startsWith("dev/koga/hopi/android/internal/") })

        assertFlavoredVariants(project, result)
        assertNetworkOff(project, result)

        // The configuration cache stores and reuses the plugin's configuration; the guards, which
        // have no outputs, run again from the stored entry.
        val again = runner(project, *tasks).build()
        assertTrue("Reusing configuration cache." in again.output, again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":app:transformDebugClassesWithAsm")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, again.task(":app:hopiReleaseGuardRelease")?.outcome)
    }

    /**
     * `network { enabled = false }` with perf on (`nonet`) and with every tool off (`notools`: a
     * debuggable variant still gets core and a generated configuration with an empty tool list;
     * ARCHITECTURE, "Consumer API"): no transform and no network runtime, so no network tool and no
     * hook class, and the app's own `URL` call stays as written; still core, its provider in the
     * merged manifest and a generated configuration, which constructs perf's tool in `nonet` and no
     * tool in `notools`.
     */
    private fun assertNetworkOff(project: File, result: org.gradle.testkit.runner.BuildResult) {
        for ((module, tools) in listOf("nonet" to listOf(PERF_ENTRY_CLASS), "notools" to emptyList<String>())) {
            assertNull(result.task(":$module:transformDebugClassesWithAsm"), module)
            assertEquals(TaskOutcome.SUCCESS, result.task(":$module:generateDebugHopiConfig")?.outcome, module)
            val debug = DexReader.ofApk(project.resolve("$module/build/outputs/apk/debug/$module-debug.apk"))
            assertTrue(GENERATED_CLASS in debug.definedClasses, module)
            assertTrue("dev/koga/hopi/android/internal/HopiInitProvider" in debug.definedClasses, module)
            assertTrue(PROVIDER in mergedManifest(project, module, "debug"), module)
            assertFalse(NETWORK_TOOL_CLASS in debug.definedClasses, "$module has no network tool")
            assertEquals(emptyList(), debug.definedClasses.filter { it == HookDescriptors.OKHTTP_HOOKS || it == HookDescriptors.URL_HOOKS }, "$module has no hook class")
            assertEquals(PERF_ENTRY_CLASS in tools, PERF_ENTRY_CLASS in debug.definedClasses, "$module: perf's tool")
            val constructed = debug.invokesByClass[GENERATED_CLASS].orEmpty().filter { it.name == "<init>" && it.owner != CONFIG_VALUES_CLASS }.map { it.owner }
            assertEquals(tools, constructed, "the tools $module's generated configuration constructs")
            val code = "com/example/$module/AppCode"
            assertTrue(code in debug.callersOf(OPEN_CONNECTION), "$module's own URL call stays: ${debug.invokesByClass[code]}")
            assertFalse(code in debug.callersOf(URL_HOOK), module)
        }
    }

    /**
     * Product flavors and custom build types: `staging` (debuggable, minified) is instrumented like
     * `debug`, `qa` (not debuggable) like `release`; OkHttp 5's Android artifact is rewritten.
     */
    private fun assertFlavoredVariants(project: File, result: org.gradle.testkit.runner.BuildResult) {
        assertNotNull(result.task(":flavored:transformFreeDebugClassesWithAsm"), result.output)
        assertNotNull(result.task(":flavored:transformFreeStagingClassesWithAsm"), result.output)
        assertNull(result.task(":flavored:transformFreeQaClassesWithAsm"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":flavored:generateFreeStagingHopiConfig")?.outcome)
        assertNull(result.task(":flavored:generateFreeQaHopiConfig"))

        val debug = DexReader.ofApk(apk(project, "flavored", "free", "debug"))
        assertTrue(OKHTTP_ANDROID_CLASS in debug.definedClasses, "OkHttp 5's Android artifact")
        assertEquals(setOf(REAL_CALL), debug.callersOf(OKHTTP_HOOK).filter { it.startsWith("okhttp3/") }.toSet())
        assertTrue("com/example/flavored/FlavoredCode" in debug.callersOf(URL_HOOK))
        assertTrue(PROVIDER in mergedManifest(project, "flavored", "freeDebug"))

        // Minified with the runtime's consumer rules: the hooks, the provider, the generated
        // configuration and RealCall's rewrite survive R8.
        val staging = DexReader.ofApk(apk(project, "flavored", "free", "staging"))
        assertTrue(staging.callersOf(OKHTTP_HOOK).any { it.startsWith("okhttp3/") }, staging.callersOf(OKHTTP_HOOK).toString())
        assertTrue("com/example/flavored/FlavoredCode" in staging.callersOf(URL_HOOK))
        assertTrue("dev/koga/hopi/android/generated/HopiGeneratedConfig" in staging.definedClasses)
        assertTrue("dev/koga/hopi/android/internal/HopiInitProvider" in staging.definedClasses)
        assertTrue(PROVIDER in mergedManifest(project, "flavored", "freeStaging"))

        val qa = DexReader.ofApk(apk(project, "flavored", "free", "qa"))
        assertEquals(emptyList(), qa.referencedTypes.filter { it.startsWith("dev/koga/hopi/android/internal/") || it.startsWith("dev/koga/hopi/android/generated/") })
        assertTrue(REAL_CALL in qa.callersOf(INTERCEPTORS))
        assertTrue("com/example/flavored/FlavoredCode" in qa.callersOf(OPEN_CONNECTION))
        assertEquals(emptyList(), qa.definedClasses.filter { it.startsWith("dev/koga/hopi/") }, "qa defines no Hopi class")
        assertFalse(PROVIDER in mergedManifest(project, "flavored", "freeQa"))
    }

    private fun apk(project: File, module: String, flavor: String, buildType: String): File =
        project.resolve("$module/build/outputs/apk/$flavor/$buildType").walkTopDown().single { it.extension == "apk" }

    /**
     * The published-coordinates path (a consumer of Maven Local): with no `hopi-network` or twin
     * project in the build, the plugin adds `dev.koga.hopi:hopi-network` at its own version,
     * `hopi-core` and, with `perf { enabled = true }`, `hopi-perf` to debug only, and swaps the Ktor
     * twins a library names by their coordinates, in both directions: the shared module asks for
     * `hopi-ktor-noop`, the debug APK gets the real modules and the release APK the Ktor noop and no
     * runtime at all. `hopi-api`, which the shared module also names (`HopiRules`, `HopiPerf`),
     * has no twin: both APKs get it. The constraint the plugin adds in debug, `hopi-api` at its own
     * version, is not checked here: every stand-in is published at that version, the one the shared
     * module names, so the APKs would be the same without it. The stand-ins are jars with one marker
     * class each, published by a build of their own into a repository of the fixture; core's also
     * holds the generated configuration's base class and the tool interface, and the network and
     * perf tools the generated class names implement it (the fixture enables perf).
     */
    @Test
    fun publishedCoordinatesAreSwappedPerVariant() {
        val root = fixtures.resolve("coordinates")
        root.deleteRecursively()
        val version = System.getProperty("hopi.version")
        val project = consumer(root, publishStandIns(root), "app", "shared")
        project.write(
            "shared/build.gradle.kts",
            """
            plugins { `java-library` }
            java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            dependencies {
                api("dev.koga.hopi:hopi-ktor-noop:$version")
                api("dev.koga.hopi:hopi-api:$version")
            }
            """.trimIndent(),
        )
        project.write(
            "app/build.gradle.kts",
            androidModule(
                "com.android.application", "com.example.consumer", hopi = true,
                dependencies = "implementation(project(\":shared\"))",
            ) + "\nhopi { perf { enabled = true } }\n",
        )
        project.write("app/src/main/AndroidManifest.xml", MANIFEST)

        val result = runner(project, ":app:assembleDebug", ":app:assembleRelease").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:generateDebugHopiConfig")?.outcome, result.output)
        // The coordinates' release resolves hopi-api and the Ktor noop only: its guard passes.
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:hopiReleaseGuardRelease")?.outcome, result.output)
        val debug = DexReader.ofApk(project.resolve("app/build/outputs/apk/debug/app-debug.apk")).definedClasses
        val release = DexReader.ofApk(project.resolve("app/build/outputs/apk/release/app-release-unsigned.apk")).definedClasses
        for ((real, noop) in listOf("hopi-ktor" to "hopi-ktor-noop")) {
            assertTrue("dev/koga/hopi/standin/${marker(real)}" in debug, "debug has $real: ${debug.filter { it.startsWith("dev/koga/hopi/") }}")
            assertFalse("dev/koga/hopi/standin/${marker(noop)}" in debug, "debug has no $noop")
            assertTrue("dev/koga/hopi/standin/${marker(noop)}" in release, "release has $noop: ${release.filter { it.startsWith("dev/koga/hopi/") }}")
            assertFalse("dev/koga/hopi/standin/${marker(real)}" in release, "release has no $real")
        }
        // The network runtime: debug only, no twin.
        assertTrue("dev/koga/hopi/standin/${marker("hopi-network")}" in debug, "debug has hopi-network")
        assertFalse("dev/koga/hopi/standin/${marker("hopi-network")}" in release, "release has no hopi-network")
        // The perf monitors' runtime, from `perf { enabled = true }`: debug only, no twin.
        assertTrue("dev/koga/hopi/standin/${marker("hopi-perf")}" in debug, "debug has hopi-perf")
        assertFalse("dev/koga/hopi/standin/${marker("hopi-perf")}" in release, "release has no hopi-perf")
        // The module of HopiRules and HopiPerf reaches both, as the shared module asked.
        assertTrue("dev/koga/hopi/standin/${marker("hopi-api")}" in debug, "debug has hopi-api")
        assertTrue("dev/koga/hopi/standin/${marker("hopi-api")}" in release, "release has hopi-api")
        // Core, which the generated configuration extends, reaches debug only.
        assertTrue("dev/koga/hopi/standin/${marker("hopi-core")}" in debug, "debug has hopi-core")
        assertFalse("dev/koga/hopi/standin/${marker("hopi-core")}" in release, "release has no hopi-core")
        assertTrue("dev/koga/hopi/android/generated/HopiGeneratedConfig" in debug)
        assertEquals(emptyList(), release.filter { it.startsWith("dev/koga/hopi/android/") || it.startsWith("dev/koga/hopi/core/") || it.startsWith("dev/koga/hopi/perf/") })
    }

    /** `hopi-ktor-noop` → `HopiKtorNoop`. */
    private fun marker(module: String): String = module.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    /**
     * Publishes stand-ins of the published modules into `<root>/repo`, by a build of their own, and
     * returns that repository: jars with one marker class each; core's also holds the generated
     * configuration's base class and the tool interface, and the network and perf tools the
     * generated class names implement it, so both depend on core, as the runtimes do.
     */
    private fun publishStandIns(root: File): String {
        val version = System.getProperty("hopi.version")
        val repo = root.resolve("repo").path.replace("\\", "/")
        val standIns = root.resolve("stand-ins")
        writeGradleFiles(standIns)
        val published = listOf("hopi-api", "hopi-core", "hopi-network", "hopi-ktor", "hopi-ktor-noop", "hopi-perf")
        standIns.write("settings.gradle.kts", published.joinToString("\n") { "include(\":$it\")" })
        standIns.write(
            "build.gradle.kts",
            """
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "maven-publish")
                group = "dev.koga.hopi"
                version = "$version"
                configure<JavaPluginExtension> {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                configure<PublishingExtension> {
                    repositories { maven { name = "standIns"; url = uri("$repo") } }
                    publications { create<MavenPublication>("maven") { from(components["java"]) } }
                }
            }
            // The tools the generated configuration names implement core's tool interface.
            for (tool in listOf(":hopi-network", ":hopi-perf")) {
                project(tool) { dependencies { "implementation"(project(":hopi-core")) } }
            }
            """.trimIndent(),
        )
        for (module in published) {
            val name = marker(module)
            standIns.write("$module/src/main/java/dev/koga/hopi/standin/$name.java", "package dev.koga.hopi.standin;\npublic final class $name {}\n")
        }
        // The generated configuration of a debuggable variant extends core's HopiConfigValues and
        // constructs the tools it lists.
        standIns.write("hopi-core/src/main/java/dev/koga/hopi/android/internal/HopiConfigValues.java", CONFIG_VALUES)
        standIns.write("hopi-core/src/main/java/dev/koga/hopi/core/start/HopiTool.java", TOOL)
        standIns.write("hopi-network/src/main/java/dev/koga/hopi/android/internal/NetworkTool.java", NETWORK_TOOL)
        standIns.write("hopi-perf/src/main/java/dev/koga/hopi/perf/internal/PerfEntry.java", PERF_ENTRY)
        runner(standIns, "publishAllPublicationsToStandInsRepository").build()
        return repo
    }

    /**
     * A consumer of the published coordinates, as of Maven Local, in `<root>/consumer`: the plugin
     * from the TestKit repository, `dev.koga.hopi` from [repo] only, and [modules] included.
     */
    private fun consumer(root: File, repo: String, vararg modules: String): File {
        val project = root.resolve("consumer")
        writeGradleFiles(project)
        project.write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${System.getProperty("hopi.functionalRepo").replace("\\", "/")}") }
                    google()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven {
                        url = uri("$repo")
                        content { includeGroup("dev.koga.hopi") }
                    }
                    google()
                    mavenCentral()
                }
            }
            """.trimIndent() + modules.joinToString("") { "\ninclude(\":$it\")" } + "\n",
        )
        project.write(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "${System.getProperty("hopi.agpVersion")}" apply false
                id("dev.koga.hopi") version "${System.getProperty("hopi.version")}" apply false
            }
            """.trimIndent(),
        )
        return project
    }

    /**
     * The release guard's negative cases (E3.6-c of the runtime-split evaluation): a runtime on a
     * release runtime classpath fails the build in `hopiReleaseGuardRelease`, before any dex task and
     * with no APK, and the failure names the runtime and what brings it. The runtime is named by
     * project (`releaseImplementation(project(":hopi-network"))`, in the apps fixture), by its
     * published coordinates (`releaseImplementation("dev.koga.hopi:hopi-network:…")`), or by a
     * library for every variant (`implementation(project(":sdk"))`, whose own `implementation`
     * names it); `-Phopi.guardCase` picks the case, which each fixture's app script reads. The
     * failure says to remove only the runtime something other than a runtime brings: `hopi-network`,
     * never the core it brings.
     */
    @Test
    fun aRuntimeInANonDebuggableVariantFailsTheBuild() {
        val apps = fixture("apps")
        val byProject = runner(apps, ":app:assembleRelease", "-Phopi.guardCase=project").buildAndFail()
        // Each component as Gradle displays it, then what depends on it, the app itself left out.
        assertGuardFailed(apps, byProject, "project ':hopi-network'", "project ':hopi-network'", "project ':hopi-core' (via project ':hopi-network')")

        val root = fixtures.resolve("guard")
        root.deleteRecursively()
        val version = System.getProperty("hopi.version")
        val consumer = consumer(root, publishStandIns(root), "app", "sdk")
        consumer.write(
            "sdk/build.gradle.kts",
            """
            plugins { `java-library` }
            java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            // A library that names the runtime for every variant of an app that depends on it.
            dependencies { implementation("dev.koga.hopi:hopi-network:$version") }
            """.trimIndent(),
        )
        consumer.write(
            "app/build.gradle.kts",
            androidModule("com.android.application", "com.example.consumer", hopi = true) + "\n" +
                """
                val guardCase = providers.gradleProperty("hopi.guardCase").orNull
                if (guardCase == "coordinates") dependencies { "releaseImplementation"("dev.koga.hopi:hopi-network:$version") }
                if (guardCase == "transitive") dependencies { "implementation"(project(":sdk")) }
                """.trimIndent() + "\n",
        )
        consumer.write("app/src/main/AndroidManifest.xml", MANIFEST)
        val network = "dev.koga.hopi:hopi-network:$version"
        val core = "dev.koga.hopi:hopi-core:$version (via $network)"
        val byCoordinates = runner(consumer, ":app:assembleRelease", "-Phopi.guardCase=coordinates").buildAndFail()
        assertGuardFailed(consumer, byCoordinates, network, network, core)
        val byLibrary = runner(consumer, ":app:assembleRelease", "-Phopi.guardCase=transitive").buildAndFail()
        assertGuardFailed(consumer, byLibrary, network, "$network (via project ':sdk')", core)
    }

    /**
     * The guard failed [result], naming each of [components] and saying to remove [remove] alone,
     * and nothing was dexed (`dexBuilder…`, `merge…Dex…`) or packaged.
     */
    private fun assertGuardFailed(project: File, result: org.gradle.testkit.runner.BuildResult, remove: String, vararg components: String) {
        assertTrue("variant release is not debuggable" in result.output, result.output)
        assertTrue("hopi-network" in result.output, result.output)
        for (component in components) assertTrue(component in result.output, "the failure names $component: ${result.output}")
        assertTrue("remove $remove from release (" in result.output, "the failure removes $remove alone: ${result.output}")
        assertFalse("Keep hopi { selectTwins } on" in result.output, "no hopi-ktor brings a runtime here: ${result.output}")
        assertEquals(TaskOutcome.FAILED, result.task(":app:hopiReleaseGuardRelease")?.outcome, result.output)
        val dexTasks = result.tasks.map { it.path }.filter { path -> path.substringAfterLast(':').let { it.startsWith("dex") || "Dex" in it } }
        assertEquals(emptyList(), dexTasks, "no dex task ran")
        assertFalse(project.resolve("app/build/outputs/apk/release").exists(), "no release APK")
    }

    @Test
    fun applyingItToALibraryFailsWithTheReason() {
        val project = fixture("library")
        val result = runner(project, ":lib:assembleDebug").buildAndFail()
        assertTrue("apply dev.koga.hopi to the Android application module" in result.output, result.output)
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project)
        .withTestKitDir(testKit)
        .withArguments(*tasks, "--configuration-cache", "--stacktrace")
        // Gradle's read-only dependency cache: AGP and its dependencies come from the developer's
        // cache without writing to it; anything missing is downloaded into the TestKit directory.
        .withEnvironment(System.getenv() + ("GRADLE_RO_DEP_CACHE" to File(System.getProperty("user.home"), ".gradle/caches").path))
        .forwardOutput()

    private fun mergedManifest(project: File, module: String, variant: String): String =
        project.resolve("$module/build/intermediates/merged_manifest/$variant").walkTopDown().first { it.name == "AndroidManifest.xml" }.readText()

    private fun fixture(name: String): File {
        val root = fixtures.resolve(name)
        root.deleteRecursively()
        val version = System.getProperty("hopi.version")
        val agp = System.getProperty("hopi.agpVersion")
        val okhttp = System.getProperty("hopi.okhttpVersion")
        val repo = System.getProperty("hopi.functionalRepo")
        writeGradleFiles(root)
        val modules = if (name == "apps") listOf("app", "plain", "flavored", "nonet", "notools", "sdk", "hopi-core", "hopi-network", "hopi-perf") else listOf("lib")
        root.write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${repo.replace("\\", "/")}") }
                    google()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            ${modules.joinToString("\n") { "include(\":$it\")" }}
            """.trimIndent(),
        )
        root.write(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "$agp" apply false
                id("com.android.library") version "$agp" apply false
                id("dev.koga.hopi") version "$version" apply false
            }
            """.trimIndent(),
        )
        if (name == "library") {
            root.write("lib/build.gradle.kts", androidModule("com.android.library", "com.example.lib", hopi = true))
            return root
        }
        root.write(
            "app/build.gradle.kts",
            androidModule("com.android.application", "com.example.app", hopi = true, dependencies = "implementation(project(\":sdk\"))") + "\n" +
                """
                // The release guard's negative case by project (aRuntimeInANonDebuggableVariantFailsTheBuild).
                if (providers.gradleProperty("hopi.guardCase").orNull == "project") dependencies { "releaseImplementation"(project(":hopi-network")) }
                """.trimIndent() + "\n",
        )
        root.write(
            "app/src/main/java/com/example/app/AppCode.java",
            """
            package com.example.app;

            public final class AppCode {
                public static java.net.URLConnection open(String url) throws java.io.IOException {
                    return new java.net.URL(url).openConnection();
                }
            }
            """.trimIndent(),
        )
        root.write("plain/build.gradle.kts", androidModule("com.android.application", "com.example.plain", hopi = true))
        val okhttp5 = System.getProperty("hopi.okhttp5Version")
        root.write(
            "flavored/build.gradle.kts",
            androidModule(
                "com.android.application", "com.example.flavored", hopi = true,
                dependencies = "implementation(\"com.squareup.okhttp3:okhttp:$okhttp5\")",
                extra = """
                    flavorDimensions += "tier"
                    productFlavors {
                        create("free") { dimension = "tier" }
                        create("paid") { dimension = "tier" }
                    }
                    buildTypes {
                        create("staging") {
                            initWith(getByName("debug"))
                            isDebuggable = true
                            isMinifyEnabled = true
                            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "rules.pro")
                            matchingFallbacks += "debug"
                        }
                        create("qa") {
                            initWith(getByName("release"))
                            isDebuggable = false
                            matchingFallbacks += "release"
                        }
                    }
                """.trimIndent(),
            ),
        )
        root.write("flavored/rules.pro", "-dontobfuscate\n-keep class com.example.** { *; }\n")
        root.write(
            "flavored/src/main/java/com/example/flavored/FlavoredCode.java",
            """
            package com.example.flavored;

            import okhttp3.OkHttpClient;
            import okhttp3.Request;

            public final class FlavoredCode {
                public static Object call(String url) throws java.io.IOException {
                    new OkHttpClient().newCall(new Request.Builder().url(url).build()).execute().close();
                    return new java.net.URL(url).openConnection();
                }
            }
            """.trimIndent(),
        )
        // Network off with perf on, and every tool off: a generated configuration with perf's tool or none.
        root.write("nonet/build.gradle.kts", androidModule("com.android.application", "com.example.nonet", hopi = true) + "\nhopi {\n    network { enabled = false }\n    perf { enabled = true }\n}\n")
        root.write("notools/build.gradle.kts", androidModule("com.android.application", "com.example.notools", hopi = true) + "\nhopi { network { enabled = false } }\n")
        for (module in listOf("nonet", "notools")) {
            root.write(
                "$module/src/main/java/com/example/$module/AppCode.java",
                """
                package com.example.$module;

                public final class AppCode {
                    public static java.net.URLConnection open(String url) throws java.io.IOException {
                        return new java.net.URL(url).openConnection();
                    }
                }
                """.trimIndent(),
            )
        }
        for (app in listOf("app", "plain", "flavored", "nonet", "notools")) {
            root.write("$app/src/main/AndroidManifest.xml", MANIFEST)
        }
        root.write(
            "plain/src/main/java/com/example/plain/PlainCode.java",
            """
            package com.example.plain;

            public final class PlainCode {
                public static java.io.InputStream read(String url) throws java.io.IOException {
                    return new java.net.URL(url).openStream();
                }
            }
            """.trimIndent(),
        )
        root.write(
            "sdk/build.gradle.kts",
            """
            plugins { `java-library` }
            java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            dependencies { implementation("com.squareup.okhttp3:okhttp:$okhttp") }
            """.trimIndent(),
        )
        root.write(
            "sdk/src/main/java/com/example/sdk/Sdk.java",
            """
            package com.example.sdk;

            import okhttp3.OkHttpClient;
            import okhttp3.Request;

            /** A third-party library: its own OkHttp client and HttpURLConnection, no Hopi. */
            public final class Sdk {
                private static final OkHttpClient CLIENT = new OkHttpClient();

                public static Object call(String url) throws java.io.IOException {
                    CLIENT.newCall(new Request.Builder().url(url).build()).execute().close();
                    return new java.net.URL(url).openConnection();
                }
            }
            """.trimIndent(),
        )
        // Stand-ins of core and the runtime with their class names: what the plugin adds, rewrites
        // calls to, constructs in the generated configuration, and declares in the manifest.
        root.write(
            "hopi-core/build.gradle.kts",
            androidModule(
                "com.android.library", "dev.koga.hopi.core", hopi = false,
                extra = "defaultConfig { consumerProguardFiles(\"consumer-rules.pro\") }",
            ),
        )
        File(System.getProperty("hopi.coreConsumerRules")).copyTo(root.resolve("hopi-core/consumer-rules.pro"))
        root.write(
            "hopi-core/src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <provider android:name="$PROVIDER" android:authorities="${'$'}{applicationId}.hopi-init" android:exported="false" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        val coreInternal = "hopi-core/src/main/java/dev/koga/hopi/android/internal"
        root.write("$coreInternal/HopiConfigValues.java", CONFIG_VALUES)
        root.write("hopi-core/src/main/java/dev/koga/hopi/core/start/HopiTool.java", TOOL)
        root.write(
            "$coreInternal/HopiInitProvider.java",
            """
            package dev.koga.hopi.android.internal;
            import android.content.ContentProvider;
            import android.content.ContentValues;
            import android.database.Cursor;
            import android.net.Uri;
            public final class HopiInitProvider extends ContentProvider {
                @Override public boolean onCreate() { return true; }
                @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
                @Override public String getType(Uri u) { return null; }
                @Override public Uri insert(Uri u, ContentValues v) { return null; }
                @Override public int delete(Uri u, String s, String[] a) { return 0; }
                @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
            }
            """.trimIndent(),
        )
        root.write(
            "hopi-network/build.gradle.kts",
            androidModule(
                "com.android.library", "dev.koga.hopi.network", hopi = false,
                dependencies = "compileOnly(\"com.squareup.okhttp3:okhttp:$okhttp\"); implementation(project(\":hopi-core\"))",
                extra = "defaultConfig { consumerProguardFiles(\"consumer-rules.pro\") }",
            ),
        )
        File(System.getProperty("hopi.consumerRules")).copyTo(root.resolve("hopi-network/consumer-rules.pro"))
        val internal = "hopi-network/src/main/java/dev/koga/hopi/android/internal"
        root.write("$internal/HopiOkHttpHooks.java", "package dev.koga.hopi.android.internal;\npublic final class HopiOkHttpHooks {\n    public static java.util.List<okhttp3.Interceptor> interceptors(okhttp3.OkHttpClient client) { return client.interceptors(); }\n}\n")
        root.write(
            "$internal/HopiUrlHooks.java",
            """
            package dev.koga.hopi.android.internal;
            import java.io.IOException;
            import java.net.URL;
            public final class HopiUrlHooks {
                public static java.net.URLConnection openConnection(URL url) throws IOException { return url.openConnection(); }
                public static java.net.URLConnection openConnection(URL url, java.net.Proxy proxy) throws IOException { return url.openConnection(proxy); }
                public static java.io.InputStream openStream(URL url) throws IOException { return url.openStream(); }
                public static Object getContent(URL url) throws IOException { return url.getContent(); }
                public static Object getContent(URL url, Class<?>[] types) throws IOException { return url.getContent(types); }
            }
            """.trimIndent(),
        )
        root.write("$internal/HopiInstrumented.java", "package dev.koga.hopi.android.internal;\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\npublic @interface HopiInstrumented {}\n")
        root.write("$internal/NetworkTool.java", NETWORK_TOOL)
        // perf's runtime, which nonet's `perf { enabled = true }` adds: its tool implements core's interface.
        root.write("hopi-perf/build.gradle.kts", androidModule("com.android.library", "dev.koga.hopi.perf", hopi = false, dependencies = "implementation(project(\":hopi-core\"))"))
        root.write("hopi-perf/src/main/java/dev/koga/hopi/perf/internal/PerfEntry.java", PERF_ENTRY)
        return root
    }

    private fun androidModule(plugin: String, namespace: String, hopi: Boolean, dependencies: String = "", extra: String = ""): String =
        """
        plugins {
            id("$plugin")
            ${if (hopi) "id(\"dev.koga.hopi\")" else ""}
        }
        android {
            namespace = "$namespace"
            compileSdk = 36
            defaultConfig { minSdk = 24 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint { checkReleaseBuilds = false }
        ${extra.prependIndent("    ").trimStart()}
        }
        dependencies { $dependencies }
        """.trimIndent()

    /** The fixture build's machine-specific files: its SDK and a small daemon that stops soon after. */
    private fun writeGradleFiles(root: File) {
        root.mkdirs()
        root.write("local.properties", "sdk.dir=${System.getProperty("hopi.sdkDir")}\n")
        root.write(
            "gradle.properties",
            """
            org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m
            org.gradle.daemon.idletimeout=60000
            android.useAndroidX=true
            """.trimIndent(),
        )
    }

    private fun File.write(path: String, text: String) {
        resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private companion object {
        val DAEMON_LOG = Regex("daemon-(\\d+)\\.out\\.log")
        const val DAEMON_MAIN = "org.gradle.launcher.daemon.bootstrap.GradleDaemon"

        fun File.createdMillis(): Long = Files.readAttributes(toPath(), BasicFileAttributes::class.java).creationTime().toMillis()

        /** A JVM running Gradle's daemon entry point; false when the arguments cannot be read. */
        fun ProcessHandle.isGradleDaemon(): Boolean = info().arguments().map { DAEMON_MAIN in it }.orElse(false)
        const val PROVIDER = "dev.koga.hopi.android.internal.HopiInitProvider"
        const val GENERATED_CLASS = "dev/koga/hopi/android/generated/HopiGeneratedConfig"
        const val CONFIG_VALUES_CLASS = "dev/koga/hopi/android/internal/HopiConfigValues"
        const val NETWORK_TOOL_CLASS = "dev/koga/hopi/android/internal/NetworkTool"
        const val PERF_ENTRY_CLASS = "dev/koga/hopi/perf/internal/PerfEntry"
        const val MANIFEST = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application />\n</manifest>\n"

        /** Core's base class of the generated configuration, with its constructor: the global settings, then the tool list. */
        val CONFIG_VALUES = """
            package dev.koga.hopi.android.internal;
            public abstract class HopiConfigValues {
                protected HopiConfigValues(int maxEntries, long maxTotalBytes, long maxBodyBytes, boolean summaryLog, boolean agentBridge,
                        boolean redactionDisabled, String[] redactHeaders, String[] redactQueryParameters, dev.koga.hopi.core.start.HopiTool... tools) {}
            }
        """.trimIndent()

        /** Core's tool interface, the element type of the tool list. */
        val TOOL = """
            package dev.koga.hopi.core.start;
            public interface HopiTool {}
        """.trimIndent()

        /** hopi-network's Android network tool, with the constructor the generated configuration calls. */
        val NETWORK_TOOL = """
            package dev.koga.hopi.android.internal;
            public final class NetworkTool implements dev.koga.hopi.core.start.HopiTool {
                public NetworkTool(boolean okhttp, boolean urlConnection, boolean rules) {}
            }
        """.trimIndent()

        /** hopi-perf's tool, with the constructor the generated configuration calls under `perf { enabled = true }`. */
        val PERF_ENTRY = """
            package dev.koga.hopi.perf.internal;
            public final class PerfEntry implements dev.koga.hopi.core.start.HopiTool {
                public PerfEntry(int autoStart, long hangThresholdMs, boolean hangHeartbeat, boolean remoteControl) {}
            }
        """.trimIndent()
        const val REAL_CALL = "okhttp3/internal/connection/RealCall"
        const val OKHTTP_ANDROID_CLASS = "okhttp3/internal/platform/android/AndroidLog"
        val OKHTTP_HOOK = MethodRef(HookDescriptors.OKHTTP_HOOKS, HookDescriptors.INTERCEPTORS, HookDescriptors.OKHTTP_HOOK_DESCRIPTOR)
        val INTERCEPTORS = MethodRef(HookDescriptors.OKHTTP_CLIENT, HookDescriptors.INTERCEPTORS, HookDescriptors.INTERCEPTORS_DESCRIPTOR)
        val URL_HOOK = MethodRef(HookDescriptors.URL_HOOKS, "openConnection", "(Ljava/net/URL;)Ljava/net/URLConnection;")
        val OPEN_CONNECTION = MethodRef(HookDescriptors.URL, "openConnection", "()Ljava/net/URLConnection;")
    }
}
