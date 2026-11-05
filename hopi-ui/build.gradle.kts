plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

description = "The desktop viewer's screens (JVM only, unpublished)."

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }

    // The only target: hopi-desktop composes these screens, and the UI tests run on the host in seconds.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // HopiPanes' signature names Compose runtime and ui types; the desktop viewer brings core itself.
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
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
