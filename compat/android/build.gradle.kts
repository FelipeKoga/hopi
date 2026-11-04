// Every plugin in one scope, the root's, as README asks: the Kotlin plugin next to dev.koga.hopi.
// On AGP 9 the Kotlin plugin is never applied (AGP's built-in Kotlin compiles the app), and its
// version here is the one the built-in Kotlin runs.
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("dev.koga.hopi") apply false
}
