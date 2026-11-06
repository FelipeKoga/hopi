package dev.koga.hopi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.koga.hopi.core.model.TransactionState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalTestApi::class)
class ViewerUiTest {
    private val root: Path = Files.createTempDirectory("hopi-viewer-ui")
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    private fun SemanticsNodeInteractionsProvider.exists(text: String, substring: Boolean = true): Boolean =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = true) {
        waitUntil(timeoutMillis = 15_000) { exists(text, substring) }
    }

    private fun KeyInjectionScope.command(key: Key) = withKeyDown(if (IS_MAC) Key.MetaLeft else Key.CtrlLeft) { pressKey(key) }

    private fun ComposeUiTest.viewer(exportTo: Path = root.resolve("export.har")): Viewer {
        // Bodies on demand, as with full bodies off: this test checks what crosses only when opened.
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).also { closeables += it; it.setFullBodies(false) }
        viewer.start()
        setContent {
            // The test window's own size (1024 x 768): wide enough for list and detail side by side.
            Box(Modifier.fillMaxSize()) {
                ViewerContent(viewer, chooseExportFile = { _, _ -> exportTo })
            }
        }
        return viewer
    }

    @Test
    fun connectsStreamsSelectsFromTheKeyboardExportsAndClears() = runComposeUiTest {
        val app = FakeApp(root.resolve("4242")).also { closeables += it }
        for (n in 1..3) app.add { completeTx(it, url = "https://api.example.com/users/$n", responseBody = textBody("""{"n":$n,"name":"Ada"}""")) }
        app.add { pendingTx(it, method = "POST", url = "https://api.example.com/orders", body = textBody("""{"qty":1}""")) }
        val export = root.resolve("session.har")
        val viewer = viewer(export)

        waitForText("Not connected")
        waitForText("JVM 4242")
        // A valid id typed for a listed target: no hint (the picker once said "no device" here).
        onNodeWithTag(ViewerTags.APP_FIELD).performTextInput(APP)
        waitForIdle()
        assertFalse(exists("no device or bridge found"))
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitForText("Connected to $APP (jvm)")
        waitForText("/users/3")
        waitForText("4 requests · 1 pending")

        // ↓ selects the newest row; only its body is fetched.
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { pressKey(Key.DirectionDown) }
        val session = viewer.session.value!!
        waitUntil(timeoutMillis = 15_000) { session.store.value.get(4)?.requestBody?.isNotFetched == false }
        for (id in 1L..3L) assertTrue(session.store.value.get(id)!!.responseBody!!.isNotFetched, "id $id was fetched")
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { pressKey(Key.DirectionDown) }
        waitUntil(timeoutMillis = 15_000) { session.store.value.get(3)?.responseBody?.isNotFetched == false }
        waitForText("GET 200")
        screenshot("viewer-connected.png")

        // ⌘E exports the session as HAR, fetching the other bodies.
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { command(Key.E) }
        waitForText("Exported 4 requests to session.har")
        val har = Json.parseToJsonElement(Files.readString(export)).jsonObject
        assertEquals(emptyList(), validateHar(har))
        val texts = har["log"]!!.jsonObject["entries"]!!.jsonArray.map { it.jsonObject["response"]!!.jsonObject["content"]!!.jsonObject["text"]?.jsonPrimitive?.content }
        assertEquals("""{"n":1,"name":"Ada"}""", texts.first(), "a body nobody opened was fetched for the export")

        // Esc clears the selection; ⌘K clears the app's store and the viewer's.
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { pressKey(Key.Escape) }
        waitForText("No request selected")
        onNodeWithTag(ViewerTags.ROOT).performKeyInput { command(Key.K) }
        waitForText("0 requests")
        assertEquals(emptyList(), app.store.since(0))
        assertFalse(exists("/users/3"))

        // A request made now arrives live.
        app.add { completeTx(it, url = "https://api.example.com/live") }
        waitForText("/live")
        assertEquals(TransactionState.Complete, session.store.value.transactions.value.single().state)
    }

    @Test
    fun theStatusBarFollowsTheAppStoppingAndStartingAgain() = runComposeUiTest {
        val directory = root.resolve("7")
        val first = FakeApp(directory)
        first.add { completeTx(it) }
        viewer()
        waitForText("JVM 7")
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitForText("Connected to $APP")

        first.close()
        waitForText("App stopped: reconnects when the process starts")
        screenshot("viewer-app-stopped.png")

        FakeApp(directory).also { closeables += it }.add { completeTx(it, url = "https://api.example.com/again") }
        waitForText("Connected to $APP")
        waitForText("/again")
        waitForText("1 request")
    }

    @Test
    fun anAndroidOrIosTargetNeedsAValidAppId() {
        val device = AndroidDevice("emulator-5554", "device", "Pixel", bridges = emptyList())
        assertTrue(targetProblem(device, null)!!.startsWith("enter the app's id"))
        assertTrue(targetProblem(device, "-rf")!!.contains("not an application id"))
        assertTrue(targetProblem(device, "../x") != null)
        assertEquals(null, targetProblem(device, "dev.koga.sample:remote"))
        assertEquals(null, targetProblem(JvmBridge(Paths.get("/tmp/x/bridge.json")), null))
    }

    @Test
    fun aTargetStaysTheSameWhileAdbUpdatesItsDevice() {
        // The picker's device after a `track-devices` update or an app restart: other model, other bridges.
        val connected = Target(AndroidDevice("emulator-5554", "device", "Pixel", bridges = listOf("dev.koga.sample")), "dev.koga.sample")
        val updated = Target(AndroidDevice("emulator-5554", "device", null, bridges = emptyList()), "dev.koga.sample")
        assertTrue(connected.sameAs(updated))
        assertFalse(connected.sameAs(updated.copy(app = "dev.koga.other")))
        assertFalse(connected.sameAs(Target(AndroidDevice("emulator-5556", "device", "Pixel", emptyList()), "dev.koga.sample")))
        assertFalse(connected.sameAs(null))
    }

    /** Writes the composed window content where the build's test task says (build/reports/screenshots). */
    private fun ComposeUiTest.screenshot(name: String) {
        val directory = System.getProperty("hopi.desktop.screenshots") ?: return
        val image = onRoot().captureToImage().toAwtImage()
        Files.createDirectories(Paths.get(directory))
        ImageIO.write(image, "png", Paths.get(directory, name).toFile())
    }
}
