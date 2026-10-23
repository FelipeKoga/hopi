plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    explicitApi()

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
    }
}

application {
    applicationName = "hopi"
    mainClass.set("dev.koga.hopi.cli.MainKt")
}

// applicationName also names the distribution; keep the module's name for build/install/hopi-cli.
distributions {
    main {
        distributionBaseName.set("hopi-cli")
    }
}

dependencies {
    // cURL export, JSON writer, body helpers and the bridge itself (tests); never reimplemented here.
    implementation(project(":hopi-core"))
    // Only the JSON tree parser; no serialization compiler plugin is needed for it.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":hopi-ktor"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    // InstalledBinaryTest runs the real `bin/hopi` as a subprocess.
    dependsOn(tasks.installDist)
    systemProperty(
        "hopi.cli.install",
        layout.buildDirectory.dir("install/hopi-cli").get().asFile.absolutePath,
    )
}
