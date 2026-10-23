import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSampleBuildInfo.flatMap { it.outputDirectory })
            dependencies {
                implementation(if (hopiEnabled) project(":hopi-ktor") else project(":hopi-ktor-noop"))
                implementation(if (hopiEnabled) project(":hopi-ui") else project(":hopi-ui-noop"))
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
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
