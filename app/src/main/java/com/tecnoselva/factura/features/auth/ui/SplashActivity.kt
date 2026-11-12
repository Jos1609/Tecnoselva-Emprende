package com.tecnoselva.factura.features.auth.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.session.SessionManager
import com.tecnoselva.factura.features.home.ui.HomeActivity
import com.tecnoselva.factura.core.session.AppSecurityManager
import com.tecnoselva.factura.features.local.data.LocalStoreProfileRepository
import com.tecnoselva.factura.features.settings.ui.AppLockActivity

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val openApp = Runnable { routeToNextScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        playBrandAnimation()
        handler.postDelayed(openApp, SPLASH_DURATION_MS)
    }

    private fun playBrandAnimation() {
        val logo = findViewById<View>(R.id.splashLogoCard)
        val title = findViewById<View>(R.id.splashTitle)
        val subtitle = findViewById<View>(R.id.splashSubtitle)

        val logoEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.72f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.72f, 1f)
            )
            duration = 650L
            interpolator = OvershootInterpolator(0.9f)
        }
        val copyEntrance = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, 22f, 0f),
                ObjectAnimator.ofFloat(subtitle, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(subtitle, View.TRANSLATION_Y, 22f, 0f)
            )
            duration = 480L
            startDelay = 260L
            interpolator = DecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(logoEntrance, copyEntrance)
            start()
        }
    }

    private fun routeToNextScreen() {
        val session = SessionManager(this)
        val localProfileExists = LocalStoreProfileRepository(this).getProfile() != null
        val security = AppSecurityManager(this)
        val destination = when {
            !session.isLoggedIn && !localProfileExists -> LoginActivity::class.java
            security.hasAnyLock -> AppLockActivity::class.java
            session.isSubscriptionActive -> HomeActivity::class.java
            else -> SubscriptionBlockedActivity::class.java
        }
        startActivity(Intent(this, destination))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(openApp)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 1650L
    }
}
