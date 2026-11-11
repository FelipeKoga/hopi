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
    // The included build of the io.github.felipekoga.hopi plugin: the rewriter's tests, the plugin's
    // unit tests and its TestKit tests against a fixture Android app.
    dependsOn(gradle.includedBuild("gradle-plugin").task(":hopi-instrument:check"))
    dependsOn(gradle.includedBuild("gradle-plugin").task(":hopi-gradle-plugin:check"))
}

/**
 * Fails unless [repositories] hold every file of [expectedFiles] and every published Gradle module
 * file and POM under `io/github/felipekoga/hopi` points only at files and Hopi artifacts that are
 * there, at [hopiVersion]: what a consumer outside this repository resolves from Maven Local or
 * Maven Central. A `project()` dependency that leaked would name the group `hopi` or the version
 * `unspecified`. No published archive may contain [buildMachinePath].
 *
 * It also holds the published libraries to the oldest build a consumer may have: every kotlin-stdlib
 * dependency at [kotlinStdlibVersion], no io.ktor above [maxKtorVersion] and no kotlinx-coroutines
 * above [maxCoroutinesVersion] (module files and POMs); no `.kotlin_module` file in `META-INF` above
 * [maxKotlinMetadataVersion] in a JVM jar or in an AAR's jars; no AAR asking for a compileSdk above
 * [maxMinCompileSdk]; and every klib, the cinterop ones and the metadata jars' included, at
 * [klibAbiVersion], since Kotlin/Native skips a klib whose ABI version is newer than its own. The
 * io.github.felipekoga.hopi plugin and hopi-instrument, which the app's Gradle loads, hold a
 * `.kotlin_module` no newer than [maxPluginKotlinMetadataVersion].
 *
 * And it holds every directory of this version to Maven Central's requirements
 * (central.sonatype.org/publish/requirements): a POM with the project's name, description and URL,
 * a license, a developer and the SCM, in the group [GROUP]; a `-sources.jar` and a `-javadoc.jar`
 * unless the POM's packaging is `pom` (the plugin marker's); an `.md5` and a `.sha1` of every file;
 * and, in the release build ([release]), an `.asc` signature of every file but the checksums, and no
 * `-SNAPSHOT` version.
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

    /** The version every kotlin-stdlib dependency of a published module requires: the oldest Kotlin a consumer may build with. */
    @get:Input
    abstract val kotlinStdlibVersion: Property<String>

    /** The newest Kotlin metadata (major.minor) a published JVM class may carry, in a `-jvm` jar or an AAR. */
    @get:Input
    abstract val maxKotlinMetadataVersion: Property<String>

    /** The `abi_version` of every published klib. */
    @get:Input
    abstract val klibAbiVersion: Property<String>

    /**
     * The newest Kotlin metadata (major.minor) of the plugin's and hopi-instrument's jars: the oldest
     * Gradle the plugin supports compiles build scripts against the plugin with its own Kotlin.
     */
    @get:Input
    abstract val maxPluginKotlinMetadataVersion: Property<String>

    /** The newest io.ktor a published module may depend on. */
    @get:Input
    abstract val maxKtorVersion: Property<String>

    /** The newest kotlinx-coroutines a published module may depend on. */
    @get:Input
    abstract val maxCoroutinesVersion: Property<String>

    /** The highest `minCompileSdk` a published AAR may ask of an app. */
    @get:Input
    abstract val maxMinCompileSdk: Property<Int>

    /** The release build (`-Phopi.release=true`), whose every file Maven Central requires signed. */
    @get:Input
    abstract val release: Property<Boolean>

    @TaskAction
    fun check() {
        val roots = repositories.files.toList()
        val version = hopiVersion.get()
        fun find(path: String): File? = roots.map { it.resolve(path) }.firstOrNull { it.isFile }
        fun published(module: String, depVersion: String?): Boolean =
            depVersion == version && find("$GROUP_PATH/$module/$version/$module-$version.pom") != null
        val problems = mutableListOf<String>()
        expectedFiles.get().filter { find(it) == null }.forEach { problems += "missing $it" }
        // This version's files only (io/github/felipekoga/hopi/<artifact>/<version>/): a repository can
        // still hold an earlier version's, whose Hopi dependencies are at that version.
        val published = roots.flatMap { root ->
            root.resolve(GROUP_PATH).walkTopDown().filter { it.isFile && it.parentFile.name == version }.map { root to it }.toList()
        }
        val machinePath = buildMachinePath.get()
        for ((root, file) in published) {
            val name = file.relativeTo(root).invariantSeparatorsPath
            when (file.extension) {
                "klib", "jar", "aar" -> {
                    if (containsText(file, machinePath)) problems += "$name contains the build machine's path $machinePath"
                    problems += archiveFloorProblems(name, file)
                }
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
                            problems += dependencyFloorProblems("$name: $variantName", group, module, dependency["version"] as Map<*, *>?)
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
                        problems += dependencyFloorProblems(name, group, child("artifactId")!!, mapOf("requires" to depVersion))
                    }
                }
            }
        }
        problems += centralProblems(published, version)
        if (problems.isNotEmpty()) throw GradleException("The published Hopi artifacts are not consumable or not fit for Maven Central:\n" + problems.joinToString("\n"))
    }

    /**
     * What Maven Central refuses in each directory of [published] (`<artifact>/<version>/`): a missing
     * or incomplete POM, a missing `-sources.jar` or `-javadoc.jar` (unless the POM's packaging is
     * `pom`), a file without its `.md5` or `.sha1`, and in the release build a file without its `.asc`
     * (checksums need none) or a snapshot [version].
     */
    private fun centralProblems(published: List<Pair<File, File>>, version: String): List<String> {
        val problems = mutableListOf<String>()
        if (release.get() && version.endsWith("-SNAPSHOT")) problems += "hopi.version $version is a snapshot, which Maven Central refuses"
        for ((directory, files) in published.groupBy({ it.second.parentFile }, { it })) {
            val where = directory.relativeTo(files.first().first).invariantSeparatorsPath
            val artifact = directory.parentFile.name
            val names = files.map { it.second.name }.toSet()
            val pom = directory.resolve("$artifact-$version.pom")
            if (!pom.isFile) {
                problems += "$where has no POM"
                continue
            }
            val model = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
            problems += POM_FIELDS.filter { text(model, it).isNullOrBlank() }.map { "$where/${pom.name} has no <${it.replace("/", "><")}>" }
            val groupId = text(model, "groupId")
            if (groupId != GROUP) problems += "$where/${pom.name} has the groupId $groupId, not $GROUP"
            if ((text(model, "packaging") ?: "jar") != "pom") {
                listOf("sources", "javadoc").map { "$artifact-$version-$it.jar" }.filter { it !in names }.forEach { problems += "$where has no $it" }
            }
            for (file in names.filterNot { file -> file.endsWith(".asc") || CHECKSUMS.any { file.endsWith(".$it") } }) {
                listOf("md5", "sha1").filter { "$file.$it" !in names }.forEach { problems += "$where/$file has no .$it checksum" }
                if (release.get() && "$file.asc" !in names) problems += "$where/$file is not signed: no $file.asc"
            }
        }
        return problems
    }

    /** The text of the element at [path] (child names separated by `/`) under [element], or null. */
    private fun text(element: org.w3c.dom.Element, path: String): String? {
        var current: org.w3c.dom.Element = element
        for (tag in path.split('/')) {
            val children = current.childNodes
            current = (0 until children.length).map { children.item(it) }.filterIsInstance<org.w3c.dom.Element>()
                .firstOrNull { it.tagName == tag } ?: return null
        }
        return current.textContent.trim()
    }

    /** Whether an entry of the archive [file] holds [text], in its stored bytes. */
    private fun containsText(file: File, text: String): Boolean = java.util.zip.ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.any { entry ->
            zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1).contains(text) }
        }
    }

    /**
     * What a dependency on [group]:[module] at [versions] (a module file's `version` map, or a POM's
     * version as `requires`) breaks of the consumer floors.
     */
    private fun dependencyFloorProblems(where: String, group: Any?, module: String, versions: Map<*, *>?): List<String> {
        val declared = versions.orEmpty().filterKeys { it in VERSION_KEYS }.values.map { it.toString() }
        fun above(max: String) = declared.filter { compareVersions(it, max) > 0 }
        return when {
            group == "org.jetbrains.kotlin" && module == "kotlin-stdlib" ->
                if (declared.isNotEmpty() && declared.all { it == kotlinStdlibVersion.get() }) emptyList()
                else listOf("$where requires kotlin-stdlib $declared, not ${kotlinStdlibVersion.get()}: an app on an older Kotlin would be moved to it")
            group == "io.ktor" -> above(maxKtorVersion.get()).map { "$where depends on io.ktor:$module:$it, above ${maxKtorVersion.get()}" }
            group == "org.jetbrains.kotlinx" && module.startsWith("kotlinx-coroutines") ->
                above(maxCoroutinesVersion.get()).map { "$where depends on $group:$module:$it, above ${maxCoroutinesVersion.get()}" }
            else -> emptyList()
        }
    }

    /**
     * What the archive [file] breaks of the consumer floors: each klib manifest's `abi_version` (a
     * klib's own, and every metadata klib's in a jar), an AAR's `minCompileSdk`, and the Kotlin
     * metadata of each `.kotlin_module` file in `META-INF` of a `-jvm` jar or of an AAR's jars, and
     * of the plugin's and hopi-instrument's jars, which must hold one.
     */
    private fun archiveFloorProblems(name: String, file: File): List<String> = java.util.zip.ZipFile(file).use { zip ->
        val problems = mutableListOf<String>()
        val jvmJar = file.extension == "jar" && file.parentFile.parentFile.name.endsWith("-jvm")
        val pluginJar = file.extension == "jar" && file.parentFile.parentFile.name in PLUGIN_ARTIFACTS &&
            !file.name.endsWith("-sources.jar") && !file.name.endsWith("-javadoc.jar")
        if (pluginJar && zip.entries().asSequence().none { isKotlinModule(it.name) }) problems += "$name holds no .kotlin_module to check"
        for (entry in zip.entries().asSequence().filterNot { it.isDirectory }) {
            val path = entry.name
            fun bytes() = zip.getInputStream(entry).use { it.readBytes() }
            when {
                path == "default/manifest" || path.endsWith("/default/manifest") -> {
                    val abi = properties(bytes()).getProperty("abi_version")
                    if (abi != klibAbiVersion.get()) problems += "$name: $path has abi_version $abi, not ${klibAbiVersion.get()}"
                }
                file.extension == "aar" && path == AAR_METADATA -> {
                    val sdk = properties(bytes()).getProperty("minCompileSdk")?.trim()?.toIntOrNull()
                    if (sdk != null && sdk > maxMinCompileSdk.get()) problems += "$name asks for minCompileSdk=$sdk, above ${maxMinCompileSdk.get()}"
                }
                file.extension == "aar" && path.endsWith(".jar") -> java.util.zip.ZipInputStream(zip.getInputStream(entry)).use { jar ->
                    generateSequence { jar.nextEntry }.filter { isKotlinModule(it.name) }.forEach {
                        problems += metadataFloorProblems("$name!$path", it.name, jar.readBytes())
                    }
                }
                jvmJar && isKotlinModule(path) -> problems += metadataFloorProblems(name, path, bytes())
                pluginJar && isKotlinModule(path) -> problems += metadataFloorProblems(name, path, bytes(), maxPluginKotlinMetadataVersion.get())
            }
        }
        problems
    }

    /** A `.kotlin_module` file starts with its metadata version: an int count, then that many ints. */
    private fun metadataFloorProblems(where: String, path: String, bytes: ByteArray, max: String = maxKotlinMetadataVersion.get()): List<String> {
        val input = java.io.DataInputStream(bytes.inputStream())
        val version = IntArray(input.readInt()) { input.readInt() }.joinToString(".")
        return if (compareVersions(version.split('.').take(2).joinToString("."), max) > 0) {
            listOf("$where: $path has Kotlin metadata $version, above $max")
        } else {
            emptyList()
        }
    }

    private fun isKotlinModule(path: String) = path.startsWith("META-INF/") && path.endsWith(".kotlin_module")

    private fun properties(bytes: ByteArray) = java.util.Properties().apply { load(bytes.inputStream()) }

    /** Compares dotted versions by their numeric segments (`3.10.0` is above `3.9.1`); a qualifier counts as 0. */
    private fun compareVersions(a: String, b: String): Int {
        fun segments(v: String) = v.split('.', '-').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = segments(a)
        val y = segments(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private companion object {
        const val GROUP = "io.github.felipekoga.hopi"
        const val GROUP_PATH = "io/github/felipekoga/hopi"
        const val AAR_METADATA = "META-INF/com/android/build/gradle/aar-metadata.properties"
        val VERSION_KEYS = setOf("requires", "strictly", "prefers")

        /** What Maven Central requires of every POM (gradle/maven-central.gradle.kts writes them). */
        val POM_FIELDS = listOf(
            "name", "description", "url", "licenses/license/name", "licenses/license/url",
            "developers/developer/id", "developers/developer/name", "developers/developer/url",
            "scm/url", "scm/connection", "scm/developerConnection",
        )

        /** The checksum files Gradle writes beside each file it publishes, which need no signature. */
        val CHECKSUMS = setOf("md5", "sha1", "sha256", "sha512")

        /** The artifacts the app's Gradle loads: the io.github.felipekoga.hopi plugin and its rewriter. */
        val PLUGIN_ARTIFACTS = setOf("hopi-gradle-plugin", "hopi-instrument")
    }
}

// Every library a consumer needs (docs/GUIDE.md, "Install"), by its targets and the cinterop klibs
// of its main compilation; each applies gradle/publishing.gradle.kts. hopi-connect, the CLI and the
// sample are not published (the macOS viewer, hopi-mac, is an Xcode project). The
// io.github.felipekoga.hopi plugin injects the network runtime (hopi-network), hopi-core and, with
// `perf { enabled }`, hopi-perf into debuggable build types only and aligns hopi-api (HopiRules,
// HopiPerf) and hopi-ktor (the thin Ktor plugin) with its own version, all of them listed here.
// hopi-start, the iOS entry a Debug framework exports and the JVM entry, brings hopi-network and
// hopi-perf; hopi-network's JVM target holds the Ktor plugin's recorder.
val allTargets = listOf("android", "jvm", "iosArm64", "iosSimulatorArm64")
// Kotlin/Native builds the iOS targets on a Mac only (their Objective-C cinterop needs Xcode): on
// another host, Linux say, :checkPublication expects the JVM and Android files alone, and a
// release build (-Phopi.release=true) refuses to start, since its bundle would have no iOS artifacts.
val appleHost = System.getProperty("os.name").startsWith("Mac")
fun buildableTargets(targets: List<String>) = if (appleHost) targets else targets.filter { it == "android" || it == "jvm" }
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

// The oldest Kotlin a consumer may build with. The published libraries are compiled by the catalog's
// Kotlin (2.2.21) at its default language version, 2.2, and declare this standard library, not the
// compiler's own, so an app on 2.2.0 is not moved to a newer one; :checkPublication holds them there.
val kotlinFloor = "2.2.0"
publishedModules.keys.forEach { module ->
    project(":$module") {
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
                coreLibrariesVersion = kotlinFloor
            }
        }
    }
}

