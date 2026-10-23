package dev.koga.hopi.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.koga.hopi.connect.HostEnvironment
import dev.koga.hopi.connect.Targets
import dev.koga.hopi.ui.desktop.rememberHopiPanesState
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop viewer. One window views one app; the menu repeats the window's shortcuts without
 * binding them a second time (the window content handles every key, see [Shortcut]).
 */
public fun main() {
    // The native menu bar on macOS, with the app's name.
    System.setProperty("apple.awt.application.name", "Hopi")
    val viewer = Viewer(Targets(HostEnvironment()))
    // SIGTERM and SIGINT too: the adb tracking subprocesses would otherwise outlive the viewer.
    Runtime.getRuntime().addShutdownHook(Thread(viewer::close, "hopi-desktop-shutdown"))
    viewer.start()
    application {
        val window = rememberWindowState(width = 1280.dp, height = 820.dp)
        val panes = rememberHopiPanesState()
        Window(
            onCloseRequest = {
                viewer.close()
                exitApplication()
            },
            title = "Hopi",
            state = window,
        ) {
            val chooseFile = remember(this.window) { { format: ExportFormat, name: String -> saveDialog(this.window, format, name) } }
            val export = { format: ExportFormat ->
                chooseFile(format, exportFileName(viewer.status.value, format))?.let { viewer.export(format, it) }
            }
            MenuBar {
                Menu("File") {
                    Item("Export HAR…   ${Shortcut.ExportHar.keys}", onClick = { export(ExportFormat.Har) })
                    Item("Export NDJSON…   ${Shortcut.ExportNdjson.keys}", onClick = { export(ExportFormat.Ndjson) })
                }
                Menu("Edit") {
                    Item("Find   ${Shortcut.Search.keys}", onClick = panes::focusSearch)
                    Item("Clear   ${Shortcut.Clear.keys}", onClick = viewer::clear)
                }
                Menu("Connection") {
                    Item("Reconnect   ${Shortcut.Reconnect.keys}", onClick = viewer::reconnect)
                    Item("Disconnect", onClick = viewer::disconnect)
                    Item("Refresh devices", onClick = viewer::refresh)
                }
            }
            ViewerContent(viewer, chooseExportFile = chooseFile, panes = panes)
        }
    }
}

/** The native save dialog; null when cancelled. */
private fun saveDialog(owner: Frame, format: ExportFormat, name: String): Path? {
    val dialog = FileDialog(owner, "Export ${format.label}", FileDialog.SAVE)
    dialog.file = name
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Paths.get(dialog.directory.orEmpty(), file)
}
