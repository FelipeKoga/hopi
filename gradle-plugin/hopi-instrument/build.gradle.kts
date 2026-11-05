import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// The coordinates hopi-network's host tests name; Gradle substitutes this project for them.
group = "io.github.felipekoga.hopi"
description = "The bytecode rewriter of the Gradle plugin io.github.felipekoga.hopi (OkHttp's RealCall and the " +
    "java.net.URL connection call sites of an Android app's debug builds), with its dex reader."
version = providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText
    .map { text -> text.lineSequence().first { it.startsWith("hopi.version=") }.substringAfter('=').trim() }.get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Maven Central's sources jar (the Kotlin sources); gradle/maven-central.gradle.kts adds the rest.
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Loaded by the app's Gradle with the plugin, so held to the plugin's floor: Kotlin 1.8, for
// Gradle 8.0's Kotlin 1.8.10 (hopi-gradle-plugin's build); its tests compile at the compiler's level.
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_1_8)
        apiVersion.set(KotlinVersion.KOTLIN_1_8)
    }
}

// One OkHttp per configuration (the 4.x and 5.x jars define the same classes), resolved without
// dependencies: the tests read RealCall from each jar, they do not run it.
val okhttp4: Configuration = configurations.create("okhttp4") { isTransitive = false }
val okhttp5: Configuration = configurations.create("okhttp5") { isTransitive = false }

dependencies {
    // AGP brings ASM at run time; the rewriter must use that one, so it compiles against the oldest,
    // AGP 8.0.0's (floor-asm), and its tests run on the catalog's.
    compileOnly(libs.floor.asm)
    compileOnly(libs.floor.asm.tree)
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.util)
    okhttp4(libs.okhttp)
    okhttp5(libs.okhttp5.jvm)
}

tasks.test {
    val jars4: FileCollection = okhttp4
    val jars5: FileCollection = okhttp5
    inputs.files(jars4, jars5).withNormalizer(ClasspathNormalizer::class).withPropertyName("okhttpJars")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dhopi.okhttp4=${jars4.singleFile}", "-Dhopi.okhttp5=${jars5.singleFile}")
    }
}

publishing {
    // The TestKit tests' plugin repository (hopi-gradle-plugin's functionalTest), which the release
    // bundle takes it from too (scripts/central-release.sh).
    repositories { maven { name = "functional"; url = uri(rootProject.layout.buildDirectory.dir("functional-repo")) } }
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
}
// What Maven Central requires: the POM's project information, the javadoc jar, and signatures when
// a signing key is set (docs/RELEASING.md).
apply(from = rootProject.file("../gradle/maven-central.gradle.kts"))
