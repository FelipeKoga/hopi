plugins {
    alias(libs.plugins.android.library)
}

// A stand-in for a third-party SDK (analytics, ads, crash reporting): Java only, its own OkHttp
// client and its own HttpURLConnection calls, and no Hopi anywhere, neither the plugin nor a
// dependency. The sample app's debug build must still record every request it makes, because the
// app's dev.koga.hopi plugin instruments the app's dependencies too (docs/design/android-capture.md).
android {
    namespace = "dev.koga.fakesdk"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Compiled against OkHttp 4.x; the app resolves the 5.x that Ktor brings, so the check also
    // covers a library built against 4.x running on 5.x.
    implementation(libs.okhttp)
}
