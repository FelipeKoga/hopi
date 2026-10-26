package dev.koga.hopi.perf.internal

import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.koga.hopi.core.HopiInternalApi

/**
 * The hang monitor's input bracket (docs/design/perf-monitors.md, "Input dispatch"): input events
 * reach the main thread through a looper fd callback, not a `Message`, so the Looper printer sees
 * none of them, and Compose runs `clickable`'s `onClick` synchronously inside `dispatchTouchEvent`.
 * This wrapper of a window's callback marks the main thread busy around the three dispatch methods,
 * only when it was idle on entry (a batched `MOVE` inside a frame message is already bracketed),
 * and delegates every method of [Window.Callback] unchanged.
 *
 * [enabled] turns it into a pure pass-through: at stop it is removed only if it is still the
 * window's callback (AppCompat or another SDK may have wrapped it since); otherwise it stays,
 * disabled, and a later start re-enables it instead of wrapping twice.
 */
@HopiInternalApi
public class InputBracketCallback(
    public val delegate: Window.Callback,
    bracket: Bracket,
) : Window.Callback {
    /** What the wrapper calls around an input event: returns whether [exit] must run. */
    public interface Bracket {
        public fun enter(): Boolean

        public fun exit()
    }

    @Volatile
    public var enabled: Boolean = true

    /** The running hang monitor's; a later start replaces it when it re-enables this wrapper. */
    @Volatile
    public var bracket: Bracket = bracket

    private inline fun <T> bracketed(dispatch: () -> T): T {
        val bracket = bracket
        val entered = enabled && bracket.enter()
        try {
            return dispatch()
        } finally {
            if (entered) bracket.exit()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = bracketed { delegate.dispatchTouchEvent(event) }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean = bracketed { delegate.dispatchKeyEvent(event) }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean = bracketed { delegate.dispatchGenericMotionEvent(event) }

    override fun dispatchKeyShortcutEvent(event: KeyEvent?): Boolean = delegate.dispatchKeyShortcutEvent(event)

    override fun dispatchTrackballEvent(event: MotionEvent?): Boolean = delegate.dispatchTrackballEvent(event)

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean = delegate.dispatchPopulateAccessibilityEvent(event)

    override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = delegate.onPreparePanel(featureId, view, menu)

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = delegate.onMenuItemSelected(featureId, item)

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?): Unit = delegate.onWindowAttributesChanged(attrs)

    override fun onContentChanged(): Unit = delegate.onContentChanged()

    override fun onWindowFocusChanged(hasFocus: Boolean): Unit = delegate.onWindowFocusChanged(hasFocus)

    override fun onAttachedToWindow(): Unit = delegate.onAttachedToWindow()

    override fun onDetachedFromWindow(): Unit = delegate.onDetachedFromWindow()

    override fun onPanelClosed(featureId: Int, menu: Menu): Unit = delegate.onPanelClosed(featureId, menu)

    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = delegate.onSearchRequested(searchEvent)

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? = delegate.onWindowStartingActionMode(callback)

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = delegate.onWindowStartingActionMode(callback, type)

    override fun onActionModeStarted(mode: ActionMode?): Unit = delegate.onActionModeStarted(mode)

    override fun onActionModeFinished(mode: ActionMode?): Unit = delegate.onActionModeFinished(mode)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int): Unit =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    override fun onPointerCaptureChanged(hasCapture: Boolean): Unit = delegate.onPointerCaptureChanged(hasCapture)

    @HopiInternalApi
    public companion object {
        /**
         * Wraps [window]'s callback, or re-enables Hopi's wrapper already in its chain at the top.
         * [known] holds the wrappers Hopi left disabled under another one; returns the wrapper in force.
         */
        public fun install(window: Window, bracket: Bracket, known: MutableMap<Window, InputBracketCallback>): InputBracketCallback? {
            val current = window.callback ?: return null
            (current as? InputBracketCallback ?: known[window])?.let { mine ->
                mine.bracket = bracket
                mine.enabled = true
                known[window] = mine
                return mine
            }
            val wrapper = InputBracketCallback(current, bracket)
            window.callback = wrapper
            known[window] = wrapper
            return wrapper
        }

        /** Removes Hopi's wrapper when it is still on top, else leaves it in place as a disabled pass-through. */
        public fun uninstall(window: Window, wrapper: InputBracketCallback, known: MutableMap<Window, InputBracketCallback>) {
            wrapper.enabled = false
            if (window.callback === wrapper) {
                window.callback = wrapper.delegate
                known.remove(window)
            }
        }
    }
}
