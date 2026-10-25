package dev.koga.hopi.android

import dev.koga.hopi.android.internal.GeneratedConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedConfigTest {
    @Test
    fun theGeneratedClassIsReadThroughItsConstructor() {
        val config = GeneratedConfig.read(javaClass.classLoader)
        assertEquals(false, config.okhttp)
        assertEquals(true, config.urlConnection)
        assertEquals(42, config.settings.maxEntries)
        assertEquals(4096L, config.settings.maxTotalBytes)
        assertEquals(1024L, config.settings.maxBodyBytes)
        assertEquals(listOf("X-Api-Key"), config.settings.redactedHeaders)
        assertEquals(listOf("token"), config.settings.redactedQueryParameters)
        assertEquals(true, config.settings.agentBridge)
        assertEquals(false, config.settings.rules)
    }

    @Test
    fun withoutTheGeneratedClassTheDefaultsApply() {
        val empty = object : ClassLoader(null) {}
        val config = GeneratedConfig.read(empty)
        assertEquals(true, config.okhttp)
        // Without the plugin nothing was rewritten: Ktor's Android engine stays with the Ktor plugin.
        assertEquals(false, config.urlConnection)
        assertEquals(500, config.settings.maxEntries)
        assertEquals(256L * 1024, config.settings.maxBodyBytes)
    }
}
