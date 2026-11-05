// Publishing of every library a consumer needs (README, "Install"): to build/publication-check
// (:checkPublication), to Maven Local, and, through the release build and
// scripts/central-release.sh, to Maven Central (docs/RELEASING.md). Applied by each published
// module after its `description`; the Kotlin Multiplatform plugin creates the publications (the
// root one with the Gradle module metadata, one per target with its sources jar, the iOS ones with
// their cinterop klibs), and gradle/maven-central.gradle.kts gives each what Maven Central requires
// (the POM's project information, a javadoc jar, and signatures when a signing key is set).
//
//   ./gradlew publishToMavenLocal          (from the root: also the io.github.felipekoga.hopi plugin)
//   ./gradlew checkPublication             (the same publications into build/publication-check)

apply(plugin = "maven-publish")

group = "io.github.felipekoga.hopi"
// One version for every artifact and the Gradle plugin, which adds the runtime at it.
version = providers.gradleProperty("hopi.version").get()

if (description == null) throw GradleException("$path: set `description` before applying gradle/publishing.gradle.kts")

configure<PublishingExtension> {
    repositories {
        // :checkPublication's repository: what `publishToMavenLocal` writes to ~/.m2, under build/.
        maven {
            name = "publicationCheck"
            url = uri(rootProject.layout.buildDirectory.dir("publication-check"))
        }
    }
}

apply(from = rootProject.file("gradle/maven-central.gradle.kts"))

// :checkPublication empties its repository first.
tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToPublicationCheckRepository") }.configureEach {
    mustRunAfter(":cleanPublicationCheck")
}
