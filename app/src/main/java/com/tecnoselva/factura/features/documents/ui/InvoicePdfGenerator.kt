package com.tecnoselva.factura.features.documents.ui

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tecnoselva.factura.features.documents.model.Invoice
import com.tecnoselva.factura.features.documents.model.InvoiceLegend
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

/** Builds a printable A4 invoice/receipt, including the stored item rows. */
object InvoicePdfGenerator {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val LEFT = 34f
    private const val RIGHT = 561f
    private const val FOOTER_TOP = 746f
    private const val FOOTER_Y = 760f
    private const val INK = 0xFF1F2937.toInt()
    private const val MUTED = 0xFF64748B.toInt()
    private const val BLUE = 0xFF2563EB.toInt()
    private const val BORDER = 0xFFE3E9F0.toInt()
    private const val PDF_TEMPLATE_VERSION = "adminstore-a4-v3"

    fun cachedFile(documentsDirectory: File, invoice: Invoice): File? {
        val file = documentFile(documentsDirectory, invoice)
        val signatureFile = File(file.parentFile, "${file.name}.meta")
        val expectedSignature = "${PDF_TEMPLATE_VERSION}:${invoice.hashCode()}"
        return file.takeIf { it.isFile && signatureFile.isFile && runCatching { signatureFile.readText() == expectedSignature }.getOrDefault(false) }
    }

