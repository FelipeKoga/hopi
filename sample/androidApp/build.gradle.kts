import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.koga.hopi.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.koga.hopi.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // `-Psample.applicationIdSuffix=.probe` builds a second install with its own uid, the
            // "another app" whose connection the bridge's peer uid check must refuse.
            providers.gradleProperty("sample.applicationIdSuffix").orNull?.let { applicationIdSuffix = it }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Hopi comes with the shared module, which selects the real modules or the noop twins by
    // the `hopi.enabled` Gradle property, for every build type (docs/ARCHITECTURE.md,
    // "Selecting real vs noop"). This module names neither, so the two are never both packaged.
    implementation(project(":sample:shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
}

/**
 * Proves which Hopi a release APK packages. It lists every class in the APK's dex files and
 * compares the Hopi ones (`dev.koga.hopi.*` outside the sample's own package) with the
 * classes of the Hopi projects on the release runtime classpath:
 *
 * - noop expected: the classpath holds exactly the noop twins, the APK no `dev.koga.hopi.core`
 *   class, and every Hopi class in the APK is one the noop twins compiled, and all of them are;
 * - real expected: the classpath holds the real modules and core, and the APK exactly their classes.
 *
 * Dex files are read directly (header, string ids, type ids, class defs), so no SDK tool runs.
 */
abstract class VerifyHopiSelection : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val expectReal: Property<Boolean>

    /** Paths of the Hopi projects on the release runtime classpath. */
    @get:Input
    abstract val hopiProjects: SetProperty<String>

    /** The classes jars of those projects. */
    @get:Classpath
    abstract val hopiJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val builtArtifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No release APK in ${apkDirectory.get().asFile}")
        val apk = File(builtArtifacts.elements.single().outputFile)
        val real = expectReal.get()
        val problems = mutableListOf<String>()

        val projects = hopiProjects.get()
        val expectedProjects = if (real) {
            setOf(":hopi-core", ":hopi-ktor", ":hopi-ui")
        } else {
            setOf(":hopi-ktor-noop", ":hopi-ui-noop")
        }
        if (projects != expectedProjects) {
            problems += "the release runtime classpath holds ${projects.sorted()}, expected ${expectedProjects.sorted()}"
        }

        val libraryClasses = hopiJars.files.flatMapTo(sortedSetOf()) { classesInJar(it) }
        val apkClasses = classesInApk(apk).filterTo(sortedSetOf()) {
            it.startsWith("dev.koga.hopi.") && !it.startsWith("dev.koga.hopi.sample.")
        }
        // D8 may add synthetic classes next to a library's own (`…$$ExternalSyntheticLambda0`).
        val apkOwnClasses = apkClasses.mapTo(sortedSetOf()) { it.substringBefore("\$\$ExternalSynthetic") }
        val coreClasses = apkClasses.filter { it.startsWith("dev.koga.hopi.core.") }

        if (libraryClasses.isEmpty()) problems += "the Hopi projects' jars hold no class"
        val unexpected = apkOwnClasses - libraryClasses
        if (unexpected.isNotEmpty()) problems += "classes of no selected Hopi project: ${unexpected.take(20)}"
        val missing = libraryClasses - apkOwnClasses
        if (missing.isNotEmpty()) problems += "classes of the selected projects missing from the APK: ${missing.take(20)}"
        if (real && coreClasses.isEmpty()) problems += "no dev.koga.hopi.core class, but the real modules need core"
        if (!real && coreClasses.isNotEmpty()) problems += "${coreClasses.size} dev.koga.hopi.core classes, e.g. ${coreClasses.take(5)}"

        val summary = buildString {
            appendLine("apk: ${apk.name}")
            appendLine("expected: ${if (real) "the real modules" else "the noop twins"}")
            appendLine("hopi projects on the release classpath: ${projects.sorted().joinToString()}")
            appendLine("hopi classes in the apk: ${apkClasses.size}, of which dev.koga.hopi.core: ${coreClasses.size}")
            apkClasses.forEach { appendLine("  $it") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("The release APK does not package the expected Hopi:\n- " + problems.joinToString("\n- ") + "\n" + summary)
        }
        report.get().asFile.writeText(summary)
        logger.lifecycle(summary.lineSequence().take(4).joinToString("\n"))
    }

    private fun classesInJar(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .toList()
    }

    private fun classesInApk(apk: File): List<String> = ZipFile(apk).use { zip ->
        val dexEntries = zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }.toList()
        if (dexEntries.isEmpty()) throw GradleException("${apk.name} has no classes*.dex")
        dexEntries.flatMap { entry -> classesInDex(zip.getInputStream(entry).use { it.readAllBytes() }, entry.name) }
    }

    /** The class names a dex file defines, from its header's string ids, type ids and class defs. */
    private fun classesInDex(bytes: ByteArray, name: String): List<String> {
        if (bytes.size < 0x70 || String(bytes, 0, 4, Charsets.US_ASCII) != "dex\n") throw GradleException("$name is not a dex file")
        val dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsOff = dex.getInt(0x3C)
        val typeIdsOff = dex.getInt(0x44)
        val classDefsSize = dex.getInt(0x60)
        val classDefsOff = dex.getInt(0x64)
        return (0 until classDefsSize).map { index ->
            val typeIndex = dex.getInt(classDefsOff + index * 32)
            val stringIndex = dex.getInt(typeIdsOff + typeIndex * 4)
            var offset = dex.getInt(stringIdsOff + stringIndex * 4)
            // string_data_item: the length in UTF-16 units as uleb128, then MUTF-8 bytes up to a NUL.
            while (bytes[offset].toInt() and 0x80 != 0) offset++
            offset++
            var end = offset
            while (bytes[end].toInt() != 0) end++
            String(bytes, offset, end - offset, Charsets.UTF_8).removePrefix("L").removeSuffix(";").replace('/', '.')
        }
    }

    private companion object {
        val DEX_ENTRY = Regex("classes\\d*\\.dex")
    }
}

