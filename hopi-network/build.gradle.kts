import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "The network runtime: automatic OkHttp and HttpURLConnection capture on Android and URLSession capture on iOS, " +
    "with their rules; the dev.koga.hopi plugin adds it to debuggable variants, hopi-start to Debug frameworks."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// The network runtime (docs/design/android-capture.md, docs/design/urlsession-capture.md):
// automatic OkHttp and HttpURLConnection capture on Android, automatic URLSession capture on iOS,
// and the rules each applies. No app names it: the `dev.koga.hopi` Gradle plugin adds it to the
// debuggable variants of an Android application, and hopi-start brings it to Debug iOS frameworks;
// every other build gets no runtime. No noop twin. The JVM target comes with the runtime-split
// plan's stage 10.
kotlin {
    explicitApi()

    // The dumps stay empty: this module has no consumer API, and a public declaration outside
    // dev.koga.hopi.android.internal and dev.koga.hopi.network.internal shows up there.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            // Android: the hooks the rewritten bytecode calls, the interceptor, the wrappers and the
            // network tool the app's generated configuration names (public in the binary: the hooks
            // and the tool must be, for the app's own classes). iOS: URLSession capture, its rules
            // and the network tool hopi-start lists. All @HopiInternalApi, no consumer API. The
            // provider that starts the tools is hopi-core's.
            exclude.byNames.add("dev.koga.hopi.android.internal.**")
            exclude.byNames.add("dev.koga.hopi.network.internal.**")
        }
    }

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
        optIn.add("kotlinx.cinterop.BetaInteropApi")
    }

    android {
        namespace = "dev.koga.hopi.network"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Keeps the hooks, the annotation and RealCall's name in a minified debuggable build;
        // only debuggable variants depend on this module.
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
        withHostTest {
            // core's platform log line calls android.util.Log, a stub on the host.
            isReturnDefaultValues = true
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // The URLSession capture library (docs/design/urlsession-capture.md), compiled per target
        // and embedded in the cinterop klib, so consumers need no extra linker setup.
        val archive = urlSessionShim(target.name)
        val interop = target.compilations.getByName("main").cinterops.create("urlsession") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/urlsession.def"))
            includeDirs(project.file("src/nativeInterop/objc"))
            extraOpts("-libraryPath", archive.get().outputs.files.singleFile.parentFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) { dependsOn(archive) }
        // Test-only fixtures (a loopback server, delegate shapes, foreign swizzlers), bound on the
        // test compilation only, so they never reach the published klib.
        val fixtures = fixturesArchive(target.name)
        val fixturesInterop = target.compilations.getByName("test").cinterops.create("fixtures") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/fixtures.def"))
            includeDirs(project.file("src/nativeInterop/objc/test"))
            extraOpts("-libraryPath", layout.buildDirectory.dir("fixtures/${target.name}").get().asFile.absolutePath)
        }
        tasks.named(fixturesInterop.interopProcessingTaskName) { dependsOn(fixtures) }
    }

    sourceSets {
        commonMain.dependencies {
            // The store, redaction, the rule engine and the start sequence's tool interface (and,
            // through core, hopi-api's registry); none of their types are in this module's API.
            implementation(project(":hopi-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // The app brings its own OkHttp (4.x or 5.x); the lowest supported API is compiled against.
            compileOnly(libs.okhttp)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.okhttp)
            // The plugin's own rewriter, substituted from the included build: the tests load OkHttp
            // and their scenarios through it, so they run the bytecode an instrumented app runs.
            implementation("dev.koga.hopi:hopi-instrument")
            implementation(libs.asm)
            implementation(libs.asm.tree)
            // The Ktor plugin on Ktor's Android engine and with ContentEncoding: one record per request.
            implementation(project(":hopi-ktor"))
            implementation(libs.ktor.client.android)
            implementation(libs.ktor.client.encoding)
        }
        iosTest.dependencies {
            // Ktor's Darwin engine with the Ktor plugin: one record per request, the capture's.
            implementation(libs.ktor.client.darwin)
            implementation(project(":hopi-ktor"))
        }
    }
}

// The compile SDK's android.jar, whose URLConnection classes the wrapper completeness test reads.
val bootClasspath = extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>().sdkComponents.bootClasspath

