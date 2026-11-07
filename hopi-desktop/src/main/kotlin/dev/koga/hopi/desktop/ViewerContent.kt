package dev.koga.hopi.desktop

import dev.koga.hopi.ui.desktop.HopiDesktopMotion
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.draw.clip
import dev.koga.hopi.ui.desktop.PaneShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.core.model.TransactionState
import dev.koga.hopi.core.store.HopiStore
import dev.koga.hopi.ui.desktop.HopiDesktopColors
import dev.koga.hopi.ui.desktop.HopiDesktopTheme
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.HopiPanes
import dev.koga.hopi.ui.desktop.HopiPanesRules
import dev.koga.hopi.ui.desktop.HopiPanesState
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import dev.koga.hopi.ui.desktop.rememberHopiPanesState
import dev.koga.hopi.ui.theme.HopiTone
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
 * ([HopiPanes] over the session's own store), the rules panel beside them while open
 * ([RulesPanel]) and the perf panel ([PerfPanel]) beside them while open, and a status bar. Every shortcut ([Shortcut]) is handled here, so it works from
 * the search field too; the list's arrow and Enter keys stay with a rules field that has focus.
 * [chooseExportFile] asks where to save and [chooseBodyFile] which body file to send (native
 * dialogs in the app, fixed paths in tests).
 */
@Composable
internal fun ViewerContent(
    viewer: Viewer,
    chooseExportFile: (ExportFormat, String) -> Path?,
    modifier: Modifier = Modifier,
    panes: HopiPanesState = rememberHopiPanesState(),
    rules: RulesPanelState = remember { RulesPanelState() },
    chooseBodyFile: () -> BodyFile? = { null },
    perf: PerfPanelState = remember { PerfPanelState() },
    theme: ThemeSettings = remember { ThemeSettings() },
    onBackground: (Color) -> Unit = {},
) {
    HopiDesktopTheme(theme.choice) {
        val colors = hopiDesktopColors
        SideEffect { onBackground(colors.background) }
        val status by viewer.status.collectAsState()
        val session by viewer.session.collectAsState()
        val rootFocus = remember { FocusRequester() }
        var appFieldFocused by remember { mutableStateOf(false) }
        var rulesFocused by remember { mutableStateOf(false) }

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
                    val shortcut = shortcutOf(event)
                    // Arrows, Enter and Esc edit a rules field; only the ⌘ shortcuts reach past it.
                    if (rulesFocused && shortcut in LIST_KEYS) return@onPreviewKeyEvent false
                    when (shortcut) {
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
                        Shortcut.Rules -> {
                            perf.open = false
                            rules.toggle()
                        }
                        Shortcut.Perf -> perf.toggle()
                        null -> return@onPreviewKeyEvent false
                    }
                    true
                },
        ) {
            TopBar(viewer, status, theme, rules, perf, onAppFieldFocus = { appFieldFocused = it })
            // The panes float on the background, 8 dp apart and from the window's edges.
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = session
                // One screen at a time: Network (with its Rules panel) or Performance.
                if (perf.open) {
                    PerfPanel(current, perf, modifier = Modifier.fillMaxSize().clip(PaneShape).background(colors.pane))
                } else {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (current == null) {
                        Placeholder()
                    } else {
                        val store by current.store.collectAsState()
                        val remoteRules by current.rules.collectAsState()
                        // Another app process, another store: its ids mean other transactions.
                        LaunchedEffect(store) { panes.clearSelection() }
                        val paneRules = remember(current, remoteRules.supported) {
                            HopiPanesRules(
                                mockThis = { rules.edit(RuleForm.mockOf(it)) },
                                unavailable = if (remoteRules.supported) null else "this app's Hopi does not support rules",
                            )
                        }
                        HopiPanes(store, onSelected = viewer::select, onClear = viewer::clear, state = panes, rules = paneRules)
                    }
                }
                // The side panels slide in from the edge as they grow, and fade: M3 Expressive's springs.
                AnimatedVisibility(
                    visible = rules.open,
                    enter = expandHorizontally(HopiDesktopMotion.defaultSpatial(), expandFrom = Alignment.Start) + fadeIn(HopiDesktopMotion.defaultEffects()),
                    exit = shrinkHorizontally(HopiDesktopMotion.defaultSpatial(), shrinkTowards = Alignment.Start) + fadeOut(HopiDesktopMotion.fastEffects()),
                ) {
                    RulesPanel(
                        current,
                        rules,
                        chooseBodyFile,
                        modifier = Modifier.width(RULES_PANEL_WIDTH).fillMaxHeight().clip(PaneShape).background(colors.pane).onFocusChanged { rulesFocused = it.hasFocus },
                    )
                }
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
private fun Placeholder() {
    val colors = hopiDesktopColors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("Not connected", style = HopiDesktopType.title.copy(color = colors.text))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Pick a device, enter the app's id and connect. Any debug build with Hopi serves it (unless agentBridge = false).",
            style = HopiDesktopType.body.copy(color = colors.textMuted),
        )
    }
}

