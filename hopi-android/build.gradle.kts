import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

description = "Automatic OkHttp and HttpURLConnection capture on Android, added by the dev.koga.hopi Gradle plugin."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// Automatic OkHttp and HttpURLConnection capture on Android (docs/design/android-capture.md).
// Android only: no app depends on it by hand, the `dev.koga.hopi` Gradle plugin adds it to the
// debuggable variants of an Android application, and its noop twin to the others.
kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with the noop twin's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            // The hooks the rewritten bytecode calls, the interceptor, the wrappers and the network
            // tool the app's generated configuration names: public in the binary (the hooks and
            // the tool must be, for the app's own classes) and @HopiInternalApi, but no consumer
            // API, and the noop has no hook. The provider is hopi-core's since stage 4 of the
            // runtime-split plan.
            exclude.byNames.add("dev.koga.hopi.android.internal.**")
        }
    }

    compilerOptions {
        optIn.add("dev.koga.hopi.api.HopiInternalApi")
    }

    android {
        namespace = "dev.koga.hopi.android"
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

    sourceSets {
        androidMain.dependencies {
            implementation(project(":hopi-core"))
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
