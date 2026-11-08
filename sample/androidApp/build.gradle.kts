import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import dev.koga.hopi.instrument.DexReader
import dev.koga.hopi.instrument.DexReader.MethodRef
import dev.koga.hopi.instrument.HookDescriptors
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // The one line an Android app needs for automatic capture (docs/design/android-capture.md).
    id("io.github.felipekoga.hopi")
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
        // `-Psample.largeHeap=true` gives the app ART's large heap (512 MB instead of 192 MB on the
        // API 35 emulator), so the giant-body measurements can see past the app's own limit.
        manifestPlaceholders["largeHeap"] = providers.gradleProperty("sample.largeHeap").orNull ?: "false"
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

// `-Psample.hopiCapture=false` builds the debug variant as release is built (no runtime declared,
// so the thin Ktor plugin installs nothing, no instrumentation), the control arm of the capture A/B
// (the benchmark scripts' `nohopi` arm).
val hopiCapture: Boolean = providers.gradleProperty("sample.hopiCapture").map { it.toBoolean() }.orElse(true).get()

hopi {
    enabled.set(hopiCapture)
    // The CLI and the macOS viewer reach the app through its bridge.
    agentBridge.set(true)
    // Capture's caps, read at build time and preset for the store at start: the giant-body
    // measurements and DeviceViewerUiTest's 5 MB body install the debug build with
    // `-Psample.maxBodyBytes=8388608` (a body cap is clamped to half the store's,
    // `-Psample.maxTotalBytes`). The defaults are the plugin's, 256 KiB and 16 MiB.
    maxBodyBytes.set(providers.gradleProperty("sample.maxBodyBytes").map { it.toLong() }.orElse(256L * 1024))
    maxTotalBytes.set(providers.gradleProperty("sample.maxTotalBytes").map { it.toLong() }.orElse(16L * 1024 * 1024))
}

dependencies {
    // Hopi's runtimes, debug only, as an app declares them: the network capture (whose tool also
    // registers the Ktor plugin's recorder) and the performance monitors (registered for `hopi perf
    // start`, the Perf panel and the perf lab's HopiPerf calls, none started until asked). Release
    // gets no runtime (its release guard fails a build that resolves one), only hopi-api and the
    // thin hopi-ktor, which the shared module names in every build.
    if (hopiCapture) {
        debugImplementation(project(":hopi-network"))
        debugImplementation(project(":hopi-perf"))
    }
    implementation(project(":sample:shared"))
    // The third-party stand-in: its own OkHttp and HttpURLConnection, no Hopi.
    implementation(project(":sample:fakesdk"))
    implementation(libs.androidx.activity.compose)
    // The shared module's Compose (compose-multiplatform-sample).
    implementation(libs.compose.sample.runtime)
}

/**
 * Proves that a release APK holds no Hopi runtime and no instrumentation. It reads the APK's dex
 * files (classes, referenced types and methods, and each class's invoke instructions, with the
 * plugin's own `DexReader`, no SDK tool) and the merged manifest:
 *
 * - the release runtime classpath's Hopi components are exactly `hopi-api` (`HopiRules` and
 *   `HopiPerf`) and the thin Ktor plugin (`hopi-ktor`, which installs nothing without a runtime):
 *   no runtime, as a project or as `io.github.felipekoga.hopi` coordinates; and the APK's Hopi classes are
 *   exactly the classes their jars hold (unminified);
 * - no type of `dev.koga.hopi.core`, `.android`, `.perf.internal`, `.ui`, `.network`, `.urlsession`
 *   or `.start` is defined or even referenced (an instrumented call site would reference a hook
 *   type), with no exemption, and no class calls a hook;
 * - the negative control: OkHttp's `RealCall` still calls `OkHttpClient.interceptors()` and the fake
 *   SDK still calls `URL.openConnection()`, so the check is known to look at the right code;
 * - the merged manifest declares no provider, activity, service or receiver of `dev.koga.hopi.`
 *   other than the app's own (`dev.koga.hopi.sample.`);
 * - no Hopi class calls the APIs the performance monitors are built on (`Window.addOnFrameMetricsAvailableListener`,
 *   `Looper.setMessageLogging`).
 *
 * The plugin's release guard already refuses a runtime on that classpath before the build; this
 * check reads what was built, so it also sees what no classpath shows.
 */
