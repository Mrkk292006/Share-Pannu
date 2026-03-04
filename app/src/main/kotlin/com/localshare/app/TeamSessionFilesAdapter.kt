package com.localshare.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class TeamSessionFilesAdapter(
    private val onDownload: (TeamFileEntry) -> Unit,
    private val onSave:     (TeamFileEntry) -> Unit,
    private val onDelete:   (TeamFileEntry) -> Unit
) : ListAdapter<TeamFileEntry, TeamSessionFilesAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TeamFileEntry>() {
            override fun areItemsTheSame(a: TeamFileEntry, b: TeamFileEntry) = a.id == b.id
            override fun areContentsTheSame(a: TeamFileEntry, b: TeamFileEntry) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:   TextView = view.findViewById(R.id.tvTeamFileName)
        val tvSize:   TextView = view.findViewById(R.id.tvTeamFileSize)
        val btnDl:    Button   = view.findViewById(R.id.btnTeamDownload)
        val btnSave:  Button   = view.findViewById(R.id.btnTeamSave)
        val btnDel:   Button   = view.findViewById(R.id.btnTeamDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_team_file, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        holder.tvName.text = entry.displayName
        holder.tvSize.text = fmtSize(entry.size)
        holder.btnDl.setOnClickListener   { onDownload(entry) }
        holder.btnSave.setOnClickListener  { onSave(entry) }
        holder.btnDel.setOnClickListener   { onDelete(entry) }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
