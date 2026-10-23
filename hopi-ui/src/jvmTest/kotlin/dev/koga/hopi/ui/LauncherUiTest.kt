package dev.koga.hopi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.list.ListTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalTestApi::class)
class LauncherUiTest {
    private val store = HopiStore(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @AfterTest
    fun closeStore() = store.close()

    @Test
    fun theAppIsNotDrawnWhileTheScreenCoversItAndDrawsAgainWhenItLeaves() = runComposeUiTest {
        var tick by mutableIntStateOf(0)
        var draws = 0
        setContent {
            HopiLauncher(store = { store }) {
                // An app that keeps changing what it draws, like a spinner or a ticking clock.
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        tick
                        draws++
                    },
                )
            }
        }
        waitForIdle()
        assertTrue(draws > 0)

        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performClick()
        mainClock.advanceTimeBy(1_000) // past the enter transition
        waitForIdle()
        val covered = draws
        repeat(5) {
            tick++
            mainClock.advanceTimeBy(100)
            waitForIdle()
        }
        assertEquals(covered, draws, "the app was drawn under the opaque screen")

        onNodeWithContentDescription(ListTags.CLOSE).performClick()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        tick++
        waitForIdle()
        assertTrue(draws > covered, "the app was not drawn again after the screen left")
    }

    @Test
    fun openingTakesFocusFromTheAppAndKeepsItOut() = runComposeUiTest {
        val requester = FocusRequester()
        var focused = false
        setContent {
            HopiLauncher(store = { store }) {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        rememberTextFieldState(),
                        Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused },
                    )
                }
            }
        }
        runOnIdle { requester.requestFocus() }
        waitForIdle()
        assertTrue(focused, "the app's field must take focus while the screen is closed")

        onNodeWithContentDescription(LAUNCHER_DESCRIPTION).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("No requests yet")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(focused, "the hidden app kept focus, and with it the keyboard and key input")

        runOnIdle { requester.requestFocus() }
        waitForIdle()
        assertFalse(focused, "focus moved into the hidden app")
    }
}
