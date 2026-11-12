package com.tecnoselva.factura.features.catalog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tecnoselva.factura.features.catalog.model.LocalCatalogProduct
import com.tecnoselva.factura.features.catalog.model.LocalCatalogVariant
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.min

class CatalogPdfGenerator(private val context: Context) {
    fun generate(
        products: List<LocalCatalogProduct>,
        businessName: String,
        categoryLabel: String
    ): File {
        require(products.isNotEmpty()) { "No hay productos para exportar" }
        val catalogCards: List<CatalogCard> = products.flatMap { product: LocalCatalogProduct ->
            if (product.variantsEnabled && product.variants.isNotEmpty()) {
                product.variants.map { variant: LocalCatalogVariant ->
                    CatalogCard(
                        name = "${product.name} - ${variant.name}",
                        category = product.category,
                        imagePath = variant.imagePath.orEmpty().ifBlank { product.imagePath },
                        minimumPrice = variant.salePrice,
                        maximumPrice = variant.salePrice,
                        stock = variant.stock
                    )
                }
            } else {
                listOf(
                    CatalogCard(
                        name = product.name,
                        category = product.category,
                        imagePath = product.imagePath,
                        minimumPrice = product.salePrice,
                        maximumPrice = product.salePrice,
                        stock = product.stock
                    )
                )
            }
        }
        val outputDirectory = File(context.cacheDir, "shared_documents").apply { mkdirs() }
        val outputFile = File(outputDirectory, "catalogo_productos.pdf")
        val document = PdfDocument()
        try {
            catalogCards.chunked(ITEMS_PER_PAGE).forEachIndexed { pageIndex, pageProducts ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                val page = document.startPage(pageInfo)
                drawPage(page.canvas, pageProducts, businessName, categoryLabel, catalogCards.size, pageIndex + 1)
                document.finishPage(page)
            }
            FileOutputStream(outputFile).use { output -> document.writeTo(output) }
        } finally {
            document.close()
        }
        return outputFile
    }

    private fun drawPage(
        canvas: Canvas,
        products: List<CatalogCard>,
        businessName: String,
        categoryLabel: String,
        totalProducts: Int,
        pageNumber: Int
    ) {
        canvas.drawColor(Color.WHITE)
        val titlePaint = textPaint(22f, NAVY, bold = true)
        val bodyPaint = textPaint(10f, SECONDARY)
        val smallPaint = textPaint(8f, SECONDARY)

        canvas.drawText("Catalogo de productos", PAGE_MARGIN, 36f, titlePaint)
        canvas.drawText(businessName.ifBlank { "Mi tienda" }, PAGE_MARGIN, 55f, bodyPaint)
        canvas.drawText("Categoria: $categoryLabel", PAGE_MARGIN, 72f, bodyPaint)
        val countText = "$totalProducts ${if (totalProducts == 1) "producto" else "productos"}"
        canvas.drawText(countText, PAGE_WIDTH - PAGE_MARGIN - bodyPaint.measureText(countText), 36f, bodyPaint)

        products.forEachIndexed { index, product ->
            val column = index % COLUMNS
            val row = index / COLUMNS
            val left = PAGE_MARGIN + column * (CARD_WIDTH + CARD_GAP)
            val top = CARDS_TOP + row * (CARD_HEIGHT + CARD_GAP)
            drawProductCard(canvas, product, left, top)
        }

        val footer = "Pagina $pageNumber"
        canvas.drawText(footer, PAGE_WIDTH - PAGE_MARGIN - smallPaint.measureText(footer), PAGE_HEIGHT - 10f, smallPaint)
    }

