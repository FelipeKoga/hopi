package dev.koga.hopi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.desktop.HopiDesktopTheme
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.HopiPanes
import dev.koga.hopi.ui.desktop.HopiPanesState
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import dev.koga.hopi.ui.desktop.rememberHopiPanesState
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object ViewerTags {
    const val ROOT = "viewer-root"
    const val APP_FIELD = "viewer-app"
    const val CONNECT = "viewer-connect"
    const val STATUS = "viewer-status"
    const val COUNTS = "viewer-counts"
}

/**
 * The viewer window's content: the target picker, the inspector's list and detail side by side
 * ([HopiPanes] over the session's own store) and a status bar. Every shortcut ([Shortcut])
 * is handled here, so it works from the search field too. [chooseExportFile] asks where to save
 * (a native dialog in the app, a fixed path in tests).
 */
@Composable
internal fun ViewerContent(
    viewer: Viewer,
    chooseExportFile: (ExportFormat, String) -> Path?,
    modifier: Modifier = Modifier,
    panes: HopiPanesState = rememberHopiPanesState(),
) {
    HopiDesktopTheme {
        val colors = hopiDesktopColors
        val status by viewer.status.collectAsState()
        val session by viewer.session.collectAsState()
        val rootFocus = remember { FocusRequester() }
        var appFieldFocused by remember { mutableStateOf(false) }

        fun export(format: ExportFormat) {
            chooseExportFile(format, exportFileName(status, format))?.let { viewer.export(format, it) }
        }

        Column(
            modifier
                .fillMaxSize()
                .background(colors.background)
                .testTag(ViewerTags.ROOT)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    when (shortcutOf(event)) {
                        Shortcut.Next -> panes.selectNext()
                        Shortcut.Previous -> panes.selectPrevious()
                        Shortcut.Open -> if (appFieldFocused) return@onPreviewKeyEvent false else if (panes.selectedId == null) panes.selectNext()
                        Shortcut.Back -> {
                            panes.clearSelection()
                            rootFocus.requestFocus()
                        }
                        Shortcut.Clear -> viewer.clear()
                        Shortcut.Search -> panes.focusSearch()
                        Shortcut.ExportHar -> export(ExportFormat.Har)
                        Shortcut.ExportNdjson -> export(ExportFormat.Ndjson)
                        Shortcut.Reconnect -> viewer.reconnect()
                        null -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            TargetBar(viewer, status, onAppFieldFocus = { appFieldFocused = it })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val current = session
                if (current == null) {
                    Placeholder()
                } else {
                    val store by current.store.collectAsState()
                    // Another app process, another store: its ids mean other transactions.
                    LaunchedEffect(store) { panes.clearSelection() }
                    HopiPanes(store, onSelected = viewer::select, onClear = viewer::clear, state = panes)
                }
            }
            StatusBar(viewer, status)
        }
        LaunchedEffect(Unit) { rootFocus.requestFocus() }
        // The JVM bridge root is watched (a poll on macOS) only while the picker is in front.
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { viewer.windowFocused(windowFocused) }
    }
}

