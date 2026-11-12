package com.tecnoselva.factura.features.auth.ui

import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.features.auth.data.AuthService
import com.tecnoselva.factura.databinding.ActivityLoginBinding
import com.tecnoselva.factura.core.ui.applySystemBarInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager
    private val authService = AuthService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        if (sessionManager.isLoggedIn) {
            if (sessionManager.isSubscriptionActive) {
                startActivity(Intent(this, HomeActivity::class.java))
            } else {
                startActivity(Intent(this, SubscriptionBlockedActivity::class.java))
            }
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Keep the scrollable form above the IME when the keyboard is open.
        applySystemBarInsets(binding.root, includeIme = true)

        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // Wait for the keyboard's resize/insets pass, then bring the primary action
                // into the visible area so the user can submit without dismissing the IME.
                binding.root.postDelayed({ revealLoginButton() }, 180L)
            }
        }

        binding.btnStartLocalAccount.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor ingrese correo y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "Iniciando sesión en Supabase..."

            thread {
                val result = authService.signIn(email, password)
                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = "Iniciar Sesión"

                    if (!result.success) {
                        Toast.makeText(this, result.error ?: "Error de autenticación", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    sessionManager.isLoggedIn = true
                    sessionManager.accessToken = result.accessToken ?: ""
                    sessionManager.refreshToken = result.refreshToken ?: ""
                    sessionManager.userEmail = result.email ?: email
                    sessionManager.currentUserId = result.userId ?: ""
                    authService.setAccessToken(sessionManager.accessToken)
                    sessionManager.businessName = result.businessName ?: email
                    sessionManager.issuerRuc = result.issuerRuc.orEmpty()
                    sessionManager.issuerLegalName = result.issuerLegalName.orEmpty()
                    sessionManager.issuerAddress = result.issuerAddress.orEmpty()
                    sessionManager.issuerCity = result.issuerCity.orEmpty()
                    sessionManager.issuerLogoUrl = result.issuerLogoUrl.orEmpty()
                    sessionManager.issuerSlogan = result.issuerSlogan.orEmpty()
                    sessionManager.issuerPhone = result.issuerPhone.orEmpty()
                    sessionManager.storeId = result.storeId ?: ""
                    sessionManager.isSubscriptionActive = result.isSubscriptionActive

                    if (sessionManager.isSubscriptionActive) {
                        Toast.makeText(this, "¡Conectado a AdminStore / Supabase!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, HomeActivity::class.java))
                    } else {
                        Toast.makeText(this, "Acceso restringido: Mensualidad vencida", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, SubscriptionBlockedActivity::class.java))
                    }
                    finish()
                }
            }
        }
    }

    private fun revealLoginButton(retriesRemaining: Int = 6) {
        if (!binding.etPassword.hasFocus()) return
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
        if (rootInsets == null || !rootInsets.isVisible(WindowInsetsCompat.Type.ime())) {
            if (retriesRemaining > 0) {
                binding.root.postDelayed({ revealLoginButton(retriesRemaining - 1) }, 50L)
            }
            return
        }

        val buttonBounds = Rect()
        binding.root.offsetDescendantRectToMyCoords(binding.btnLogin, buttonBounds)
        val visibleBottom = binding.root.height - binding.root.paddingBottom
        val bottomGap = (16 * resources.displayMetrics.density).toInt()
        val requiredScroll = buttonBounds.bottom - visibleBottom + bottomGap
        if (requiredScroll > 0) {
            binding.root.smoothScrollTo(0, binding.root.scrollY + requiredScroll)
        }
    }
}
