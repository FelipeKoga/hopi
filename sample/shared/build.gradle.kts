import java.io.ByteArrayOutputStream
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    // Hopi's whole iOS setup (docs/ARCHITECTURE.md, "Selecting the runtimes in a KMP project"):
    // hopi-api in every build, exported; hopi-start, which brings the runtimes, in a Debug build
    // only (-Phopi.enabled, else Xcode's configuration), exported; the generated Hopi Swift
    // calls in every configuration; the iOS release guard before every Release link. The thin
    // hopi-ktor is in every build and installs nothing in a Release framework.
    id("io.github.felipekoga.hopi")
}

// The start's settings, which Hopi passes to Hopi.start in a Debug build: the Android app's
// (sample/androidApp). The CLI and the macOS viewer reach the app through its bridge; the
// giant-body measurements set the caps at build time (`-Psample.maxBodyBytes=8388608`, through the
// Xcode build phase's SAMPLE_GRADLE_ARGS); the monitors are registered, none started.
hopi {
    agentBridge.set(true)
    maxBodyBytes.set(providers.gradleProperty("sample.maxBodyBytes").map { it.toLong() }.orElse(256L * 1024))
    maxTotalBytes.set(providers.gradleProperty("sample.maxTotalBytes").map { it.toLong() }.orElse(16L * 1024 * 1024))
}

// The runtimes, Debug frameworks only (the plugin applies them there), declared as the Android app
// declares them: the URLSession capture and the performance monitors, none started.
dependencies {
    debugImplementation(project(":hopi-network"))
    debugImplementation(project(":hopi-perf"))
}

/** The plugin's choice for this invocation: a Debug build, whose iOS framework links Hopi's runtimes. */
val hopiRuntime: Boolean = hopi.iosRuntime.get()

/** Writes `SampleBuildInfo.kt`, so the app can show which Hopi it was built with. */
abstract class GenerateSampleBuildInfo : DefaultTask() {
    @get:Input
    abstract val realHopi: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = outputDirectory.get().asFile
        root.deleteRecursively()
        val directory = root.resolve("dev/koga/hopi/sample")
        directory.mkdirs()
        directory.resolve("SampleBuildInfo.kt").writeText(
            """
            |package dev.koga.hopi.sample
            |
            |/** Generated from the io.github.felipekoga.hopi plugin's choice (`hopi.iosRuntime`): true when an iOS build links Hopi's runtimes (hopi-start). */
            |internal const val HOPI_ENABLED: Boolean = ${realHopi.get()}
            |
            """.trimMargin(),
        )
    }
}

val generateSampleBuildInfo = tasks.register<GenerateSampleBuildInfo>("generateSampleBuildInfo") {
    realHopi.set(hopi.iosRuntime)
    outputDirectory.set(layout.buildDirectory.dir("generated/sampleBuildInfo/commonMain/kotlin"))
}

