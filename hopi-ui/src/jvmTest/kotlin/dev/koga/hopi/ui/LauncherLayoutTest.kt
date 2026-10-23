package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How `HopiLauncher` lays out the app's content. The same file runs in hopi-ui and in
 * hopi-ui-noop, so a release build (noop) has exactly the layout tested in debug (real).
 */
@OptIn(ExperimentalTestApi::class)
class LauncherLayoutTest {
    @Test
    fun contentGetsTheLaunchersConstraintsUnchanged() = runComposeUiTest {
        setContent {
            // Fixed constraints, as a window's root gives them.
            Box(Modifier.size(300.dp, 400.dp), propagateMinConstraints = true) {
                HopiLauncher {
                    Column(Modifier.testTag(CONTENT)) { BasicText("app") }
                }
            }
        }
        assertEquals(DpRect(0.dp, 0.dp, 300.dp, 400.dp), onNodeWithTag(CONTENT).getBoundsInRoot())
    }

    @Test
    fun rootChildrenStackAtTheTopStartAndTheLauncherTakesTheirSize() = runComposeUiTest {
        setContent {
            Column(Modifier.size(300.dp, 400.dp)) {
                HopiLauncher {
                    Box(Modifier.size(50.dp).testTag(FIRST))
                    Box(Modifier.size(20.dp).testTag(SECOND))
                }
                Box(Modifier.size(10.dp).testTag(AFTER))
            }
        }
        assertEquals(DpRect(0.dp, 0.dp, 50.dp, 50.dp), onNodeWithTag(FIRST).getBoundsInRoot())
        assertEquals(DpRect(0.dp, 0.dp, 20.dp, 20.dp), onNodeWithTag(SECOND).getBoundsInRoot())
        // The launcher is as tall as its largest child, not the whole column.
        assertEquals(DpRect(0.dp, 50.dp, 10.dp, 60.dp), onNodeWithTag(AFTER).getBoundsInRoot())
    }

    private companion object {
        const val CONTENT = "content"
        const val FIRST = "first"
        const val SECOND = "second"
        const val AFTER = "after"
    }
}
