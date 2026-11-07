package com.deltafiles.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.deltafiles.app.databinding.ItemFileBinding
import java.text.DateFormat
import java.util.Date

class FileAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLongClick: (Entry) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private var items: List<Entry> = emptyList()
    private var selected: Set<String> = emptySet()
    private val dateFormat: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    class VH(val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Entry>, selection: Set<String>) {
        items = list
        selected = HashSet(selection)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val ctx = holder.itemView.context
        val isSelected = selected.contains(e.path)

        holder.b.name.text = e.name

        val date = dateFormat.format(Date(e.modified))
        holder.b.meta.text = if (e.isDir) {
            ctx.resources.getQuantityString(R.plurals.items_count, e.childCount, e.childCount) + " · " + date
        } else {
            Formatter.formatShortFileSize(ctx, e.size) + " · " + date
        }

        if (isSelected) {
            holder.b.icon.setImageResource(R.drawable.ic_check_circle)
            holder.b.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.delta_primary))
            holder.b.root.setBackgroundColor(ContextCompat.getColor(ctx, R.color.delta_selected_bg))
        } else {
            val kind = FileOps.kindOf(e)
            holder.b.icon.setImageResource(iconFor(kind))
            holder.b.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, colorFor(kind)))
            holder.b.root.setBackgroundColor(Color.TRANSPARENT)
        }

        holder.itemView.setOnClickListener { onClick(e) }
        holder.itemView.setOnLongClickListener {
            onLongClick(e)
            true
        }
    }

    private fun iconFor(kind: Kind): Int = when (kind) {
        Kind.FOLDER -> R.drawable.ic_folder
        Kind.IMAGE -> R.drawable.ic_image
        Kind.VIDEO -> R.drawable.ic_video
        Kind.AUDIO -> R.drawable.ic_audio
        Kind.ARCHIVE -> R.drawable.ic_archive
        Kind.OTHER -> R.drawable.ic_file
    }

    private fun colorFor(kind: Kind): Int = when (kind) {
        Kind.FOLDER -> R.color.kind_folder
        Kind.IMAGE -> R.color.kind_image
        Kind.VIDEO -> R.color.kind_video
        Kind.AUDIO -> R.color.kind_audio
        Kind.ARCHIVE -> R.color.kind_archive
        Kind.OTHER -> R.color.kind_other
    }
}
