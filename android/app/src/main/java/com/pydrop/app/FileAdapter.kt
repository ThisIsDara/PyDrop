package com.pydrop.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DecimalFormat

class FileAdapter(
    private val files: List<ReceivedFile>,
    private val onClick: (ReceivedFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvFileIcon)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvSize: TextView = view.findViewById(R.id.tvFileSize)
        val btnDownload: Button = view.findViewById(R.id.btnDownload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.tvName.text = file.name
        holder.tvSize.text = formatSize(file.size)
        holder.tvIcon.text = getFileIcon(file.name)
        holder.btnDownload.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = files.size

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun getFileIcon(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when(ext) {
            "pdf" -> "📕"
            "doc", "docx" -> "📘"
            "xls", "xlsx" -> "📗"
            "zip", "rar", "7z" -> "📦"
            "mp3", "wav" -> "🎵"
            "mp4", "avi", "mkv" -> "🎬"
            "jpg", "jpeg", "png", "gif", "webp" -> "🖼️"
            else -> "📄"
        }
    }
}
