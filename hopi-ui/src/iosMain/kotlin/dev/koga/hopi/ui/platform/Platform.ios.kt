package dev.koga.hopi.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

// Creating an NSDateFormatter is costly; formatting with one is thread-safe (iOS 7+), so one each.
private val seconds by lazy { timeFormatter("HH:mm:ss") }
private val millis by lazy { timeFormatter("HH:mm:ss.SSS") }

private fun timeFormatter(pattern: String) = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    dateFormat = pattern
}

internal actual fun formatTimeOfDay(epochMs: Long, withMillis: Boolean): String =
    (if (withMillis) millis else seconds).stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberShareSheet(): ShareSheet? {
    val host = LocalUIViewController.current
    return remember(host) {
        ShareSheet { _, text ->
            var presenter: UIViewController = host
            while (true) presenter = presenter.presentedViewController ?: break
            val sheet = UIActivityViewController(activityItems = listOf(capForShare(text)), applicationActivities = null)
            // On iPad the sheet is a popover and must be anchored, or UIKit raises an exception.
            sheet.popoverPresentationController?.let { popover ->
                popover.sourceView = presenter.view
                presenter.view.bounds.useContents {
                    popover.sourceRect = CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0)
                }
            }
            presenter.presentViewController(sheet, animated = true, completion = null)
        }
    }
}

/** UIKit asks the view controller for the status bar style; the host app's choice stands. */
@Composable
internal actual fun SystemBarIcons(lightBackground: Boolean) {
}
