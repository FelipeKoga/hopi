# Hopi's automatic capture, in debuggable variants only (docs/design/android-capture.md).
# The rewritten bytecode calls these hooks by name, and the provider is the manifest's.
-keep class dev.koga.hopi.android.internal.** { *; }
-keep class dev.koga.hopi.android.generated.** { *; }
# The runtime reads @HopiInstrumented from OkHttp's RealCall by name, without initializing it.
-keepattributes RuntimeVisibleAnnotations
-keep,allowoptimization,allowshrinking class okhttp3.internal.connection.RealCall
-keep,allowoptimization,allowshrinking class okhttp3.RealCall
-dontwarn okhttp3.**
-dontwarn okio.**
