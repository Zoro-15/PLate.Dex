package com.plate.launcher.adapters

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.plate.launcher.R
import com.plate.launcher.models.App
import com.plate.launcher.utils.ColorUtils
import com.plate.launcher.utils.IconPackUtils
import com.plate.launcher.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

class AppAdapter(
    private val context: Context,
    private var apps: List<App>,
    private val listener: OnAppClickListener,
    private val large: Boolean,
    private val iconPackUtils: IconPackUtils?
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private val allApps: ArrayList<App> = ArrayList(apps)
    private var iconBackground = 0
    private val iconPadding: Int
    private val singleLine: Boolean
    private val isWinStyle: Boolean
    // Gap 50: ConcurrentHashMap instead of HashMap — thread-safe
    private val dominantColorCache = ConcurrentHashMap<String, Int>()
    private var query: String = ""
    // PERF: Single shared scope — avoids new CoroutineScope per onBindViewHolder call
    private val adapterScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    interface OnAppClickListener {
        fun onAppClicked(app: App, item: View)
        fun onAppLongClicked(app: App, item: View)
    }

    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        // Gap 53: toIntOrNull instead of toInt() to avoid a NumberFormatException
        iconPadding = Utils.dpToPx(context,
            sharedPreferences.getString("icon_padding", "5")?.toIntOrNull() ?: 5)
        singleLine = sharedPreferences.getBoolean("single_line_labels", true)
        val shape = sharedPreferences.getString("icon_shape", "circle")
        isWinStyle = shape == "win"
        when (shape) {
            "circle"     -> iconBackground = R.drawable.circle
            "round_rect" -> iconBackground = R.drawable.round_square
            "win"        -> iconBackground = R.drawable.win_square
            else         -> iconBackground = -1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, arg1: Int): ViewHolder {
        // Gap 51: pass parent with attachToRoot=false to apply the correct LayoutParams
        val itemLayoutView = LayoutInflater.from(context)
            .inflate(if (large) R.layout.app_entry_large else R.layout.app_entry, parent, false)
        return ViewHolder(itemLayoutView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val app = apps[position]
        val name = app.name

        if (query.isNotEmpty()) {
            val spanStart = name.lowercase(Locale.getDefault())
                .indexOf(query.lowercase(Locale.getDefault()))
            if (spanStart != -1) {
                val spannable = SpannableString(name)
                spannable.setSpan(StyleSpan(Typeface.BOLD),
                    spanStart, spanStart + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                viewHolder.nameTv.text = spannable
            } else viewHolder.nameTv.text = name
        } else viewHolder.nameTv.text = name

        // FIX: getArabicTypeface/getLatinTypeface cached → no disk read on every bind
        // applyToTextView adds a TextWatcher → updated text picks up the font automatically
        com.plate.launcher.utils.FontManager.applyToTextView(
            viewHolder.nameTv,
            com.plate.launcher.utils.FontManager.getArabicTypeface(context),
            com.plate.launcher.utils.FontManager.getLatinTypeface(context)
        )

        val iconDrawable = iconPackUtils?.getAppThemedIcon(app.packageName) ?: app.icon
        viewHolder.iconIv.setImageDrawable(iconDrawable)

        // Scale the icon and label font size up/down based on the user's setting
        com.plate.launcher.utils.IconScaleUtils.applyIconSize(viewHolder.iconIv, context, isDockIcon = false)
        com.plate.launcher.utils.IconScaleUtils.applyLabelFontSize(viewHolder.nameTv, context, isDockLabel = false)

        if (iconBackground != -1) {
            val pad = if (isWinStyle) Utils.dpToPx(context, 7) else iconPadding
            viewHolder.iconIv.setPadding(pad, pad, pad, pad)
            viewHolder.iconIv.setBackgroundResource(iconBackground)
            if (isWinStyle) {
                viewHolder.iconIv.background?.clearColorFilter()
            } else if (iconDrawable != null) {
                // Gap 49: computed on an IO thread — doesn't slow down the UI
                val cached = dominantColorCache[app.packageName]
                if (cached != null) {
                    viewHolder.iconIv.background?.setColorFilter(cached,
                        android.graphics.PorterDuff.Mode.SRC_ATOP)
                } else {
                    adapterScope.launch {
                        val color = ColorUtils.getDrawableDominantColor(iconDrawable)
                        dominantColorCache[app.packageName] = color
                        withContext(Dispatchers.Main) {
                            viewHolder.iconIv.background?.setColorFilter(color,
                                android.graphics.PorterDuff.Mode.SRC_ATOP)
                        }
                    }
                }
            }
        }
        viewHolder.bind(app, listener)
    }

    override fun getItemCount() = apps.size

    fun updateApps(newApps: List<App>) {
        // Gap 60: clear the cache when apps update (the icon pack may have changed)
        dominantColorCache.clear()
        // Gap 58: update allApps atomically instead of separate clear+addAll calls
        val newList = ArrayList(newApps)
        this.allApps.clear()
        this.allApps.addAll(newList)

        if (query.isNotEmpty()) {
            filter(query)
        } else {
            // Gap 89: DiffUtil instead of a full notifyDataSetChanged
            applyDiff(this.apps, newApps)
            this.apps = newApps
        }
    }

    fun filter(query: String) {
        this.query = query
        val newApps: List<App> = if (query.length > 1) {
            if (isCalcExpression(query)) {
                listOf(App(
                    Utils.solve(query).toString(),
                    context.packageName + ".calc",
                    ResourcesCompat.getDrawable(context.resources, R.drawable.ic_calculator, context.theme)!!
                ))
            } else {
                val lower = query.lowercase(Locale.getDefault())
                allApps.filter { it.name.lowercase(Locale.getDefault()).contains(lower) }
            }
        } else allApps.toList()

        // Gap 48: always notifyDataSetChanged after apps change
        applyDiff(this.apps, newApps)
        this.apps = newApps
    }

    /** Whether [text] is a two-operand arithmetic expression (e.g. "12+3", "5*2.5") — used to route app-drawer search into the inline calculator. */
    private fun isCalcExpression(text: String): Boolean {
        fun numberLen(s: String, start: Int): Int? {
            var i = start
            while (i < s.length && s[i].isDigit()) i++
            if (i == start) return null // needs at least one digit before an optional '.'
            if (i < s.length && s[i] == '.') {
                var j = i + 1
                while (j < s.length && s[j].isDigit()) j++
                if (j == i + 1) return null // '.' with no digits after it
                return j - start
            }
            return i - start
        }

        val firstLen = numberLen(text, 0) ?: return false
        if (firstLen >= text.length) return false // need an operator + second number after it
        if (text[firstLen] !in charArrayOf('+', '-', '*', '/')) return false
        val restStart = firstLen + 1
        val restLen = numberLen(text, restStart) ?: return false
        return restStart + restLen == text.length // anchored: nothing left over
    }

    private fun applyDiff(old: List<App>, new: List<App>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].packageName == new[n].packageName
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        })
        diff.dispatchUpdatesTo(this)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var iconIv: ImageView = itemView.findViewById(R.id.app_icon_iv)
        var nameTv: TextView  = itemView.findViewById(R.id.app_name_tv)
        init { nameTv.maxLines = if (singleLine) 1 else 2 }

        fun bind(app: App, listener: OnAppClickListener) {
            // Same opt-in double-click behavior as DockAppAdapter — see that
            // file's bind() for the full reasoning on the "require_double_click_apps"
            // setting and why a single click intentionally no-ops when it's on.
            val prefs = PreferenceManager.getDefaultSharedPreferences(itemView.context)
            if (prefs.getBoolean("require_double_click_apps", false)) {
                val gestureDetector = android.view.GestureDetector(
                    itemView.context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            listener.onAppClicked(app, itemView)
                            return true
                        }
                    }
                )
                itemView.setOnClickListener(null)
                itemView.setOnTouchListener { v, e ->
                    if (e.buttonState == MotionEvent.BUTTON_SECONDARY) {
                        listener.onAppLongClicked(app, v); true
                    } else {
                        gestureDetector.onTouchEvent(e)
                        v.performClick()
                        true
                    }
                }
            } else {
                itemView.setOnClickListener      { v -> listener.onAppClicked(app, v) }
                itemView.setOnTouchListener      { v, e ->
                    if (e.buttonState == MotionEvent.BUTTON_SECONDARY) {
                        listener.onAppLongClicked(app, v); true
                    } else false
                }
            }
            itemView.setOnLongClickListener  { v -> listener.onAppLongClicked(app, v); true }
        }
    }
}
