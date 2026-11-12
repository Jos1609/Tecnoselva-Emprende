package com.tecnoselva.factura.features.auth.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.databinding.ActivitySubscriptionBlockedBinding
import com.tecnoselva.factura.core.ui.applySystemBarInsets

class SubscriptionBlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBlockedBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        binding = ActivitySubscriptionBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.btnRefresh.setOnClickListener {
            sessionManager.isSubscriptionActive = true
            Toast.makeText(this, "¡Suscripción verificada y activada con éxito!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        binding.btnLogout.setOnClickListener {
            sessionManager.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
