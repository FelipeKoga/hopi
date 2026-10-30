plugins {
    // `check` for the root project, which carries the included build's checks and checkPublication.
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// Kotlin/Native stores each source file's absolute path in the klib (its IR's file entries), which
// would put this machine's paths into every published iOS klib and every consumer's debug info:
// store them relative to the repository. JVM and Android artifacts store file names only. Here, not
// in gradle/publishing.gradle.kts, because a script plugin does not see the Kotlin plugin's types.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=${rootDir.absolutePath}")
    }
}

tasks.named("check") {
    // The included build of the dev.koga.hopi plugin: the rewriter's tests, the plugin's unit tests
    // and its TestKit tests against a fixture Android app.
    dependsOn(gradle.includedBuild("gradle-plugin").task(":hopi-instrument:check"))
    dependsOn(gradle.includedBuild("gradle-plugin").task(":hopi-gradle-plugin:check"))
}

/**
 * Fails unless [repositories] hold every file of [expectedFiles] and every published Gradle module
 * file and POM under `dev/koga/hopi` points only at files and Hopi artifacts that are there, at
 * [hopiVersion]: what a consumer outside this repository resolves from Maven Local. A `project()`
 * dependency that leaked would name the group `hopi` or the version `unspecified`. No published
 * archive may contain [buildMachinePath].
 */
abstract class PublicationCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositories: ConfigurableFileCollection

    @get:Input
    abstract val expectedFiles: ListProperty<String>

    @get:Input
    abstract val hopiVersion: Property<String>

    /** The repository's absolute path, which no published archive may contain (a klib's source paths). */
    @get:Input
    abstract val buildMachinePath: Property<String>

    @TaskAction
    fun check() {
        val roots = repositories.files.toList()
        val version = hopiVersion.get()
        fun find(path: String): File? = roots.map { it.resolve(path) }.firstOrNull { it.isFile }
        fun published(module: String, depVersion: String?): Boolean =
            depVersion == version && find("$GROUP_PATH/$module/$version/$module-$version.pom") != null
        val problems = mutableListOf<String>()
        expectedFiles.get().filter { find(it) == null }.forEach { problems += "missing $it" }
        // This version's files only (dev/koga/hopi/<artifact>/<version>/): a repository can still hold
        // an earlier version's, whose Hopi dependencies are at that version.
        val published = roots.flatMap { root ->
            root.resolve(GROUP_PATH).walkTopDown().filter { it.isFile && it.parentFile.name == version }.map { root to it }.toList()
        }
        val machinePath = buildMachinePath.get()
        for ((root, file) in published) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            when (file.extension) {
                "klib", "jar", "aar" -> if (containsText(file, machinePath)) problems += "$name contains the build machine's path $machinePath"
                "module" -> {
                    @Suppress("UNCHECKED_CAST")
                    val variants = (groovy.json.JsonSlurper().parse(file) as Map<String, Any?>)["variants"] as List<Map<String, Any?>>
                    for (variant in variants) {
                        val variantName = variant["name"]
                        (variant["available-at"] as Map<*, *>?)?.let { at ->
                            if (!file.parentFile.resolve(at["url"] as String).isFile) problems += "$name: $variantName is available at a missing ${at["url"]}"
                        }
                        (variant["files"] as List<*>?).orEmpty().map { (it as Map<*, *>)["url"] as String }
                            .filterNot { file.parentFile.resolve(it).isFile }
                            .forEach { problems += "$name: $variantName names a missing file $it" }
                        for (dependency in (variant["dependencies"] as List<*>?).orEmpty().map { it as Map<*, *> }) {
                            val group = dependency["group"]
                            val module = dependency["module"] as String
                            val depVersion = (dependency["version"] as Map<*, *>?)?.get("requires") as String?
                            if ((group == GROUP || group == "hopi" || depVersion == "unspecified") && !(group == GROUP && published(module, depVersion))) {
                                problems += "$name: $variantName depends on $group:$module:$depVersion, which is not published here"
                            }
                        }
                    }
                }
                "pom" -> {
                    val pom = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    val dependencies = pom.getElementsByTagName("dependency")
                    for (i in 0 until dependencies.length) {
                        val dependency = dependencies.item(i) as org.w3c.dom.Element
                        fun child(tag: String): String? = dependency.getElementsByTagName(tag).item(0)?.textContent?.trim()
                        val group = child("groupId")
                        val depVersion = child("version")
                        if ((group == GROUP || group == "hopi" || depVersion == "unspecified") && !(group == GROUP && published(child("artifactId")!!, depVersion))) {
                            problems += "$name depends on $group:${child("artifactId")}:$depVersion, which is not published here"
                        }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException("The published Hopi artifacts are not consumable:\n" + problems.joinToString("\n"))
    }

    /** Whether an entry of the archive [file] holds [text], in its stored bytes. */
    private fun containsText(file: File, text: String): Boolean = java.util.zip.ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.any { entry ->
            zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1).contains(text) }
        }
    }

    private companion object {
        const val GROUP = "dev.koga.hopi"
        const val GROUP_PATH = "dev/koga/hopi"
    }
}

