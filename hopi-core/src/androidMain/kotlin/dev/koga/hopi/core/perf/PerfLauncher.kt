package dev.koga.hopi.core.perf

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.koga.hopi.api.HopiInternalApi
import java.lang.ref.WeakReference
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * The activity a frames or hangs start attaches to at once (docs/design/perf-monitors.md, "Frames",
 * attaching), instead of at the next activity start: there is no public API for the activity
 * already started. Core's start writes it from start on ([CurrentActivityCallbacks]: the resumed
 * activity), and `HopiLauncher` still writes the activity it is composed in while a perf
 * controller is registered, until the in-app UI goes (the runtime-split plan's stage 5). A weak
 * reference; the perf monitors keep their own lifecycle listener only while frames or hangs runs.
 */
@HopiInternalApi
public object PerfLauncher {
    @Volatile
    private var current: WeakReference<Activity>? = null

    /** The activity written last (resumed, or the launcher's) while it is not finishing; null otherwise. */
    public val activity: Activity?
        get() = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    public fun attach(activity: Activity) {
        current = WeakReference(activity)
    }

    /** Clears it only if it is still [activity] (another activity, or a recreated one's launcher, may have written its own). */
    public fun detach(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}

/**
 * Core's current-activity tracking (the runtime-split plan's P5): the resumed activity goes into
 * [PerfLauncher], cleared when it pauses or is destroyed, so frames and hangs attach at once with
 * or without the in-app launcher. The provider registers it once, before the tools start, whatever
 * tools are listed; it runs only on the activities' lifecycle events, with no thread and no timer.
 */
internal object CurrentActivityCallbacks : Application.ActivityLifecycleCallbacks {
    private val registered = AtomicBoolean(false)

    fun register(application: Application) {
        if (registered.compareAndSet(false, true)) application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) = PerfLauncher.attach(activity)

    override fun onActivityPaused(activity: Activity) = PerfLauncher.detach(activity)

    override fun onActivityDestroyed(activity: Activity) = PerfLauncher.detach(activity)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
