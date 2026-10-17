package com.plate.launcher.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.view.*
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.plate.launcher.R
import com.plate.launcher.utils.MultiUserManager.YoukiUser
import com.plate.launcher.utils.ShizukoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UserSwitcherPopup — the user-switching bubble
 *
 * - The background follows bubbleColor (same transparency as the other bubbles)
 * - The header color = the dock's main color (colors[0]), not the bubble's color
 * - The avatar has no background circle — RoundedBitmapDrawable makes the correct circle shape
 */
object UserSwitcherPopup {

    private var activePopup: PopupWindow? = null

    fun show(
        context: Context,
        anchor: View,
        dockColor: Int,          // the dock's color (colors[0]) for the header and buttons
        bubbleColor: Int = android.graphics.Color.argb(255, 28, 28, 30), // the bubble's color (with alpha)
        dockAtTop: Boolean = false,  // FIX: determines the popup's direction (below the dock or above it)
        onAddUserRequested: () -> Unit
    ) {
        activePopup?.dismiss()

        val inflater  = LayoutInflater.from(context)
        val popupView = inflater.inflate(R.layout.popup_user_switcher, null)
        AppFontScaleUtils.applyToViewHierarchy(popupView)

        val headerChip   = popupView.findViewById<LinearLayout>(R.id.switcher_header_chip)
        val headerAvatar = popupView.findViewById<ImageView>(R.id.switcher_current_avatar)
        val headerName   = popupView.findViewById<TextView>(R.id.switcher_current_name)
        val headerIdTv   = popupView.findViewById<TextView>(R.id.switcher_current_id)
        val container    = popupView.findViewById<LinearLayout>(R.id.switcher_users_container)
        val addBtn       = popupView.findViewById<LinearLayout>(R.id.switcher_add_user_btn)

        // ── FIX: the popup's background follows bubbleColor (the bubbles' transparency) ──
        val bubbleAlpha = android.graphics.Color.alpha(bubbleColor)
        popupView.background?.mutate()?.apply {
            setColorFilter(bubbleColor, PorterDuff.Mode.SRC_ATOP)
            alpha = bubbleAlpha
        }

        // ── FIX: if the bubble is light-colored, use black text so it stays readable ──
        val isBubbleLight = isBubbleLight(bubbleColor)
        val nameColor   = if (isBubbleLight) android.graphics.Color.BLACK  else 0xFFFFFFFF.toInt()
        val idColor     = if (isBubbleLight) 0xCC000000.toInt() else 0x99FFFFFF.toInt()
        popupView.findViewById<TextView?>(R.id.switcher_current_name)?.setTextColor(nameColor)
        popupView.findViewById<TextView?>(R.id.switcher_current_id)?.setTextColor(idColor)

        // ── The dock's color on the header and add button (alpha relative to bubbleAlpha) ──
        tintDockColor(headerChip, dockColor, alpha = minOf(255, bubbleAlpha + 60))
        tintDockColor(addBtn,     dockColor, alpha = minOf(160, bubbleAlpha + 30))

        // ── The current user's data ──
        val currentId = MultiUserManager.getCurrentUserId()
        headerIdTv?.text = "ID: $currentId"
        // FIX: was reading only DeviceUtils.getUserName() (the Android system
        // profile name via UserManager.getUserName()), which on stock/
        // non-work-profile devices is frequently a generic OEM default
        // ("Owner", blank, etc.). OnboardingPrefs.getDisplayName() is the name
        // the person actually typed in onboarding's profile step (or any
        // later edit of it) — it already falls back to getUserName() itself
        // when nothing's been set, so this alone covers both cases correctly.
        val displayName = OnboardingPrefs.getDisplayName(context)
        headerName.text = if (!displayName.isNullOrBlank()) displayName else "User"
        loadCircularAvatar(context, headerAvatar, currentId)

        // ── PopupWindow ──
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).also {
            it.elevation          = 24f
            it.isOutsideTouchable = true
            // FIX: Animation_Dialog caused a bounce/jump — we use a simple fade instead
            it.animationStyle     = android.R.style.Animation_Toast
        }
        activePopup = popup

        addBtn.setOnClickListener { popup.dismiss(); onAddUserRequested() }

