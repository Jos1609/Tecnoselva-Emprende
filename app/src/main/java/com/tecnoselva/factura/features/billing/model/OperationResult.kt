package com.tecnoselva.factura.features.billing.model

data class OperationResult(
    val success: Boolean,
    val message: String? = null,
    val documentNumber: String? = null
)
