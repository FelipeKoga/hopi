import dev.koga.hopi.gradle.HopiMonitor
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// An app module with Hopi's plugin, configured as an app's Kotlin DSL script does: the hopi { }
// block, its nested blocks and a type of the plugin's (HopiMonitor), which the build's Gradle
// compiles against the plugin's Kotlin metadata (Gradle 8.0 with its Kotlin 1.8.10). `.set()`,
// since assigning a Property with `=` needs Gradle 8.2.
plugins {
    id("com.android.application")
    id("io.github.felipekoga.hopi")
}

// AGP 8 compiles Kotlin with the Kotlin Android plugin; AGP 9 with its built-in Kotlin, which
// refuses that plugin.
val agp8 = androidComponents.pluginVersion.major < 9
if (agp8) apply(plugin = "org.jetbrains.kotlin.android")

android {
    namespace = "floor.app"
    compileSdk = 34
    // AGP 8.0's own build tools (33.0.1) are older than compileSdk 34; AGP 9 ignores any below 36.
    if (agp8) buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "floor.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // AGP 8.0 defaults Java to 1.8, and the Kotlin plugin fails a build whose Java and Kotlin
    // targets differ.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

hopi {
    network {
        rules.set(true)
    }
    perf {
        autoStart(HopiMonitor.Frames)
    }
}

dependencies {
    // The runtimes, debug only: the network capture and the performance monitors.
    debugImplementation("io.github.felipekoga.hopi:hopi-network:0.1.0")
    debugImplementation("io.github.felipekoga.hopi:hopi-perf:0.1.0")
    implementation("io.github.felipekoga.hopi:hopi-api:0.1.0")
    implementation("io.github.felipekoga.hopi:hopi-ktor:0.1.0")
    implementation("io.ktor:ktor-client-okhttp:3.3.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// For compat/run.sh: the Hopi modules and the kotlin-stdlib that each variant's runtime classpath
// resolves, one `compat-report` line per variant (a KMP module once: hopi-api for hopi-api and the
// hopi-api-android it redirects to).
tasks.register("compatReport") {
    val classpaths = listOf("debug", "release").associateWith { variant ->
        configurations.named("${variant}RuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    }
    doLast {
        for ((variant, root) in classpaths) {
            val components = mutableSetOf<ResolvedComponentResult>()
            fun collect(component: ResolvedComponentResult) {
                if (components.add(component)) {
                    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { collect(it.selected) }
                }
            }
            collect(root.get())
            val modules = components.mapNotNull { it.moduleVersion }
            val hopi = modules.filter { it.group == "io.github.felipekoga.hopi" }.map { it.name.removeSuffix("-android") }.distinct().sorted()
            val stdlib = modules.firstOrNull { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" }?.version
            println("compat-report $variant hopi=${hopi.joinToString(",")} kotlin-stdlib=$stdlib")
        }
    }
}