        // ── Loading the users ──
        // Gap 82: cache currentId once — Gap 90
        val cachedCurrentId = MultiUserManager.getCurrentUserId()
        MultiUserManager.listUsers(context) { users ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Gap 82: ignore the callback if the popup has been closed
                if (!popup.isShowing) return@post
                users.firstOrNull { it.isCurrentUser }?.let { cur ->
                    // Respect the same OnboardingPrefs.getDisplayName() override
                    // as the initial paint above — otherwise this callback
                    // silently stomps it back to the Android system profile
                    // name a moment after the popup opens.
                    headerName.text = if (!displayName.isNullOrBlank()) displayName else cur.name
                    headerIdTv?.text = "ID: ${cur.id}"
                }
                buildUsersList(context, container, users, popup, dockColor)
            }
        }

        // ── Position: above the anchor (dock at bottom) or below it (dock at top) ──
        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // Dock at bottom → popup above the anchor (negative yOffset)
        // Dock at top → popup below the anchor (yOffset zero = the default for showAsDropDown)
        val yOffset = if (dockAtTop) 0
                      else -(anchor.height + popupView.measuredHeight + Utils.dpToPx(context, 8))
        popup.showAsDropDown(anchor, 0, yOffset)
    }

    // ─────────────────────────────────────────────────────────
    private fun buildUsersList(
        context: Context,
        container: LinearLayout,
        users: List<YoukiUser>,
        popup: PopupWindow,
        dockColor: Int
    ) {
        container.removeAllViews()
        val inflater  = LayoutInflater.from(context)
        val currentId = MultiUserManager.getCurrentUserId()

        if (users.isEmpty()) {
            // FIX: removed the fake "current user" fallback that used to always
            // show up as "active" with the current user's ID even if pm list users
            // failed completely. Now: an honest message, and if there's a
            // privilege → a tappable row that opens real diagnostics.
            val shizuku = ShizukoManager.getInstance(context)
            val root    = RootManager.getInstance(context)
            val hasPriv = shizuku.hasPermission || root.isAvailable
            container.addView(buildEmptyView(context, hasPriv) {
                popup.dismiss()
                showDiagnosticsDialog(context)
            })
            return
        }

        users.forEach { user ->
            val row       = inflater.inflate(R.layout.item_user_switcher, container, false)
            val avatarIv  = row.findViewById<ImageView>(R.id.switcher_item_avatar)
            val nameTv    = row.findViewById<TextView>(R.id.switcher_item_name)
            val idTv      = row.findViewById<TextView>(R.id.switcher_item_id)
            val activeDot = row.findViewById<View>(R.id.switcher_item_active_dot)

            // The dock's color on the item — the current one is darker
            tintDockColor(row, dockColor, alpha = if (user.id == currentId) 180 else 100)

            nameTv.text  = user.name
            idTv.text    = "ID: ${user.id}"
            nameTv.alpha = if (user.id == currentId) 1f else 0.75f
            activeDot.visibility = if (user.id == currentId) View.VISIBLE else View.GONE

            loadCircularAvatar(
                context, avatarIv, user.id,
                sysFallback = if (user.id == currentId) DeviceUtils.getUserIcon(context) else null
            )

            row.setOnClickListener {
                if (user.id == currentId) { popup.dismiss(); return@setOnClickListener }
                popup.dismiss()
                MultiUserManager.switchToUser(context, user.id) { _, msg ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            container.addView(row)
        }
    }

    // ── Applies the dock's color via colorFilter + alpha ──
    private fun tintDockColor(view: View, color: Int, alpha: Int) {
        view.background?.mutate()?.apply {
            setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            this.alpha = alpha
        }
    }

    // ── Loads the avatar as a correct circle (with no distortion) ──
    private fun loadCircularAvatar(
        context: Context,
        iv: ImageView,
        userId: Int,
        sysFallback: Bitmap? = null
    ) {
        if (AvatarDisplay.showOn(iv, context, userId)) return

        val bmp = sysFallback
            ?: if (userId == MultiUserManager.getCurrentUserId()) DeviceUtils.getUserIcon(context) else null

        if (bmp != null) {
            iv.setImageBitmap(bmp)
        } else {
            iv.setImageResource(R.drawable.ic_user)
        }
    }

    /**
     * The empty-state row:
     *  - No privilege  → message only
     *  - Has privilege but the list is empty → message + tappable to open real diagnostics
     */
    private fun buildEmptyView(context: Context, hasPrivilege: Boolean, onDiagnose: () -> Unit) =
        TextView(context).apply {
            text = if (hasPrivilege)
                "${context.getString(R.string.could_not_read_users)} — ${context.getString(R.string.tap_to_diagnose)}"
            else
                context.getString(R.string.requires_shizuku_or_root_manage_users)
            setTextColor(0x80FFFFFF.toInt())
            textSize = 12f
            gravity  = Gravity.CENTER
            setPadding(
                Utils.dpToPx(context, 12), Utils.dpToPx(context, 10),
                Utils.dpToPx(context, 12), Utils.dpToPx(context, 10)
            )
            if (hasPrivilege) {
                isClickable = true
                isFocusable = true
                background  = context.getDrawable(R.drawable.popup_item_bg)
                setOnClickListener { onDiagnose() }
            }
        }

    /**
     * Builds the diagnoseUserListing report and shows it in a copyable dialog.
     * This is what reveals why "pm list users" doesn't return real data despite the privilege existing.
     */
    private fun showDiagnosticsDialog(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            val report = withContext(Dispatchers.IO) {
                MultiUserManager.diagnoseUserListing(context)
            }
            val tv = TextView(context).apply {
                text = report
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setPadding(
                    Utils.dpToPx(context, 16), Utils.dpToPx(context, 12),
                    Utils.dpToPx(context, 16), Utils.dpToPx(context, 12)
                )
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.user_diagnostics))
                .setView(ScrollView(context).apply { addView(tv) })
                .setPositiveButton(context.getString(R.string.copy_report)) { _, _ ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("diagnostics", report))
                    Toast.makeText(context, context.getString(R.string.report_copied), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(context.getString(R.string.close), null)
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Public helpers — called by PerfectServer
    // ─────────────────────────────────────────────────────────

    fun updateDockChip(context: Context, avatarIv: ImageView, nameTv: TextView) {
        val name = OnboardingPrefs.getDisplayName(context)
        nameTv.text = if (!name.isNullOrEmpty()) name else context.getString(R.string.the_user)
        loadCircularAvatar(context, avatarIv, MultiUserManager.getCurrentUserId())
    }

    fun updateDockAvatar(context: Context, avatarIv: ImageView?) {
        avatarIv ?: return
        loadCircularAvatar(context, avatarIv, MultiUserManager.getCurrentUserId())
    }

    fun dismiss() {
        activePopup?.dismiss()
        activePopup = null
    }

    // ── Luminance helpers (was Rust) ────────────────────────────────────────

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun relativeLuminance(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
    }

    /** Note: uses this project's own 0.35 threshold, not the WCAG-standard 0.5. */
    private fun isBubbleLight(argb: Int): Boolean = relativeLuminance(argb) > 0.35
}
