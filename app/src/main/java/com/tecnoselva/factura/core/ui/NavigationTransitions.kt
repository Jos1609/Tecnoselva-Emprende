package com.tecnoselva.factura.core.ui

import android.app.Activity
import com.tecnoselva.factura.R

@Suppress("DEPRECATION")
fun Activity.applyNavigationTransition() {
    overridePendingTransition(R.anim.navigation_fade_in, R.anim.navigation_fade_out)
}