// OkHttp versions a scenario loads in place of the tests' own 4.x, as plain jars (`@jar`: no
// variant matching, nothing transitive): OkHttp 3.12, older than the API the interceptor calls,
// 3.14, the first with it, and Ktor's OkHttp engine with the OkHttp 5 it requires.
val okhttpJars = mapOf(
    "okhttp312" to listOf("com.squareup.okhttp3:okhttp:${libs.versions.okhttp3.old.get()}", "com.squareup.okio:okio:${libs.versions.okio.okhttp3.old.get()}"),
    "okhttp314" to listOf("com.squareup.okhttp3:okhttp:${libs.versions.okhttp3.last.get()}", "com.squareup.okio:okio:${libs.versions.okio.okhttp3.last.get()}"),
    "ktorOkhttp" to listOf(
        "io.ktor:ktor-client-okhttp-jvm:${libs.versions.ktor.get()}",
        "com.squareup.okhttp3:okhttp-jvm:${libs.versions.okhttp5.get()}",
        "com.squareup.okio:okio-jvm:${libs.versions.okio.okhttp5.get()}",
    ),
).mapValues { (name, coordinates) ->
    // The resolved files, not the configuration: what the configuration cache can store.
    configurations.create("${name}Jars") {
        isCanBeConsumed = false
        coordinates.forEach { dependencies.add(project.dependencies.create("$it@jar")) }
    }.incoming.files
}

tasks.withType<Test>().configureEach {
    val boot: Provider<List<RegularFile>> = bootClasspath
    inputs.files(boot).withNormalizer(ClasspathNormalizer::class).withPropertyName("androidJar")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dhopi.androidJar=${boot.get().map { it.asFile }.first { it.name == "android.jar" }}")
    }
    for ((name, jars) in okhttpJars) {
        inputs.files(jars).withNormalizer(ClasspathNormalizer::class).withPropertyName(name)
        jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dhopi.$name=${jars.files.joinToString(File.pathSeparator)}") }
    }
}

/**
 * Kotlin/Native's minimum iOS version (`minVersion.ios` in its `konan.properties`), so the archive
 * links into any framework Kotlin builds. :hopi-network:checkUrlSessionMinIos compares the two.
 */
val urlSessionMinIos = "15.0"

/** Compiles and archives the URLSession capture library for one iOS target; returns the archive task. */
fun urlSessionShim(targetName: String): TaskProvider<Exec> {
    val suffix = targetName.replaceFirstChar(Char::uppercase)
    val simulator = targetName == "iosSimulatorArm64"
    val source = layout.projectDirectory.file("src/nativeInterop/objc/hopi_urlsession.m")
    val header = layout.projectDirectory.file("src/nativeInterop/objc/hopi_urlsession.h")
    // Core's hit counter header, which only the .m includes: the library claims a rule's hits
    // through core's hopi_hits_* functions, which core's own cinterop klib holds, so every binary
    // that links this library links them too. The binding (urlsession.def) stays this header's.
    val hitsInclude = rootProject.layout.projectDirectory.dir("hopi-core/src/nativeInterop/objc")
    val hitsHeader = hitsInclude.file("hopi_hits.h")
    val outDir = layout.buildDirectory.dir("urlsession/$targetName")
    val objectFile = outDir.map { it.file("hopi_urlsession.o") }
    val compile = tasks.register<Exec>("compileUrlSessionShim$suffix") {
        description = "Compiles the URLSession capture library for $targetName."
        inputs.files(source, header).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(hitsHeader).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("hitsHeader")
        inputs.property("minIos", urlSessionMinIos)
        outputs.file(objectFile)
        val target = "arm64-apple-ios$urlSessionMinIos" + if (simulator) "-simulator" else ""
        val objectPath = objectFile.get().asFile
        doFirst { objectPath.parentFile.mkdirs() }
        commandLine(
            "xcrun", "--sdk", if (simulator) "iphonesimulator" else "iphoneos", "clang", "-c", "-x", "objective-c",
            "-fobjc-arc", "-fmodules", "-O2", "-Wall", "-Wextra", "-Werror", "-target", target,
            "-I", hitsInclude.asFile.absolutePath,
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
        if (actual != expected) throw GradleException("Kotlin/Native's minimum iOS is $actual; set urlSessionMinIos in hopi-network/build.gradle.kts to it")
    }
}

tasks.named("check") { dependsOn(checkUrlSessionMinIos) }

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
