package dev.koga.hopi.desktop

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.koga.hopi.connect.Platform
import dev.koga.hopi.ui.desktop.HopiDesktopColors
import dev.koga.hopi.ui.desktop.HopiDesktopMotion
import dev.koga.hopi.ui.desktop.HopiDesktopType
import dev.koga.hopi.ui.desktop.hopiDesktopColors
import dev.koga.hopi.ui.desktop.hopiMorphingPill
import dev.koga.hopi.ui.desktop.hopiPressScale

internal object TopBarTags {
    const val TARGET = "top-target"
    const val DEVICE_MENU = "top-device-menu"
    const val DISCONNECT = "Disconnect"
    const val REFRESH = "Refresh devices"
    const val RULES = "Rules (⌘L)"
    const val NETWORK = "Network"
    const val PERF = "Performance (⌘P)"
}

/**
 * The window's top app bar, after M3 Expressive's: one 64 dp row on the background, no band.
 * In the middle a search-bar-like pill holds the whole target, from left to right: the connection's
 * dot, the device (a menu of every device, each Android one with the bridges it lists, and the
 * notes of a missing tool), the app id and the filled Connect button, so choosing and connecting
 * read as one control. On the right, icon buttons: the Rules and Perf panels as toggles (filled
 * while open, their corners squaring as M3 Expressive's toggle buttons do), then the theme.
 */
