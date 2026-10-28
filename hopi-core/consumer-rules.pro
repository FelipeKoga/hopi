# Hopi's start on Android, in debuggable variants only (docs/design/android-capture.md, "Auto-start").
# The manifest names the provider, and the provider loads the generated configuration by name;
# hopi-android's rules keep the same packages for its hooks.
-keep class dev.koga.hopi.android.internal.** { *; }
-keep class dev.koga.hopi.android.generated.** { *; }
