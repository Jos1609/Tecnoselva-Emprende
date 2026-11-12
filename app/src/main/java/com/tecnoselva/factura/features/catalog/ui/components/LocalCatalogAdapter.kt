package com.tecnoselva.factura.features.catalog.ui.components

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tecnoselva.factura.R
import com.tecnoselva.factura.databinding.ItemLocalCatalogBinding
import com.tecnoselva.factura.features.catalog.model.LocalCatalogProduct
import java.io.File
import java.util.Locale

class LocalCatalogAdapter(
    private val onProductClick: (LocalCatalogProduct) -> Unit
) : RecyclerView.Adapter<LocalCatalogAdapter.ProductViewHolder>() {
    private var items: List<LocalCatalogProduct> = emptyList()

    fun submitList(newItems: List<LocalCatalogProduct>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val binding = ItemLocalCatalogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProductViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class ProductViewHolder(private val binding: ItemLocalCatalogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(product: LocalCatalogProduct) = with(binding) {
            tvLocalProductName.text = product.name
            tvLocalProductPrice.text = "S/ ${money(product.displayedSalePrice)}"
            tvLocalProductStock.text = "Stock: ${quantity(product.totalStock)}"
            tvLocalProductStock.setTextColor(root.context.getColor(if (product.totalStock > 0.0) R.color.brand_success else R.color.brand_danger))

            val imagePath = (listOf(product.imagePath) + product.variants.map { it.imagePath })
                .firstOrNull { it.isNotBlank() && File(it).exists() }
            val image = imagePath?.let(::File)
            if (image?.exists() == true) {
                ivLocalProduct.setImageURI(Uri.fromFile(image))
                ivLocalProduct.setPadding(0, 0, 0, 0)
            } else {
                ivLocalProduct.setImageResource(R.drawable.ic_store)
                val padding = (12 * root.resources.displayMetrics.density).toInt()
                ivLocalProduct.setPadding(padding, padding, padding, padding)
            }
            root.setOnClickListener { onProductClick(product) }
        }
    }

    private fun money(value: Double) = String.format(Locale.US, "%.2f", value)
    private fun quantity(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else money(value)
}
