package dev.koga.hopi.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A platform-only diagnostic (Android's bridge self-check): a button label and the work it runs. */
public class PlatformAction(public val label: String, public val run: suspend () -> String)

/**
 * Which Hopi this process runs: the Diagnostics caption says it, and the evidence lines carry it
 * (`"build":"real"` or `"nohopi"`, `"real":true` or `false`). On iOS the `hopi.enabled` property
 * decides whether the framework links the runtimes, so the generated [HOPI_ENABLED] says which. On
 * Android the dev.koga.hopi plugin decides per variant whatever the property says (the runtimes in
 * debug, none in release and with `-Psample.hopiCapture=false`), so the activity reports what runs
 * instead.
 */
public object SampleBuild {
    /**
     * The network status of `hopi-api`'s runtime registry (`networkStarted`), set by the Android
     * activity before the UI: in this app capture runs exactly when the plugin gave the variant
     * Hopi's runtimes, in debug. Null on iOS.
     */
    public var androidCaptureStarted: Boolean? = null

    /** True when Hopi's runtimes run (Android: capture started; iOS: built with the flag). */
    internal val realHopi: Boolean get() = androidCaptureStarted ?: HOPI_ENABLED

    /** The Diagnostics caption: which Hopi runs and what selected it. */
    internal val caption: String
        get() = when (androidCaptureStarted) {
            true -> "Hopi: runtimes (debug build: the dev.koga.hopi plugin adds them)"
            false -> "Hopi: no runtime (release build, or -Psample.hopiCapture=false)"
            null -> if (HOPI_ENABLED) "Hopi: runtimes (built with -Phopi.enabled=true)" else "Hopi: no runtime (built without -Phopi.enabled=true)"
        }
}

/**
 * The sample app both platforms host: a small shop (item list, item detail, a form that POSTs a
 * new item) against the dev server, and a diagnostics tab that fires one request of each kind the
 * inspector must handle and opens the perf lab. [platformActions] add buttons to the diagnostics
 * tab; [launchAction] runs once at launch and opens that tab with its result. [perfVerify] (`all`
 * or a comma list of the lab's steps) opens the perf lab at launch and runs those injections once
 * ([PerfLabRun]).
 */
@Composable
public fun SampleApp(platformActions: List<PlatformAction> = emptyList(), launchAction: PlatformAction? = null, perfVerify: String? = null) {
    SampleTheme {
        Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
            SampleContent(platformActions, launchAction, perfVerify?.let(::parsePerfLabSteps))
        }
    }
}

private sealed interface Route {
    data object Items : Route
    data class Detail(val id: Long) : Route
    data object NewItem : Route
    data object Diagnostics : Route
    data object PerfLab : Route
}

/** Saves the route across Android configuration changes and process death as one string. */
private val RouteSaver = Saver<Route, String>(
    save = { route ->
        when (route) {
            Route.Items -> "items"
            is Route.Detail -> "detail:${route.id}"
            Route.NewItem -> "new"
            Route.Diagnostics -> "diagnostics"
            Route.PerfLab -> "perflab"
        }
    },
    restore = { saved ->
        when {
            saved == "new" -> Route.NewItem
            saved == "diagnostics" -> Route.Diagnostics
            saved == "perflab" -> Route.PerfLab
            saved.startsWith("detail:") -> saved.removePrefix("detail:").toLongOrNull()?.let(Route::Detail) ?: Route.Items
            else -> Route.Items
        }
    },
)

@Composable
private fun SampleContent(platformActions: List<PlatformAction>, launchAction: PlatformAction?, perfVerify: Set<String>?) {
    var route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf(
            when {
                perfVerify != null -> Route.PerfLab
                launchAction != null -> Route.Diagnostics
                else -> Route.Items
            },
        )
    }
    // The launch's perf injections run once, the first time the lab is shown.
    var pendingPerfVerify by remember { mutableStateOf(perfVerify) }
    // Bumped after a create, so the list loads again when it is shown.
    var itemsVersion by rememberSaveable { mutableIntStateOf(0) }
    val diagnostics = remember { mutableStateListOf<String>() }
    if (launchAction != null) {
        LaunchedEffect(Unit) { diagnostics.add(launchAction.run()) }
    }

    // ui-backhandler's BackHandler: a detail goes back to the list, any other tab to the list.
    @Suppress("DEPRECATION")
    BackHandler(enabled = route != Route.Items) {
        route = Route.Items
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val current = route) {
                Route.Items -> ItemsScreen(itemsVersion, onOpen = { route = Route.Detail(it) })
                is Route.Detail -> ItemDetailScreen(current.id, onBack = { route = Route.Items })
                Route.NewItem -> NewItemScreen(onCreated = {
                    itemsVersion++
                    route = Route.Detail(it)
                })
                Route.Diagnostics -> DiagnosticsScreen(platformActions, diagnostics, onPerfLab = { route = Route.PerfLab })
                Route.PerfLab -> PerfLabScreen(pendingPerfVerify, onVerifyStarted = { pendingPerfVerify = null }, onBack = { route = Route.Diagnostics })
            }
        }
        TabBar(
            selected = when (route) {
                Route.Items, is Route.Detail -> 0
                Route.NewItem -> 1
                Route.Diagnostics, Route.PerfLab -> 2
            },
            onSelect = { index -> route = listOf(Route.Items, Route.NewItem, Route.Diagnostics)[index] },
        )
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
        listOf("Items", "New item", "Diagnostics").forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RowShape)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = TextStyle(
                        color = if (isSelected) colors.accent else colors.muted,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
            }
        }
    }
}

