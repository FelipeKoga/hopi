import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// How a host tool reaches an app's bridge: adb and simctl targets, bridge.json, the bridge client
// and its proof check. Shared by the CLI and the desktop viewer; never a dependency of an app.
kotlin {
    explicitApi()

    // Every public declaration is @HopiInternalApi; the dump keeps the surface reviewed.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        optIn.add("dev.koga.hopi.core.HopiInternalApi")
    }
}

dependencies {
    // The JSON writer (requests) and parseFlatJsonObject (bridge.json); JsonWriter is in the API.
    api(project(":hopi-core"))
    // Only the JSON tree parser (replies); Reply.json is a JsonObject.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    // RemoteSessionTest fetches a body over 16 MB: the line, its parse and its bytes at once.
    maxHeapSize = "1g"
}
