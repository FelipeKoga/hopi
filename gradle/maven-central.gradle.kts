// What Maven Central requires of every Hopi publication (central.sonatype.org/publish/requirements;
// docs/RELEASING.md), applied after `description` by gradle/publishing.gradle.kts (the root build's
// libraries) and by the included plugin build's two projects:
//
// - a POM with the project's name, description and URL, its license, developer and SCM;
// - a -javadoc.jar beside every publication with an artifact: empty, one Jar task per publication
//   (the signing plugin signs each publication's files apart, and one jar shared by several
//   publications would get several signing tasks writing the same .asc). The -sources.jar comes
//   from the Kotlin Multiplatform plugin, or `java { withSourcesJar() }` in the plugin build. The
//   plugin marker, a POM with no artifact of its own, needs neither;
// - a signature of every file, made with the Gradle properties `signingInMemoryKey` (an
//   ASCII-armored secret key) and `signingInMemoryKeyPassword`, which CI passes as
//   ORG_GRADLE_PROJECT_signingInMemoryKey and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword (the
//   environment reaches the included build too). Without a key nothing is signed and nothing
//   changes. The release build, `./gradlew --no-configuration-cache checkPublication
//   -Phopi.release=true`, fails here at once without one, and :checkPublication then requires
//   every file's .asc.

val centralDescription = description ?: throw GradleException("$path: set `description` before applying gradle/maven-central.gradle.kts")
val repositoryUrl = "https://github.com/FelipeKoga/hopi"

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        val publication = name
        if (!publication.endsWith("PluginMarkerMaven")) {
            artifact(
                tasks.register<Jar>("${publication}EmptyJavadocJar") {
                    description = "Assembles the empty javadoc jar Maven Central requires of the $publication publication."
                    archiveClassifier.set("javadoc")
                    destinationDirectory.set(layout.buildDirectory.dir("javadoc-jars/$publication"))
                },
            )
        }
        pom {
            name.convention(provider { artifactId })
            description.convention(centralDescription)
            url.set(repositoryUrl)
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("FelipeKoga")
                    name.set("koga")
                    url.set("https://github.com/FelipeKoga")
                }
            }
            scm {
                url.set(repositoryUrl)
                connection.set("scm:git:$repositoryUrl.git")
                developerConnection.set("scm:git:ssh://git@github.com/FelipeKoga/hopi.git")
            }
        }
    }
}

val releaseBuild = providers.gradleProperty("hopi.release").orNull.toBoolean()
val signingKey = providers.gradleProperty("signingInMemoryKey").orNull?.takeIf { it.isNotBlank() }
if (releaseBuild && signingKey == null) {
    throw GradleException(
        "$path: -Phopi.release=true signs every publication and needs a signing key: set the Gradle properties " +
            "signingInMemoryKey (an ASCII-armored secret key) and signingInMemoryKeyPassword, for example as " +
            "ORG_GRADLE_PROJECT_signingInMemoryKey and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword (docs/RELEASING.md)",
    )
}
if (signingKey != null) {
    apply(plugin = "signing")
    configure<SigningExtension> {
        useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
        sign(the<PublishingExtension>().publications)
    }
    // Every file is signed before any publication's files are published.
    tasks.withType<AbstractPublishToMaven>().configureEach { mustRunAfter(tasks.withType<Sign>()) }
}