    fun create(
        documentsDirectory: File,
        businessName: String,
        issuerName: String,
        issuerRuc: String,
        issuerAddress: String,
        issuerCity: String,
        issuerLogoUrl: String,
        issuerSlogan: String,
        issuerPhone: String,
        invoice: Invoice
    ): File {
        documentsDirectory.mkdirs()
        val file = documentFile(documentsDirectory, invoice)
        val pdf = PdfDocument()
        val issuerLogo = loadLogo(issuerLogoUrl)
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun beginPage(firstPage: Boolean) {
            pageNumber += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = checkNotNull(page).canvas
            val activeCanvas = checkNotNull(canvas)
            activeCanvas.drawColor(0xFFFFFFFF.toInt())
            if (firstPage) {
                val infoY = drawFirstPageHeader(activeCanvas, businessName, issuerName, issuerRuc, issuerAddress, issuerCity, issuerLogo, issuerSlogan, issuerPhone, invoice)
                y = drawCustomerBlock(activeCanvas, invoice, infoY)
            } else {
                y = mm(8f)
            }
            drawTableHeader(activeCanvas, y)
            y += mm(7f)
        }

        fun finishPage() {
            val activeCanvas = checkNotNull(canvas)
            activeCanvas.drawLine(LEFT, FOOTER_TOP, RIGHT, FOOTER_TOP, paint(1f, false, BORDER))
            activeCanvas.drawText("Representacion impresa de la ${footerDocumentTitle(invoice.documentType)}", PAGE_WIDTH / 2f, FOOTER_Y, paint(7.5f, false, MUTED, Paint.Align.CENTER))
            val isElectronic = invoice.documentType == "01" || invoice.documentType == "03"
            if (isElectronic) {
                val hashLine = invoice.hash.takeIf { it.isNotBlank() }?.let { "Código hash: $it" }
                hashLine?.let { activeCanvas.drawText(it, PAGE_WIDTH / 2f, FOOTER_Y + mm(4f), paint(7f, false, MUTED, Paint.Align.CENTER)) }
                activeCanvas.drawText("Para consultar o descargar el comprobante ingrese al portal: www.sunat.gob.pe", PAGE_WIDTH / 2f, FOOTER_Y + mm(if (hashLine == null) 4f else 8f), paint(7f, false, MUTED, Paint.Align.CENTER))
                activeCanvas.drawText("Software de facturacion por D'JOS SOLUTIONS · 933 006 041 · tecnoselva.pe", PAGE_WIDTH / 2f, FOOTER_Y + mm(if (hashLine == null) 9f else 13f), paint(8f, true, BLUE, Paint.Align.CENTER))
            } else {
                activeCanvas.drawText("Documento interno de venta", PAGE_WIDTH / 2f, FOOTER_Y + mm(4f), paint(7.5f, false, MUTED, Paint.Align.CENTER))
                activeCanvas.drawText("Software de facturacion por D'JOS SOLUTIONS · 933 006 041 · tecnoselva.pe", PAGE_WIDTH / 2f, FOOTER_Y + mm(9f), paint(8f, true, BLUE, Paint.Align.CENTER))
            }
            pdf.finishPage(checkNotNull(page))
        }

        fun newContinuationPage() {
            finishPage()
            beginPage(firstPage = false)
        }

        try {
            beginPage(firstPage = true)
            val lines = invoice.lineItems
            if (lines.isEmpty()) {
                val note = "El registro disponible no contiene el detalle de productos o servicios de este comprobante."
                val noteLines = wrap(note, RIGHT - LEFT - 24f, paint(9f, false, MUTED))
                val rowHeight = maxOf(38f, noteLines.size * 13f + 18f)
                if (y + rowHeight > FOOTER_TOP - 25f) newContinuationPage()
                val activeCanvas = checkNotNull(canvas)
                activeCanvas.drawRoundRect(LEFT, y, RIGHT, y + rowHeight, 4f, 4f, paint(1f, false, BORDER, style = Paint.Style.STROKE))
                noteLines.forEachIndexed { index, text ->
                    activeCanvas.drawText(text, LEFT + 12f, y + 22f + index * 13f, paint(9f, false, MUTED))
                }
                y += rowHeight + 12f
            } else {
                lines.forEachIndexed { index, line ->
                    val descriptionPaint = paint(8.5f, false, INK)
                    val descriptionLines = wrap(line.description, mm(96f) - 12f, descriptionPaint)
                    val rowHeight = maxOf(mm(4.4f), descriptionLines.size * mm(3.9f) + mm(4.4f))
                    if (y + rowHeight > FOOTER_TOP - mm(6f)) newContinuationPage()
                    val activeCanvas = checkNotNull(canvas)
                    val columnXs = listOf(LEFT, LEFT + mm(16f), LEFT + mm(112f), LEFT + mm(146f), LEFT + mm(174f))
                    for (column in 0 until columnXs.lastIndex) {
                        activeCanvas.drawRect(columnXs[column], y, columnXs[column + 1], y + rowHeight, paint(0.55f, false, BORDER, style = Paint.Style.STROKE))
                    }
                    descriptionLines.forEachIndexed { lineIndex, text ->
                        activeCanvas.drawText(text, columnXs[1] + mm(2f), y + mm(3.3f) + lineIndex * mm(3.9f), descriptionPaint)
                    }
                    activeCanvas.drawText(quantity(line.quantity), (columnXs[0] + columnXs[1]) / 2f, y + mm(3.3f), paint(8.5f, false, INK, Paint.Align.CENTER))
                    activeCanvas.drawText("${currency(invoice)} ${money(line.unitPrice)}", columnXs[3] - mm(2f), y + mm(3.3f), paint(8.5f, false, INK, Paint.Align.RIGHT))
                    activeCanvas.drawText("${currency(invoice)} ${money(line.quantity * line.unitPrice)}", columnXs[4] - mm(2f), y + mm(3.3f), paint(8.5f, false, INK, Paint.Align.RIGHT))
                    y += rowHeight
                }
                y += mm(4.5f)
            }

            if (y + mm(8f) + totalsBlockHeight(invoice) + extraClosingHeight(invoice) > FOOTER_TOP) {
                newContinuationPage()
            }
            drawTotals(checkNotNull(canvas), invoice, y)
            finishPage()
            FileOutputStream(file).use { output -> pdf.writeTo(output) }
            File(file.parentFile, "${file.name}.meta").writeText("${PDF_TEMPLATE_VERSION}:${invoice.hashCode()}")
            return file
        } finally {
            issuerLogo?.recycle()
            pdf.close()
        }
    }

