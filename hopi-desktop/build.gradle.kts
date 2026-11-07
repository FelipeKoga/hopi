import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// The desktop viewer: an app's traffic on the developer's screen, over the same bridge as the CLI.
// A host application; never a dependency of an app.
val desktopVersion = "1.0.0"

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

dependencies {
    // Targets, adb tracking, bridge.json, RemoteSession and BridgeDecoder: shared with the CLI.
    implementation(project(":hopi-connect"))
    // HopiPanes and its theme: the desktop viewer's screens (JVM only, unpublished).
    implementation(project(":hopi-ui"))
    // The viewer's own HopiStore, the JSON writer (exports) and the redactor.
    implementation(project(":hopi-core"))
    // Skiko and AWT windowing for this OS. Foundation only: Material is not on the classpath.
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.compose.ui.test)
}

sourceSets.test {
    // FakeUsbmuxd and FakeXcrun (USB iPhones without a phone).
    kotlin.srcDir(rootProject.layout.projectDirectory.dir("hopi-connect/src/testShared/kotlin"))
}

tasks.jar {
    // Read back as the HAR export's `creator.version`.
    manifest { attributes("Implementation-Version" to desktopVersion) }
}

tasks.test {
    // The UI test composes a real window's content and writes its screenshot here.
    systemProperty("hopi.desktop.screenshots", layout.buildDirectory.dir("reports/screenshots").get().asFile.absolutePath)
    // DeviceViewerUiTest and DeviceRulesPanelUiTest run only with -Phopi.desktop.device=android|ios (a real emulator or simulator).
    val device = providers.gradleProperty("hopi.desktop.device").orNull
    if (device != null) {
        systemProperty("hopi.desktop.device", device)
        // -Phopi.desktop.evidence=<dir> writes the checks and screenshots elsewhere.
        val evidence = providers.gradleProperty("hopi.desktop.evidence").orNull ?: rootProject.layout.buildDirectory.dir("evidence/desktop").get().asFile.absolutePath
        systemProperty("hopi.desktop.evidence", evidence)
        systemProperty("hopi.repo", rootProject.layout.projectDirectory.asFile.absolutePath)
        // runTest's own limit (60 s by default), which the device runs outlast.
        systemProperty("kotlinx.coroutines.test.default_timeout", "30m")
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}

compose.desktop {
    application {
        mainClass = "dev.koga.hopi.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Hopi"
            packageVersion = desktopVersion
            description = "Hopi desktop viewer: devtools for a running KMP or CMP app"
            vendor = "dev.koga"
            // Beyond the defaults: the bridge.json watch's sensitivity modifier (com.sun.nio.file).
            modules("jdk.unsupported")
            macOS {
                bundleID = "dev.koga.hopi.desktop"
                // Rendered from icon/Hopi.svg by scripts/desktop-icon.swift.
                iconFile.set(project.file("icon/Hopi.icns"))
            }
        }
    }
}
