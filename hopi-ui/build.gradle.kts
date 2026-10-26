import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

description = "HopiScreen and HopiLauncher, the in-app inspector (Compose Multiplatform)."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with the noop twin's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        // Compose compiler output that is public in the binary but no declaration of ours:
        // lambda holders for composable lambdas and stability markers of internal classes.
        // byNames matches a `$` in a JVM class name as `.`, hence `ComposableSingletons.*`.
        filters {
            exclude.byNames.addAll("**.ComposableSingletons.*", "**\$stableprop", "**\$stableprop_getter")
            // The desktop viewer's entry point (jvm only, @HopiInternalApi, names core's
            // HopiStore): Hopi-internal, not consumer API, so it has no noop twin.
            exclude.byNames.add("dev.koga.hopi.ui.desktop.**")
        }
    }

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    android {
        namespace = "dev.koga.hopi.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // Host target so logic and Compose UI tests run in seconds.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The consumer API names only Compose runtime and ui types; core stays internal.
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
            implementation(libs.compose.ui.backhandler)
            implementation(project(":hopi-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            // Only the skiko native runtime that ui-test needs on the host, not
            // `compose.desktop.currentOs`, which would bring Material onto the test classpath.
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-${skikoHostSuffix()}:${libs.versions.skiko.get()}")
        }
        // The giant-body fixtures and probes, shared by every module's benchmark.
        jvmTest { kotlin.srcDir(rootProject.layout.projectDirectory.dir("benchmark/fixtures")) }
    }
}

apply(from = rootProject.file("gradle/giant-body-benchmark.gradle.kts"))

fun skikoHostSuffix(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
    return when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
}
