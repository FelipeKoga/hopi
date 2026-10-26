package dev.koga.hopi.core.perf

import android.app.Activity
import dev.koga.hopi.core.HopiInternalApi
import java.lang.ref.WeakReference

/**
 * The activity `HopiLauncher` is composed in (docs/design/perf-monitors.md, "Frames", attaching):
 * there is no public API for the activity already started, and Hopi keeps no lifecycle listener
 * while every monitor is off, so a frames or hangs start attaches to this one at once instead of
 * at the next activity start. The launcher writes it on its first composition, only when a perf
 * controller is registered, and clears it when it leaves; a weak reference, never a listener.
 */
@HopiInternalApi
public object PerfLauncher {
    @Volatile
    private var current: WeakReference<Activity>? = null

    /** The launcher's activity while it is composed and not finishing; null otherwise. */
    public val activity: Activity?
        get() = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    public fun attach(activity: Activity) {
        current = WeakReference(activity)
    }

    /** Clears it only if it is still [activity] (a recreated activity's launcher may have written its own). */
    public fun detach(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}