@Composable
private fun StatusBar(viewer: Viewer, status: ViewerStatus) {
    val colors = hopiDesktopColors
    val session by viewer.session.collectAsState()
    val notice by viewer.notice.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The connection's dot is the top bar's; this line says what it means in words.
        val tone = statusTone(status, colors)
        BasicText(
            statusText(status),
            modifier = Modifier.weight(1f).testTag(ViewerTags.STATUS),
            // A link that is down and not just waiting for the app says so in its hue too.
            style = HopiDesktopType.meta.copy(color = if (status is ViewerStatus.Waiting && status.kind != WaitKind.AppStopped) tone.fg else colors.textMuted),
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
        // The same string as `notice   ·   counts`, the notice in its hue.
        val right = buildAnnotatedString {
            notice?.let { text ->
                withStyle(SpanStyle(color = noticeTone(text, colors).fg)) { append(text) }
                if (counts.isNotEmpty()) append("   ·   ")
            }
            append(counts)
        }
        BasicText(right, modifier = Modifier.testTag(ViewerTags.COUNTS), style = HopiDesktopType.meta.copy(color = colors.textMuted), maxLines = 1)
    }
}

/** The connection's hue: connected success, connecting info, reconnecting or device offline warning, a failed connect error, idle and app stopped neutral. */
internal fun statusTone(status: ViewerStatus, colors: HopiDesktopColors): HopiTone = when (status) {
    ViewerStatus.Idle -> colors.neutral
    is ViewerStatus.Connecting -> colors.info
    is ViewerStatus.Connected -> colors.success
    is ViewerStatus.Waiting -> when (status.kind) {
        WaitKind.Reconnecting, WaitKind.DeviceOffline -> colors.warning
        WaitKind.AppStopped -> colors.neutral
        WaitKind.Failed -> colors.error
    }
}

/** The export notice's hue, by the words Viewer.export and ExportResult.describe begin it with. */
private fun noticeTone(notice: String, colors: HopiDesktopColors): HopiTone = when {
    notice.startsWith("Export failed") -> colors.error
    notice.startsWith("Exported ") && " without bodies" in notice -> colors.warning
    notice.startsWith("Exported ") -> colors.success
    else -> colors.neutral
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
        // Nothing on the host tells when an app on a USB iPhone starts (its bridge.json is on the phone).
        // devicectl's reason, when its copy failed, tells a stopped app from a missing one or a locked phone.
        WaitKind.AppStopped -> if (status.target.device is UsbIphoneDevice) {
            "App stopped: Reconnect (⌘R) once ${status.target.app} runs on the iPhone" + devicectlReason(status.message)
        } else {
            "App stopped: reconnects when ${status.target.app ?: "the process"} starts"
        }
        WaitKind.DeviceOffline -> "Device offline: reconnects when adb lists it (${status.message})"
        WaitKind.Failed -> "Not connected: ${status.message}"
    }
}

/** The ` (devicectl could not copy …: <its reason>)` that ends `Targets.openUsbIphone`'s message when devicectl failed, or "". */
private fun devicectlReason(message: String): String {
    val at = message.indexOf(" (devicectl could not copy ")
    return if (at < 0) "" else message.substring(at)
}

/** Null for JVM bridges, whose label says JVM already. */
internal fun platformLabel(platform: Platform): String? = when (platform) {
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

/** The list's own keys, left to a focused rules field. */
private val LIST_KEYS = setOf(Shortcut.Next, Shortcut.Previous, Shortcut.Open, Shortcut.Back)

private val RULES_PANEL_WIDTH = 420.dp

