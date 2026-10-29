import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.koga.hopi"
val hopiVersion: String = providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText
    .map { text -> text.lineSequence().first { it.startsWith("hopi.version=") }.substringAfter('=').trim() }.get()
version = hopiVersion

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

gradlePlugin {
    plugins {
        create("hopi") {
            id = "dev.koga.hopi"
            implementationClass = "dev.koga.hopi.gradle.HopiPlugin"
            displayName = "Hopi"
            description = "Automatic OkHttp and HttpURLConnection capture in the debuggable variants of an Android app."
        }
    }
}

/** Writes the version the plugin adds the runtime at, from the repository's `hopi.version`. */
abstract class GeneratePluginVersion : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile.resolve("dev/koga/hopi/gradle")
        directory.mkdirs()
        directory.resolve("HopiPluginVersion.kt").writeText(
            """
            |package dev.koga.hopi.gradle
            |
            |/** Generated from `hopi.version` in the repository's gradle.properties. */
            |internal const val HOPI_VERSION: String = "${version.get()}"
            |
            """.trimMargin(),
        )
    }
}

val generatePluginVersion = tasks.register<GeneratePluginVersion>("generatePluginVersion") {
    version.set(hopiVersion)
    outputDirectory.set(layout.buildDirectory.dir("generated/pluginVersion/kotlin"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generatePluginVersion.flatMap { it.outputDirectory }) }

val functionalTest: SourceSet = sourceSets.create("functionalTest")
gradlePlugin.testSourceSets(functionalTest)

dependencies {
    implementation(project(":hopi-instrument"))
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.asm)
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation(libs.kotlin.test)
    testImplementation(gradleApi())
    testImplementation(libs.android.gradle.api)
    "functionalTestImplementation"(kotlin("stdlib"))
    "functionalTestImplementation"(libs.kotlin.test)
    "functionalTestImplementation"(kotlin("test-junit"))
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"(project(":hopi-instrument"))
}

tasks.test {
    systemProperty("hopi.repositoryProperties", layout.projectDirectory.file("../../gradle.properties").asFile.absolutePath)
}

publishing {
    // The TestKit tests' plugin repository, nothing else: the fixture resolves dev.koga.hopi from it.
    repositories { maven { name = "functional"; url = uri(rootProject.layout.buildDirectory.dir("functional-repo")) } }
}

/**
 * The TestKit tests (docs/design/android-capture.md, "Tests"): a fixture build that applies AGP and
 * this plugin, resolved from the repository above. Its Gradle runs in a TestKit directory of its
 * own, never the developer's Gradle home, and its daemon is stopped when the tests end.
 */
val functionalTestTask = tasks.register<Test>("functionalTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the plugin's TestKit tests against a fixture Android app."
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    dependsOn("publishAllPublicationsToFunctionalRepository", ":hopi-instrument:publishAllPublicationsToFunctionalRepository")
    val repo = rootProject.layout.buildDirectory.dir("functional-repo")
    val testKit = layout.buildDirectory.dir("testkit")
    val work = layout.buildDirectory.dir("functional-fixtures")
    inputs.files(fileTree(repo)).withPropertyName("pluginRepository")
    systemProperty("hopi.functionalRepo", repo.get().asFile.absolutePath)
    systemProperty("hopi.testKitDir", testKit.get().asFile.absolutePath)
    systemProperty("hopi.fixturesDir", work.get().asFile.absolutePath)
    systemProperty("hopi.version", hopiVersion)
    systemProperty("hopi.agpVersion", libs.versions.agp.get())
    systemProperty("hopi.okhttpVersion", libs.versions.okhttp.get())
    systemProperty("hopi.okhttp5Version", libs.versions.okhttp5.get())
    // The network runtime's and core's own consumer rules, which the fixture's stand-ins ship for
    // its minified variant (core's keep the provider and the generated configuration).
    val consumerRules = layout.projectDirectory.file("../../hopi-network/consumer-rules.pro")
    inputs.file(consumerRules).withPropertyName("consumerRules")
    systemProperty("hopi.consumerRules", consumerRules.asFile.absolutePath)
    val coreConsumerRules = layout.projectDirectory.file("../../hopi-core/consumer-rules.pro")
    inputs.file(coreConsumerRules).withPropertyName("coreConsumerRules")
    systemProperty("hopi.coreConsumerRules", coreConsumerRules.asFile.absolutePath)
    systemProperty("hopi.sdkDir", androidSdkDir())
    // One Gradle build at a time on the development machine: the fixture build runs after the rest.
    maxParallelForks = 1
}

tasks.named("check") { dependsOn(functionalTestTask) }

fun androidSdkDir(): String {
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
    val local = layout.projectDirectory.file("../../local.properties").asFile
    if (local.isFile) {
        local.readLines().firstOrNull { it.startsWith("sdk.dir=") }?.let { return it.substringAfter('=').trim() }
    }
    return System.getProperty("user.home") + "/Library/Android/sdk"
}
