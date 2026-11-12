package com.tecnoselva.factura.features.pos.data

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.billing.model.InvoiceLineItem
import com.tecnoselva.factura.features.pos.model.*

class PosRepository(
    private val sessionRepository: SessionRepository,
    private val currentUserId: String
) {
    private val printAgentService = PosPrintAgentService()

    fun loadCatalog(): List<PosCatalogItem> = sessionRepository.withValidSession {
        val products = SupabaseService.callRpc("get_products", JsonObject().apply {
            addProperty("p_store_id", sessionRepository.storeId)
            add("p_category_id", JsonNull.INSTANCE)
            addProperty("p_status", "active")
            add("p_search", JsonNull.INSTANCE)
            addProperty("p_limit", 250)
            addProperty("p_offset", 0)
        }).asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            val id = row.text("id")
            if (id.isBlank()) return@mapNotNull null
            val hasVariants = row.bool("has_variants")
            PosCatalogItem(
                id = id,
                type = PosCatalogType.PRODUCT,
                name = row.text("name", "Producto"),
                sku = row.text("sku"),
                category = row.text("category_name", "Producto"),
                price = if (hasVariants) row.number("variants_price_min").takeIf { it > 0 } ?: row.number("price") else row.number("price"),
                stock = if (hasVariants) row.numberOrNull("variants_total_stock") else row.numberOrNull("stock"),
                trackInventory = row.bool("track_inventory"),
                hasVariants = hasVariants
            )
        }

        val services = SupabaseService.callRpc("get_services", JsonObject().apply {
            addProperty("p_store_id", sessionRepository.storeId)
            add("p_search", JsonNull.INSTANCE)
            add("p_category_id", JsonNull.INSTANCE)
            addProperty("p_active", true)
            addProperty("p_limit", 200)
            addProperty("p_offset", 0)
        }).asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            val id = row.text("id")
            if (id.isBlank()) return@mapNotNull null
            PosCatalogItem(id, PosCatalogType.SERVICE, row.text("name", "Servicio"), row.text("sku"), row.text("category_name", "Servicio"), row.number("price"), null, false)
        }
        (products + services).sortedBy { it.name.lowercase() }
    }

    fun loadVariants(productId: String): List<PosVariant> = sessionRepository.withValidSession {
        SupabaseService.callRpc("get_product_variants", JsonObject().apply {
            addProperty("p_product_id", productId)
        }).asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            if (!row.bool("active", true)) return@mapNotNull null
            val id = row.text("id")
            if (id.isBlank()) return@mapNotNull null
            val attributes = row.get("attributes")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { attr ->
                    attr.takeIf { it.isJsonObject }?.asJsonObject?.let { "${it.text("name")}: ${it.text("value")}" }
                }?.filter { it.isNotBlank() }?.joinToString(" / ").orEmpty()
            PosVariant(id, attributes.ifBlank { row.text("sku", "Variante") }, row.text("sku"), row.number("price"), row.number("stock"))
        }
    }

    fun createSale(cart: List<PosCartItem>, checkout: PosCheckout): PosSaleResult = sessionRepository.withValidSession {
        require(cart.isNotEmpty()) { "Agrega al menos un producto o servicio." }
        val total = cart.sumOf { it.subtotal }.roundMoney()
        val paid = checkout.amountPaid.coerceIn(0.0, total).roundMoney()
        val items = JsonArray().apply {
            cart.forEach { item -> add(JsonObject().apply {
                addProperty("item_type", if (item.type == PosCatalogType.SERVICE) "service" else "product")
                if (item.type == PosCatalogType.PRODUCT) addProperty("product_id", item.catalogId) else add("product_id", JsonNull.INSTANCE)
                if (item.type == PosCatalogType.SERVICE) addProperty("service_id", item.catalogId) else add("service_id", JsonNull.INSTANCE)
                item.variantId?.let { addProperty("variant_id", it) } ?: add("variant_id", JsonNull.INSTANCE)
                item.variantLabel?.let { addProperty("variant_label", it) } ?: add("variant_label", JsonNull.INSTANCE)
                addProperty("custom_name", item.name)
                addProperty("quantity", item.quantity)
                addProperty("unit_price", item.unitPrice)
                addProperty("discount", 0)
            }) }
        }
        val customer = JsonObject().apply {
            addProperty("document_type", checkout.customerDocumentType.apiValue)
            addProperty("document_number", checkout.customerDocumentNumber)
            addProperty("name", checkout.customerName)
        }
        val payments = JsonArray().apply {
            if (paid > 0) add(JsonObject().apply {
                addProperty("method", checkout.paymentMethod.apiValue)
                addProperty("amount", paid)
                addProperty("sort_order", 0)
            })
        }
        val payload = JsonObject().apply {
            addProperty("p_store_id", sessionRepository.storeId)
            addProperty("p_order_type", "pickup")
            add("p_customer", customer)
            add("p_shipping", JsonNull.INSTANCE)
            add("p_items", items)
            addProperty("p_document_type", "nota_venta")
            addProperty("p_payment_method", if (checkout.paymentMode == PosPaymentMode.CREDIT) "other" else checkout.paymentMethod.apiValue)
            addProperty("p_amount_paid", paid)
            add("p_payments", payments)
            addProperty("p_discount", 0)
            addProperty("p_tax_rate", if (checkout.includeIgv) 18 else 0)
            add("p_notes", JsonNull.INSTANCE)
            if (currentUserId.isBlank()) add("p_created_by", JsonNull.INSTANCE) else addProperty("p_created_by", currentUserId)
        }

        val created = try {
            SupabaseService.callRpc("create_order", payload)
        } catch (error: Exception) {
            // Compatibility with older AdminStore deployments without split-payment support.
            if (!error.message.orEmpty().contains("PGRST202")) throw error
            payload.remove("p_payments")
            SupabaseService.callRpc("create_order", payload)
        }
        val result = created.asJsonObject
        if (result.get("success")?.asBoolean != true) {
            return@withValidSession PosSaleResult(false, result.text("error", "No se pudo registrar la venta"))
        }
        val orderId = result.text("order_id")
        val orderNumber = result.text("order_number")
        if (orderId.isNotBlank()) {
            runCatching { SupabaseService.callRpc("update_order_status", JsonObject().apply {
                addProperty("p_order_id", orderId)
                addProperty("p_status", "delivered")
                if (currentUserId.isBlank()) add("p_changed_by", JsonNull.INSTANCE) else addProperty("p_changed_by", currentUserId)
                add("p_reason", JsonNull.INSTANCE)
            }) }
        }

        // Igual que AdminStore web: la impresión ocurre después de confirmar create_order.
        // Un agente apagado nunca revierte ni duplica una venta ya registrada.
        val printMessage = if (orderId.isBlank()) "" else {
            runCatching {
                val printer = printAgentService.printSale(
                    storeId = sessionRepository.storeId,
                    orderId = orderId,
                    orderNumber = orderNumber,
                    cart = cart,
                    checkout = checkout
                )
                printer?.let { " Ticket enviado a $it." }.orEmpty()
            }.getOrElse { error ->
                // La impresión es best-effort: nunca afecta ni genera alertas en la venta.
                Log.w("PosAutoPrint", "No se pudo autoimprimir la venta $orderId", error)
                ""
            }
        }

        if (checkout.documentType != PosDocumentType.SALE_NOTE) {
            val docType = if (checkout.documentType == PosDocumentType.INVOICE) "01" else "03"
            val customerDocType = when (checkout.customerDocumentType) {
                PosCustomerDocumentType.RUC -> "6"; PosCustomerDocumentType.DNI -> "1"
                PosCustomerDocumentType.CE -> "4"; PosCustomerDocumentType.PASSPORT -> "7"
            }
            val invoiceItems = cart.map { item ->
                InvoiceLineItem(
                    description = item.name + (item.variantLabel?.let { " · $it" } ?: ""),
                    quantity = item.quantity.toDouble(),
                    unitPrice = if (checkout.includeIgv) item.unitPrice / 1.18 else item.unitPrice,
                    itemType = if (item.type == PosCatalogType.SERVICE) "servicio" else "bien",
                    taxed = checkout.includeIgv
                )
            }
            val invoice = SupabaseService.createInvoice(
                storeId = sessionRepository.storeId,
                documentType = docType,
                customerDocType = if (docType == "01") "6" else customerDocType,
                clientRuc = checkout.customerDocumentNumber,
                clientName = checkout.customerName,
                clientAddress = "",
                lineItems = invoiceItems,
                observations = "Venta POS ${orderNumber.ifBlank { orderId }}",
                orderId = orderId
            )
            if (!invoice.success) return@withValidSession PosSaleResult(
                true,
                "Venta ${orderNumber.ifBlank { orderId }} registrada.$printMessage El comprobante falló: ${invoice.message}",
                orderId,
                orderNumber
            )
        }
        PosSaleResult(
            true,
            "Venta ${orderNumber.ifBlank { orderId }} registrada correctamente.$printMessage",
            orderId,
            orderNumber
        )
    }

    private fun JsonObject.text(key: String, default: String = ""): String =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }?.takeIf { it.isNotBlank() } ?: default
    private fun JsonObject.number(key: String): Double = numberOrNull(key) ?: 0.0
    private fun JsonObject.numberOrNull(key: String): Double? = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: default
    private fun Double.roundMoney(): Double = kotlin.math.round(this * 100.0) / 100.0
}
