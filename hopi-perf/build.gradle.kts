import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "HopiPerf, the performance monitors (frames, hangs, CPU, memory)."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// HopiPerf: the performance monitors (docs/design/perf-monitors.md): frames, hangs, CPU, memory,
// each off by default. The model, its JSON and the bridge commands live in hopi-core; this module
// holds the consumer API and the monitors. Its noop twin, hopi-perf-noop, has the same API and does
// nothing.
kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with hopi-perf-noop's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            // PerfEntry, which hopi-android's provider loads by name, and the monitors: public in
            // the binary where the provider needs it, @HopiInternalApi, no consumer API.
            exclude.byNames.add("dev.koga.hopi.perf.internal.**")
        }
    }

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    android {
        namespace = "dev.koga.hopi.perf"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Keeps PerfEntry, which the provider loads reflectively, in a minified debuggable build.
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
        withHostTest {
            // android.* are stubs on the host.
            isReturnDefaultValues = true
        }
    }
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // The iOS monitors' native part (run-loop observers, stack capture, task_info), compiled
        // per target and embedded in the cinterop klib, so consumers need no extra linker setup.
        val archive = perfShim(target.name)
        val interop = target.compilations.getByName("main").cinterops.create("perf") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/perf.def"))
            includeDirs(project.file("src/nativeInterop/objc"))
            extraOpts("-libraryPath", archive.get().outputs.files.singleFile.parentFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) { dependsOn(archive) }
    }

    sourceSets {
        commonMain.dependencies {
            // The model and the bridge; none of its types are in this module's API.
            implementation(project(":hopi-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

/** Compiles and archives the iOS monitors' native library for one target; returns the archive task. */
fun perfShim(targetName: String): TaskProvider<Exec> {
    val suffix = targetName.replaceFirstChar(Char::uppercase)
    val simulator = targetName == "iosSimulatorArm64"
    val source = layout.projectDirectory.file("src/nativeInterop/objc/hopi_perf.m")
    val header = layout.projectDirectory.file("src/nativeInterop/objc/hopi_perf.h")
    val outDir = layout.buildDirectory.dir("perfshim/$targetName")
    val objectFile = outDir.map { it.file("hopi_perf.o") }
    // Kotlin/Native's minimum iOS, as hopi-core's URLSession library (checkUrlSessionMinIos).
    val minIos = "15.0"
    val compile = tasks.register<Exec>("compilePerfShim$suffix") {
        description = "Compiles the iOS monitors' native library for $targetName."
        inputs.files(source, header).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("minIos", minIos)
        outputs.file(objectFile)
        val target = "arm64-apple-ios$minIos" + if (simulator) "-simulator" else ""
        val objectPath = objectFile.get().asFile
        doFirst { objectPath.parentFile.mkdirs() }
        commandLine(
            "xcrun", "--sdk", if (simulator) "iphonesimulator" else "iphoneos", "clang", "-c", "-x", "objective-c",
            "-fobjc-arc", "-fmodules", "-O2", "-Wall", "-Wextra", "-Werror", "-target", target,
            "-o", objectPath.absolutePath, source.asFile.absolutePath,
        )
    }
    val archiveFile = outDir.map { it.file("libhopi_perf.a") }
    return tasks.register<Exec>("archivePerfShim$suffix") {
        description = "Archives the iOS monitors' native library for $targetName."
        dependsOn(compile)
        inputs.file(objectFile).withPathSensitivity(PathSensitivity.NONE)
        outputs.file(archiveFile)
        commandLine("xcrun", "libtool", "-static", "-o", archiveFile.get().asFile.absolutePath, objectFile.get().asFile.absolutePath)
    }
}

// `-Phopi.simulator=<udid>` runs the iOS tests on that already booted simulator (see hopi-core).
providers.gradleProperty("hopi.simulator").orNull?.let { udid ->
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(udid)
        standalone.set(false)
    }
}
