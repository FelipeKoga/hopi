import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// The coordinates hopi-network's host tests name; Gradle substitutes this project for them.
group = "dev.koga.hopi"
version = providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText
    .map { text -> text.lineSequence().first { it.startsWith("hopi.version=") }.substringAfter('=').trim() }.get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// One OkHttp per configuration (the 4.x and 5.x jars define the same classes), resolved without
// dependencies: the tests read RealCall from each jar, they do not run it.
val okhttp4: Configuration = configurations.create("okhttp4") { isTransitive = false }
val okhttp5: Configuration = configurations.create("okhttp5") { isTransitive = false }

dependencies {
    // AGP brings ASM at run time; the rewriter must use that one.
    compileOnly(libs.asm)
    compileOnly(libs.asm.tree)
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
    // The TestKit tests' plugin repository (hopi-gradle-plugin's functionalTest), nothing else.
    repositories { maven { name = "functional"; url = uri(rootProject.layout.buildDirectory.dir("functional-repo")) } }
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
}
