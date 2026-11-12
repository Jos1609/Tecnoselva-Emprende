package com.tecnoselva.factura.features.settings.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.graphics.Typeface
import android.view.View
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.AppSecurityManager
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.core.ui.applySystemBarInsets
import com.tecnoselva.factura.databinding.ActivityAppLockBinding
import com.tecnoselva.factura.features.auth.ui.SubscriptionBlockedActivity
import com.tecnoselva.factura.features.home.ui.HomeActivity

class AppLockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppLockBinding
    private lateinit var security: AppSecurityManager
    private var biometricCancellation: CancellationSignal? = null
    private val pinDigits = StringBuilder(6)
    private var showPinError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        security = AppSecurityManager(this)
        if (!security.hasAnyLock) {
            openApp()
            return
        }
        binding = ActivityAppLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.btnUnlockBiometric.visibility = if (security.biometricEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) View.VISIBLE else View.GONE
        binding.btnUnlockBiometric.setOnClickListener { authenticateBiometric() }
        renderPinDots()
        setupPinKeypad()
        if (!security.pinEnabled && security.biometricEnabled) authenticateBiometric()
    }

    private fun unlockWithPin() {
        if (pinDigits.length < 4) return
        if (security.verifyPin(pinDigits.toString())) {
            openApp()
        } else if (pinDigits.length == 6) {
            pinDigits.clear()
            showPinError = true
            renderPinDots()
        }
    }

    private fun setupPinKeypad() {
        (1..9).forEach { number ->
            val index = number - 1
            addPinKey(number.toString(), index / 3, index % 3)
        }
        addPinKey("", 3, 0)
        addPinKey("0", 3, 1)
        addPinKey("", 3, 2, isDelete = true)
    }

    private fun addPinKey(label: String, row: Int, column: Int, isDelete: Boolean = false) {
        val spacer = label.isBlank() && !isDelete
        val key: View = when {
            spacer -> View(this)
            isDelete -> ImageView(this).apply {
                setImageResource(R.drawable.ic_backspace)
                imageTintList = getColorStateList(R.color.brand_text_secondary)
                contentDescription = "Borrar último dígito"
                scaleType = ImageView.ScaleType.CENTER
            }
            else -> TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 25f
                setTextColor(getColor(R.color.brand_text_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                contentDescription = label
            }
        }
        if (!spacer) {
            key.isClickable = true
            key.isFocusable = true
            key.setBackgroundResource(R.drawable.bg_pin_key)
            key.setOnClickListener {
                if (isDelete) {
                    if (pinDigits.isNotEmpty()) pinDigits.deleteCharAt(pinDigits.lastIndex)
                    showPinError = false
                } else if (pinDigits.length < 6) {
                    pinDigits.append(label)
                    showPinError = false
                    unlockWithPin()
                }
                renderPinDots()
            }
        }
        binding.pinKeypad.addView(key, GridLayout.LayoutParams(
            GridLayout.spec(row), GridLayout.spec(column, 1f)
        ).apply {
            width = 0
            height = dp(54)
            setMargins(dp(3), dp(2), dp(3), dp(2))
        })
    }

    private fun renderPinDots() {
        binding.pinDots.removeAllViews()
        repeat(6) { index ->
            val dot = View(this).apply {
                background = getDrawable(
                    if (index < pinDigits.length) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty
                )
            }
            binding.pinDots.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            })
        }
        binding.tvPinError.visibility = if (showPinError) View.VISIBLE else View.INVISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun authenticateBiometric() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Snackbar.make(binding.root, "La huella requiere Android 9 o superior.", Snackbar.LENGTH_LONG).show()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardSecure) {
            Snackbar.make(binding.root, "Configura primero una huella o bloqueo de pantalla en tu equipo.", Snackbar.LENGTH_LONG).show()
            return
        }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Desbloquear TECNOSELVA EMPRENDE")
            .setSubtitle("Confirma tu identidad")
            .setNegativeButton("Usar PIN", mainExecutor) { _, _ -> }
            .build()
        val cancellation = CancellationSignal()
        biometricCancellation = cancellation
        prompt.authenticate(cancellation, mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { openApp() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(this@AppLockActivity, errString, Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun openApp() {
        val destination = if (SessionManager(this).isLoggedIn && !SessionManager(this).isSubscriptionActive) {
            SubscriptionBlockedActivity::class.java
        } else HomeActivity::class.java
        startActivity(Intent(this, destination).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        finish()
    }

    override fun onDestroy() {
        biometricCancellation?.cancel()
        super.onDestroy()
    }
}
