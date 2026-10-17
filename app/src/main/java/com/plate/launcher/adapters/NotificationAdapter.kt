package com.plate.launcher.adapters

import android.app.Notification
import android.app.PendingIntent.CanceledException
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.plate.launcher.R
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.ColorUtils
import com.plate.launcher.utils.Utils

class NotificationAdapter(
    private val context: Context,
    private var notifications: Array<StatusBarNotification>,
    private val listener: OnNotificationClickListener
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    // Gap 94: don't hold onto sharedPreferences — read it when needed or pass it in from outside
    private val actionsHeight = Utils.dpToPx(context, 20)

    interface OnNotificationClickListener {
        fun onNotificationClicked(notification: StatusBarNotification, item: View)
        fun onNotificationLongClicked(notification: StatusBarNotification, item: View)
        fun onNotificationCancelClicked(notification: StatusBarNotification, item: View)
    }

    override fun onCreateViewHolder(parent: ViewGroup, arg1: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.notification_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val sbn          = notifications[position]
        val notification = sbn.notification
        val actions      = notification.actions
        val extras       = notification.extras

        viewHolder.notifActionsLayout.removeAllViews()

        if (actions != null) {
            val actionLayoutParams = LinearLayout.LayoutParams(0, actionsHeight).apply { weight = 1f }
            if (AppUtils.isMediaNotification(notification)) {
                for (action in actions) {
                    val actionIv = ImageView(context)
                    // Gap 79: try/catch for getResourcesForApplication — it can throw NameNotFoundException
                    // Gap 80: action.icon is deprecated on API 23+ — we avoid using it
                    // Gap 81: getDrawable(id) is deprecated — we use ResourcesCompat
                    val drawable: Drawable? = try {
                        val res = context.packageManager.getResourcesForApplication(sbn.packageName)
                        // action.icon = 0 on API 23+ → ignore it and use smallIcon
                        val iconRes = if (action.icon != 0) action.icon else 0
                        if (iconRes != 0)
                            ResourcesCompat.getDrawable(res, iconRes, context.theme)
                        else null
                    } catch (e: Exception) { null }

                    if (drawable != null) {
                        drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                        actionIv.setImageDrawable(drawable)
                    } else {
                        // fallback: use the notification's smallIcon
                        actionIv.setImageIcon(notification.smallIcon)
                        actionIv.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                    }
                    actionIv.setOnClickListener {
                        try { action.actionIntent.send() } catch (e: CanceledException) {}
                    }
                    viewHolder.notifText.isSingleLine = true
                    viewHolder.notifActionsLayout.addView(actionIv, actionLayoutParams)
                }
            } else {
                for (action in actions) {
                    val actionTv = TextView(context).apply {
                        setTextColor(context.getColor(R.color.action))
                        isSingleLine = true
                        text = action.title
                        setOnClickListener {
                            try { action.actionIntent.send() } catch (e: CanceledException) {}
                        }
                    }
                    viewHolder.notifActionsLayout.addView(actionTv, actionLayoutParams)
                }
            }
        }

        var notificationTitle = extras.getString(Notification.EXTRA_TITLE)
            ?: AppUtils.getPackageLabel(context, sbn.packageName)
        val notificationText  = extras.getCharSequence(Notification.EXTRA_TEXT)
        val progress          = extras.getInt(Notification.EXTRA_PROGRESS)
        val formattedProgress = if (progress != 0) " $progress%" else ""

        viewHolder.notifTitle.text = notificationTitle + formattedProgress
        viewHolder.notifText.text  = notificationText

        if (sbn.isClearable) {
            viewHolder.notifCancelBtn.alpha = 1f
            viewHolder.notifCancelBtn.setOnClickListener { view ->
                listener.onNotificationCancelClicked(sbn, view)
            }
        } else viewHolder.notifCancelBtn.alpha = 0f

        if (AppUtils.isMediaNotification(notification) && notification.getLargeIcon() != null) {
            val padding = Utils.dpToPx(context, 0)
            viewHolder.notifIcon.setPadding(padding, padding, padding, padding)
            viewHolder.notifIcon.background = null
            viewHolder.notifIcon.setImageIcon(notification.getLargeIcon())
        } else {
            val iconPadding = Utils.dpToPx(context, 10)
            viewHolder.notifIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            viewHolder.notifIcon.setBackgroundResource(R.drawable.circle)
            ColorUtils.applySecondaryColor(context,
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context),
                viewHolder.notifIcon)
            notification.smallIcon.setTint(Color.WHITE)
            viewHolder.notifIcon.setImageIcon(notification.smallIcon)
        }

        viewHolder.bind(sbn, listener)
    }

    override fun getItemCount() = notifications.size

    // Gap 89: DiffUtil instead of a full notifyDataSetChanged
    fun updateNotifications(newNotifications: Array<StatusBarNotification>) {
        val old = notifications
        notifications = newNotifications
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newNotifications.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == newNotifications[n].key
            override fun areContentsTheSame(o: Int, n: Int) = old[o].key == newNotifications[n].key &&
                old[o].postTime == newNotifications[n].postTime
        })
        diff.dispatchUpdatesTo(this)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val notifIcon          : ImageView   = itemView.findViewById(R.id.notification_icon_iv)
        val notifCancelBtn     : ImageView   = itemView.findViewById(R.id.notification_close_btn)
        val notifTitle         : TextView    = itemView.findViewById(R.id.notification_title_tv)
        val notifText          : TextView    = itemView.findViewById(R.id.notification_text_tv)
        val notifActionsLayout : LinearLayout = itemView.findViewById(R.id.notification_actions_layout)

        fun bind(notification: StatusBarNotification, listener: OnNotificationClickListener) {
            itemView.setOnClickListener      { v -> listener.onNotificationClicked(notification, v) }
            itemView.setOnLongClickListener  { v -> listener.onNotificationLongClicked(notification, v); true }
        }
    }
}
