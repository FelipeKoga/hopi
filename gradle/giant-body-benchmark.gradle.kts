// The giant-body benchmarks: opt-in, never part of `check`. Applied by every module that has one
// (classes named `*GiantBodyBenchmark`); the module's tests skip them, and a benchmark task runs only
// them, with a 2 GB heap, printing their tables and saving them as Markdown under build/giant-body/:
// `jvmGiantBodyBenchmark` beside `jvmTest` in a KMP module, `testGiantBodyBenchmark` beside `test` in
// a JVM-only one, and `androidHostGiantBodyBenchmark` beside `testAndroidHostTest` in a module that
// sets `extra["hopi.giantBodyTestTask"] = "testAndroidHostTest"` before applying this script.
//
//   ./gradlew :hopi-core:jvmGiantBodyBenchmark
//   ./gradlew :hopi-cli:testGiantBodyBenchmark
//   ./gradlew :hopi-network:androidHostGiantBodyBenchmark

val testTaskName = if (extra.has("hopi.giantBodyTestTask")) {
    extra["hopi.giantBodyTestTask"] as String
} else if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
    "jvmTest"
} else {
    "test"
}
val benchmarkTaskName = when (testTaskName) {
    "jvmTest" -> "jvmGiantBodyBenchmark"
    "test" -> "testGiantBodyBenchmark"
    "testAndroidHostTest" -> "androidHostGiantBodyBenchmark"
    else -> "${testTaskName}GiantBodyBenchmark"
}
val benchmarkClasses = "*GiantBodyBenchmark"

// By name, lazily: AGP registers its host test task after this script runs.
tasks.withType<Test>().configureEach {
    if (name == testTaskName) {
        filter {
            excludeTestsMatching(benchmarkClasses)
            isFailOnNoMatchingTests = false
        }
    }
}

tasks.register<Test>(benchmarkTaskName) {
    group = "verification"
    description = "Runs the opt-in giant-body benchmarks of this module."
    // Looked up when this task is configured, once every plugin has registered its test task.
    val unitTests = tasks.named<Test>(testTaskName).get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter { includeTestsMatching(benchmarkClasses) }
    maxHeapSize = "2g"
    // Every run measures afresh.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    systemProperty("hopi.bench.out", layout.buildDirectory.dir("giant-body").get().asFile.absolutePath)
}
