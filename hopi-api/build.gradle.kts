import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "The API an app calls; it does nothing until a debug build's runtime registers."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    // The consumer API dump; Hopi's own declarations (the registry) stay out of it.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            exclude.annotatedWith.add("dev.koga.hopi.api.HopiInternalApi")
        }
    }

    android {
        namespace = "dev.koga.hopi.api"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // No dependency at all: release builds link this module.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