// One source for the version: this build's gradle.properties, which the io.github.felipekoga.hopi
// plugin's build reads too (its HOPI_VERSION, and hopi-instrument's version). An override with -P or
// ORG_GRADLE_PROJECT_hopi.version would reach the libraries only, and the plugin would add the
// runtime at another version than the one published: refuse it.
val publishedVersion: String = providers.fileContents(layout.projectDirectory.file("gradle.properties")).asText
    .map { text -> text.lineSequence().first { it.startsWith("hopi.version=") }.substringAfter('=').trim() }.get()
providers.gradleProperty("hopi.version").get().let { requested ->
    if (requested != publishedVersion) {
        throw GradleException(
            "hopi.version is $requested in this build but $publishedVersion in gradle.properties, which the io.github.felipekoga.hopi " +
                "plugin reads: set it in gradle.properties, not with -P or ORG_GRADLE_PROJECT_hopi.version",
        )
    }
}
val pluginBuild = gradle.includedBuild("gradle-plugin")

fun expectedPublication(module: String, targets: List<String>): List<String> {
    fun files(artifact: String, vararg suffixes: String) =
        (suffixes.toList() + "-javadoc.jar").map { "io/github/felipekoga/hopi/$artifact/$publishedVersion/$artifact-$publishedVersion$it" }
    return files(module, ".module", ".pom", "-sources.jar") + targets.flatMap { target ->
        val artifact = "$module-${target.lowercase()}"
        when (target) {
            "android" -> files(artifact, ".module", ".pom", ".aar", "-sources.jar")
            "jvm" -> files(artifact, ".module", ".pom", ".jar", "-sources.jar")
            else -> files(artifact, ".module", ".pom", ".klib", "-sources.jar", "-metadata.jar") +
                publishedCinterops[module].orEmpty().map { "io/github/felipekoga/hopi/$artifact/$publishedVersion/$artifact-$publishedVersion-cinterop-$it.klib" }
        }
    }
}

