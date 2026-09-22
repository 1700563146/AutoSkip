package com.example.autoskip.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.autoskip.databinding.ItemAppBinding
import com.example.autoskip.model.WhitelistEntry

/**
 * Lists installed launchable apps with a per-app enable switch.
 *
 * The icon is kept alongside the entry rather than inside [WhitelistEntry] so
 * the model stays a plain data class with no Android UI types in it.
 */
class WhitelistAdapter(
    private val onToggle: (WhitelistEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<WhitelistAdapter.Holder>() {

    data class AppRow(val entry: WhitelistEntry, val icon: Drawable)

    private val rows = mutableListOf<AppRow>()

    fun submit(newRows: List<AppRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    inner class Holder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: AppRow) {
            binding.imgIcon.setImageDrawable(row.icon)
            binding.textName.text = row.entry.appName
            binding.textPackage.text = row.entry.packageName

            // Detach before setting the value, or recycling a row would fire the
            // previous row's callback.
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = row.entry.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(row.entry, isChecked)
            }
        }
    }
}