/** What a screen that loads one thing shows: progress, the error with a retry, or the value. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
}

/** Loads [block] whenever [key] or the retry count changes; cancelled when the screen leaves. */
@Composable
private fun <T> rememberLoad(key: Any, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, attempt) {
        state = Load.Loading
        state = try {
            Load.Loaded(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.describe())
        }
    }
    return state to { attempt++ }
}

@Composable
private fun ItemsScreen(version: Int, onOpen: (Long) -> Unit) {
    val (state, retry) = rememberLoad(version) { SampleHttp.api.items() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Items", actions = { SampleButton("Reload", retry, primary = false) })
        when (state) {
            Load.Loading -> Centered { Caption("Loading…") }
            is Load.Failed -> Failure(state.message, retry)
            is Load.Loaded -> if (state.value.isEmpty()) {
                Centered { Caption("No items yet. Add one in “New item”.") }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.value, key = { it.id }) { item ->
                        ItemRow(item, onClick = { onOpen(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ItemSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RowShape)
            .border(1.dp, colors.border, RowShape)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Body(item.title, modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.size(12.dp))
        Body(formatPrice(item.price), color = colors.muted)
    }
}

@Composable
private fun ItemDetailScreen(id: Long, onBack: () -> Unit) {
    val (state, retry) = rememberLoad(id) { SampleHttp.api.item(id) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Item #$id", onBack = onBack)
        when (state) {
            Load.Loading -> Centered { Caption("Loading…") }
            is Load.Failed -> Failure(state.message, retry)
            is Load.Loaded -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Title(state.value.title)
                Body(formatPrice(state.value.price), color = colors.accent)
                Body(state.value.description.ifEmpty { "No description." })
            }
        }
    }
}

@Composable
private fun NewItemScreen(onCreated: (Long) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var showErrors by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val parsedPrice = price.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val titleError = if (title.isBlank()) "Enter a title." else null
    val priceError = if (parsedPrice == null) "Enter a price, like 19.90." else null

    Column(Modifier.fillMaxSize()) {
        TopBar("New item")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Field("Title", title, { title = it }, error = titleError.takeIf { showErrors })
            Field("Price", price, { price = it }, keyboardType = KeyboardType.Decimal, error = priceError.takeIf { showErrors })
            Field("Description", description, { description = it }, singleLine = false)
            failure?.let { Caption("Could not create the item: $it", color = colors.error) }
            SampleButton(
                if (submitting) "Creating…" else "Create item",
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showErrors = true
                    if (titleError != null || parsedPrice == null) return@SampleButton
                    submitting = true
                    failure = null
                    scope.launch {
                        try {
                            val created = SampleHttp.api.create(ItemDraft(title.trim(), parsedPrice, description.trim()))
                            title = ""
                            price = ""
                            description = ""
                            showErrors = false
                            onCreated(created.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failure = e.describe()
                        } finally {
                            submitting = false
                        }
                    }
                },
            )
            Caption("POSTs JSON to /items with an Authorization header, which Hopi shows redacted.")
        }
    }
}

@Composable
private fun DiagnosticsScreen(platformActions: List<PlatformAction>, results: MutableList<String>, onPerfLab: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        TopBar("Diagnostics")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Caption(SampleBuild.caption)
            SampleButton("Fire requests", modifier = Modifier.fillMaxWidth(), onClick = {
                results.clear()
                scope.launch { fireRequests(SampleHttp.client) { results.add(it) } }
            })
            SampleButton("Slow request (60 s)", primary = false, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { results.add(slowRequest(SampleHttp.client, seconds = 60)) }
            })
            SampleButton("Create 500 items", primary = false, modifier = Modifier.fillMaxWidth(), onClick = {
                results.clear()
                // Logged too: DeviceViewerUiTest reads it, and the screen shows it below the fold.
                scope.launch { results.add(createItems(SampleHttp.api, count = 500).also(::sampleLog)) }
            })
            SampleButton("Giant JSON (1, 5, 20 MB)", primary = false, modifier = Modifier.fillMaxWidth(), onClick = {
                results.clear()
                scope.launch { results.add(runGiant(SampleHttp.client, GiantSpec.parse("mb=1,5,20;runs=1"))) }
            })
            SampleButton("Giant JSON (8 MB)", primary = false, modifier = Modifier.fillMaxWidth(), onClick = {
                results.clear()
                scope.launch { results.add(runGiant(SampleHttp.client, GiantSpec.parse("mb=8;runs=1"))) }
            })
            SampleButton("Perf lab (jank, hangs, CPU, memory)", primary = false, modifier = Modifier.fillMaxWidth(), onClick = onPerfLab)
            platformActions.forEach { action ->
                SampleButton(action.label, primary = false, modifier = Modifier.fillMaxWidth(), onClick = {
                    results.clear()
                    scope.launch { results.add(action.run()) }
                })
            }
            FrameTicker()
            results.forEach { Body(it) }
        }
    }
}

@Composable
private fun Failure(message: String, retry: () -> Unit) {
    Centered {
        Body(message, color = colors.error)
        Spacer(Modifier.size(12.dp))
        SampleButton("Try again", retry, primary = false)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private val RowShape = RoundedCornerShape(10.dp)

/** A price with two decimals, without platform formatting APIs (common code has none). */
internal fun formatPrice(value: Double): String {
    val cents = kotlin.math.round(value * 100).toLong()
    val whole = cents / 100
    val fraction = (cents % 100).toString().padStart(2, '0')
    return "$$whole.$fraction"
}
