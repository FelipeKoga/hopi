# Hopi's automatic capture, in debuggable variants only (docs/design/android-capture.md).
# The rewritten bytecode calls these hooks, and the generated configuration constructs the network
# tool, by name, which registers the Ktor plugin's recorder (a direct reference: no -keep for it);
# the provider's rules are hopi-core's.
-keep class dev.koga.hopi.android.internal.** { *; }
-keep class dev.koga.hopi.android.generated.** { *; }
# The runtime reads @HopiInstrumented from OkHttp's RealCall by name, without initializing it.
-keepattributes RuntimeVisibleAnnotations
-keep,allowoptimization,allowshrinking class okhttp3.internal.connection.RealCall
-keep,allowoptimization,allowshrinking class okhttp3.RealCall
-dontwarn okhttp3.**
-dontwarn okio.**
# The Ktor plugin's recorder is compiled against Ktor and hopi-ktor, which the app may not have
# (the network tool catches the missing class): R8 must not fail a minified debuggable variant on them.
# As the OkHttp lines above do, these also mute R8's missing-class reports for the app's own Ktor and
# kotlinx-io, in the minified debuggable variants only, the only ones that get this module.
-dontwarn io.ktor.**
-dontwarn dev.koga.hopi.ktor.**
-dontwarn kotlinx.io.**