    private fun drawProductCard(canvas: Canvas, product: CatalogCard, left: Float, top: Float) {
        val card = RectF(left, top, left + CARD_WIDTH, top + CARD_HEIGHT)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(card, 9f, 9f, cardPaint)
        canvas.drawRoundRect(card, 9f, 9f, borderPaint)

        val imageArea = RectF(left + 12f, top + 10f, left + CARD_WIDTH - 12f, top + 112f)
        val imageBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE }
        canvas.drawRoundRect(imageArea, 6f, 6f, imageBackground)
        val bitmap = product.imagePath.takeIf { it.isNotBlank() }?.let { decodeSampledBitmap(it, imageArea.width().toInt(), imageArea.height().toInt()) }
        if (bitmap != null) {
            drawBitmapFitCenter(canvas, bitmap, imageArea)
            bitmap.recycle()
        } else {
            val placeholder = textPaint(9f, MUTED, bold = true)
            val label = "SIN IMAGEN"
            canvas.drawText(
                label,
                imageArea.centerX() - placeholder.measureText(label) / 2f,
                imageArea.centerY() + 3f,
                placeholder
            )
        }

        val namePaint = textPaint(10f, NAVY, bold = true)
        val categoryPaint = textPaint(8f, SECONDARY)
        val pricePaint = textPaint(12f, GREEN, bold = true)
        val stockPaint = textPaint(9f, NAVY, bold = true)
        val textLeft = left + 14f
        val textWidth = CARD_WIDTH - 28f
        drawWrappedText(canvas, product.name, textLeft, top + 130f, textWidth, namePaint, 12f, 2)
        canvas.drawText(product.category.ifBlank { "Sin categoria" }, textLeft, top + 157f, categoryPaint)

        val minimum = product.minimumPrice
        val maximum = product.maximumPrice
        val priceText = if (minimum != maximum) {
            "S/ ${money(minimum)} - S/ ${money(maximum)}"
        } else {
            "S/ ${money(minimum)}"
        }
        canvas.drawText(priceText, textLeft, top + CARD_HEIGHT - 17f, pricePaint)
        val stockText = "${quantity(product.stock)} uds"
        canvas.drawText(
            stockText,
            left + CARD_WIDTH - 14f - stockPaint.measureText(stockText),
            top + CARD_HEIGHT - 17f,
            stockPaint
        )
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
        lineHeight: Float,
        maxLines: Int
    ) {
        var remaining = text.trim()
        var line = 0
        while (remaining.isNotEmpty() && line < maxLines) {
            var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val lastSpace = remaining.substring(0, count).lastIndexOf(' ')
                if (lastSpace > 0) count = lastSpace
            }
            var segment = remaining.substring(0, count).trim()
            remaining = remaining.substring(count).trim()
            if (line == maxLines - 1 && remaining.isNotEmpty()) {
                while (segment.isNotEmpty() && paint.measureText("$segment...") > maxWidth) segment = segment.dropLast(1)
                segment += "..."
            }
            canvas.drawText(segment, x, y + line * lineHeight, paint)
            line++
        }
    }

    private fun drawBitmapFitCenter(canvas: Canvas, bitmap: Bitmap, area: RectF) {
        val scale = min(area.width() / bitmap.width, area.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val destination = RectF(
            area.centerX() - width / 2f,
            area.centerY() - height / 2f,
            area.centerX() + width / 2f,
            area.centerY() + height / 2f
        )
        canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun decodeSampledBitmap(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth && bounds.outHeight / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
    private fun quantity(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private data class CatalogCard(
        val name: String,
        val category: String,
        val imagePath: String,
        val minimumPrice: Double,
        val maximumPrice: Double,
        val stock: Double
    )

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val ITEMS_PER_PAGE = 9
        const val COLUMNS = 3
        const val PAGE_MARGIN = 22f
        const val CARD_GAP = 10f
        const val CARD_WIDTH = 177f
        const val CARD_HEIGHT = 220f
        const val CARDS_TOP = 92f
        val NAVY = Color.rgb(10, 37, 64)
        val GREEN = Color.rgb(0, 125, 87)
        val SECONDARY = Color.rgb(102, 112, 133)
        val MUTED = Color.rgb(148, 163, 184)
        val BORDER = Color.rgb(221, 228, 236)
        val SURFACE = Color.rgb(245, 247, 250)
    }
}