    private fun drawFirstPageHeader(
        canvas: Canvas,
        businessName: String,
        issuerName: String,
        issuerRuc: String,
        issuerAddress: String,
        issuerCity: String,
        issuerLogo: Bitmap?,
        issuerSlogan: String,
        issuerPhone: String,
        invoice: Invoice
    ): Float {
        val marginTop = mm(8f)
        val boxLeft = PAGE_WIDTH - mm(12f) - mm(64f)
        val boxWidth = mm(64f)
        val issuerBlockName = issuerName.ifBlank { businessName.ifBlank { "Comprobante electrónico" } }
        var issuerX = LEFT
        var issuerWidth = boxLeft - LEFT - mm(6f)
        var issuerY = marginTop
        var cursorY = marginTop
        var renderedLogoHeight = 0f
        if (issuerLogo != null) {
            val scale = minOf(mm(42f) / issuerLogo.width, mm(28f) / issuerLogo.height)
            val logoWidth = issuerLogo.width * scale
            val logoHeight = issuerLogo.height * scale
            renderedLogoHeight = logoHeight
            val logoRect = RectF(LEFT, marginTop, LEFT + logoWidth, marginTop + logoHeight)
            canvas.drawBitmap(issuerLogo, null, logoRect, null)
            val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 18 }
            val watermarkWidth = mm(120f)
            val watermarkHeight = watermarkWidth * issuerLogo.height / issuerLogo.width
            val watermarkTop = (mm(297f) - watermarkHeight) / 2f
            canvas.drawBitmap(issuerLogo, null, RectF((PAGE_WIDTH - watermarkWidth) / 2f, watermarkTop, (PAGE_WIDTH + watermarkWidth) / 2f, watermarkTop + watermarkHeight), watermarkPaint)
            issuerX = LEFT + logoWidth + mm(5f)
            issuerWidth = maxOf(mm(38f), boxLeft - issuerX - mm(5f))
            cursorY = maxOf(cursorY, marginTop + logoHeight + mm(3f))
        }
        if (issuerLogo == null) {
            val brandName = businessName.ifBlank { issuerBlockName }
            val brandLines = wrap(brandName, boxLeft - LEFT - mm(10f), paint(16f, true, BLUE)).take(2)
            brandLines.forEachIndexed { index, line -> canvas.drawText(line, LEFT, marginTop + mm(9f) + index * mm(5f), paint(16f, true, BLUE)) }
            cursorY = maxOf(cursorY, marginTop + mm(9f) + brandLines.size * mm(5f))
            issuerY = cursorY
        } else {
            val count = listOf(issuerBlockName, issuerAddress, issuerCity, issuerPhone).count { it.isNotBlank() }
            val linesHeight = count * mm(4f)
            issuerY = marginTop + maxOf(0f, (renderedLogoHeight - linesHeight) / 2f) + mm(3f)
        }

        val issuerLines = if (issuerLogo == null) listOf(issuerSlogan, issuerBlockName, issuerAddress, issuerCity, issuerPhone.takeIf { it.isNotBlank() }?.let { "Tel: $it" }.orEmpty())
            else listOf(issuerBlockName, issuerAddress, issuerCity, issuerPhone.takeIf { it.isNotBlank() }?.let { "Tel: $it" }.orEmpty())
        issuerLines.filter { it.isNotBlank() }.forEachIndexed { index, line ->
            val isSlogan = issuerLogo == null && issuerSlogan.isNotBlank() && index == 0
            val isLogoEmphasis = issuerLogo != null && index == 0
            val textPaint = when {
                isSlogan -> paint(8.5f, true, BLUE)
                isLogoEmphasis -> paint(8.5f, true, 0xFF000000.toInt())
                else -> paint(8.5f, false, MUTED)
            }
            val wrapped = wrap(line, issuerWidth, textPaint)
            wrapped.forEach { wrappedLine ->
                canvas.drawText(wrappedLine, issuerX, issuerY, textPaint)
                issuerY += mm(4f)
            }
        }
        cursorY = maxOf(cursorY, issuerY + mm(1f))

