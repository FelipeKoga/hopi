import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.felipekoga.hopi"
description = "The Gradle plugin io.github.felipekoga.hopi: Hopi's runtimes in the debug builds of an Android app or a Kotlin " +
    "Multiplatform module's iOS framework, and the release guards that keep them out of release builds."
val hopiVersion: String = providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText
    .map { text -> text.lineSequence().first { it.startsWith("hopi.version=") }.substringAfter('=').trim() }.get()
version = hopiVersion

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Maven Central's sources jar (the Kotlin sources); gradle/maven-central.gradle.kts adds the rest.
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // gradleApi() and gradleTestKit() put the running Gradle's own Kotlin libraries (2.4.0 in
        // Gradle 9.7.1) on every classpath here, whose metadata Kotlin 2.2.21 reads only up to 2.3.0.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// Gradle 8.0, the oldest the plugin supports, embeds Kotlin 1.8.10: it compiles an app's build
// scripts against the plugin's hopi { } DSL, reading its metadata, and runs the plugin on its own
// standard library. So the plugin is compiled at Kotlin 1.8 (metadata 1.8.0, which
// :checkPublication checks) and references nothing outside that library (BytecodeFloorTest); its
// tests compile at the compiler's own level.
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_1_8)
        apiVersion.set(KotlinVersion.KOTLIN_1_8)
    }
}