val cleanPublicationCheck = tasks.register<Delete>("cleanPublicationCheck") {
    description = "Deletes the repository :checkPublication publishes into."
    delete(layout.buildDirectory.dir("publication-check"))
}

val checkPublication = tasks.register<PublicationCheck>("checkPublication") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Publishes every Hopi artifact into build/publication-check (the plugin into its functional-test repository) " +
        "and checks a consumer can resolve them and Maven Central would take them (with -Phopi.release=true, signed)."
    dependsOn(cleanPublicationCheck)
    dependsOn(publishedModules.keys.map { ":$it:publishAllPublicationsToPublicationCheckRepository" })
    // The plugin, its marker and hopi-instrument, in the TestKit tests' repository.
    dependsOn(pluginBuild.task(":hopi-gradle-plugin:publishAllPublicationsToFunctionalRepository"))
    dependsOn(pluginBuild.task(":hopi-instrument:publishAllPublicationsToFunctionalRepository"))
    repositories.from(layout.buildDirectory.dir("publication-check"), layout.projectDirectory.dir("gradle-plugin/build/functional-repo"))
    hopiVersion.set(publishedVersion)
    buildMachinePath.set(layout.projectDirectory.asFile.absolutePath)
    // The consumer floors, set here rather than read from the catalog, so that raising a version
    // there fails this check instead of moving a floor: Kotlin 2.2 (the standard library, the JVM
    // metadata and Kotlin/Native's klib ABI), Ktor 3.3.3 (3.4 is built with Kotlin 2.3),
    // kotlinx-coroutines 1.11.0 (built with 2.2.20) and compileSdk 34 (AGP 8.1.1).
    kotlinStdlibVersion.set(kotlinFloor)
    maxKotlinMetadataVersion.set("2.2")
    // Gradle 8.0, the plugin's floor, compiles build scripts against it with Kotlin 1.8.10.
    maxPluginKotlinMetadataVersion.set("1.8")
    klibAbiVersion.set("2.2.0")
    maxKtorVersion.set("3.3.3")
    maxCoroutinesVersion.set("1.11.0")
    maxMinCompileSdk.set(34)
    // The release build (docs/RELEASING.md) signs every file, which this check then requires;
    // gradle/maven-central.gradle.kts fails it at once without a signing key.
    release.set(providers.gradleProperty("hopi.release").map { it.toBoolean() }.orElse(false))
    if (!appleHost && providers.gradleProperty("hopi.release").orNull.toBoolean()) {
        throw GradleException("A release build runs on a Mac only: on ${System.getProperty("os.name")} it would publish no iOS artifacts")
    }
    expectedFiles.set(
        publishedModules.flatMap { (module, targets) -> expectedPublication(module, buildableTargets(targets)) } +
            listOf("hopi-gradle-plugin", "hopi-instrument").flatMap { artifact ->
                listOf(".jar", ".pom", "-sources.jar", "-javadoc.jar").map { "$artifact/$publishedVersion/$artifact-$publishedVersion$it" }
            }.map { "io/github/felipekoga/hopi/$it" } +
            "io/github/felipekoga/hopi/io.github.felipekoga.hopi.gradle.plugin/$publishedVersion/io.github.felipekoga.hopi.gradle.plugin-$publishedVersion.pom",
    )
}

// `./gradlew publishToMavenLocal` runs each published module's task of that name; this one adds the
// io.github.felipekoga.hopi plugin, its marker and hopi-instrument (which the plugin depends on).
tasks.register("publishToMavenLocal") {
    group = PublishingPlugin.PUBLISH_TASK_GROUP
    description = "Publishes every Hopi artifact and the io.github.felipekoga.hopi Gradle plugin to Maven Local."
    dependsOn(publishedModules.keys.map { ":$it:publishToMavenLocal" })
    dependsOn(pluginBuild.task(":hopi-gradle-plugin:publishToMavenLocal"))
    dependsOn(pluginBuild.task(":hopi-instrument:publishToMavenLocal"))
}

tasks.named("check") { dependsOn(checkPublication) }
