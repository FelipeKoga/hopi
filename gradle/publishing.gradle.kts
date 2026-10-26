// Publishing of every library a consumer needs (README, "Install"): Maven Local only, no remote
// repository and no signing. Applied by each published module after its `description`; the Kotlin
// Multiplatform plugin creates the publications (the root one with the Gradle module metadata, one
// per target with its sources jar, the iOS ones with their cinterop klibs).
//
//   ./gradlew publishToMavenLocal          (from the root: also the dev.koga.hopi plugin)
//   ./gradlew checkPublication             (the same publications into build/publication-check)

apply(plugin = "maven-publish")

group = "dev.koga.hopi"
// One version for every artifact and the Gradle plugin, which adds the runtime at it.
version = providers.gradleProperty("hopi.version").get()

val moduleDescription = description ?: throw GradleException("$path: set `description` before applying gradle/publishing.gradle.kts")

configure<PublishingExtension> {
    repositories {
        // :checkPublication's repository: what `publishToMavenLocal` writes to ~/.m2, under build/.
        maven {
            name = "publicationCheck"
            url = uri(rootProject.layout.buildDirectory.dir("publication-check"))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(provider { artifactId })
            description.set(moduleDescription)
        }
    }
}

// :checkPublication empties its repository first.
tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToPublicationCheckRepository") }.configureEach {
    mustRunAfter(":cleanPublicationCheck")
}
