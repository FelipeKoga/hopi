package dev.koga.hopi.start

import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.core.urlsession.NetworkTool
import dev.koga.hopi.perf.internal.PerfEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * What [Hopi.start] hands core's start sequence: the configuration and the tool list, read without
 * starting either (the real start, capture and the bridge included, is the simulator check of the
 * sample app). The one start below never gets past the start sequence's own checks.
 */
class HopiStartConfigTest {
    @Test
    fun theDefaultsAreCoresStartConfig() {
        assertEquals(StartConfig(), HopiStartConfig().toStartConfig())
    }

    @Test
    fun everySettingReachesTheStartConfig() {
        val config = HopiStartConfig().apply {
            enabled = false
            maxEntries = 7
            maxTotalBytes = 4096
            maxBodyBytes = 1024
            summaryLog = false
            agentBridge = true
            redactHeaders("X-Api-Key")
            redactHeaders("X-Trace", "X-Session")
            redactQueryParameters("token")
            unsafeDisableRedaction()
        }
        assertEquals(
            StartConfig(
                enabled = false,
                maxEntries = 7,
                maxTotalBytes = 4096,
                maxBodyBytes = 1024,
                redactedHeaders = listOf("X-Api-Key", "X-Trace", "X-Session"),
                redactedQueryParameters = listOf("token"),
                redactionDisabled = true,
                summaryLog = false,
                agentBridge = true,
            ),
            config.toStartConfig(),
        )
    }

    @Test
    fun theStartConfigKeepsTheListsOfTheCall() {
        val config = HopiStartConfig().apply { redactHeaders("X-Api-Key") }
        val start = config.toStartConfig()
        config.redactHeaders("X-Later")
        config.redactQueryParameters("later")
        assertEquals(listOf("X-Api-Key"), start.redactedHeaders)
        assertEquals(emptyList(), start.redactedQueryParameters)
    }

    @Test
    fun theNetworkToolStartsFirstThenPerf() {
        val tools = tools(HopiStartConfig())
        assertEquals(listOf("network", "perf"), tools.map { it.name })
        assertIs<PerfEntry>(tools[1])
        assertEquals(true, assertIs<NetworkTool>(tools[0]).rules)
    }

    @Test
    fun perfFalseLeavesPerfOutAndRulesReachTheNetworkTool() {
        val tools = tools(HopiStartConfig().apply { perf = false; rules = false })
        assertEquals(listOf("network"), tools.map { it.name })
        assertEquals(false, assertIs<NetworkTool>(tools.single()).rules)
    }

    @Test
    fun aDisabledStartAndInvalidCapsStartNothingAndThrowNothing() {
        Hopi.start { enabled = false }
        assertFalse(Hopi.isStarted)
        // One notice line each in the platform log; Swift could not catch an exception.
        Hopi.start { maxEntries = 0 }
        Hopi.start { maxBodyBytes = -1 }
        assertFalse(Hopi.isStarted)
    }
}
