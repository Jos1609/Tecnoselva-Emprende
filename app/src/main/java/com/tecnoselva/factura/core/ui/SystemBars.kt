package com.tecnoselva.factura.core.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps app content inside the safe area on gesture navigation and edge-to-edge Android versions. */
fun Activity.applySystemBarInsets(
    root: View,
    includeTop: Boolean = true,
    includeBottom: Boolean = true,
    includeIme: Boolean = false
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val initialLeft = root.paddingLeft
    val initialTop = root.paddingTop
    val initialRight = root.paddingRight
    val initialBottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = if (includeIme) insets.getInsets(WindowInsetsCompat.Type.ime()) else null
        view.setPadding(
            initialLeft + systemBars.left,
            initialTop + if (includeTop) systemBars.top else 0,
            initialRight + systemBars.right,
            initialBottom + if (includeBottom) maxOf(systemBars.bottom, ime?.bottom ?: 0) else 0
        )
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