abstract class VerifyReleaseHasNoRuntime : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** The Hopi components on the release runtime classpath ([HopiGraph.componentsIn]). */
    @get:Input
    abstract val hopiComponents: SetProperty<String>

    /** The classes jars of those components. */
    @get:Classpath
    abstract val hopiJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val builtArtifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No release APK in ${apkDirectory.get().asFile}")
        val apk = File(builtArtifacts.elements.single().outputFile)
        val problems = mutableListOf<String>()

        val components = hopiComponents.get()
        if (components != EXPECTED_COMPONENTS) {
            problems += "the release runtime classpath holds ${components.sorted()}, expected ${EXPECTED_COMPONENTS.sorted()}"
        }

        val dex = DexReader.ofApk(apk)
        val libraryClasses = hopiJars.files.flatMapTo(sortedSetOf()) { classesInJar(it) }
        val apkClasses = dex.definedClasses.map { it.replace('/', '.') }.filterTo(sortedSetOf()) {
            it.startsWith("dev.koga.hopi.") && !it.startsWith("dev.koga.hopi.sample.")
        }
        // D8 may add synthetic classes next to a library's own (`…$$ExternalSyntheticLambda0`).
        val apkOwnClasses = apkClasses.mapTo(sortedSetOf()) { it.substringBefore("\$\$ExternalSynthetic") }
        if (libraryClasses.isEmpty()) problems += "the Hopi components' jars hold no class"
        val unexpected = apkOwnClasses - libraryClasses
        if (unexpected.isNotEmpty()) problems += "classes of no selected Hopi component: ${unexpected.take(20)}"
        val missing = libraryClasses - apkOwnClasses
        if (missing.isNotEmpty()) problems += "classes of the selected components missing from the APK: ${missing.take(20)}"

        val forbidden = dex.referencedTypes.filter { type -> FORBIDDEN_PACKAGES.any { type.startsWith(it) } }
        if (forbidden.isNotEmpty()) problems += "${forbidden.size} referenced runtime or capture types, e.g. ${forbidden.take(5)}"
        val hookCalls = dex.invokesByClass.filterValues { calls -> calls.any { it.owner == HookDescriptors.OKHTTP_HOOKS || it.owner == HookDescriptors.URL_HOOKS } }.keys
        if (hookCalls.isNotEmpty()) problems += "classes that call a Hopi hook: ${hookCalls.take(10)}"
        val realCallCalls = dex.callersOf(INTERCEPTORS)
        if (HookDescriptors.REAL_CALL_CLASSES.none { it in realCallCalls }) problems += "RealCall does not call OkHttpClient.interceptors() (the negative control found nothing)"
        val sdkCalls = dex.callersOf(OPEN_CONNECTION).filter { it.startsWith("dev/koga/fakesdk/") }
        if (sdkCalls.isEmpty()) problems += "the fake SDK does not call URL.openConnection() (the negative control found nothing)"
        val manifestComponents = hopiManifestComponents(mergedManifest.get().asFile)
        if (manifestComponents.isNotEmpty()) problems += "the merged manifest declares Hopi components: $manifestComponents"
        val perfCalls = dex.invokesByClass.filter { (owner, calls) -> owner.startsWith("dev/koga/hopi/") && calls.any { it in PerfApis.ALL } }.keys
        if (perfCalls.isNotEmpty()) problems += "Hopi classes that call the perf monitors' APIs: ${perfCalls.take(10)}"

        val summary = buildString {
            appendLine("apk: ${apk.name}")
            appendLine("expected: hopi-api and the thin Ktor plugin, no runtime, no instrumentation")
            appendLine("hopi components on the release classpath: ${components.sorted().joinToString()}")
            appendLine("hopi classes in the apk: ${apkClasses.size}; referenced runtime/capture types: ${forbidden.size}; classes calling a hook: ${hookCalls.size}; Hopi manifest components: ${manifestComponents.size}")
            appendLine("negative control: RealCall calls OkHttpClient.interceptors(): ${realCallCalls.filter { it in HookDescriptors.REAL_CALL_CLASSES }}; fake SDK classes calling URL.openConnection(): $sdkCalls")
            apkClasses.forEach { appendLine("  $it") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("The release APK holds Hopi runtime code or instrumentation:\n- " + problems.joinToString("\n- ") + "\n" + summary)
        }
        report.get().asFile.writeText(summary)
        logger.lifecycle(summary.lineSequence().take(5).joinToString("\n"))
    }

    private fun classesInJar(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .toList()
    }

    /** The `android:name` of every provider, activity, service and receiver of `dev.koga.hopi.` but the app's own. */
    private fun hopiManifestComponents(manifest: File): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        return MANIFEST_COMPONENTS.flatMap { tag ->
            val elements = document.getElementsByTagName(tag)
            (0 until elements.length).map { (elements.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        }.filter { it.startsWith("dev.koga.hopi.") && !it.startsWith("dev.koga.hopi.sample.") }
    }

    private companion object {
        /** `HopiRules` and `HopiPerf`, and the thin Ktor plugin, which brings no runtime. */
        val EXPECTED_COMPONENTS = setOf(":hopi-api", ":hopi-ktor")
        val FORBIDDEN_PACKAGES = listOf(
            "dev/koga/hopi/core/", "dev/koga/hopi/android/", "dev/koga/hopi/perf/internal/", "dev/koga/hopi/ui/",
            "dev/koga/hopi/network/", "dev/koga/hopi/urlsession/", "dev/koga/hopi/start/",
        )
        val MANIFEST_COMPONENTS = listOf("provider", "activity", "service", "receiver")
        val INTERCEPTORS = MethodRef(HookDescriptors.OKHTTP_CLIENT, HookDescriptors.INTERCEPTORS, HookDescriptors.INTERCEPTORS_DESCRIPTOR)
        val OPEN_CONNECTION = MethodRef(HookDescriptors.URL, "openConnection", "()Ljava/net/URLConnection;")
    }
}