        val boxY = marginTop
        val boxHeight = mm(24f)
        canvas.drawRoundRect(boxLeft, boxY, boxLeft + boxWidth, boxY + boxHeight, mm(2f), mm(2f), paint(0.4f, false, BLUE, style = Paint.Style.STROKE))
        canvas.drawText(documentTitle(invoice.documentType), boxLeft + boxWidth / 2f, boxY + mm(6f), paint(10f, true, BLUE, Paint.Align.CENTER))
        canvas.drawText("RUC: $issuerRuc", boxLeft + boxWidth / 2f, boxY + mm(12f), paint(10.5f, false, INK, Paint.Align.CENTER))
        canvas.drawText(invoice.id, boxLeft + boxWidth / 2f, boxY + mm(19f), paint(14f, true, BLUE, Paint.Align.CENTER))
        return maxOf(cursorY + mm(2f), boxY + mm(28f)) + mm(1.5f)
    }

    private fun drawCustomerBlock(canvas: Canvas, invoice: Invoice, infoY: Float): Float {
        val infoGap = mm(18f)
        val columnWidth = (RIGHT - LEFT - infoGap) / 2f
        val rightX = LEFT + columnWidth + infoGap
        val valuePaint = paint(8.5f, false, INK)
        val labelPaint = paint(8.5f, true, INK)
        val infoStartY = infoY + mm(1.5f)
        var leftY = infoStartY
        leftY = drawInfoRow(canvas, customerDocumentLabel(invoice.clientRuc), invoice.clientRuc, LEFT, leftY, columnWidth, mm(22f), labelPaint, valuePaint)
        val nameLabel = if (invoice.clientRuc.length == 11) "Razon Social" else "Cliente"
        leftY = drawInfoRow(canvas, nameLabel, invoice.clientName, LEFT, leftY, columnWidth, mm(22f), labelPaint, valuePaint)
        leftY = drawInfoRow(canvas, "Direccion", invoice.customerAddress, LEFT, leftY, columnWidth, mm(22f), labelPaint, valuePaint)

        var rightY = infoStartY
        rightY = drawInfoRow(canvas, "Fecha", formatDate(invoice.date), rightX, rightY, columnWidth, mm(20f), labelPaint, valuePaint)
        rightY = drawInfoRow(canvas, "Hora", formatTime(invoice.date), rightX, rightY, columnWidth, mm(20f), labelPaint, valuePaint)
        return maxOf(leftY, rightY) + mm(4.5f)
    }

    private fun drawInfoRow(canvas: Canvas, label: String, value: String, x: Float, y: Float, width: Float, labelWidth: Float, labelPaint: Paint, valuePaint: Paint): Float {
        canvas.drawText(label, x, y, labelPaint)
        val colonX = x + labelWidth + mm(2f)
        val valueX = colonX + mm(5f)
        val valueLines = wrap(value.ifBlank { "-" }, width - labelWidth - mm(7f), valuePaint).take(2)
        canvas.drawText(":", colonX, y, labelPaint)
        valueLines.forEachIndexed { index, line -> canvas.drawText(line, valueX, y + index * mm(3.9f), valuePaint) }
        return y + maxOf(mm(4.4f), valueLines.size * mm(3.9f)) + mm(1f)
    }

    private fun drawTableHeader(canvas: Canvas, top: Float) {
        val xs = listOf(LEFT, LEFT + mm(16f), LEFT + mm(112f), LEFT + mm(146f), LEFT + mm(174f))
        val headerHeight = mm(7f)
        canvas.drawRect(LEFT, top, xs.last(), top + headerHeight, paint(1f, false, BLUE))
        val headerPaint = paint(8.5f, true, 0xFFFFFFFF.toInt())
        canvas.drawText("Cant.", (xs[0] + xs[1]) / 2f, top + mm(4.2f), paint(8.5f, true, 0xFFFFFFFF.toInt(), Paint.Align.CENTER))
        canvas.drawText("Descripcion", xs[1] + mm(2f), top + mm(4.2f), headerPaint)
        canvas.drawText("P. Unit.", xs[3] - mm(2f), top + mm(4.2f), paint(8.5f, true, 0xFFFFFFFF.toInt(), Paint.Align.RIGHT))
        canvas.drawText("Total", xs[4] - mm(2f), top + mm(4.2f), paint(8.5f, true, 0xFFFFFFFF.toInt(), Paint.Align.RIGHT))
    }

    private fun drawTotals(canvas: Canvas, invoice: Invoice, finalTableY: Float) {
        val totals = buildList {
            add((if (invoice.taxRate == 0.0 || invoice.igvAmount == 0.0) "Op. Exonerada" else "Op. Gravada") to invoice.operationSubtotal)
            add("IGV (${percent(invoice.taxRate)}%)" to invoice.igvAmount)
            if (invoice.allowanceTotalAmount > 0) add("Descuento" to -invoice.allowanceTotalAmount)
            if (invoice.chargeTotalAmount > 0) add("Envio" to invoice.chargeTotalAmount)
            add("TOTAL" to invoice.payableAmount)
        }
        val totalBlockHeight = mm(12f) + maxOf(0, totals.size - 1) * mm(5f)
        val amazonianLegend = getAmazoniaLegend(invoice)
        val mainLegend = amountLegend(invoice)
        val legendHeight = mm(if (amazonianLegend == null) 8f else 14f)
        val notesHeight = if (invoice.notes.isNotBlank()) mm(8f) else 0f
        val closingHeight = totalBlockHeight + legendHeight + notesHeight + mm(8f)
        val closingStartY = maxOf(finalTableY + mm(8f), FOOTER_TOP - closingHeight)

        if (invoice.qrData.isNotBlank()) {
            val qrSize = mm(22f)
            canvas.drawRect(LEFT, closingStartY, LEFT + qrSize, closingStartY + qrSize, paint(0.6f, false, 0xFFCBD5E1.toInt(), style = Paint.Style.STROKE))
            runCatching {
                val matrix = QRCodeWriter().encode(invoice.qrData, BarcodeFormat.QR_CODE, 256, 256, mapOf(EncodeHintType.MARGIN to 0))
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                for (x in 0 until 256) for (y in 0 until 256) bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                canvas.drawBitmap(bitmap, null, RectF(LEFT, closingStartY, LEFT + qrSize, closingStartY + qrSize), null)
                bitmap.recycle()
            }
        }

        val totalsX = PAGE_WIDTH - mm(12f) - mm(66f)
        canvas.drawRoundRect(totalsX, closingStartY, totalsX + mm(66f), closingStartY + totalBlockHeight, mm(2f), mm(2f), paint(0.6f, false, 0xFFE2E8F0.toInt(), style = Paint.Style.STROKE))
        var rowY = closingStartY + mm(5f)
        totals.forEachIndexed { index, row ->
            if (index == totals.lastIndex) {
                canvas.drawRoundRect(totalsX + mm(2f), rowY - mm(3.5f), totalsX + mm(64f), rowY + mm(5f), mm(1f), mm(1f), paint(1f, false, BLUE))
                canvas.drawText(row.first, totalsX + mm(5f), rowY + mm(1.5f), paint(10.5f, true, 0xFFFFFFFF.toInt()))
                canvas.drawText("${currency(invoice)} ${money(row.second)}", totalsX + mm(61f), rowY + mm(1.5f), paint(10.5f, true, 0xFFFFFFFF.toInt(), Paint.Align.RIGHT))
            } else {
                canvas.drawText(row.first, totalsX + mm(4f), rowY, paint(8.5f, false, INK))
                val amountPrefix = if (row.first == "Descuento") "-" else ""
                canvas.drawText("$amountPrefix${currency(invoice)} ${money(kotlin.math.abs(row.second))}", totalsX + mm(62f), rowY, paint(8.5f, false, INK, Paint.Align.RIGHT))
                rowY += mm(5f)
            }
        }

        var postTotalsY = closingStartY + totalBlockHeight + mm(4f)
        if (mainLegend.value.isNotBlank()) {
            val boxHeight = if (amazonianLegend == null) mm(8f) else mm(14f)
            canvas.drawRoundRect(LEFT, postTotalsY, RIGHT, postTotalsY + boxHeight, mm(1.5f), mm(1.5f), paint(1f, false, 0xFFFEF3C7.toInt()))
            canvas.drawText("SON: ${mainLegend.value}", LEFT + mm(3f), postTotalsY + mm(5f), paint(8f, true, 0xFF92400E.toInt()))
            if (amazonianLegend != null) canvas.drawText(amazonianLegend, LEFT + mm(3f), postTotalsY + mm(10f), paint(8f, false, 0xFF92400E.toInt()))
            postTotalsY += boxHeight + mm(2f)
        }
        if (invoice.notes.isNotBlank()) {
            canvas.drawRoundRect(LEFT, postTotalsY, RIGHT, postTotalsY + mm(8f), mm(1.5f), mm(1.5f), paint(1f, false, 0xFFF1F5F9.toInt()))
            canvas.drawText("Observaciones: ${invoice.notes}", LEFT + mm(3f), postTotalsY + mm(5f), paint(8f, false, INK))
        }
    }

    private fun totalsBlockHeight(invoice: Invoice): Float {
        val rows = 3 + (if (invoice.allowanceTotalAmount > 0) 1 else 0) + (if (invoice.chargeTotalAmount > 0) 1 else 0)
        return mm(12f) + maxOf(0, rows - 1) * mm(5f)
    }

    private fun extraClosingHeight(invoice: Invoice): Float {
        val amazonianLegend = getAmazoniaLegend(invoice)
        val legendHeight = mm(if (amazonianLegend == null) 8f else 14f)
        val notesHeight = if (invoice.notes.isNotBlank()) mm(8f) else 0f
        return legendHeight + notesHeight + mm(8f)
    }

    private fun amountLegend(invoice: Invoice) = invoice.legends.firstOrNull { it.code != "2000" }
        ?: InvoiceLegend("1000", amountInWords(invoice.payableAmount, invoice.currency))

    private fun getAmazoniaLegend(invoice: Invoice): String? {
        val explicit = invoice.legends.filter { it.code == "2000" }.joinToString(" / ") { it.value }
        if (explicit.isNotBlank()) return explicit
        if (invoice.taxRate != 0.0 && invoice.igvAmount != 0.0) return null
        val hasServices = invoice.lineItems.any { it.itemType.equals("servicio", true) || it.itemType.equals("ZZ", true) }
        val hasGoods = invoice.lineItems.isEmpty() || invoice.lineItems.any { !it.itemType.equals("servicio", true) && !it.itemType.equals("ZZ", true) }
        return listOfNotNull(
            if (hasGoods) "BIENES TRANSFERIDOS EN LA AMAZONIA PARA SER CONSUMIDOS EN LA MISMA" else null,
            if (hasServices) "SERVICIOS PRESTADOS EN LA AMAZONIA" else null
        ).joinToString(" / ").ifBlank { null }
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, top: Float, width: Float, textPaint: Paint, lineHeight: Float): Float {
        val lines = wrap(text, width, textPaint)
        lines.forEachIndexed { index, line -> canvas.drawText(line, x, top + index * lineHeight, textPaint) }
        return top + lines.size * lineHeight
    }

    private fun wrap(text: String, width: Float, textPaint: Paint): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
            var remaining = word
            while (remaining.isNotEmpty()) {
                val candidate = if (current.isEmpty()) remaining else "$current $remaining"
                if (textPaint.measureText(candidate) <= width) {
                    current = candidate
                    remaining = ""
                } else if (current.isNotEmpty()) {
                    result += current
                    current = ""
                } else {
                    var end = remaining.length
                    while (end > 1 && textPaint.measureText(remaining.substring(0, end)) > width) end -= 1
                    result += remaining.substring(0, end)
                    remaining = remaining.substring(end)
                }
            }
        }
        if (current.isNotEmpty()) result += current
        return result.ifEmpty { listOf("") }
    }

    private fun paint(
        size: Float,
        bold: Boolean,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT,
        style: Paint.Style = Paint.Style.FILL
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        this.color = color
        textAlign = align
        this.style = style
        strokeWidth = 1f
    }

    private fun documentTitle(type: String) = when (type) {
        "01" -> "FACTURA ELECTRONICA"
        "03" -> "BOLETA DE VENTA ELECTRONICA"
        else -> "NOTA DE VENTA"
    }

    private fun documentPrefix(type: String) = when (type) {
        "01" -> "FACTURA"
        "03" -> "BOLETA"
        "07" -> "NOTA_CREDITO"
        "08" -> "NOTA_DEBITO"
        else -> "COMPROBANTE"
    }

    private fun documentFile(documentsDirectory: File, invoice: Invoice): File {
        val safeNumber = invoice.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(documentsDirectory, "${documentPrefix(invoice.documentType)}_$safeNumber.pdf")
    }

    private fun currency(invoice: Invoice) = if (invoice.currency.equals("PEN", true)) "S/" else invoice.currency
    private fun customerDocumentLabel(documentNumber: String) = if (documentNumber.length == 11) "RUC" else if (documentNumber.length == 8) "DNI" else "Doc."
    private fun footerDocumentTitle(type: String) = when (type) {
        "01" -> "Factura Electronica"
        "03" -> "Boleta de Venta Electronica"
        else -> "Nota de Venta"
    }
    private fun percent(rate: Double) = if (rate % 1.0 == 0.0) rate.toInt().toString() else money(rate)
    private fun mm(value: Float) = value * 72f / 25.4f
    private fun money(amount: Double) = String.format(Locale.US, "%.2f", amount)
    private fun quantity(amount: Double) = if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(Locale.US, "%.2f", amount)
    private fun amountInWords(amount: Double, currencyCode: String): String {
        val integerPart = amount.toLong().coerceIn(0L, 999_999_999L)
        val cents = kotlin.math.round((amount - integerPart) * 100).toInt().coerceIn(0, 99)
        val currencyName = if (currencyCode.equals("PEN", true)) "SOLES" else currencyCode
        return "${numberToSpanish(integerPart)} CON ${cents.toString().padStart(2, '0')}/100 $currencyName"
    }

    private fun numberToSpanish(number: Long): String {
        if (number == 0L) return "CERO"
        val millions = number / 1_000_000
        val remainder = number % 1_000_000
        val millionText = when {
            millions == 0L -> ""
            millions == 1L -> "UN MILLON"
            else -> "${convertThousands(millions)} MILLONES"
        }
        val remainderText = convertThousands(remainder)
        return listOf(millionText, remainderText).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun convertThousands(number: Long): String {
        if (number == 0L) return ""
        val thousands = number / 1000
        val remainder = (number % 1000).toInt()
        val thousandsText = when {
            thousands == 0L -> ""
            thousands == 1L -> "MIL"
            else -> "${convertGroup(thousands.toInt())} MIL"
        }
        val remainderText = convertGroup(remainder)
        return listOf(thousandsText, remainderText).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun convertGroup(number: Int): String {
        if (number == 0) return ""
        if (number == 100) return "CIEN"
        val units = arrayOf("", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE")
        val teens = arrayOf("DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISEIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE")
        val tens = arrayOf("", "", "VEINTE", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA")
        val hundreds = arrayOf("", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS", "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS")
        val hundred = number / 100
        val rest = number % 100
        var result = hundreds[hundred]
        if (rest == 0) return result
        val restText = when {
            rest < 10 -> units[rest]
            rest < 20 -> teens[rest - 10]
            rest / 10 == 2 && rest % 10 > 0 -> "VEINTI${units[rest % 10]}"
            rest % 10 == 0 -> tens[rest / 10]
            else -> "${tens[rest / 10]} Y ${units[rest % 10]}"
        }
        result = listOf(result, restText).filter { it.isNotBlank() }.joinToString(" ")
        return result
    }
    private fun formatDate(raw: String): String {
        val dateParts = raw.substringBefore('T').split('-')
        return if (dateParts.size == 3) "${dateParts[2]}/${dateParts[1]}/${dateParts[0]}" else "-"
    }
    private fun formatTime(raw: String) = raw.substringAfter('T', "--:--").substringBefore('.').take(5).ifBlank { "--:--" }

    private fun loadLogo(url: String): Bitmap? {
        if (url.isBlank()) return null
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
            }
            try {
                connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
