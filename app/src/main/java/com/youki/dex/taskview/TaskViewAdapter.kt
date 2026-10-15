package com.youki.dex.taskview

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.youki.dex.R
import com.youki.dex.models.AppTask

/**
 * Adapter for the Task View overview cards matching the desktop reference design:
 * Preview window card with top-right close badge + circular app icon badge beneath it.
 */
class TaskViewAdapter(
    private val context: Context,
    private val tasks: ArrayList<AppTask>,
    private val snapshots: Map<Int, Bitmap>? = null,
    private val onTaskClick: (AppTask, View) -> Unit,
    private val onTaskClose: (AppTask, Int) -> Unit
) : RecyclerView.Adapter<TaskViewAdapter.TaskViewHolder>() {

    private var selectedPosition = -1

    class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: View = itemView.findViewById(R.id.task_tile_root)
        val cardWindow: View = itemView.findViewById(R.id.task_card_window)
        val previewIcon: ImageView = itemView.findViewById(R.id.task_tile_preview_icon)
        val snapshotIv: ImageView = itemView.findViewById(R.id.task_tile_snapshot_iv)
        val closeBtn: View = itemView.findViewById(R.id.task_tile_close_btn)
        val iconBadgeContainer: View = itemView.findViewById(R.id.task_icon_badge_container)
        val badgeIcon: ImageView = itemView.findViewById(R.id.task_tile_badge_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_view_grid_tile, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = tasks[position]
        val isSelected = position == selectedPosition

        // App icons
        holder.previewIcon.setImageDrawable(task.icon)
        holder.badgeIcon.setImageDrawable(task.icon)

        // Snapshot if present
        val snapshot = snapshots?.get(task.id)
        if (snapshot != null) {
            holder.snapshotIv.setImageBitmap(snapshot)
            holder.snapshotIv.visibility = View.VISIBLE
            holder.previewIcon.visibility = View.GONE
        } else {
            holder.snapshotIv.visibility = View.GONE
            holder.previewIcon.visibility = View.VISIBLE
        }

        // Selection styling
        if (isSelected) {
            holder.cardWindow.setBackgroundResource(R.drawable.bg_task_preview_card_selected)
            holder.root.scaleX = 1.04f
            holder.root.scaleY = 1.04f
        } else {
            holder.cardWindow.setBackgroundResource(R.drawable.bg_task_preview_card)
            holder.root.scaleX = 1.0f
            holder.root.scaleY = 1.0f
        }

        // Tapping either preview window or icon badge opens the task
        val clickListener = View.OnClickListener {
            setSelectedPosition(holder.bindingAdapterPosition)
            onTaskClick(task, holder.root)
        }
        holder.cardWindow.setOnClickListener(clickListener)
        holder.iconBadgeContainer.setOnClickListener(clickListener)

        // Close button
        holder.closeBtn.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION && currentPos < tasks.size) {
                onTaskClose(tasks[currentPos], currentPos)
            }
        }
    }

    override fun getItemCount(): Int = tasks.size

    fun getSelectedPosition(): Int = selectedPosition

    fun setSelectedPosition(pos: Int) {
        val prev = selectedPosition
        selectedPosition = pos.coerceIn(-1, tasks.size - 1)
        if (prev != -1) notifyItemChanged(prev)
        if (selectedPosition != -1) notifyItemChanged(selectedPosition)
    }

    fun getTaskAt(pos: Int): AppTask? = tasks.getOrNull(pos)

    fun removeTaskAt(position: Int) {
        if (position in 0 until tasks.size) {
            tasks.removeAt(position)
            notifyItemRemoved(position)
            if (selectedPosition >= tasks.size) {
                selectedPosition = tasks.size - 1
            }
        }
    }

    fun clearAll() {
        val count = tasks.size
        tasks.clear()
        selectedPosition = -1
        notifyItemRangeRemoved(0, count)
    }
}
