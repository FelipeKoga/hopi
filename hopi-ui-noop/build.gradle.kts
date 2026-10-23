import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The Compose compiler only, for the @Composable calling convention; not the Compose
    // Multiplatform Gradle plugin, which this module needs nothing from.
    alias(libs.plugins.compose.compiler)
}

kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with hopi-ui's. The filters are the
    // same as hopi-ui's, so both dumps are made the same way.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        // byNames matches a `$` in a JVM class name as `.`, hence `ComposableSingletons.*`.
        filters {
            exclude.byNames.addAll("**.ComposableSingletons.*", "**\$stableprop", "**\$stableprop_getter")
            // The desktop viewer's entry point (jvm only, @HopiInternalApi, names core's
            // HopiStore): Hopi-internal, not consumer API, so it has no noop twin.
            exclude.byNames.add("dev.koga.hopi.ui.desktop.**")
        }
    }

    android {
        namespace = "dev.koga.hopi.ui.noop"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Only what the consumer API names: @Composable (runtime) and Modifier (ui).
            api(libs.compose.runtime)
            api(libs.compose.ui)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui.test)
            // The same host skiko runtime as hopi-ui's UI tests, without Material.
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-${skikoHostSuffix()}:${libs.versions.skiko.get()}")
        }
    }
}

fun skikoHostSuffix(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    return when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
}
