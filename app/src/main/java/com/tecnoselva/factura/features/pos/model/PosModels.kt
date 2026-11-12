package com.tecnoselva.factura.features.pos.model

enum class PosCatalogType { PRODUCT, SERVICE }
enum class PosDocumentType(val apiValue: String, val label: String) {
    SALE_NOTE("nota_venta", "Nota de venta"),
    RECEIPT("boleta", "Boleta"),
    INVOICE("factura", "Factura")
}
enum class PosPaymentMode(val label: String) { PAID("Contado"), PARTIAL("Pago parcial"), CREDIT("Crédito") }
enum class PosPaymentMethod(val apiValue: String, val label: String) {
    CASH("cash", "Efectivo"), CARD("card", "Tarjeta"), YAPE("yape", "Yape"),
    PLIN("plin", "Plin"), TRANSFER("transfer", "Transferencia"), OTHER("other", "Otro")
}
enum class PosCustomerDocumentType(val apiValue: String, val label: String) {
    DNI("dni", "DNI"), RUC("ruc", "RUC"), CE("ce", "C.E."), PASSPORT("pasaporte", "Pasaporte")
}

data class PosCatalogItem(
    val id: String,
    val type: PosCatalogType,
    val name: String,
    val sku: String,
    val category: String,
    val price: Double,
    val stock: Double?,
    val trackInventory: Boolean,
    val hasVariants: Boolean = false
) {
    val available: Boolean get() = !trackInventory || (stock ?: 0.0) > 0
}

data class PosVariant(
    val id: String,
    val label: String,
    val sku: String,
    val price: Double,
    val stock: Double
)

data class PosCartItem(
    val key: String,
    val catalogId: String,
    val type: PosCatalogType,
    val name: String,
    val sku: String,
    val unitPrice: Double,
    val quantity: Int,
    val stock: Double?,
    val trackInventory: Boolean,
    val variantId: String? = null,
    val variantLabel: String? = null
) {
    val subtotal: Double get() = unitPrice * quantity
}

data class PosCheckout(
    val documentType: PosDocumentType,
    val customerDocumentType: PosCustomerDocumentType,
    val customerDocumentNumber: String,
    val customerName: String,
    val includeIgv: Boolean,
    val paymentMode: PosPaymentMode,
    val paymentMethod: PosPaymentMethod,
    val amountPaid: Double
)

data class PosSaleResult(
    val success: Boolean,
    val message: String,
    val orderId: String? = null,
    val orderNumber: String? = null
)
