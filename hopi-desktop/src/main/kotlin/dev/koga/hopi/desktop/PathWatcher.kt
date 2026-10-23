package dev.koga.hopi.desktop

import com.sun.nio.file.SensitivityWatchEventModifier
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * Calls [onChange] when anything changes in the [directories] (recomputed after every event, so a
 * directory that appears later is watched from then on), on one thread blocked in
 * [WatchService.take]. Also called when the set of watched directories changes after the start,
 * since a file may have been written into a directory before it was watched, and, with
 * [notifyOnStart], once as soon as the first directories are watched: whatever changed before
 * then is looked at, so nothing written between a caller's last look and the watch is missed.
 *
 * The JDK's watch service is native on Linux and Windows. On macOS (JDK 17) it is the JDK's
 * polling implementation: a JDK thread lists each watched directory every 2 seconds (the `HIGH`
 * sensitivity), on this host only; the app is never asked. So the viewer holds a watcher only
 * while it waits for what the watch reports (see `Viewer`), and closes it as soon as it is
 * connected.
 */
internal class PathWatcher(
    name: String,
    private val directories: () -> List<Path>,
    private val notifyOnStart: Boolean = false,
    private val onChange: () -> Unit,
) : AutoCloseable {
    private val service: WatchService = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<Path, WatchKey>()

    init {
        Thread(::run, name).apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            sync(initial = true)
            if (notifyOnStart) onChange()
            while (true) {
                val key = service.take()
                key.pollEvents()
                if (!key.reset()) keys.values.remove(key)
                if (!sync()) onChange()
            }
        } catch (_: ClosedWatchServiceException) {
            // Closed.
        } catch (_: InterruptedException) {
            // Closed.
        }
    }

    /** Watches exactly the directories that exist now; returns true (after calling [onChange]) when that set changed. */
    private fun sync(initial: Boolean = false): Boolean {
        val wanted = directories().filter { Files.isDirectory(it) }.toSet()
        var changed = false
        for ((path, key) in keys.entries.toList()) {
            if (path !in wanted || !key.isValid) {
                key.cancel()
                keys.remove(path)
                changed = true
            }
        }
        for (path in wanted - keys.keys) {
            try {
                keys[path] = path.register(service, KINDS, SensitivityWatchEventModifier.HIGH)
                changed = true
            } catch (_: IOException) {
                // Gone again; the next event recomputes.
            }
        }
        if (changed && !initial) onChange()
        return changed
    }

    override fun close() {
        try {
            service.close()
        } catch (_: IOException) {
            // Nothing to release.
        }
    }

    companion object {
        private val KINDS = arrayOf(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)

        /** The nearest existing directory of [file]'s parent and its ancestors, up to [levels] up. */
        fun nearestExisting(file: Path, levels: Int = 3): List<Path> {
            var dir = file.parent
            repeat(levels) {
                if (dir == null) return emptyList()
                if (Files.isDirectory(dir)) return listOf(dir)
                dir = dir.parent
            }
            return emptyList()
        }
    }
}
