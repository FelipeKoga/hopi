import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "The recording store, redaction and agent bridge every Hopi module shares."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

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
        val main = target.compilations.getByName("main")
        main.cinterops.create("oslog") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/oslog.def"))
        }
        // The URLSession capture library (docs/design/urlsession-capture.md), compiled per target
        // and embedded in the cinterop klib, so consumers need no extra linker setup.
        val archive = urlSessionShim(target.name)
        val interop = main.cinterops.create("urlsession") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/urlsession.def"))
            includeDirs(project.file("src/nativeInterop/objc"))
            extraOpts("-libraryPath", archive.get().outputs.files.singleFile.parentFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) { dependsOn(archive) }
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

/**
 * Kotlin/Native's minimum iOS version (`minVersion.ios` in its `konan.properties`), so the archive
 * links into any framework Kotlin builds. :hopi-core:checkUrlSessionMinIos compares the two.
 */
val urlSessionMinIos = "15.0"

/** Compiles and archives the URLSession capture library for one iOS target; returns the archive task. */
fun urlSessionShim(targetName: String): TaskProvider<Exec> {
    val suffix = targetName.replaceFirstChar(Char::uppercase)
    val simulator = targetName == "iosSimulatorArm64"
    val source = layout.projectDirectory.file("src/nativeInterop/objc/hopi_urlsession.m")
    val header = layout.projectDirectory.file("src/nativeInterop/objc/hopi_urlsession.h")
    val outDir = layout.buildDirectory.dir("urlsession/$targetName")
    val objectFile = outDir.map { it.file("hopi_urlsession.o") }
    val compile = tasks.register<Exec>("compileUrlSessionShim$suffix") {
        description = "Compiles the URLSession capture library for $targetName."
        inputs.files(source, header).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("minIos", urlSessionMinIos)
        outputs.file(objectFile)
        val target = "arm64-apple-ios$urlSessionMinIos" + if (simulator) "-simulator" else ""
        val objectPath = objectFile.get().asFile
        doFirst { objectPath.parentFile.mkdirs() }
        commandLine(
            "xcrun", "--sdk", if (simulator) "iphonesimulator" else "iphoneos", "clang", "-c", "-x", "objective-c",
            "-fobjc-arc", "-fmodules", "-O2", "-Wall", "-Wextra", "-Werror", "-target", target,
            "-o", objectPath.absolutePath, source.asFile.absolutePath,
        )
    }
    val archiveFile = outDir.map { it.file("libhopi_urlsession.a") }
    return tasks.register<Exec>("archiveUrlSessionShim$suffix") {
        description = "Archives the URLSession capture library for $targetName."
        dependsOn(compile)
        inputs.file(objectFile).withPathSensitivity(PathSensitivity.NONE)
        outputs.file(archiveFile)
        commandLine("xcrun", "libtool", "-static", "-o", archiveFile.get().asFile.absolutePath, objectFile.get().asFile.absolutePath)
    }
}

val checkUrlSessionMinIos = tasks.register("checkUrlSessionMinIos") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the URLSession library's minimum iOS version is Kotlin/Native's."
    val kotlinVersion = libs.versions.kotlin.get()
    val expected = urlSessionMinIos
    val properties = File(System.getProperty("user.home"), ".konan/kotlin-native-prebuilt-macos-aarch64-$kotlinVersion/konan/konan.properties")
    onlyIf { properties.isFile }
    doLast {
        val line = properties.readLines().firstOrNull { it.startsWith("minVersion.ios =") }
            ?: throw GradleException("no minVersion.ios in $properties")
        val actual = line.substringAfter('=').trim()
        if (actual != expected) throw GradleException("Kotlin/Native's minimum iOS is $actual; set urlSessionMinIos in hopi-core/build.gradle.kts to it")
    }
}

tasks.named("check") { dependsOn(checkUrlSessionMinIos) }

// `-Phopi.simulator=<udid>` runs the iOS tests on that already booted simulator instead of a
// standalone one, so its unified log can be read afterwards with `simctl spawn booted log show`.
providers.gradleProperty("hopi.simulator").orNull?.let { udid ->
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        device.set(udid)
        standalone.set(false)
    }
}

// `-Phopi.hotpath` runs only the hot-path benchmarks (store updates, summary lines, hand-off CPU),
// on the JVM and on the iOS simulator, printing `HOTPATH …` lines; without it they return at once.
if (providers.gradleProperty("hopi.hotpath").isPresent) {
    tasks.named<Test>("jvmTest") {
        systemProperty("hopi.hotpath", "1")
        filter { includeTestsMatching("*HotPathBenchmark") }
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        environment("SIMCTL_CHILD_HOPI_HOTPATH", "1")
        filter.includeTestsMatching("*HotPathBenchmark")
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}
