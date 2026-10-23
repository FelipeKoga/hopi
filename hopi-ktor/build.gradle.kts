import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with the noop twin's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    android {
        namespace = "dev.koga.hopi.ktor"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The consumer API names ClientPlugin; no core type appears in it.
            api(libs.ktor.client.core)
            implementation(project(":hopi-core"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            // A real socket engine for the giant-body benchmark (the sample's Android engine).
            implementation(libs.ktor.client.okhttp)
        }
        // The giant-body fixtures and probes, shared by every module's benchmark.
        jvmTest { kotlin.srcDir(rootProject.layout.projectDirectory.dir("benchmark/fixtures")) }
    }
}

apply(from = rootProject.file("gradle/giant-body-benchmark.gradle.kts"))
