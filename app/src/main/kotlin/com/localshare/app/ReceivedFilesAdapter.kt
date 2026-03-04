package com.localshare.app

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Model for a file received from the laptop.
 *
 * @param uri        MediaStore content URI (API 29+) or file:// URI (API 26-28)
 * @param contentId  MediaStore _id (API 29+, used for delete); -1 for API 26-28
 * @param name       Display name
 * @param size       Size in bytes
 * @param modified   Epoch milliseconds
 * @param subfolder  e.g. "Images", "Videos", "Audio", "Documents", "Archives", "Others"
 */
data class ReceivedFileItem(
    val uri: Uri,
    val contentId: Long,
    val name: String,
    val size: Long,
    val modified: Long,
    val subfolder: String
)

class ReceivedFilesAdapter(
    private val onDelete: (ReceivedFileItem) -> Unit
) : RecyclerView.Adapter<ReceivedFilesAdapter.ReceivedVH>() {

    private val items = mutableListOf<ReceivedFileItem>()

    class ReceivedVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName    : TextView    = view.findViewById(R.id.tvReceivedName)
        val tvMeta    : TextView    = view.findViewById(R.id.tvReceivedMeta)
        val btnDelete : ImageButton = view.findViewById(R.id.btnReceivedDelete)
        val btnShare  : ImageButton = view.findViewById(R.id.btnReceivedShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceivedVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_received_file, parent, false)
        return ReceivedVH(view)
    }

    override fun onBindViewHolder(holder: ReceivedVH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name

        val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(item.modified))
        holder.tvMeta.text = "${fmtSize(item.size)}  ·  $dateStr  ·  📂 ${item.subfolder}"

        holder.btnDelete.setOnClickListener {
            onDelete(item)
        }

        holder.btnShare.setOnClickListener { v ->
            try {
                val shareUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // MediaStore content URI — no FileProvider needed
                    item.uri
                } else {
                    // file:// URI needs FileProvider on API 26-28
                    val file = File(item.uri.path ?: return@setOnClickListener)
                    FileProvider.getUriForFile(
                        v.context,
                        "${v.context.packageName}.fileprovider",
                        file
                    )
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                v.context.startActivity(Intent.createChooser(intent, "Share ${item.name}"))
            } catch (e: Exception) {
                Toast.makeText(v.context, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<ReceivedFileItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
