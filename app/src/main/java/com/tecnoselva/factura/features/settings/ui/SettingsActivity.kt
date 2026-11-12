package com.tecnoselva.factura.features.settings.ui

import android.os.Bundle
import android.os.Build
import android.app.KeyguardManager
import android.os.CancellationSignal
import android.view.WindowManager
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.AppSecurityManager
import com.tecnoselva.factura.core.session.confirmLogout
import com.tecnoselva.factura.features.home.ui.AppNavigationDrawer
import com.tecnoselva.factura.databinding.ActivitySettingsBinding
import com.tecnoselva.factura.databinding.DialogPinSetupBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var security: AppSecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        AppNavigationDrawer.setContentView(this, binding.root, AppNavigationDrawer.Section.SETTINGS)
        security = AppSecurityManager(this)
        refreshSecurityUi()
        binding.btnSettingsPin.setOnClickListener { showPinDialog() }
        binding.btnSettingsBiometric.setOnClickListener { configureBiometric() }
        binding.btnSettingsLogout.setOnClickListener { confirmLogout() }
    }

    private fun refreshSecurityUi() {
        binding.tvSecurityStatus.text = when {
            security.pinEnabled && security.biometricEnabled -> "PIN y huella activos"
            security.pinEnabled -> "Bloqueo con PIN activo"
            else -> "Protección desactivada"
        }
        binding.tvSecurityDescription.text = when {
            security.pinEnabled && security.biometricEnabled -> "Tu app está protegida. El PIN queda como respaldo."
            security.pinEnabled -> "PIN listo. Puedes activar la huella para acceder más rápido."
            else -> "Registra un PIN para proteger el acceso y habilitar la huella."
        }
        binding.tvPinStatus.text = if (security.pinEnabled) "Configurado · respaldo listo" else "Obligatorio para usar huella"
        binding.tvBiometricStatus.text = when {
            security.biometricEnabled -> "Activa en este dispositivo"
            security.pinEnabled -> "Disponible después de confirmar identidad"
            else -> "Requiere un PIN de seguridad"
        }
        binding.btnSettingsPin.text = if (security.pinEnabled) "Cambiar PIN" else "Configurar PIN"
        binding.btnSettingsBiometric.text = if (security.biometricEnabled) "Desactivar huella digital" else "Activar huella digital"
    }

    private fun showPinDialog(onSaved: (() -> Unit)? = null) {
        val pinBinding = DialogPinSetupBinding.inflate(layoutInflater)
        val isPinChange = security.pinEnabled
        pinBinding.tvPinDialogTitle.text = if (isPinChange) "Cambia tu PIN" else "Crea tu PIN"
        pinBinding.tvPinDialogSubtitle.text = if (isPinChange) {
            "Elige seis dígitos para reemplazar el PIN actual."
        } else {
            "Elige seis dígitos. Será requisito para activar la huella."
        }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(pinBinding.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            pinBinding.btnCancelPin.setOnClickListener { dialog.dismiss() }
            val digits = StringBuilder(6)
            var firstPin: String? = null

            fun renderDots(clearError: Boolean = true) {
                if (clearError) pinBinding.tvPinError.visibility = View.INVISIBLE
                pinBinding.pinDots.removeAllViews()
                repeat(6) { index ->
                    val dot = View(this).apply {
                        background = getDrawable(
                            if (index < digits.length) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty
                        )
                    }
                    val dotSize = dp(14)
                    pinBinding.pinDots.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        marginStart = dp(7)
                        marginEnd = dp(7)
                    })
                }
            }

            fun addKey(label: String, row: Int, column: Int, deletes: Boolean = false) {
                val spacer = label.isBlank() && !deletes
                val key: View = if (spacer) {
                    View(this)
                } else if (deletes) {
                    ImageView(this).apply {
                        setImageResource(R.drawable.ic_backspace)
                        imageTintList = getColorStateList(R.color.brand_text_secondary)
                        contentDescription = "Borrar último dígito"
                        scaleType = ImageView.ScaleType.CENTER
                    }
                } else {
                    TextView(this).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = 25f
                        setTextColor(getColor(R.color.brand_text_primary))
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        contentDescription = label
                    }
                }
                if (!spacer) {
                    key.isClickable = true
                    key.isFocusable = true
                    key.setBackgroundResource(R.drawable.bg_pin_key)
                }
                if (!spacer) key.setOnClickListener {
                    if (deletes) {
                        if (digits.isNotEmpty()) digits.deleteCharAt(digits.lastIndex)
                        renderDots()
                        return@setOnClickListener
                    }
                    if (digits.length >= 6) return@setOnClickListener
                    digits.append(label)
                    renderDots()
                    if (digits.length == 6) {
                        val entered = digits.toString()
                        val savedPin = firstPin
                        if (savedPin == null) {
                            firstPin = entered
                            digits.clear()
                            pinBinding.tvPinDialogStep.text = "CONFIGURACIÓN SEGURA  ·  2 DE 2"
                            pinBinding.tvPinDialogTitle.text = "Confirma tu PIN"
                            pinBinding.tvPinDialogSubtitle.text = "Vuelve a ingresar los seis dígitos."
                            renderDots()
                        } else if (entered == savedPin) {
                            security.savePin(entered)
                            refreshSecurityUi()
                            dialog.dismiss()
                            Snackbar.make(binding.root, if (isPinChange) "PIN actualizado." else "PIN guardado de forma segura.", Snackbar.LENGTH_SHORT).show()
                            onSaved?.let { continueSetup -> binding.root.post { continueSetup() } }
                        } else {
                            firstPin = null
                            digits.clear()
                            pinBinding.tvPinDialogStep.text = "CONFIGURACIÓN SEGURA  ·  1 DE 2"
                            pinBinding.tvPinDialogTitle.text = if (isPinChange) "Cambia tu PIN" else "Crea tu PIN"
                            pinBinding.tvPinDialogSubtitle.text = "Vuelve a ingresar seis dígitos para confirmarlo."
                            pinBinding.tvPinError.visibility = View.VISIBLE
                            pinBinding.tvPinError.text = "No coincide. Inténtalo de nuevo."
                            renderDots(clearError = false)
                        }
                    }
                }
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row), GridLayout.spec(column, 1f)
                ).apply {
                    width = 0
                    height = dp(58)
                    setMargins(dp(3), dp(2), dp(3), dp(2))
                }
                pinBinding.pinKeypad.addView(key, params)
            }

            renderDots()
            (1..9).forEach { value ->
                val index = value - 1
                addKey(value.toString(), index / 3, index % 3)
            }
            addKey("", 3, 0)
            addKey("0", 3, 1)
            addKey("", 3, 2, deletes = true)
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun configureBiometric() {
        if (security.biometricEnabled) {
            security.disableBiometric()
            refreshSecurityUi()
            return
        }
        if (!security.pinEnabled) {
            showPinDialog(onSaved = { activateBiometric() })
            return
        }
        activateBiometric()
    }

    private fun activateBiometric() {
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
            .setTitle("Activar huella digital")
            .setSubtitle("Confirma tu identidad para proteger la app")
            .setNegativeButton("Cancelar", mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                if (!security.enableBiometric()) {
                    Snackbar.make(binding.root, "Configura un PIN antes de activar la huella.", Snackbar.LENGTH_LONG).show()
                    return
                }
                runOnUiThread {
                    refreshSecurityUi()
                    Snackbar.make(binding.root, "Huella digital activada.", Snackbar.LENGTH_SHORT).show()
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Snackbar.make(binding.root, errString, Snackbar.LENGTH_LONG).show()
            }
        })
    }
}
