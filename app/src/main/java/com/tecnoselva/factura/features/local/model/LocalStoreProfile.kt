package com.tecnoselva.factura.features.local.model

data class LocalStoreProfile(
    val localStoreId: String,
    val businessName: String,
    val address: String,
    val phone: String,
    val logoPath: String = "",
    val termsVersion: String,
    val termsAcceptedAt: Long
)
