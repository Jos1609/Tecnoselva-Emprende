package com.tecnoselva.factura.features.documents.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.tecnoselva.factura.R
import com.tecnoselva.factura.core.ui.applySystemBarInsets
import java.io.File
import java.util.concurrent.Executors

class DocumentPreviewActivity : AppCompatActivity() {
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private lateinit var pdfFile: File
    private lateinit var pagesContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document_preview)
        applySystemBarInsets(findViewById(R.id.previewRoot))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarPreview)
        toolbar.setNavigationOnClickListener { finish() }
        pagesContainer = findViewById(R.id.previewPages)
        progress = findViewById(R.id.progressPreview)
        errorMessage = findViewById(R.id.tvPreviewError)

        val path = intent.getStringExtra(EXTRA_PDF_PATH).orEmpty()
        pdfFile = File(path)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { pdfFile.nameWithoutExtension }
        findViewById<MaterialButton>(R.id.btnSharePreview).setOnClickListener { sharePdf() }

        if (!pdfFile.isFile) {
            showError("No se encontró la copia guardada del comprobante.")
            return
        }
        renderPdf()
    }

    private fun renderPdf() {
        renderExecutor.execute {
            try {
                val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(descriptor)
                try {
                    val targetWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(400)
                    for (pageIndex in 0 until renderer.pageCount) {
                        val page = renderer.openPage(pageIndex)
                        val bitmap = Bitmap.createBitmap(
                            targetWidth,
                            (targetWidth * page.height.toFloat() / page.width).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) addPage(bitmap, pageIndex + 1)
                            else bitmap.recycle()
                        }
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) progress.visibility = View.GONE
                    }
                } finally {
                    renderer.close()
                    descriptor.close()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showError("No se pudo abrir el PDF: ${error.localizedMessage ?: "error desconocido"}")
                }
            }
        }
    }

    private fun addPage(bitmap: Bitmap, pageNumber: Int) {
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Página $pageNumber del comprobante"
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        val pageLayout = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
        pagesContainer.addView(image, pageLayout)
    }

    private fun sharePdf() {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", pdfFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, pdfFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir comprobante PDF A4"))
        } catch (error: Exception) {
            Snackbar.make(pagesContainer, "No se pudo compartir el PDF: ${error.localizedMessage ?: "error desconocido"}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        errorMessage.text = message
        errorMessage.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        renderExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_TITLE = "extra_title"
    }
}