@Composable
internal fun TopBar(
    viewer: Viewer,
    status: ViewerStatus,
    theme: ThemeSettings,
    rules: RulesPanelState,
    perf: PerfPanelState,
    onAppFieldFocus: (Boolean) -> Unit,
) {
    val colors = hopiDesktopColors
    val discovery by viewer.discovery.collectAsState()
    var selectedId by remember { mutableStateOf(viewer.launch?.device) }
    val app = rememberTextFieldState(viewer.launch?.app.orEmpty())
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
    // A connection the launch made shows in the picker too.
    LaunchedEffect(viewing?.device?.id) { viewing?.let { selectedId = it.device.id } }
    val appId = app.text.toString().trim().ifEmpty { null }
    val problem = when {
        selected == null -> "no device or bridge found: start an emulator, a simulator or a JVM app"
        app.text.isEmpty() -> null
        else -> targetProblem(selected, appId)
    }
    val connectable = selected != null && targetProblem(selected, appId) == null
    val same = viewing != null && selected != null && viewing.sameAs(Target(selected, appId))

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            // The pill takes the width left of the actions, up to 760 dp; the actions keep the right edge.
            Box(Modifier.weight(1f)) {
                TargetPill(
                    status = status,
                    devices = devices,
                    selected = selected,
                    notes = discovery.notes,
                    app = app,
                    connectLabel = if (same) "Reconnect" else "Connect",
                    connectable = connectable,
                    viewing = viewing != null,
                    onDevice = { device, bridge ->
                        selectedId = device.id
                        if (bridge != null) app.setTextAndPlaceCursorAtEnd(bridge)
                    },
                    onConnect = { selected?.let { viewer.connect(Target(it, appId)) } },
                    onDisconnect = viewer::disconnect,
                    onRefresh = viewer::refresh,
                    onAppFieldFocus = onAppFieldFocus,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ScreenButton("Network", TopBarTags.NETWORK, selected = !perf.open) { perf.open = false }
                ScreenButton("Performance", TopBarTags.PERF, selected = perf.open) { perf.open = true }
                Spacer(Modifier.width(8.dp))
                if (!perf.open) ToolbarIconButton(TopBarIcons.Rules, TopBarTags.RULES, onClick = rules::toggle, selected = rules.open)
                ThemeButton(theme)
            }
        }
        // What stops a connect (a bad app id, no device): one quiet line, only then.
        problem?.let {
            BasicText(
                it,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                style = HopiDesktopType.meta.copy(color = colors.textMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One of the two screens: tonal while shown. */
@Composable
private fun ScreenButton(text: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    ChromeButton(
        text,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = tag },
        variant = if (selected) ButtonVariant.Secondary else ButtonVariant.Ghost,
    )
}

@Composable
private fun TargetPill(
    status: ViewerStatus,
    devices: List<Device>,
    selected: Device?,
    notes: List<String>,
    app: TextFieldState,
    connectLabel: String,
    connectable: Boolean,
    viewing: Boolean,
    onDevice: (Device, String?) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onAppFieldFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = hopiDesktopColors
    val fieldSource = remember { MutableInteractionSource() }
    val focused by fieldSource.collectIsFocusedAsState()
    val ring by animateColorAsState(if (focused) colors.ring else Color.Transparent, HopiDesktopMotion.fastEffects(), label = "pillRing")
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(colors.containerHigh)
            .border(2.dp, ring, RoundedCornerShape(28.dp))
            .padding(start = 8.dp, end = 6.dp)
            .testTag(TopBarTags.TARGET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceSelector(status, devices, selected, notes, onDevice, onRefresh)
        Box(Modifier.width(1.dp).height(24.dp).background(colors.border))
        Box(Modifier.weight(1f).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (app.text.isEmpty()) {
                BasicText(
                    if (selected?.platform == Platform.Jvm) "App id (optional)" else "App id",
                    style = HopiDesktopType.mono.copy(color = colors.textFaint),
                    maxLines = 1,
                )
            }
            BasicTextField(
                state = app,
                modifier = Modifier.fillMaxWidth().testTag(ViewerTags.APP_FIELD).onFocusChanged { onAppFieldFocus(it.isFocused) },
                textStyle = HopiDesktopType.mono.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                lineLimits = TextFieldLineLimits.SingleLine,
                interactionSource = fieldSource,
            )
        }
        if (viewing) {
            ToolbarIconButton(TopBarIcons.Disconnect, TopBarTags.DISCONNECT, onClick = onDisconnect)
            Spacer(Modifier.width(4.dp))
        }
        ChromeButton(
            connectLabel,
            onClick = onConnect,
            enabled = connectable,
            variant = ButtonVariant.Primary,
            modifier = Modifier.testTag(ViewerTags.CONNECT),
        )
    }
}

/** The pill's leading part: the connection's dot and the device, which opens the device menu. */
@Composable
private fun DeviceSelector(
    status: ViewerStatus,
    devices: List<Device>,
    selected: Device?,
    notes: List<String>,
    onDevice: (Device, String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = hopiDesktopColors
    val open = remember { MutableTransitionState(false) }
    val source = remember { MutableInteractionSource() }
    Box {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .hopiPressScale(source)
                .clip(hopiMorphingPill(source, 40.dp))
                .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.DropdownList) {
                    open.targetState = !open.targetState
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tone = statusTone(status, colors)
            Box(
                Modifier.size(10.dp)
                    .background(if (status is ViewerStatus.Connected) tone.fg else Color.Transparent, CircleShape)
                    .border(2.dp, tone.fg, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            BasicText(
                selected?.let(::deviceLabel) ?: "No device",
                modifier = Modifier.widthIn(max = 240.dp),
                style = HopiDesktopType.label.copy(color = if (selected == null) colors.textMuted else colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            VectorIcon(TopBarIcons.ChevronDown, colors.textMuted, 16.dp)
        }
        if (open.currentState || open.targetState) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 52),
                onDismissRequest = { open.targetState = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    open,
                    enter = scaleIn(HopiDesktopMotion.fastSpatial(), initialScale = 0.9f, transformOrigin = TransformOrigin(0f, 0f)) + fadeIn(HopiDesktopMotion.fastEffects()),
                    exit = scaleOut(HopiDesktopMotion.fastSpatial(), targetScale = 0.9f, transformOrigin = TransformOrigin(0f, 0f)) + fadeOut(HopiDesktopMotion.fastEffects()),
                ) {
                    DeviceMenu(devices, selected, notes, onPick = { device, bridge ->
                        onDevice(device, bridge)
                        open.targetState = false
                    }, onRefresh = onRefresh)
                }
            }
        }
    }
}

/**
 * M3 Expressive's menu: a 16 dp rounded container, 48 dp items, the selected one in
 * secondaryContainer. Each device, and under an Android device each bridge it lists (picking one
 * fills the app id); the notes of a missing tool (adb, Xcode) as supporting text; Refresh last.
 */
@Composable
private fun DeviceMenu(
    devices: List<Device>,
    selected: Device?,
    notes: List<String>,
    onPick: (Device, String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = hopiDesktopColors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .widthIn(min = 300.dp, max = 420.dp)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(colors.pane)
            .padding(vertical = 8.dp)
            .testTag(TopBarTags.DEVICE_MENU),
    ) {
        if (devices.isEmpty()) MenuSupporting("No device: start an emulator, a simulator or a JVM app", colors)
        for (device in devices) {
            val bridges = (device as? AndroidDevice)?.bridges.orEmpty()
            MenuItem(
                text = deviceLabel(device),
                detail = when {
                    !device.ready -> (device as? AndroidDevice)?.state
                    bridges.isEmpty() && device.platform == Platform.Android -> "no bridge listed"
                    else -> null
                },
                selected = device == selected && bridges.isEmpty(),
                onClick = { onPick(device, null) },
            )
            for (bridge in bridges) {
                MenuItem(text = bridge, detail = null, selected = device == selected, indent = true, mono = true, onClick = { onPick(device, bridge) })
            }
        }
        for (note in notes) MenuSupporting(note, colors)
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.border))
        MenuItem(text = TopBarTags.REFRESH, detail = null, selected = false, onClick = onRefresh)
    }
}

@Composable
private fun MenuItem(text: String, detail: String?, selected: Boolean, onClick: () -> Unit, indent: Boolean = false, mono: Boolean = false) {
    val colors = hopiDesktopColors
    val fill by animateColorAsState(if (selected) colors.secondaryContainer else Color.Transparent, HopiDesktopMotion.fastEffects(), label = "menuItem")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .semantics { this.selected = selected }
            .clickable(onClick = onClick)
            .padding(start = if (indent) 32.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text,
            modifier = Modifier.weight(1f),
            style = (if (mono) HopiDesktopType.mono else HopiDesktopType.label).copy(color = if (selected) colors.onSecondaryContainer else colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let {
            Spacer(Modifier.width(8.dp))
            BasicText(it, style = HopiDesktopType.meta.copy(color = colors.warning.fg), maxLines = 1)
        }
    }
}

@Composable
private fun MenuSupporting(text: String, colors: HopiDesktopColors) {
    BasicText(
        text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = HopiDesktopType.meta.copy(color = colors.textMuted),
    )
}

/** `Android · Pixel 9`, `iOS · iPhone 17`, `JVM 4242`. */
private fun deviceLabel(device: Device): String = listOfNotNull(platformLabel(device.platform), device.label).joinToString(" · ")

/**
 * M3 Expressive's standard icon button, 40 dp in a 48 dp target, its [description] in a tooltip
 * and for screen readers. A toggle ([selected] not null) fills with secondaryContainer while on,
 * and its corners morph from round to 12 dp, as M3 Expressive's toggle icon buttons do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolbarIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    selected: Boolean? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = hopiDesktopColors
    val source = remember { MutableInteractionSource() }
    val on = selected == true
    val radius by animateDpAsState(if (on) 12.dp else 20.dp, HopiDesktopMotion.fastSpatial(), label = "toggleShape")
    val fill by animateColorAsState(if (on) colors.secondaryContainer else Color.Transparent, HopiDesktopMotion.fastEffects(), label = "toggleFill")
    TooltipArea(
        tooltip = {
            BasicText(
                description,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.text).padding(horizontal = 10.dp, vertical = 6.dp),
                style = HopiDesktopType.meta.copy(color = colors.background),
            )
        },
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.ComponentRect(anchor = Alignment.BottomCenter, alignment = Alignment.BottomCenter, offset = DpOffset(0.dp, 6.dp)),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = description; if (selected != null) this.selected = on }
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = if (selected == null) Role.Button else Role.Switch, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .hopiPressScale(source)
                    .clip(RoundedCornerShape(radius))
                    .background(fill)
                    .indication(source, LocalIndication.current),
                contentAlignment = Alignment.Center,
            ) {
                VectorIcon(icon, if (on) colors.onSecondaryContainer else colors.text, 20.dp)
            }
        }
    }
}

@Composable
internal fun VectorIcon(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp) {
    Image(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** Lucide's icons (lucide.dev), as the in-app UI's: 2 dp round strokes on a 24 dp grid. */
internal object TopBarIcons {
    val ChevronDown by lazy { icon("ChevronDown", "M6 9l6 6 6-6") }

    /** list-checks. */
    val Rules by lazy { icon("Rules", "M3 17l2 2 4-4", "M3 7l2 2 4-4", "M13 6h8", "M13 12h8", "M13 18h8") }

    /** activity. */

    /** pencil. */
    val Edit by lazy { icon("Edit", "M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5z", "M15 5l4 4") }

    /** trash-2. */
    val Trash by lazy { icon("Trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2") }

    /** contrast. */
    val Theme by lazy { icon("Theme", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 18a6 6 0 0 0 0-12v12z") }

    /** unplug. */
    val Disconnect by lazy { icon("Disconnect", "M19 5l3-3", "M2 22l3-3", "M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4z", "M7.5 13.5L10 11", "M10.5 16.5L13 14", "M12 6l6 6 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0z") }

    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (path in paths) {
            builder.addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
