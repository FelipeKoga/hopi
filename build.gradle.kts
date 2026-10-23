plugins {
    // `check` for the root project, which the noop parity check hangs off.
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

/**
 * Fails when the public API dump of [realModule] (its `api/` directory, written by
 * `updateKotlinAbi`) differs from that of its noop twin [noopModule]. The only difference allowed
 * is the module's own name: in dump file names and in the klib dump's `// Library unique name:`
 * line. Everything else (every declaration, every target, every file) must be identical.
 */
abstract class NoopParityCheck : DefaultTask() {
    @get:Input
    abstract val realModule: Property<String>

    @get:Input
    abstract val noopModule: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val realDumps: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noopDumps: DirectoryProperty

    @TaskAction
    fun check() {
        val real = realModule.get()
        val noop = noopModule.get()
        val realFiles = dumpsByName(realDumps.get().asFile, real)
        val noopFiles = dumpsByName(noopDumps.get().asFile, noop)
        val problems = mutableListOf<String>()
        if (realFiles.isEmpty()) problems += "$real has no API dump"
        for (name in (realFiles.keys + noopFiles.keys).sorted()) {
            val realFile = realFiles[name]
            val noopFile = noopFiles[name]
            when {
                realFile == null -> problems += "$noop has the dump $name and $real does not"
                noopFile == null -> problems += "$real has the dump $name and $noop does not"
                else -> {
                    val realLines = normalized(realFile)
                    val noopLines = normalized(noopFile)
                    if (realLines != noopLines) {
                        val onlyReal = realLines - noopLines.toSet()
                        val onlyNoop = noopLines - realLines.toSet()
                        problems += buildString {
                            append("$name differs")
                            onlyReal.forEach { append("\n    only in $real: ${it.trim()}") }
                            onlyNoop.forEach { append("\n    only in $noop: ${it.trim()}") }
                            if (onlyReal.isEmpty() && onlyNoop.isEmpty()) append("\n    (the same lines in another order or count)")
                        }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The public API of $noop is not the same as $real's:\n" + problems.joinToString("\n") +
                    "\nChange both modules together, then run ./gradlew :$real:updateKotlinAbi :$noop:updateKotlinAbi.",
            )
        }
    }

    /** Dump files by their path under `api/`, the module's name in the file name replaced by `<module>`. */
    private fun dumpsByName(directory: File, module: String): Map<String, File> {
        val ownName = Regex("^" + Regex.escape(module) + "(?=\\.)")
        return directory.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".api") }
            .associateBy { file ->
                val parent = file.parentFile.relativeTo(directory).invariantSeparatorsPath
                val name = file.name.replaceFirst(ownName, "<module>")
                if (parent.isEmpty()) name else "$parent/$name"
            }
    }

    private fun normalized(file: File): List<String> = file.readLines().filterNot { it.startsWith(LIBRARY_NAME_LINE) }

    private companion object {
        const val LIBRARY_NAME_LINE = "// Library unique name:"
    }
}

val noopTwins = listOf("hopi-ktor" to "hopi-ktor-noop", "hopi-ui" to "hopi-ui-noop")

val noopParityChecks = noopTwins.map { (real, noop) ->
    val taskName = "check" + noop.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Parity"
    tasks.register<NoopParityCheck>(taskName) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Checks that $noop has exactly the public API of $real."
        realModule.set(real)
        noopModule.set(noop)
        realDumps.set(layout.projectDirectory.dir("$real/api"))
        noopDumps.set(layout.projectDirectory.dir("$noop/api"))
        // Each reference dump must match its compiled code before the two are compared.
        dependsOn(":$real:checkKotlinAbi", ":$noop:checkKotlinAbi")
    }
}

tasks.register("checkNoopParity") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that every noop twin has exactly the public API of its real module."
    dependsOn(noopParityChecks)
}

tasks.named("check") {
    dependsOn("checkNoopParity")
}
