package dev.koga.hopi.perf.internal

import android.view.Window
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The input bracket's window callback delegates every method, and brackets only the three dispatches. */
class InputBracketCallbackTest {
    private class Recording : InputBracketCallback.Bracket {
        var idle = true
        val calls = ArrayList<String>()

        override fun enter(): Boolean {
            calls += "enter"
            if (!idle) return false
            idle = false
            return true
        }

        override fun exit() {
            calls += "exit"
            idle = true
        }
    }

    private fun delegateRecording(invoked: MutableList<String>): Window.Callback =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Window.Callback::class.java)) { _, method, _ ->
            invoked += method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                else -> null
            }
        } as Window.Callback

    private fun defaultArgs(method: java.lang.reflect.Method): Array<Any?> = method.parameterTypes.map { type ->
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            // A non-null interface parameter (Menu): an inert proxy.
            else -> if (type.isInterface) Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, _, _ -> null } else null
        }
    }.toTypedArray()

    @Test
    fun everyMethodOfTheCallbackIsDelegated() {
        val invoked = ArrayList<String>()
        val bracket = Recording()
        val wrapper = InputBracketCallback(delegateRecording(invoked), bracket)
        // Every method of the compile SDK's Window.Callback, default methods included.
        val methods = Window.Callback::class.java.methods.filter { !Modifier.isStatic(it.modifiers) }
        assertTrue(methods.size >= 25, "${methods.size}")
        for (method in methods) {
            val declared = InputBracketCallback::class.java.getMethod(method.name, *method.parameterTypes)
            assertEquals(InputBracketCallback::class.java, declared.declaringClass, "${method.name} is not overridden")
            invoked.clear()
            declared.invoke(wrapper, *defaultArgs(method))
            assertEquals(listOf(method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName }), invoked, "${method.name} delegates once")
        }
        // Only the three input dispatches are bracketed.
        assertEquals(List(3) { listOf("enter", "exit") }.flatten(), bracket.calls)
    }

    @Test
    fun aDispatchInsideABusyUnitIsNotBracketedAgainAndADisabledWrapperPassesThrough() {
        val bracket = Recording()
        val wrapper = InputBracketCallback(delegateRecording(ArrayList()), bracket)
        bracket.idle = false
        wrapper.dispatchTouchEvent(null)
        assertEquals(listOf("enter"), bracket.calls, "busy on entry: no exit")
        bracket.idle = true
        bracket.calls.clear()
        wrapper.enabled = false
        wrapper.dispatchKeyEvent(null)
        assertEquals(emptyList(), bracket.calls)
    }

    @Test
    fun anExceptionInTheAppStillEndsTheBracket() {
        val bracket = Recording()
        val throwing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Window.Callback::class.java)) { _, _, _ ->
            throw IllegalStateException("the app's handler")
        } as Window.Callback
        val wrapper = InputBracketCallback(throwing, bracket)
        runCatching { wrapper.dispatchTouchEvent(null) }
        assertEquals(listOf("enter", "exit"), bracket.calls)
        assertTrue(bracket.idle)
    }

    @Test
    fun theAnrTraceKeepsOnlyTheMainThreadsSection() {
        val trace = """
            |----- pid 123 at 2026-10-25 -----
            |"Signal Catcher" daemon prio=10 tid=2 Runnable
            |  at nothing
            |
            |"main" prio=5 tid=1 Sleeping
            |  at java.lang.Thread.sleep(Native Method)
            |  at dev.koga.hopi.sample.PerfLab.hang(PerfLab.kt:41)
            |
            |"hopi-store" prio=5 tid=20 Waiting
            |  at x
        """.trimMargin()
        val main = MainThreadTrace.read(trace.byteInputStream(), 16 * 1024)
        assertEquals("\"main\" prio=5 tid=1 Sleeping\n  at java.lang.Thread.sleep(Native Method)\n  at dev.koga.hopi.sample.PerfLab.hang(PerfLab.kt:41)\n", main)
        assertEquals(null, MainThreadTrace.read("no main here".byteInputStream(), 1024))
        assertEquals("\"main\" prio=5 tid=1 Sleeping\n", MainThreadTrace.read(trace.byteInputStream(), 40), "bounded")
    }
}
