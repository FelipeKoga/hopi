package dev.koga.hopi.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.koga.hopi.ui.theme.HopiAccent
import dev.koga.hopi.ui.theme.HopiThemeChoice
import dev.koga.hopi.ui.theme.HopiThemeMode
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ThemePickerUiTest {
    private val root: Path = Files.createTempDirectory("hopi-theme-ui")
    private val closeables = mutableListOf<AutoCloseable>()
    private val prefs = Preferences.userRoot().node("dev/koga/hopi/desktop-test-${ProcessHandle.current().pid()}")

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        root.toFile().deleteRecursively()
        prefs.removeNode()
    }

    @Test
    fun thePickerChangesTheAccentAndModeAndKeepsThem() = runComposeUiTest {
        val app = FakeApp(root.resolve("4242")).also { closeables += it }
        for (n in 1..3) app.add { completeTx(it, url = "https://api.example.com/users/$n", responseBody = textBody("""{"n":$n}""")) }
        val viewer = Viewer(jvmOnlyTargets(), jvmBridgeRoot = root).also { closeables += it }
        viewer.start()
        val theme = ThemeSettings(prefs)
        setContent { Box(Modifier.fillMaxSize()) { ViewerContent(viewer, chooseExportFile = { _, _ -> null }, theme = theme) } }
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("JVM 4242", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ViewerTags.APP_FIELD).performTextInput("dev.koga.sample")
        onNodeWithTag(ViewerTags.CONNECT).performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodes(hasText("/users/3", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(ThemeTags.BUTTON).performClick()
        onNodeWithTag(ThemeTags.accent(HopiAccent.Violet)).performClick()
        onNodeWithTag(ThemeTags.mode(HopiThemeMode.Dark)).performClick()
        waitForIdle()
        assertEquals(HopiThemeChoice(HopiAccent.Violet, HopiThemeMode.Dark), theme.choice)
        screenshot("theme-picker-violet-dark.png")
        onNodeWithTag(ThemeTags.accent(HopiAccent.Blue)).performClick()
        onNodeWithTag(ThemeTags.mode(HopiThemeMode.Light)).performClick()
        waitForIdle()
        screenshot("theme-picker-blue-light.png")

        // Kept: a new settings object over the same preferences reads the choice back.
        assertEquals(HopiThemeChoice(HopiAccent.Blue, HopiThemeMode.Light), ThemeSettings(prefs).choice)
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val directory = System.getProperty("hopi.desktop.screenshots") ?: return
        Files.createDirectories(Paths.get(directory))
        // The popover is a root of its own over the window's: draw each root, in order, onto one image.
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes().indices.map { onAllNodes(isRoot())[it].captureToImage().toAwtImage() }
        val image = BufferedImage(roots[0].width, roots[0].height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { roots.forEach { drawImage(it, 0, 0, null) }; dispose() }
        ImageIO.write(image, "png", Paths.get(directory, name).toFile())
    }
}
