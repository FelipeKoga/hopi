// A JVM app that records its Ktor client's requests: hopi-start's Hopi.start, hopi-api's HopiRules
// (which hopi-ktor brings) and hopi-ktor's plugin, next to the app's own Ktor at 3.3.3, the oldest
// hopi-ktor supports.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation("dev.koga.hopi:hopi-start:0.1.0")
    implementation("dev.koga.hopi:hopi-ktor:0.1.0")
    implementation("io.ktor:ktor-client-mock:3.3.3")
}

application {
    mainClass.set("compat.jvm.MainKt")
}
