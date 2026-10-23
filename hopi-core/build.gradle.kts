import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    android {
        namespace = "dev.koga.hopi.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // Host target so pure-logic tests run in seconds.
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("oslog") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/oslog.def"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            // StateFlow and SharedFlow are part of core's API surface.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // The giant-body fixtures and probes, shared by every module's benchmark.
        jvmTest { kotlin.srcDir(rootProject.layout.projectDirectory.dir("benchmark/fixtures")) }
    }
}

apply(from = rootProject.file("gradle/giant-body-benchmark.gradle.kts"))

// `-Phopi.simulator=<udid>` runs the iOS tests on that already booted simulator instead of a
// standalone one, so its unified log can be read afterwards with `simctl spawn booted log show`.
providers.gradleProperty("hopi.simulator").orNull?.let { udid ->
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        device.set(udid)
        standalone.set(false)
    }
}
