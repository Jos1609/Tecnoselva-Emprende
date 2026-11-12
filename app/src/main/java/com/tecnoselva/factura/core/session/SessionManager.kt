package com.tecnoselva.factura.core.session

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tecnoselva_factura_prefs", Context.MODE_PRIVATE)

    var isLoggedIn: Boolean
        get() = prefs.getBoolean("is_logged_in", false)
        set(value) = prefs.edit().putBoolean("is_logged_in", value).apply()

    var businessName: String
        get() = prefs.getString("business_name", "Mi Bodega / Tienda") ?: "Mi Bodega"
        set(value) = prefs.edit().putString("business_name", value).apply()

    var issuerRuc: String
        get() = prefs.getString("issuer_ruc", "") ?: ""
        set(value) = prefs.edit().putString("issuer_ruc", value).apply()

    var issuerLegalName: String
        get() = prefs.getString("issuer_legal_name", "") ?: ""
        set(value) = prefs.edit().putString("issuer_legal_name", value).apply()

    var issuerAddress: String
        get() = prefs.getString("issuer_address", "") ?: ""
        set(value) = prefs.edit().putString("issuer_address", value).apply()

    var issuerCity: String
        get() = prefs.getString("issuer_city", "") ?: ""
        set(value) = prefs.edit().putString("issuer_city", value).apply()

    var issuerLogoUrl: String
        get() = prefs.getString("issuer_logo_url", "") ?: ""
        set(value) = prefs.edit().putString("issuer_logo_url", value).apply()

    var issuerSlogan: String
        get() = prefs.getString("issuer_slogan", "") ?: ""
        set(value) = prefs.edit().putString("issuer_slogan", value).apply()

    var issuerPhone: String
        get() = prefs.getString("issuer_phone", "") ?: ""
        set(value) = prefs.edit().putString("issuer_phone", value).apply()

    var storeId: String
        get() = prefs.getString("store_id", "") ?: ""
        set(value) = prefs.edit().putString("store_id", value).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    fun updateTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .apply()
    }

    var userEmail: String
        get() = prefs.getString("user_email", "") ?: ""
        set(value) = prefs.edit().putString("user_email", value).apply()

    var currentUserId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(value) = prefs.edit().putString("user_id", value).apply()

    var isSubscriptionActive: Boolean
        get() = prefs.getBoolean("is_subscription_active", true)
        set(value) = prefs.edit().putBoolean("is_subscription_active", value).apply()

    var subscriptionExpiryDate: String
        get() = prefs.getString("subscription_expiry", "2026-12-31") ?: "2026-12-31"
        set(value) = prefs.edit().putString("subscription_expiry", value).apply()

    fun logout() {
        prefs.edit().clear().apply()
    }
}
