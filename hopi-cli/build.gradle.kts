import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        // The released archive runs on any JDK 17+, whatever JDK builds it.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    applicationName = "hopi"
    mainClass.set("dev.koga.hopi.cli.MainKt")
}

// applicationName also names the distribution; keep the module's name for build/install/hopi-cli.
distributions {
    main {
        distributionBaseName.set("hopi-cli")
    }
}

// The release asset (.github/workflows/cli.yml): build/distributions/hopi-cli.tar.gz, with
// hopi-cli/bin/hopi inside. No version in either name, so
// releases/latest/download/hopi-cli.tar.gz always resolves and the unpacked path never changes.
tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveVersion.set("")
    archiveExtension.set("tar.gz")
}
tasks.named<Zip>("distZip") {
    archiveVersion.set("")
}

sourceSets.test {
    // The giant-body fixtures and probes, shared by every module's benchmark.
    kotlin.srcDir(rootProject.layout.projectDirectory.dir("benchmark/fixtures"))
    // FakeUsbmuxd and FakeXcrun (USB iPhones without a phone), shared with :hopi-connect's tests.
    kotlin.srcDir(rootProject.layout.projectDirectory.dir("hopi-connect/src/testShared/kotlin"))
    // The adb and simctl output fixtures live with the parsers in :hopi-connect.
    resources.srcDir(rootProject.layout.projectDirectory.dir("hopi-connect/src/test/resources"))
}

dependencies {
    // Targets, adb and simctl, bridge.json and the bridge client (hopi-connect).
    implementation(project(":hopi-connect"))
    // cURL export, JSON writer, body helpers and the bridge itself (tests); never reimplemented here.
    implementation(project(":hopi-core"))
    // Only the JSON tree parser; no serialization compiler plugin is needed for it.
    implementation(libs.kotlinx.serialization.json)

    // The real Ktor plugin over a MockEngine fills the store, through the JVM entry's recorder:
    // hopi-start brings the network runtime, which takes hopi-ktor compileOnly.
    testImplementation(project(":hopi-ktor"))
    testImplementation(project(":hopi-start"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    // InstalledBinaryTest runs the real `bin/hopi` as a subprocess.
    dependsOn(tasks.installDist)
    systemProperty(
        "hopi.cli.install",
        layout.buildDirectory.dir("install/hopi-cli").get().asFile.absolutePath,
    )
}

apply(from = rootProject.file("gradle/giant-body-benchmark.gradle.kts"))
