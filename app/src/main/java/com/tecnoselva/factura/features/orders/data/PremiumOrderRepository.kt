package com.tecnoselva.factura.features.orders.data

import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.tecnoselva.factura.core.network.SupabaseService
import com.tecnoselva.factura.features.auth.data.SessionRepository
import com.tecnoselva.factura.features.orders.model.OrderSummary
import com.tecnoselva.factura.features.orders.model.OrderLineSummary

class PremiumOrderRepository(private val sessionRepository: SessionRepository) {
    fun getOrders(search: String? = null): List<OrderSummary> = sessionRepository.withValidSession {
        val result = SupabaseService.callRpc("get_orders_management", JsonObject().apply {
            addProperty("p_store_id", sessionRepository.storeId)
            add("p_status", com.google.gson.JsonNull.INSTANCE)
            add("p_order_type", com.google.gson.JsonNull.INSTANCE)
            add("p_document_type", com.google.gson.JsonNull.INSTANCE)
            add("p_payment_status", com.google.gson.JsonNull.INSTANCE)
            search?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("p_search", it) }
                ?: add("p_search", com.google.gson.JsonNull.INSTANCE)
            add("p_visible_to_user_id", com.google.gson.JsonNull.INSTANCE)
            addProperty("p_include_stats", false)
            addProperty("p_limit", 100)
            addProperty("p_offset", 0)
        })
        val rows = when {
            result.isJsonArray -> result.asJsonArray
            result.isJsonObject -> result.asJsonObject.getAsJsonArray("orders")
                ?: result.asJsonObject.getAsJsonArray("data")
            else -> null
        }
        val orders = rows?.mapNotNull { element ->
            element.asJsonObject?.let { row ->
                val items = parseItems(row.get("items"))
                OrderSummary(
                    id = row.text("id"),
                    orderNumber = row.text("order_number", "Pedido"),
                    customerName = row.text("customer_name", "Cliente sin nombre"),
                    total = row.number("total"),
                    status = row.text("status", "pending"),
                    paymentStatus = row.text("payment_status", "pending"),
                    documentType = row.text("document_type", "nota_venta"),
                    createdAt = row.text("created_at"),
                    itemsSummary = row.text("items_summary").ifBlank {
                        items.joinToString(" · ") { "${formatQuantity(it.quantity)}x ${it.name}" }
                    },
                    items = items,
                    taxRate = row.number("tax_rate")
                ).takeIf { it.id.isNotBlank() }
            }
        }.orEmpty()
        hydrateOrdersWithElectronicDocuments(orders)
    }

    fun getOrderDetail(order: OrderSummary): OrderSummary = sessionRepository.withValidSession {
        val result = runCatching {
            SupabaseService.callRpc("get_order_by_id", JsonObject().apply {
                addProperty("p_order_id", order.id)
            })
        }.getOrNull()
        if (result == null || !result.isJsonObject) {
            val fallbackItems = loadItemsFromTable(order.id)
            return@withValidSession order.copy(
                items = fallbackItems,
                itemsSummary = fallbackItems.joinToString(" · ") { "${formatQuantity(it.quantity)}x ${it.name}" }
            )
        }
        val root = result.asJsonObject
        val items = parseItems(root.get("items"))
            .ifEmpty { loadItemsFromTable(order.id) }
            .ifEmpty { order.items }
        val document = root.objectOrNull("document")
        val linkedDocument = loadLinkedElectronicDocument(order.id)
        val linkedType = electronicDocumentType(linkedDocument?.text("document_type"))
        val nestedDocumentNumber = document?.text("full_number").orEmpty()
        val nestedType = electronicDocumentType(
            document?.text("document_type").orEmpty().ifBlank { document?.text("type").orEmpty() }
        )
            ?: electronicDocumentTypeFromNumber(nestedDocumentNumber)
        val resolvedDocumentType = linkedType ?: nestedType ?: root.text("document_type", order.documentType)
        val hasNestedElectronicDocument = nestedType != null && nestedDocumentNumber.isNotBlank()
        val base = order.copy(
            orderNumber = root.text("order_number", order.orderNumber),
            customerName = root.text("customer_name", order.customerName),
            total = root.numberOrNull("total") ?: order.total,
            status = root.text("status", order.status),
            paymentStatus = root.text("payment_status", order.paymentStatus),
            documentType = resolvedDocumentType,
            createdAt = root.text("created_at", order.createdAt),
            itemsSummary = items.joinToString(" · ") { "${formatQuantity(it.quantity)}x ${it.name}" }.ifBlank { order.itemsSummary },
            items = items
        )
        runCatching {
            val customer = root.objectOrNull("customer")
            val payment = root.objectOrNull("payment")
            val shipping = root.objectOrNull("shipping")
            val total = document?.numberOrNull("total") ?: base.total
            base.copy(
                customerName = customer?.text("name", base.customerName) ?: base.customerName,
                orderType = root.text("order_type", order.orderType),
                customerDocumentType = customer?.text("document_type").orEmpty(),
                customerDocumentNumber = customer?.text("document_number").orEmpty(),
                customerPhone = customer?.text("phone").orEmpty(),
                customerAddress = customer?.text("address").orEmpty(),
                shippingRecipient = shipping?.text("recipient_name").orEmpty(),
                shippingPhone = shipping?.text("recipient_phone").orEmpty(),
                shippingAddress = shipping?.text("address").orEmpty(),
                shippingReference = shipping?.text("reference").orEmpty(),
                paymentMethod = payment?.text("method").orEmpty(),
                amountPaid = payment?.number("amount_paid") ?: 0.0,
                amountPending = payment?.numberOrNull("amount_pending") ?: (total - (payment?.number("amount_paid") ?: 0.0)).coerceAtLeast(0.0),
                subtotal = document?.number("subtotal") ?: 0.0,
                tax = document?.number("tax") ?: 0.0,
                taxRate = document?.numberOrNull("tax_rate") ?: root.numberOrNull("tax_rate") ?: order.taxRate,
                discount = document?.number("discount") ?: 0.0,
                shippingCost = document?.number("shipping_cost") ?: shipping?.number("shipping_cost") ?: 0.0,
                notes = root.text("notes"),
                cancelledReason = root.text("cancelled_reason"),
                documentNumber = linkedDocument?.text("full_number")?.ifBlank { nestedDocumentNumber }
                    ?: nestedDocumentNumber,
                sunatStatus = linkedDocument?.text("status") ?: document?.text("sunat_status").orEmpty(),
                sunatQrData = linkedDocument?.text("qr_code") ?: document?.text("qr_data").orEmpty(),
                sunatHash = linkedDocument?.text("xml_hash") ?: document?.text("hash").orEmpty(),
                hasElectronicDocument = linkedDocument?.text("full_number").orEmpty().isNotBlank() || hasNestedElectronicDocument
            )
        }.getOrDefault(base)
    }

    private fun parseItems(element: JsonElement?): List<OrderLineSummary> {
        val array = when {
            element == null || element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray
            element.isJsonPrimitive -> runCatching { com.google.gson.JsonParser.parseString(element.asString).asJsonArray }.getOrNull()
            else -> null
        }
        return array?.mapNotNull { item ->
            item.asJsonObject?.let { row ->
                val name = row.text("product_name")
                    .ifBlank { row.text("name") }
                    .ifBlank { row.text("custom_name", "Producto") }
                OrderLineSummary(
                    name = name,
                    quantity = row.number("quantity"),
                    unitPrice = row.number("unit_price"),
                    subtotal = row.number("subtotal"),
                    variantLabel = row.text("variant_label"),
                    itemType = row.text("item_type", "product"),
                    notes = row.text("notes")
                )
            }
        }.orEmpty()
    }

    private fun loadItemsFromTable(orderId: String): List<OrderLineSummary> = runCatching {
        val result = SupabaseService.getRest(
            "order_items?order_id=eq.$orderId&select=product_name,product_sku,product_image,quantity,unit_price,subtotal,variant_label,item_type,notes,created_at&order=created_at.asc"
        )
        parseItems(result)
    }.getOrDefault(emptyList())

    private fun loadLinkedElectronicDocument(orderId: String): JsonObject? = runCatching {
        val result = SupabaseService.getRest(
            "electronic_documents?order_id=eq.$orderId&document_type=in.(01,03)&status=neq.voided&select=*&order=created_at.desc&limit=1"
        )
        result.asJsonArray.firstOrNull()?.asJsonObject
    }.getOrNull()

    private fun electronicDocumentType(value: String?): String? = when (value?.lowercase()) {
        "01", "factura" -> "factura"
        "03", "boleta" -> "boleta"
        else -> null
    }

    private fun electronicDocumentTypeFromNumber(value: String): String? = when {
        value.substringBefore('-').uppercase().startsWith("F") -> "factura"
        value.substringBefore('-').uppercase().startsWith("B") -> "boleta"
        else -> null
    }

    private fun hydrateOrdersWithElectronicDocuments(orders: List<OrderSummary>): List<OrderSummary> {
        if (orders.isEmpty()) return orders
        val ids = orders.map { it.id }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return orders
        val linkedDocuments = runCatching {
            val result = SupabaseService.getRest(
                "electronic_documents?order_id=in.(${ids.joinToString(",")})&document_type=in.(01,03)&status=neq.voided&select=*&order=created_at.desc"
            )
            result.asJsonArray.mapNotNull { it.asJsonObject }
        }.getOrDefault(emptyList())
        val latestByOrderId = linkedDocuments.fold(linkedMapOf<String, JsonObject>()) { latest, document ->
            val orderId = document.text("order_id")
            if (orderId.isNotBlank() && !latest.containsKey(orderId)) latest[orderId] = document
            latest
        }
        return orders.map { order ->
            val document = latestByOrderId[order.id] ?: return@map order
            val type = when (document.text("document_type")) {
                "01" -> "factura"
                "03" -> "boleta"
                else -> order.documentType
            }
            order.copy(
                documentType = type,
                documentNumber = document.text("full_number").ifBlank { order.documentNumber },
                sunatStatus = document.text("status").ifBlank { order.sunatStatus },
                sunatQrData = document.text("qr_code").ifBlank { order.sunatQrData },
                sunatHash = document.text("xml_hash").ifBlank { order.sunatHash },
                hasElectronicDocument = document.text("full_number").isNotBlank()
            )
        }
    }

    private fun formatQuantity(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(java.util.Locale.US, value)

    private fun JsonObject.text(key: String, default: String = ""): String =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() } ?: default
    private fun JsonObject.number(key: String): Double =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
    private fun JsonObject.numberOrNull(key: String): Double? =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }
    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asJsonObject }.getOrNull() }
    fun updateOrderStatus(orderId: String, status: String, changedBy: String? = null, reason: String? = null): Boolean = sessionRepository.withValidSession {
        val result = SupabaseService.callRpc("update_order_status", JsonObject().apply {
            addProperty("p_order_id", orderId)
            addProperty("p_status", status)
            if (changedBy.isNullOrBlank()) add("p_changed_by", com.google.gson.JsonNull.INSTANCE) else addProperty("p_changed_by", changedBy)
            addProperty("p_reason", reason)
        })
        result.asJsonObject.get("success")?.asBoolean ?: true
    }

    fun cancelOrder(orderId: String, reason: String, changedBy: String? = null): Boolean = sessionRepository.withValidSession {
        val result = SupabaseService.callRpc("cancel_order", JsonObject().apply {
            addProperty("p_order_id", orderId)
            addProperty("p_reason", reason)
            if (changedBy.isNullOrBlank()) add("p_changed_by", com.google.gson.JsonNull.INSTANCE) else addProperty("p_changed_by", changedBy)
        })
        result.asJsonObject.get("success")?.asBoolean ?: true
    }

    fun updateOrderPayment(orderId: String, amountPaid: Double, reference: String = ""): Boolean = sessionRepository.withValidSession {
        val result = SupabaseService.callRpc("update_order_payment", JsonObject().apply {
            addProperty("p_order_id", orderId)
            addProperty("p_amount_paid", amountPaid)
            if (reference.isBlank()) add("p_payment_reference", com.google.gson.JsonNull.INSTANCE) else addProperty("p_payment_reference", reference)
        })
        result.asJsonObject.get("success")?.asBoolean ?: true
    }
}