// Every library a consumer needs (README, "Install"), by its targets and the cinterop klibs of its
// main compilation; each applies gradle/publishing.gradle.kts. hopi-connect, the CLI, the desktop
// viewer with its screens (hopi-ui) and the sample are not published. The dev.koga.hopi plugin
// injects the network runtime (hopi-network), hopi-core and, with `perf { enabled }`, hopi-perf
// into debuggable build types only and aligns hopi-api (HopiRules, HopiPerf) and hopi-ktor (the
// thin Ktor plugin) with its own version, all of them listed here.
// hopi-start, the iOS entry a Debug framework exports and the JVM entry, brings hopi-network and
// hopi-perf; hopi-network's JVM target holds the Ktor plugin's recorder.
val allTargets = listOf("android", "jvm", "iosArm64", "iosSimulatorArm64")
val publishedModules = mapOf(
    "hopi-api" to allTargets,
    "hopi-core" to allTargets,
    "hopi-ktor" to allTargets,
    "hopi-perf" to allTargets,
    "hopi-network" to allTargets,
    "hopi-start" to listOf("jvm", "iosArm64", "iosSimulatorArm64"),
)
val publishedCinterops = mapOf(
    "hopi-core" to listOf("oslog", "hits"),
    "hopi-network" to listOf("urlsession"),
    "hopi-perf" to listOf("perf"),
)

// One source for the version: this build's gradle.properties, which the dev.koga.hopi plugin's build
// reads too (its HOPI_VERSION, and hopi-instrument's version). An override with -P or
// ORG_GRADLE_PROJECT_hopi.version would reach the libraries only, and the plugin would add the
// runtime at another version than the one published: refuse it.
val publishedVersion: String = providers.fileContents(layout.projectDirectory.file("gradle.properties")).asText
    .map { text -> text.lineSequence().first { it.startsWith("hopi.version=") }.substringAfter('=').trim() }.get()
providers.gradleProperty("hopi.version").get().let { requested ->
    if (requested != publishedVersion) {
        throw GradleException(
            "hopi.version is $requested in this build but $publishedVersion in gradle.properties, which the dev.koga.hopi " +
                "plugin reads: set it in gradle.properties, not with -P or ORG_GRADLE_PROJECT_hopi.version",
        )
    }
}
val pluginBuild = gradle.includedBuild("gradle-plugin")

fun expectedPublication(module: String, targets: List<String>): List<String> {
    fun files(artifact: String, vararg suffixes: String) =
        suffixes.map { "dev/koga/hopi/$artifact/$publishedVersion/$artifact-$publishedVersion$it" }
    return files(module, ".module", ".pom", "-sources.jar") + targets.flatMap { target ->
        val artifact = "$module-${target.lowercase()}"
        when (target) {
            "android" -> files(artifact, ".module", ".pom", ".aar", "-sources.jar")
            "jvm" -> files(artifact, ".module", ".pom", ".jar", "-sources.jar")
            else -> files(artifact, ".module", ".pom", ".klib", "-sources.jar", "-metadata.jar") +
                publishedCinterops[module].orEmpty().flatMap { files(artifact, "-cinterop-$it.klib") }
        }
    }
}

val cleanPublicationCheck = tasks.register<Delete>("cleanPublicationCheck") {
    description = "Deletes the repository :checkPublication publishes into."
    delete(layout.buildDirectory.dir("publication-check"))
}

val checkPublication = tasks.register<PublicationCheck>("checkPublication") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Publishes every Hopi artifact into build/publication-check (the plugin into its functional-test repository) and checks a consumer can resolve them."
    dependsOn(cleanPublicationCheck)
    dependsOn(publishedModules.keys.map { ":$it:publishAllPublicationsToPublicationCheckRepository" })
    // The plugin, its marker and hopi-instrument, in the TestKit tests' repository.
    dependsOn(pluginBuild.task(":hopi-gradle-plugin:publishAllPublicationsToFunctionalRepository"))
    dependsOn(pluginBuild.task(":hopi-instrument:publishAllPublicationsToFunctionalRepository"))
    repositories.from(layout.buildDirectory.dir("publication-check"), layout.projectDirectory.dir("gradle-plugin/build/functional-repo"))
    hopiVersion.set(publishedVersion)
    buildMachinePath.set(layout.projectDirectory.asFile.absolutePath)
    expectedFiles.set(
        publishedModules.flatMap { (module, targets) -> expectedPublication(module, targets) } +
            listOf(
                "hopi-gradle-plugin/$publishedVersion/hopi-gradle-plugin-$publishedVersion.jar",
                "hopi-gradle-plugin/$publishedVersion/hopi-gradle-plugin-$publishedVersion.pom",
                "dev.koga.hopi.gradle.plugin/$publishedVersion/dev.koga.hopi.gradle.plugin-$publishedVersion.pom",
                "hopi-instrument/$publishedVersion/hopi-instrument-$publishedVersion.jar",
                "hopi-instrument/$publishedVersion/hopi-instrument-$publishedVersion.pom",
            ).map { "dev/koga/hopi/$it" },
    )
}

// `./gradlew publishToMavenLocal` runs each published module's task of that name; this one adds the
// dev.koga.hopi plugin, its marker and hopi-instrument (which the plugin depends on).
tasks.register("publishToMavenLocal") {
    group = PublishingPlugin.PUBLISH_TASK_GROUP
    description = "Publishes every Hopi artifact and the dev.koga.hopi Gradle plugin to Maven Local."
    dependsOn(publishedModules.keys.map { ":$it:publishToMavenLocal" })
    dependsOn(pluginBuild.task(":hopi-gradle-plugin:publishToMavenLocal"))
    dependsOn(pluginBuild.task(":hopi-instrument:publishToMavenLocal"))
}

tasks.named("check") { dependsOn(checkPublication) }
