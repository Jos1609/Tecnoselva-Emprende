package com.tecnoselva.factura.core.session

import android.app.Activity
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tecnoselva.factura.features.auth.ui.LoginActivity

fun Activity.confirmLogout() {
    MaterialAlertDialogBuilder(this)
        .setTitle("Cerrar sesión")
        .setMessage("¿Deseas salir de tu cuenta?")
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Cerrar sesión") { _, _ ->
            SessionManager(this).logout()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
        .show()
}
