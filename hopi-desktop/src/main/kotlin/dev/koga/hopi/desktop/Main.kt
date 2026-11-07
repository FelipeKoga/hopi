package dev.koga.hopi.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import dev.koga.hopi.core.body.formatBytes
import dev.koga.hopi.core.rules.RuleLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop viewer. One window views one app; the menu repeats the window's shortcuts without
 * binding them a second time (the window content handles every key, see [Shortcut]).
 */
public fun main(args: Array<String>) {
    // The native menu bar on macOS, with the app's name.
    System.setProperty("apple.awt.application.name", "Hopi")
    val viewer = Viewer(Targets(HostEnvironment()), launch = ViewerLaunch.parse(args.asList()))
    // SIGTERM and SIGINT too: the adb tracking subprocesses would otherwise outlive the viewer.
    Runtime.getRuntime().addShutdownHook(Thread(viewer::close, "hopi-desktop-shutdown"))
    viewer.start()
    application {
        val window = rememberWindowState(width = 1280.dp, height = 820.dp)
        val panes = rememberHopiPanesState()
        val rules = remember { RulesPanelState() }
        val perf = remember { PerfPanelState() }
        Window(
            onCloseRequest = {
                viewer.close()
                exitApplication()
            },
            title = "Hopi",
            state = window,
        ) {
            val chooseFile = remember(this.window) { { format: ExportFormat, name: String -> saveDialog(this.window, format, name) } }
            val chooseBody = remember(this.window) { { bodyDialog(this.window, rules) } }
            val export = { format: ExportFormat ->
                chooseFile(format, exportFileName(viewer.status.value, format))?.let { viewer.export(format, it) }
            }
            // The title bar takes the theme's background, whatever the system's appearance.
            remember(this.window) {
                this.window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                this.window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
            val titleBar = remember(this.window) { { color: Color -> this.window.background = java.awt.Color(color.toArgb()) } }
            MenuBar {
                Menu("File") {
                    Item("Export HAR…   ${Shortcut.ExportHar.keys}", onClick = { export(ExportFormat.Har) })
                    Item("Export NDJSON…   ${Shortcut.ExportNdjson.keys}", onClick = { export(ExportFormat.Ndjson) })
                }
                Menu("Edit") {
                    Item("Find   ${Shortcut.Search.keys}", onClick = panes::focusSearch)
                    Item("Clear   ${Shortcut.Clear.keys}", onClick = viewer::clear)
                }
                Menu("Rules") {
                    Item("Rules panel   ${Shortcut.Rules.keys}", onClick = rules::toggle)
                    Item("New mock…", onClick = { rules.edit(RuleForm(FormKind.Mock)) })
                    Item("New fail…", onClick = { rules.edit(RuleForm(FormKind.Fail)) })
                    Item("New delay…", onClick = { rules.edit(RuleForm(FormKind.Delay)) })
                    Item("New edit…", onClick = { rules.edit(RuleForm(FormKind.Edit)) })
                }
                Menu("Perf") {
                    Item("Performance screen   ${Shortcut.Perf.keys}", onClick = perf::toggle)
                }
                Menu("Connection") {
                    Item("Reconnect   ${Shortcut.Reconnect.keys}", onClick = viewer::reconnect)
                    Item("Disconnect", onClick = viewer::disconnect)
                    Item("Refresh devices", onClick = viewer::refresh)
                }
            }
            val theme = remember { ThemeSettings.stored() }
            ViewerContent(viewer, chooseExportFile = chooseFile, panes = panes, rules = rules, chooseBodyFile = chooseBody, perf = perf, theme = theme, onBackground = titleBar)
        }
    }
}

/**
 * The native open dialog for a rule body; null when cancelled. A file over the 1 MiB a rule body
 * may hold is not read: the panel says so instead.
 */
private fun bodyDialog(owner: Frame, rules: RulesPanelState): BodyFile? {
    val dialog = FileDialog(owner, "Choose a body file", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val file = Paths.get(dialog.directory.orEmpty(), name)
    return try {
        val size = Files.size(file)
        if (size > RuleLimits.MAX_BODY_BYTES) {
            rules.message = "$name: ${formatBytes(size)}, more than the ${formatBytes(RuleLimits.MAX_BODY_BYTES.toLong())} a rule body may hold"
            null
        } else {
            BodyFile(name, Files.readAllBytes(file))
        }
    } catch (e: IOException) {
        rules.message = "Cannot read $name: ${e.message}"
        null
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
