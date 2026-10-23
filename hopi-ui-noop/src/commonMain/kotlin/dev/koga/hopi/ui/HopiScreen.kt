package dev.koga.hopi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The noop twin of `hopi-ui`'s inspector screen: it renders nothing and never calls [onClose]. */
@Composable
public fun HopiScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {}
