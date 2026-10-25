package dev.koga.hopi.android

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HopiAndroidNoopTest {
    @Test
    fun neverStartedNeverInstrumented() {
        assertFalse(HopiAndroid.isStarted)
        assertFalse(HopiAndroid.okHttpInstrumented)
    }

    @Test
    fun noCaptureCodeAndNoCoreOnTheClasspath() {
        val classpath = System.getProperty("java.class.path").orEmpty()
        assertFalse("hopi-core" in classpath, classpath)
        assertFalse(Regex("hopi-android[/\\\\]").containsMatchIn(classpath), classpath)
        for (name in listOf("dev.koga.hopi.core.store.HopiStore", "dev.koga.hopi.android.internal.AndroidCapture", "dev.koga.hopi.android.internal.HopiInitProvider")) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
    }
}