val hopiEnabled = providers.gradleProperty("hopi.enabled").orNull?.toBoolean() ?: false

// An object, not a script function: the configuration cache stores the provider mapping that
// uses it, and a script function would capture the script.
object HopiGraph {
    /** The Hopi projects in a resolved dependency graph, by project path. */
    fun projectsIn(root: ResolvedComponentResult): Set<String> {
        val found = sortedSetOf<String>()
        val visited = mutableSetOf(root.id)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            (component.id as? ProjectComponentIdentifier)?.projectPath?.takeIf { it.startsWith(":hopi-") }?.let(found::add)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }.filter { visited.add(it.id) }.forEach(queue::add)
        }
        return found
    }
}

fun VerifyHopiSelection.configureSelectionCheck(real: Boolean, reportName: String) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    expectReal.set(real)
    report.set(layout.buildDirectory.file("reports/hopi/$reportName.txt"))
}

// The default every consumer relies on: built without the flag, the release APK ships the noop
// twins and no hopi-core. With -Phopi.enabled=true this task fails, as it should.
val verifyReleaseUsesNoop = tasks.register<VerifyHopiSelection>("verifyReleaseUsesNoop") {
    description = "Checks that the release APK ships only the noop twins (the build without -Phopi.enabled=true)."
    configureSelectionCheck(real = false, reportName = "release-uses-noop")
}

// Follows the flag, so it proves both directions: run it once without and once with
// -Phopi.enabled=true.
val verifyHopiSelection = tasks.register<VerifyHopiSelection>("verifyHopiSelection") {
    description = "Checks that the release APK ships the Hopi modules -Phopi.enabled selects."
    configureSelectionCheck(real = hopiEnabled, reportName = "release-selection")
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        listOf(verifyReleaseUsesNoop, verifyHopiSelection).forEach { task ->
            task.configure {
                apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                val classpath = variant.runtimeConfiguration
                hopiProjects.set(classpath.incoming.resolutionResult.rootComponent.map(HopiGraph::projectsIn))
                hopiJars.from(
                    classpath.incoming.artifactView {
                        attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
                        componentFilter { it is ProjectComponentIdentifier && it.projectPath.startsWith(":hopi-") }
                    }.files,
                )
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyHopiSelection)
}
