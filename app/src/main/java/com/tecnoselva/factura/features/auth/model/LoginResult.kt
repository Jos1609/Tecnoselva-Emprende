package com.tecnoselva.factura.features.auth.model

data class LoginResult(
    val success: Boolean,
    val error: String?,
    val userId: String?,
    val email: String?,
    val storeId: String?,
    val businessName: String?,
    val isSubscriptionActive: Boolean,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val issuerRuc: String? = null,
    val issuerLegalName: String? = null,
    val issuerAddress: String? = null,
    val issuerCity: String? = null,
    val issuerLogoUrl: String? = null,
    val issuerSlogan: String? = null,
    val issuerPhone: String? = null
)

data class CustomerLookupResult(
    val success: Boolean,
    val name: String?,
    val address: String?,
    val error: String?
)
