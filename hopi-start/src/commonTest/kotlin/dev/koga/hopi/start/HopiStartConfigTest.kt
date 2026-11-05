package dev.koga.hopi.start

import dev.koga.hopi.core.start.StartConfig
import dev.koga.hopi.network.internal.NetworkTool
import dev.koga.hopi.perf.internal.PerfEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What [Hopi.start] hands core's start sequence: the configuration and the tool list, read without
 * starting either, on iOS and the JVM (the real start is the simulator check of the sample app on
 * iOS, JvmStartTest on the JVM; on iOS, IosStartTest starts nothing past the sequence's checks).
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
        assertEquals(true, assertIs<PerfEntry>(tools[1]).remoteControl, "remote control on by default")
        assertEquals(true, assertIs<NetworkTool>(tools[0]).rules)
    }

    @Test
    fun perfRemoteControlReachesPerfsTool() {
        val tools = tools(HopiStartConfig().apply { perfRemoteControl = false })
        assertEquals(false, assertIs<PerfEntry>(tools[1]).remoteControl)
    }

    @Test
    fun perfFalseLeavesPerfOutAndRulesReachTheNetworkTool() {
        val tools = tools(HopiStartConfig().apply { perf = false; rules = false })
        assertEquals(listOf("network"), tools.map { it.name })
        assertEquals(false, assertIs<NetworkTool>(tools.single()).rules)
    }
}