/** What the performance monitors call: absent from every Hopi class of a release APK, present in debug. */
object PerfApis {
    val ALL = setOf(
        MethodRef("android/view/Window", "addOnFrameMetricsAvailableListener", "(Landroid/view/Window\$OnFrameMetricsAvailableListener;Landroid/os/Handler;)V"),
        MethodRef("android/os/Looper", "setMessageLogging", "(Landroid/util/Printer;)V"),
    )
}

/**
 * The positive control on the debug APK: the instrumentation is there. OkHttp's `RealCall` calls
 * Hopi's interceptors hook and no longer `OkHttpClient.interceptors()`; no class outside Hopi's own
 * runtime still opens a `URL` connection directly; the fake SDK's classes and Kotlin's
 * `kotlin.io.TextStreamsKt` (the sample's `URL.readText()`, inlined into a call of its
 * `readBytes()`) call the URL hooks; the merged manifest
 * declares the provider; the rule engine is packaged (the release check's absent core, seen from
 * the other side); the Ktor plugin's recorder is packaged (`KtorHooks`, the forwarder the network
 * tool registers); and the debug runtime classpath holds the runtimes.
 */
abstract class VerifyDebugCapture : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** The Hopi components on the debug runtime classpath ([HopiGraph.componentsIn]). */
    @get:Input
    abstract val hopiComponents: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val apk = File(builtArtifactsLoader.get().load(apkDirectory.get())?.elements?.single()?.outputFile ?: throw GradleException("No debug APK"))
        val dex = DexReader.ofApk(apk)
        val problems = mutableListOf<String>()
        val projects = hopiComponents.get()
        val required = setOf(":hopi-api", ":hopi-core", ":hopi-ktor", ":hopi-network", ":hopi-perf")
        if (!projects.containsAll(required)) problems += "the debug runtime classpath holds ${projects.sorted()}, expected $required"

        val hookCallers = dex.callersOf(OKHTTP_HOOK)
        val realCalls = HookDescriptors.REAL_CALL_CLASSES.filter { it in dex.definedClasses }
        if (realCalls.isEmpty()) problems += "no RealCall in the APK"
        realCalls.filter { it !in hookCallers }.forEach { problems += "$it does not call ${OKHTTP_HOOK}" }
        val stillDirect = dex.callersOf(INTERCEPTORS).filter { it in HookDescriptors.REAL_CALL_CLASSES }
        if (stillDirect.isNotEmpty()) problems += "$stillDirect still call OkHttpClient.interceptors()"

        val urlMethods = HookDescriptors.URL_HOOK_METHODS.map { MethodRef(HookDescriptors.URL, it.name, it.descriptor) }.toSet()
        val direct = dex.invokesByClass.filter { (owner, calls) -> !owner.startsWith(HookDescriptors.RUNTIME_PACKAGE) && calls.any { it in urlMethods } }.keys
        if (direct.isNotEmpty()) problems += "classes that still open URL connections directly: ${direct.take(10)}"
        val urlHookCallers = dex.invokesByClass.filterValues { calls -> calls.any { it.owner == HookDescriptors.URL_HOOKS } }.keys
        for (prefix in listOf("dev/koga/fakesdk/", "kotlin/io/TextStreamsKt")) {
            if (urlHookCallers.none { it.startsWith(prefix) }) problems += "no class $prefix* calls a URL hook"
        }
        val manifest = mergedManifest.get().asFile.readText()
        if (PROVIDER !in manifest) problems += "the merged manifest does not declare $PROVIDER"
        val ruleEngine = RULE_ENGINE in dex.definedClasses
        if (!ruleEngine) problems += "the debug APK holds no $RULE_ENGINE"
        val ktorRecorder = KTOR_HOOKS in dex.definedClasses
        if (!ktorRecorder) problems += "the debug APK holds no $KTOR_HOOKS"
        // The perf monitors: the tool the generated configuration lists, and the calls they are built on.
        val perfEntry = PERF_ENTRY in dex.definedClasses
        if (!perfEntry) problems += "the debug APK holds no $PERF_ENTRY"
        val perfCalls = PerfApis.ALL.filter { api -> dex.invokesByClass.any { (owner, calls) -> owner.startsWith("dev/koga/hopi/perf/") && api in calls } }
        if (perfCalls.size != PerfApis.ALL.size) problems += "hopi-perf calls ${perfCalls.map { it.name }}, expected ${PerfApis.ALL.map { it.name }}"

        val summary = buildString {
            appendLine("apk: ${apk.name}")
            appendLine("hopi projects on the debug classpath: ${projects.sorted().joinToString()}")
            appendLine("RealCall calling the interceptors hook: ${realCalls.filter { it in hookCallers }}")
            appendLine("classes calling a URL hook: ${urlHookCallers.size}, e.g. ${urlHookCallers.filter { !it.startsWith("dev/koga/hopi/android/") }.take(8)}")
            appendLine("classes still opening URL connections directly: ${direct.size}; provider declared: ${PROVIDER in manifest}; rule engine packaged: $ruleEngine")
            appendLine("perf entry packaged: $perfEntry; perf monitor calls: ${perfCalls.map { it.name }}")
            appendLine("ktor recorder packaged: $ktorRecorder")
        }
        if (problems.isNotEmpty()) throw GradleException("The debug APK is not instrumented as expected:\n- " + problems.joinToString("\n- ") + "\n" + summary)
        report.get().asFile.writeText(summary)
        logger.lifecycle(summary.trimEnd())
    }

    private companion object {
        val OKHTTP_HOOK = MethodRef(HookDescriptors.OKHTTP_HOOKS, HookDescriptors.INTERCEPTORS, HookDescriptors.OKHTTP_HOOK_DESCRIPTOR)
        val INTERCEPTORS = MethodRef(HookDescriptors.OKHTTP_CLIENT, HookDescriptors.INTERCEPTORS, HookDescriptors.INTERCEPTORS_DESCRIPTOR)
        const val PROVIDER = "dev.koga.hopi.android.internal.HopiInitProvider"
        const val RULE_ENGINE = "dev/koga/hopi/core/rules/RuleEngine"
        const val PERF_ENTRY = "dev/koga/hopi/perf/internal/PerfEntry"
        const val KTOR_HOOKS = "dev/koga/hopi/network/internal/ktor/KtorHooks"
    }
}

