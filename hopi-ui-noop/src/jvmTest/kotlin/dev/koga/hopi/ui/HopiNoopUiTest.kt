package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HopiNoopUiTest {
    @Test
    fun theScreenRendersNothingAndNeverCloses() = runComposeUiTest {
        var closed = 0
        setContent {
            Box(Modifier.testTag(HOST)) {
                HopiScreen(onClose = { closed++ })
            }
        }
        val host = onNodeWithTag(HOST).fetchSemanticsNode()
        assertEquals(emptyList(), host.children, "nodes under the noop screen")
        assertEquals(0, host.size.width * host.size.height, "the noop screen takes no space")
        assertEquals(0, closed)
    }

    @Test
    fun theLauncherRendersTheContentOnly() = runComposeUiTest {
        var compositions = 0
        setContent {
            Box(Modifier.testTag(HOST)) {
                HopiLauncher {
                    compositions++
                    BasicText("app content")
                }
            }
        }
        onNodeWithText("app content").assertIsDisplayed()
        val host = onNodeWithTag(HOST).fetchSemanticsNode()
        assertEquals(1, host.children.size, "nodes under the noop launcher")
        assertEquals(0, onAllNodes(hasContentDescription(LAUNCHER_DESCRIPTION)).fetchSemanticsNodes().size)
        assertEquals(1, compositions)
    }

    @Test
    fun hopiCoreIsNotOnTheRuntimeClasspath() {
        assertFailsWith<ClassNotFoundException> { Class.forName("dev.koga.hopi.core.store.HopiStore") }
        val classpath = System.getProperty("java.class.path").split(java.io.File.pathSeparatorChar)
        assertEquals(emptyList(), classpath.filter { "hopi-core" in it }, "hopi-core on the classpath")
        // Neither is any part of the real UI.
        assertTrue(classpath.none { "hopi-ui/" in it || "hopi-ui-jvm" in it }, "hopi-ui on the classpath")
    }

    private companion object {
        const val HOST = "host"

        // The real launcher's button description.
        const val LAUNCHER_DESCRIPTION = "Open Hopi"
    }
}