@Composable
private fun TargetBar(viewer: Viewer, status: ViewerStatus, onAppFieldFocus: (Boolean) -> Unit) {
    val colors = hopiDesktopColors
    val discovery by viewer.discovery.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val app = rememberTextFieldState()
    val devices = discovery.devices
    val selected = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull { it.ready }
    // A device's only bridge is the obvious app; a typed id is never overwritten.
    LaunchedEffect(selected) {
        val bridges = (selected as? AndroidDevice)?.bridges.orEmpty()
        if (app.text.isEmpty() && bridges.size == 1) app.setTextAndPlaceCursorAtEnd(bridges.single())
    }
    val viewing = when (status) {
        ViewerStatus.Idle -> null
        is ViewerStatus.Connecting -> status.target
        is ViewerStatus.Connected -> status.target
        is ViewerStatus.Waiting -> status.target
    }
    val appId = app.text.toString().trim().ifEmpty { null }
    val problem = if (selected == null) "no device or bridge found: start an emulator, a simulator or a JVM app" else targetProblem(selected, appId)

    Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Device", style = HopiDesktopType.section.copy(color = colors.textFaint))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devices, key = { it.platform.id + ":" + it.id }) { device ->
                    ChromeChip(
                        text = listOfNotNull(platformLabel(device.platform), device.label).joinToString(" · "),
                        detail = if (device.ready) null else (device as? AndroidDevice)?.state,
                        selected = device == selected,
                        onClick = { selectedId = device.id },
                    )
                }
            }
            ChromeTextField(
                app,
                placeholder = if (selected?.platform == Platform.Jvm) "app id (optional)" else "app id",
                modifier = Modifier.width(260.dp).testTag(ViewerTags.APP_FIELD).onFocusChanged { onAppFieldFocus(it.isFocused) },
            )
            if (viewing != null) {
                ChromeButton("Disconnect", onClick = viewer::disconnect)
            }
            ChromeButton(
                if (viewing != null && selected != null && viewing.sameAs(Target(selected, appId))) "Reconnect" else "Connect",
                onClick = { selected?.let { viewer.connect(Target(it, appId)) } },
                enabled = selected != null && targetProblem(selected, appId) == null,
                primary = true,
                modifier = Modifier.testTag(ViewerTags.CONNECT),
            )
            ChromeButton("Refresh", onClick = viewer::refresh)
        }
        val bridges = (selected as? AndroidDevice)?.bridges.orEmpty()
        val hint = when {
            bridges.isNotEmpty() -> null
            selected != null && problem != null && app.text.isNotEmpty() -> problem
            discovery.notes.isNotEmpty() -> discovery.notes.joinToString("; ")
            else -> null
        }
        if (bridges.isNotEmpty() || hint != null) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (bridges.isNotEmpty()) {
                    BasicText("Bridges", style = HopiDesktopType.section.copy(color = colors.textFaint))
                    for (bridge in bridges) {
                        ChromeChip(bridge, selected = app.text.toString() == bridge, onClick = { app.setTextAndPlaceCursorAtEnd(bridge) })
                    }
                }
                hint?.let { BasicText(it, style = HopiDesktopType.meta.copy(color = colors.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun Placeholder() {
    val colors = hopiDesktopColors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("Not connected", style = HopiDesktopType.title.copy(color = colors.text))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Pick a device, enter the app's id and connect. The app needs agentBridge = true in a debug build.",
            style = HopiDesktopType.body.copy(color = colors.textMuted),
        )
    }
}

@Composable
private fun StatusBar(viewer: Viewer, status: ViewerStatus) {
    val colors = hopiDesktopColors
    val session by viewer.session.collectAsState()
    val notice by viewer.notice.collectAsState()
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val connected = status is ViewerStatus.Connected
        Box(
            Modifier.size(8.dp)
                .background(if (connected) colors.text else colors.background, CircleShape)
                .border(1.dp, if (connected) colors.text else colors.textFaint, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        BasicText(
            statusText(status),
            modifier = Modifier.weight(1f).testTag(ViewerTags.STATUS),
            style = HopiDesktopType.meta.copy(color = colors.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val current = session
        val counts = if (current != null) {
            val store by current.store.collectAsState()
            val dropped by current.dropped.collectAsState()
            countsText(store, dropped)
        } else {
            ""
        }
        val right = listOfNotNull(notice, counts.ifEmpty { null }).joinToString("   ·   ")
        BasicText(right, modifier = Modifier.testTag(ViewerTags.COUNTS), style = HopiDesktopType.meta.copy(color = colors.textMuted), maxLines = 1)
    }
}

@Composable
private fun countsText(store: HopiStore, dropped: Long): String {
    val transactions by store.transactions.collectAsState()
    val pending = transactions.count { it.state == TransactionState.Pending }
    return buildString {
        append(transactions.size).append(if (transactions.size == 1) " request" else " requests")
        if (pending > 0) append(" · ").append(pending).append(" pending")
        if (dropped > 0) append(" · the app dropped ").append(dropped)
    }
}

internal fun statusText(status: ViewerStatus): String = when (status) {
    ViewerStatus.Idle -> "Not connected"
    is ViewerStatus.Connecting -> "Connecting to ${status.target.label}…"
    is ViewerStatus.Connected -> "Connected to ${status.app} (${status.platform}) on ${status.target.device.label}"
    is ViewerStatus.Waiting -> when (status.kind) {
        WaitKind.Reconnecting -> "Reconnecting to ${status.target.label}…"
        WaitKind.AppStopped -> "App stopped: reconnects when ${status.target.app ?: "the process"} starts"
        WaitKind.DeviceOffline -> "Device offline: reconnects when adb lists it (${status.message})"
        WaitKind.Failed -> "Not connected: ${status.message}"
    }
}

/** Null for JVM bridges, whose label says JVM already. */
private fun platformLabel(platform: Platform): String? = when (platform) {
    Platform.Android -> "Android"
    Platform.Ios -> "iOS"
    Platform.Jvm -> null
}

/** `hopi-<app>-<yyyyMMdd-HHmmss>.<ext>`, the save dialog's suggestion. */
internal fun exportFileName(status: ViewerStatus, format: ExportFormat): String {
    val app = (status as? ViewerStatus.Connected)?.app ?: "session"
    val stamp = LocalDateTime.now().format(FILE_STAMP)
    return "hopi-${app.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$stamp.${format.extension}"
}

private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