gradlePlugin {
    plugins {
        create("hopi") {
            id = "io.github.felipekoga.hopi"
            implementationClass = "dev.koga.hopi.gradle.HopiPlugin"
            displayName = "Hopi"
            description = "Hopi's runtimes in the debuggable variants of an Android app (OkHttp and HttpURLConnection capture, " +
                "the performance monitors) and in the Debug iOS frameworks of a Kotlin Multiplatform module (with a generated " +
                "HopiSetup for Swift), and a build that fails when one reaches a release build."
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
    // The oldest APIs the plugin calls, which the app's build supplies (the catalog's floor-*); the
    // tests run on the catalog's own versions.
    compileOnly(libs.floor.agp.api)
    // The KMP path only (HopiKmp), loaded once org.jetbrains.kotlin.multiplatform is applied.
    compileOnly(libs.floor.kgp)
    compileOnly(libs.floor.asm)
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation(libs.kotlin.test)
    testImplementation(gradleApi())
    testImplementation(libs.android.gradle.api)
    // BytecodeFloorTest reads the plugin's and the rewriter's classes.
    testImplementation(libs.asm)
    "functionalTestImplementation"(kotlin("stdlib"))
    "functionalTestImplementation"(libs.kotlin.test)
    "functionalTestImplementation"(kotlin("test-junit"))
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"(project(":hopi-instrument"))
}

// What the oldest build the plugin supports runs it with, the only libraries BytecodeFloorTest lets
// the classes reference (each jar alone, without its dependencies): Gradle 8.0's Kotlin standard
// library, Gradle 8.0.2's API and AGP 8.0.2's. The plugin compiles against newer ones, the running
// Gradle's API (java-gradle-plugin's gradleApi()) and AGP 8.3.2's (floor-agp-api).
fun floorJar(library: Provider<MinimalExternalModuleDependency>): FileCollection =
    configurations.detachedConfiguration(dependencies.create(library.get())).apply { isTransitive = false }

val floorJars: Map<String, FileCollection> = mapOf(
    "stdlibFloor" to floorJar(libs.floor.kotlin.stdlib),
    "gradleApiFloor" to floorJar(libs.floor.gradle.api),
    "agpApiFloor" to floorJar(libs.floor.agp.plugin.api),
)

tasks.test {
    systemProperty("hopi.repositoryProperties", layout.projectDirectory.file("../../gradle.properties").asFile.absolutePath)
    val jars = floorJars
    for ((name, jar) in jars) inputs.files(jar).withNormalizer(ClasspathNormalizer::class).withPropertyName(name)
    jvmArgumentProviders += CommandLineArgumentProvider { jars.map { (name, jar) -> "-Dhopi.$name=${jar.singleFile}" } }
}

publishing {
    // The TestKit tests' plugin repository, which the release bundle also takes the plugin, its marker
    // and hopi-instrument from (scripts/central-release.sh): the fixture resolves the plugin from it.
    repositories { maven { name = "functional"; url = uri(rootProject.layout.buildDirectory.dir("functional-repo")) } }
}
// What Maven Central requires of the plugin's and its marker's publications: the POM's project
// information, the javadoc jar, and signatures when a signing key is set (docs/RELEASING.md).
apply(from = rootProject.file("../gradle/maven-central.gradle.kts"))

/** One fixture build at a time, the floor's or the catalog's: both run on the developer's machine. */
abstract class FixtureBuilds : BuildService<BuildServiceParameters.None>

val fixtureBuilds = gradle.sharedServices.registerIfAbsent("hopiFixtureBuilds", FixtureBuilds::class) { maxParallelUsages.set(1) }

/**
 * The TestKit tests (docs/design/android-capture.md, "Tests"): a fixture build that applies AGP and
 * this plugin, resolved from the repository above. Its Gradle runs in [testKit], a TestKit directory
 * of its own, never the developer's Gradle home, and its daemon is stopped when the tests end.
 */
fun Test.fixtureTests(testKit: File, fixtures: File) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    usesService(fixtureBuilds)
    dependsOn("publishAllPublicationsToFunctionalRepository", ":hopi-instrument:publishAllPublicationsToFunctionalRepository")
    val repo = rootProject.layout.buildDirectory.dir("functional-repo")
    inputs.files(fileTree(repo)).withPropertyName("pluginRepository")
    systemProperty("hopi.functionalRepo", repo.get().asFile.absolutePath)
    systemProperty("hopi.testKitDir", testKit.absolutePath)
    systemProperty("hopi.fixturesDir", fixtures.absolutePath)
    systemProperty("hopi.version", hopiVersion)
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

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the plugin's TestKit tests against fixture Android apps and a fixture KMP module."
    fixtureTests(layout.buildDirectory.dir("testkit").get().asFile, layout.buildDirectory.dir("functional-fixtures").get().asFile)
    systemProperty("hopi.agpVersion", libs.versions.agp.get())
    systemProperty("hopi.kotlinVersion", libs.versions.kotlin.get())
}

/**
 * The same tests on the oldest build the plugin supports: Gradle 8.0.2, AGP 8.0.2 with compileSdk 34
 * and build tools 34.0.0, and the Kotlin Gradle plugin 2.2.0 (the catalog's floor-*). Opt-in, outside
 * `check`: it downloads them into build/compat-home at the repository's root, the floor's one Gradle
 * user home, which can be deleted whole: it is the TestKit directory and the tests' own Gradle user
 * home, where TestKit unpacks the Gradle distribution, compat/run.sh's floor builds use it too, and
 * Kotlin/Native's data directory is there.
 */
tasks.register<Test>("functionalTestFloor") {
    description = "Runs the plugin's TestKit tests on Gradle 8.0.2, AGP 8.0.2 and the Kotlin Gradle plugin 2.2.0."
    val compatHome = layout.projectDirectory.asFile.resolve("../../build/compat-home").normalize()
    fixtureTests(compatHome, layout.buildDirectory.dir("functional-fixtures-floor").get().asFile)
    systemProperty("gradle.user.home", compatHome.absolutePath)
    systemProperty("hopi.gradleVersion", libs.versions.floor.gradle.get())
    systemProperty("hopi.agpVersion", libs.versions.floor.agp.plugin.get())
    systemProperty("hopi.kotlinVersion", libs.versions.floor.kgp.get())
    systemProperty("hopi.compileSdk", "34")
    systemProperty("hopi.buildToolsVersion", "34.0.0")
    // AGP 8.0 knows compileSdk 33 at most and builds 34 with this flag; Kotlin/Native's data goes
    // to compat-home, not ~/.konan. Lines of the fixtures' gradle.properties, separated by '|'.
    systemProperty(
        "hopi.fixtureProperties",
        "android.suppressUnsupportedCompileSdk=34|konan.data.dir=${compatHome.resolve("konan").absolutePath}",
    )
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