// An object, not a script function: the configuration cache stores the provider mapping that
// uses it, and a script function would capture the script.
object HopiGraph {
    /**
     * The Hopi components in a resolved dependency graph: this repository's projects by path
     * (`:hopi-api`), published ones as `io.github.felipekoga.hopi:<module>`.
     */
    fun componentsIn(root: ResolvedComponentResult): Set<String> {
        val found = sortedSetOf<String>()
        val visited = mutableSetOf(root.id)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            name(component.id)?.let(found::add)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }.filter { visited.add(it.id) }.forEach(queue::add)
        }
        return found
    }

    /** Whether a component's jar belongs to the release check's class comparison. */
    fun isHopi(id: ComponentIdentifier): Boolean = name(id) != null

    private fun name(id: ComponentIdentifier): String? = when (id) {
        is ProjectComponentIdentifier -> id.projectPath.takeIf { it.startsWith(":hopi-") }
        is ModuleComponentIdentifier -> "${id.group}:${id.module}".takeIf { id.group == "io.github.felipekoga.hopi" }
        else -> null
    }
}

// What every consumer relies on: the release APK holds hopi-api and the thin Ktor plugin, no
// runtime and no instrumentation, whatever -Phopi.enabled says (the property no longer reaches an
// Android build: the io.github.felipekoga.hopi plugin gives only debuggable variants the runtimes).
val verifyReleaseHasNoRuntime = tasks.register<VerifyReleaseHasNoRuntime>("verifyReleaseHasNoRuntime") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the release APK holds hopi-api and the thin Ktor plugin only: no Hopi runtime, no capture code, no instrumentation."
    report.set(layout.buildDirectory.file("reports/hopi/release-has-no-runtime.txt"))
}

val verifyDebugCapture = tasks.register<VerifyDebugCapture>("verifyDebugCapture") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the debug APK is instrumented: RealCall and every URL call site go to Hopi's hooks."
    report.set(layout.buildDirectory.file("reports/hopi/debug-capture.txt"))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        verifyReleaseHasNoRuntime.configure {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            val classpath = variant.runtimeConfiguration
            hopiComponents.set(classpath.incoming.resolutionResult.rootComponent.map(HopiGraph::componentsIn))
            hopiJars.from(
                classpath.incoming.artifactView {
                    attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
                    componentFilter(HopiGraph::isHopi)
                }.files,
            )
        }
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        verifyDebugCapture.configure {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            hopiComponents.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(HopiGraph::componentsIn))
        }
    }
}

tasks.named("check") {
    dependsOn(verifyReleaseHasNoRuntime)
    // The positive control needs instrumentation, which -Psample.hopiCapture=false turns off.
    if (providers.gradleProperty("sample.hopiCapture").orNull != "false") dependsOn(verifyDebugCapture)
}
