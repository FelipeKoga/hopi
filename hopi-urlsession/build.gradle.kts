import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "Automatic capture of every URLSession request on iOS."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with the noop twin's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
    }

    android {
        namespace = "dev.koga.hopi.urlsession"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // Test-only fixtures (a loopback server, delegate shapes, foreign swizzlers), bound on the
        // test compilation only, so they never reach the published klib.
        val archive = fixturesArchive(target.name)
        val interop = target.compilations.getByName("test").cinterops.create("fixtures") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/fixtures.def"))
            includeDirs(project.file("src/nativeInterop/objc/test"))
            extraOpts("-libraryPath", layout.buildDirectory.dir("fixtures/${target.name}").get().asFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) { dependsOn(archive) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":hopi-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(project(":hopi-ktor"))
        }
    }
}

/** Compiles and archives the test fixtures for one iOS target; returns the archive task. */
fun fixturesArchive(targetName: String): TaskProvider<Exec> {
    val suffix = targetName.replaceFirstChar(Char::uppercase)
    val name = "archiveUrlSessionFixtures$suffix"
    val simulator = targetName == "iosSimulatorArm64"
    val source = layout.projectDirectory.file("src/nativeInterop/objc/test/hopi_fixtures.m")
    val header = layout.projectDirectory.file("src/nativeInterop/objc/test/hopi_fixtures.h")
    val outDir = layout.buildDirectory.dir("fixtures/$targetName")
    val objectFile = outDir.map { it.file("hopi_fixtures.o") }
    val compile = tasks.register<Exec>("compileUrlSessionFixtures$suffix") {
        inputs.files(source, header).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(objectFile)
        val objectPath = objectFile.get().asFile
        doFirst { objectPath.parentFile.mkdirs() }
        commandLine(
            "xcrun", "--sdk", if (simulator) "iphonesimulator" else "iphoneos", "clang", "-c", "-x", "objective-c",
            "-fobjc-arc", "-fmodules", "-O1", "-Wall", "-Wno-unused-parameter",
            "-target", "arm64-apple-ios15.0" + if (simulator) "-simulator" else "",
            "-o", objectPath.absolutePath, source.asFile.absolutePath,
        )
    }
    val archiveFile = outDir.map { it.file("libhopi_fixtures.a") }
    return tasks.register<Exec>(name) {
        dependsOn(compile)
        inputs.file(objectFile).withPathSensitivity(PathSensitivity.NONE)
        outputs.file(archiveFile)
        commandLine("xcrun", "libtool", "-static", "-o", archiveFile.get().asFile.absolutePath, objectFile.get().asFile.absolutePath)
    }
}

// `-Phopi.simulator=<udid>` runs the iOS tests on that already booted simulator (see hopi-core).
providers.gradleProperty("hopi.simulator").orNull?.let { udid ->
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        device.set(udid)
        standalone.set(false)
    }
}
