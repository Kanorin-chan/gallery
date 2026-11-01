package dev.kanorin.gallery.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class Kept<T>(var value: T)

/** [value], or the last non-null one while it's null: a grid keeps drawing its layout while the next query loads. */
@Composable
internal fun <T : Any> lastNonNull(value: T?, initial: T): T {
    val kept = remember { Kept(initial) }
    if (value != null) kept.value = value
    return kept.value
}
