// The giant-body benchmarks: opt-in, never part of `check`. Applied by every module that has one
// (classes named `*GiantBodyBenchmark`); the module's JVM tests skip them, and
// `jvmGiantBodyBenchmark` (`testGiantBodyBenchmark` in a JVM-only module) runs only them, with a 2 GB heap, printing their tables and
// saving them as Markdown under build/giant-body/.
//
//   ./gradlew :hopi-core:jvmGiantBodyBenchmark
//   ./gradlew :hopi-cli:testGiantBodyBenchmark

val testTaskName = if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) "jvmTest" else "test"
val benchmarkClasses = "*GiantBodyBenchmark"

tasks.named<Test>(testTaskName) {
    filter {
        excludeTestsMatching(benchmarkClasses)
        isFailOnNoMatchingTests = false
    }
}

val unitTests = tasks.named<Test>(testTaskName)
tasks.register<Test>(if (testTaskName == "jvmTest") "jvmGiantBodyBenchmark" else "testGiantBodyBenchmark") {
    group = "verification"
    description = "Runs the opt-in giant-body benchmarks of this module."
    testClassesDirs = unitTests.get().testClassesDirs
    classpath = unitTests.get().classpath
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
