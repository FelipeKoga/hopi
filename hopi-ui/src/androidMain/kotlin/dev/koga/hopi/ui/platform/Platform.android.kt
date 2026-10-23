package dev.koga.hopi.ui.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// SimpleDateFormat is not thread-safe, and rows are built on Dispatchers.Default: one per thread.
private val seconds = ThreadLocal<SimpleDateFormat>()
private val millis = ThreadLocal<SimpleDateFormat>()

internal actual fun formatTimeOfDay(epochMs: Long, withMillis: Boolean): String {
    val local = if (withMillis) millis else seconds
    val formatter = local.get()
        ?: SimpleDateFormat(if (withMillis) "HH:mm:ss.SSS" else "HH:mm:ss", Locale.US).also(local::set)
    return formatter.format(Date(epochMs))
}

@Composable
internal actual fun rememberShareSheet(): ShareSheet? {
    val context = LocalContext.current
    return remember(context) {
        ShareSheet { subject, text ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, capForShare(text))
            val chooser = Intent.createChooser(send, null)
            // Started from a non-Activity context (an application context), it needs its own task.
            if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chooser)
            } catch (_: ActivityNotFoundException) {
                // No app can receive text: nothing to show, and the app keeps running.
            }
        }
    }
}

@Composable
internal actual fun SystemBarIcons(lightBackground: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, lightBackground) {
        val window = view.context.findActivity()?.window
        if (window == null || view.isInEditMode) return@DisposableEffect onDispose {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return@DisposableEffect onDispose {}
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                // API 30 reads 0 until the app has called setSystemBarsAppearance, even when its
                // theme or the legacy SYSTEM_UI_FLAG_LIGHT_* flags made the bars light; restoring
                // that 0 would leave white icons on a light bar. An empty update (mask 0) makes
                // the controller own the appearance the window already derived from those flags,
                // so the read below returns it (androidx WindowInsetsControllerCompat does the same).
                controller.setSystemBarsAppearance(0, 0)
            }
            val previous = controller.systemBarsAppearance and mask
            controller.setSystemBarsAppearance(if (lightBackground) mask else 0, mask)
            onDispose { controller.setSystemBarsAppearance(previous, mask) }
        } else {
            @Suppress("DEPRECATION") // The only way below API 30, without androidx.core.
            val decor = window.decorView
            @Suppress("DEPRECATION")
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            @Suppress("DEPRECATION")
            val previous = decor.systemUiVisibility and mask
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (decor.systemUiVisibility and mask.inv()) or (if (lightBackground) mask else 0)
            @Suppress("DEPRECATION")
            onDispose { decor.systemUiVisibility = (decor.systemUiVisibility and mask.inv()) or previous }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