kotlin {
    explicitApi()

    compilerOptions {
        // ui-backhandler's BackHandler.
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    android {
        namespace = "dev.koga.hopi.sample.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // A static framework, linked into the iOS app and copied by embedAndSignAppleFrameworkForXcode.
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // The plugin exports hopi-api (HopiRules, which the rules scenarios call from Swift,
            // CaptureVerification.swift, and HopiPerf) from every framework, and hopi-start from a
            // Debug build's only: with no runtime each HopiRules call returns -1, false or an
            // empty list, or does nothing.
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSampleBuildInfo.flatMap { it.outputDirectory })
            dependencies {
                // The thin Ktor plugin, in every build: it records only where a debug build's network
                // runtime registered its recorder (Android's debug variants), and installs nothing
                // on iOS, where URLSession capture records the Darwin engine.
                implementation(project(":hopi-ktor"))
                // HopiRules (mock, fail, delay, edit), which the rules scenarios call, and HopiPerf,
                // which the perf lab and bench start, come from hopi-api, which the plugin adds as
                // api in every build (the Android app's rules scenarios name HopiRules too): a debug
                // build's start registers the runtimes behind them (on Android the app's plugin adds
                // hopi-perf with `perf { enabled }`; on iOS hopi-start names perf's tool, so
                // Kotlin/Native keeps hopi-perf).
                // The sample's Compose, 1.10.3 (the catalog's compose-multiplatform-sample): Kotlin/Native
                // 2.2.21 reads its iOS klibs.
                implementation(libs.compose.sample.runtime)
                implementation(libs.compose.sample.foundation)
                implementation(libs.compose.sample.ui)
                implementation(libs.compose.sample.ui.backhandler)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain {
            // Test-only accessor for the Swift capture tests (iosAppCaptureTests), which read the
            // records from the store: built only with the runtimes, never into a release.
            if (hopiRuntime) kotlin.srcDir("src/iosCaptureProbe/kotlin")
            dependencies {
                implementation(libs.ktor.client.darwin)
                // The probe's store (the plugin adds hopi-start, which brings core, in a Debug build).
                if (hopiRuntime) implementation(project(":hopi-core"))
            }
        }
    }
}

/**
 * Proves that an iOS framework built without `-Phopi.enabled=true` holds no Hopi runtime: none of
 * hopi-network's URLSession capture and its rules, none of core, of the performance monitors'
 * runtime or of hopi-start. In `xcrun nm -m` (an argument array): no `_hopi_us_` symbol (the rule
 * functions `_hopi_us_rule…` included), no `HopiUS` capture or rule-protocol Objective-C class, no
 * `_hopi_hits_` symbol (core's hit counter), no `_hopi_perf_` symbol and no `HopiPerfCadence` class
 * (perf's C library), no Kotlin symbol of any kind (`_kfun:`, `_kclass:`, …) in `dev.koga.hopi.core`,
 * `.network`, `.urlsession`, `.android`, `.ui`, `.perf.internal` or `.start`; in its bytes,
 * neither the capture and rule queues' labels nor the private selector names (C strings) nor the
 * hang watchdog's and the sampler's thread names (Kotlin strings, UTF-16); in its header, no
 * declaration of `HopiStartConfig` or the sample's capture probe. The generated `Hopi` entry
 * (`SharedHopi`, the plugin's, in every configuration) is exported in both builds, once in the
 * binary and once in the header: hopi-start's own `Hopi` is `@HiddenFromObjC`, so a second one
 * would mean it reached Swift. `hopi-api`'s symbols and exported classes
 * (`dev.koga.hopi.api`, `.rules`, `.perf`) and the Ktor plugin's (`dev.koga.hopi.ktor`) are expected.
 * With the flag it requires every one of them instead (the positive control), so the check is known
 * to see what it looks for. Either way the header declares no module-prefixed Hopi class
 * (`SharedHopi_…`: an internal type that reached Swift) and no `HopiRuntimes` (the registry,
 * `@HopiInternalApi`). The header is read as declarations (`@interface`, `@protocol`, the `@class`
 * and `@protocol` lists), never as text: the exported KDoc names types in prose.
 *
 * Run it both ways, each requested by its own name: with the flag the Release link it consumes
 * holds every runtime, which the iOS release guard (the io.github.felipekoga.hopi plugin's) lets through
 * only for that link and only while this task is requested by name, so
 * `./gradlew check -Phopi.enabled=true`, like any invocation that links a Release framework with
 * the flag, fails in the guard.
 */
abstract class VerifyIosCaptureSelection : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

    /** The framework's Objective-C header, `Headers/Shared.h` beside [binary]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val header: RegularFileProperty

    @get:Input
    abstract val real: Property<Boolean>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun verify() {
        val file = binary.get().asFile
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine("xcrun", "nm", "-m", file.absolutePath)
            standardOutput = output
        }
        val symbols = output.toString(Charsets.UTF_8).lines()
        val declared = declarations(header.get().asFile.readText())
        // Each family's count: 0 without the flag, above 0 with it.
        val families = linkedMapOf(
            "_hopi_us_ symbols" to symbols.count { "_hopi_us_" in it },
            "capture classes" to symbols.count { "HopiUSTask" in it || "HopiUSObserver" in it },
            "rule symbols" to symbols.count { "HopiUSRuleProtocol" in it || "_hopi_us_rule" in it },
            "_hopi_hits_ symbols" to symbols.count { "_hopi_hits_" in it },
            "runtime Kotlin symbols" to symbols.count { RUNTIME_KOTLIN.containsMatchIn(it) },
            "_hopi_perf_ symbols" to symbols.count { "_hopi_perf_" in it },
            "perf classes" to symbols.count { "HopiPerfCadence" in it },
            "HopiStartConfig declarations" to declared.count { it == "SharedHopiStartConfig" },
            "capture probe declarations" to declared.count { it == "SharedSampleCaptureProbe" },
        )
        val bytes = file.readBytes()
        // C strings, NUL included: the capture and rule queues' labels, the private selectors' names.
        // A Kotlin string literal is stored as UTF-16: the hang watchdog's and the sampler's thread names.
        val strings = C_STRINGS.associateWith { contains(bytes, it.toByteArray() + 0) } +
            KOTLIN_STRINGS.associateWith { contains(bytes, it.toByteArray(Charsets.UTF_16LE)) }
        // 0 with and without the flag.
        val never = linkedMapOf(
            "module-prefixed Hopi declarations" to declared.count { it.startsWith("SharedHopi_") },
            "HopiRuntimes declarations" to declared.count { it == "SharedHopiRuntimes" },
        )
        // 1 with and without the flag: the generated entry, never hopi-start's hidden `Hopi`.
        val once = linkedMapOf(
            "Hopi classes" to symbols.count { it.trimEnd().endsWith(HOPI_CLASS) },
            // Its `@interface`; the `@class` forward list names it once more.
            "Hopi declarations" to header.get().asFile.readLines().count { it.startsWith("@interface SharedHopi ") },
        )
        val found = (families + never + once).entries.joinToString { "${it.value} ${it.key}" } + "; strings $strings"
        if (never.values.any { it != 0 }) {
            throw GradleException("The framework's header declares an internal Hopi type: $found")
        }
        if (once.values.any { it != 1 }) {
            throw GradleException("The framework does not export exactly one Hopi, the generated entry: $found")
        }
        if (real.get()) {
            if (families.values.any { it == 0 } || strings.values.any { !it }) {
                throw GradleException("The -Phopi.enabled=true framework lacks a runtime's code (the check would not see it): $found")
            }
        } else if (families.values.any { it != 0 } || strings.values.any { it }) {
            throw GradleException("The framework built without -Phopi.enabled=true holds Hopi runtime code: $found")
        }
        logger.lifecycle("${file.name} (${if (real.get()) "with the flag" else "without the flag"}): $found")
    }

    /**
     * The names the header declares or forward-declares: `@interface X …` and `@protocol X …` (a
     * category is its class's name again), and each name of an `@class …;` or `@protocol …;` list.
     */
    private fun declarations(header: String): List<String> {
        val names = ArrayList<String>()
        for (line in header.lines()) {
            val text = line.trim()
            if (!text.startsWith("@interface ") && !text.startsWith("@protocol ") && !text.startsWith("@class ")) continue
            if (text.endsWith(";")) {
                text.substringAfter(' ').removeSuffix(";").split(',').mapNotNullTo(names) { NAME.find(it.trim())?.value }
            } else if (!text.startsWith("@class ")) {
                NAME.find(text.substringAfter(' '))?.value?.let(names::add)
            }
        }
        return names
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        /** Every Kotlin/Native symbol kind (`_kfun:`, `_kclass:`, `_ktypew:`, …) of a runtime's package. */
        val RUNTIME_KOTLIN = Regex("""_k[a-z]+:dev\.koga\.hopi\.(core|network|urlsession|android|ui|perf\.internal|start)[.#]""")
        const val HOPI_CLASS = "_OBJC_CLASS_\$_SharedHopi"
        val NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*""")
        val C_STRINGS = listOf("dev.koga.hopi.urlsession-capture", "dev.koga.hopi.urlsession-rules", "_didReceiveData:", "_didFinishWithError:")
        val KOTLIN_STRINGS = listOf("hopi-perf-hang", "hopi-perf-samp")
    }
}

// The iOS release guard (the io.github.felipekoga.hopi plugin's): every Release link of this module first fails
// on a Hopi runtime, except the link the release check below consumes, and that one only while the
// check is requested by name (its positive control links every runtime with -Phopi.enabled=true).
val releaseCheckLink = "linkReleaseFrameworkIosSimulatorArm64"
extra["hopiIosReleaseGuardExemptLink"] = releaseCheckLink

// The release framework, simulator slice: what an App Store build links, minus the device slice.
tasks.register<VerifyIosCaptureSelection>("verifyIosReleaseExcludesCapture") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the iOS release framework holds no Hopi runtime (with -Phopi.enabled=true: that it holds every one)."
    val link = tasks.named<KotlinNativeLink>(releaseCheckLink)
    dependsOn(link)
    binary.set(link.flatMap { it.outputFile }.map { project.layout.projectDirectory.file(it.resolve("Shared").absolutePath) })
    header.set(link.flatMap { it.outputFile }.map { project.layout.projectDirectory.file(it.resolve("Headers/Shared.h").absolutePath) })
    real.set(hopi.iosRuntime)
}

if (System.getProperty("os.name").startsWith("Mac")) {
    tasks.named("check") { dependsOn("verifyIosReleaseExcludesCapture") }
}
