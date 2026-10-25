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
}

// The selection every KMP consumer makes (docs/ARCHITECTURE.md, "Selecting real vs noop"): a shared
// module has no build types, so a Gradle property picks the real artifacts or their noop twins for
// every target at once. Debug runs pass -Phopi.enabled=true (the Xcode build phase does for
// the Debug configuration); anything built without it ships the noop.
val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false

// Automatic URLSession capture, iOS only (Android has no URLSession): the real module or its noop.
val urlSessionModule = if (hopiEnabled) project(":hopi-urlsession") else project(":hopi-urlsession-noop")

// HopiRules (mock, fail, delay, edit, replay), which the rules scenarios call: the real module or its noop.
val rulesModule = if (hopiEnabled) project(":hopi-rules") else project(":hopi-rules-noop")

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
            |/** Generated from the `hopi.enabled` Gradle property: true for the real modules, false for the noop twins. */
            |internal const val HOPI_ENABLED: Boolean = ${realHopi.get()}
            |
            """.trimMargin(),
        )
    }
}

val generateSampleBuildInfo = tasks.register<GenerateSampleBuildInfo>("generateSampleBuildInfo") {
    realHopi.set(hopiEnabled)
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
            // Swift calls HopiUrlSession.shared.start() itself (iOSApp.swift) and HopiRules.shared in
            // the rules scenarios (CaptureVerification.swift), so the framework exports both modules,
            // real or noop: the same API either way.
            export(urlSessionModule)
            export(rulesModule)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSampleBuildInfo.flatMap { it.outputDirectory })
            dependencies {
                implementation(if (hopiEnabled) project(":hopi-ktor") else project(":hopi-ktor-noop"))
                implementation(if (hopiEnabled) project(":hopi-ui") else project(":hopi-ui-noop"))
                // api: the Android app's rules scenarios name HopiRules too, and the iOS framework exports it.
                api(rulesModule)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.backhandler)
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
            // records from the store: built only with the real modules, never into a release.
            if (hopiEnabled) kotlin.srcDir("src/iosCaptureProbe/kotlin")
            dependencies {
                implementation(libs.ktor.client.darwin)
                api(urlSessionModule)
                if (hopiEnabled) implementation(project(":hopi-core"))
            }
        }
    }
}

/**
 * Proves that an iOS framework built without `-Phopi.enabled=true` holds none of the URLSession
 * capture and none of its rules: no `_hopi_us_` symbol (the rule functions `_hopi_us_rule…`
 * included), no `HopiUS` capture or rule-protocol Objective-C class, no `kfun:dev.koga.hopi.core`
 * symbol (`xcrun nm`, an argument array), and neither the capture and rule queues' labels nor the
 * private selector names in its bytes. With the flag it requires all of them instead (the positive
 * control), so the check is known to see what it looks for. Run it both ways.
 */
abstract class VerifyIosCaptureSelection : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val binary: RegularFileProperty

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
        val symbols = output.toString(Charsets.UTF_8).lineSequence()
        val nativeSymbols = symbols.count { "_hopi_us_" in it }
        val captureClasses = symbols.count { "HopiUSTask" in it || "HopiUSObserver" in it }
        val ruleSymbols = symbols.count { "HopiUSRuleProtocol" in it || "_hopi_us_rule" in it }
        val coreSymbols = symbols.count { "kfun:dev.koga.hopi.core" in it }
        val bytes = file.readBytes()
        // C strings, NUL included: the capture queue's label and the private selectors' names.
        val strings = listOf("dev.koga.hopi.urlsession-capture", "dev.koga.hopi.urlsession-rules", "_didReceiveData:", "_didFinishWithError:")
            .associateWith { contains(bytes, it.toByteArray() + 0) }
        val found = "$nativeSymbols _hopi_us_ symbols, $captureClasses capture classes, $ruleSymbols rule symbols, $coreSymbols core symbols, strings $strings"
        if (real.get()) {
            if (nativeSymbols == 0 || captureClasses == 0 || ruleSymbols == 0 || coreSymbols == 0 || strings.values.any { !it }) {
                throw GradleException("The -Phopi.enabled=true framework lacks the capture code (the check would not see it): $found")
            }
        } else if (nativeSymbols != 0 || captureClasses != 0 || ruleSymbols != 0 || coreSymbols != 0 || strings.values.any { it }) {
            throw GradleException("The framework built without -Phopi.enabled=true holds URLSession capture code: $found")
        }
        logger.lifecycle("${file.name} (${if (real.get()) "real" else "noop"}): $found")
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}

// The release framework, simulator slice: what an App Store build links, minus the device slice.
tasks.register<VerifyIosCaptureSelection>("verifyIosReleaseExcludesCapture") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the iOS release framework holds no URLSession capture code (with -Phopi.enabled=true: that it does)."
    val link = tasks.named<KotlinNativeLink>("linkReleaseFrameworkIosSimulatorArm64")
    dependsOn(link)
    binary.set(link.flatMap { it.outputFile }.map { project.layout.projectDirectory.file(it.resolve("Shared").absolutePath) })
    real.set(hopiEnabled)
}

if (System.getProperty("os.name").startsWith("Mac")) {
    tasks.named("check") { dependsOn("verifyIosReleaseExcludesCapture") }
}
