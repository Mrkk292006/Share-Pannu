package com.localshare.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class LogEntry(val message: String)

class TransferLogAdapter : RecyclerView.Adapter<TransferLogAdapter.LogVH>() {

    private val items = mutableListOf<LogEntry>()

    class LogVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        view.setBackgroundColor(0x00000000)
        return LogVH(view)
    }

    override fun onBindViewHolder(holder: LogVH, position: Int) {
        val msg = items[position].message
        holder.text.text = msg
        holder.text.typeface = android.graphics.Typeface.MONOSPACE
        holder.text.textSize = 12f

        // Color code by prefix
        holder.text.setTextColor(when {
            msg.contains("[✓]") -> 0xFF00FF88.toInt()   // neon green
            msg.contains("[⇄]") -> 0xFF00D4FF.toInt()   // cyan
            msg.contains("[✗]") -> 0xFFFF4444.toInt()   // red
            msg.contains("→")   -> 0xFF888888.toInt()   // grey
            else                -> 0xFFCCCCCC.toInt()   // default light
        })
    }

    override fun getItemCount() = items.size

    fun addEntry(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        items.add(LogEntry("[$ts] $msg"))
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
