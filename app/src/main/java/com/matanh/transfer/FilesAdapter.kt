package com.matanh.transfer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilesAdapter(
    private val onShareClick: (DocumentFile) -> Unit,
    private val onDeleteClick: (DocumentFile) -> Unit
) : ListAdapter<DocumentFile, FilesAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = getItem(position)
        holder.bind(file, onShareClick, onDeleteClick)
    }

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val fileSizeAndDate: TextView = itemView.findViewById(R.id.tvFileSizeAndDate)
        // private val fileIcon: ImageView = itemView.findViewById(R.id.ivFileIcon) // For type-specific icons later
        private val menuButton: ImageButton = itemView.findViewById(R.id.btnFileMenu)

        fun bind(
            file: DocumentFile,
            onShareClick: (DocumentFile) -> Unit,
            onDeleteClick: (DocumentFile) -> Unit
        ) {
            fileName.text = file.name
            val sizeStr = Utils.formatFileSize(file.length())
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
            fileSizeAndDate.text = "$sizeStr - $dateStr"

            // TODO: Set fileIcon based on file.type (MIME type)

            menuButton.setOnClickListener { view ->
                showPopupMenu(view.context, view, file, onShareClick, onDeleteClick)
            }
            itemView.setOnClickListener {
                showPopupMenu(it.context, menuButton, file, onShareClick, onDeleteClick) // Or open file
            }
        }

        private fun showPopupMenu(
            context: Context,
            anchor: View,
            file: DocumentFile,
            onShareClick: (DocumentFile) -> Unit,
            onDeleteClick: (DocumentFile) -> Unit
        ) {
            val popup = PopupMenu(context, anchor)
            popup.menuInflater.inflate(R.menu.file_item_menu, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_share_file_item -> {
                        onShareClick(file)
                        true
                    }
                    R.id.action_delete_file_item -> {
                        onDeleteClick(file)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<DocumentFile>() {
        override fun areItemsTheSame(oldItem: DocumentFile, newItem: DocumentFile): Boolean {
            return oldItem.uri == newItem.uri
        }
        override fun areContentsTheSame(oldItem: DocumentFile, newItem: DocumentFile): Boolean {
            // DocumentFile doesn't have easy content hash. Name, size, and lastModified are good proxies.
            return oldItem.name == newItem.name && oldItem.length() == newItem.length() && oldItem.lastModified() == newItem.lastModified()
        }
    }
}