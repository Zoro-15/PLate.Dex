package com.plate.launcher.fragments

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.plate.launcher.R
import com.plate.launcher.plugins.PluginInstaller
import com.plate.launcher.plugins.PluginInstaller.InstallState
import com.plate.launcher.plugins.PluginManager
import com.plate.launcher.plugins.YoukiPlugin
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager
import kotlinx.coroutines.launch

class PluginStoreFragment : Fragment() {

    private lateinit var installingSection:        LinearLayout
    private lateinit var installProgressContainer: LinearLayout
    private lateinit var installedContainer:       LinearLayout
    private lateinit var emptyCard:                View
    private lateinit var installFab:               ExtendedFloatingActionButton

    private val pickZips = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        startInstallation(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_plugin_store, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        installingSection        = view.findViewById(R.id.ps_installing_section)
        installProgressContainer = view.findViewById(R.id.ps_install_progress_container)
        installedContainer       = view.findViewById(R.id.ps_installed_container)
        emptyCard                = view.findViewById(R.id.ps_empty_card)
        installFab               = view.findViewById(R.id.ps_install_fab)

        installFab.setOnClickListener {
            pickZips.launch(arrayOf("application/zip", "*/*"))
        }

        loadInstalledPlugins()
    }

    override fun onResume() { super.onResume(); loadInstalledPlugins() }

    // ── Load installed plugins ────────────────────────────────────────────────
    private fun loadInstalledPlugins() {
        val plugins = try {
            PluginManager.listPlugins(requireContext())
        } catch (e: Exception) { emptyList() }

        installedContainer.removeAllViews()
        if (plugins.isEmpty()) {
            emptyCard.visibility = View.VISIBLE
        } else {
            emptyCard.visibility = View.GONE
            plugins.forEach { plugin ->
                try {
                    installedContainer.addView(buildMagiskCard(plugin))
                } catch (e: Exception) { /* skip broken plugin, continue */ }
            }
        }
    }

    // ── Magisk-style module card ──────────────────────────────────────────────
    private fun buildMagiskCard(plugin: YoukiPlugin): View {
        val ctx = requireContext()
        val dp  = { n: Float -> (n * resources.displayMetrics.density).toInt() }

        fun themeColor(attr: Int): Int {
            val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
            val c  = ta.getColor(0, 0xFFFFFFFF.toInt())
            ta.recycle()
            return c
        }

        // ── Root card ─────────────────────────────────────────────────────────
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.marginStart  = dp(12f)
                it.marginEnd    = dp(12f)
                it.topMargin    = dp(6f)
                it.bottomMargin = dp(6f)
            }
            radius          = dp(16f).toFloat()
            strokeWidth     = dp(1f)
            strokeColor     = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
            cardElevation   = dp(2f).toFloat()
            setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }

        val cardInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // ── Top row: icon + info + toggle ─────────────────────────────────────
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(16f), dp(12f), dp(10f))
        }

        // The plugin icon inside a colored circle (like Magisk)
        val iconWrapper = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)).also {
                it.marginEnd = dp(14f)
            }
            val bg = GradientDrawable().apply {
                shape         = GradientDrawable.OVAL
                setColor(themeColor(com.google.android.material.R.attr.colorPrimaryContainer))
            }
            background = bg
        }
        val iconIv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(28f), dp(28f), android.view.Gravity.CENTER)
            scaleType    = ImageView.ScaleType.CENTER_CROP
            val loaded = if (plugin.hasIcon) {
                try { BitmapFactory.decodeFile(plugin.iconPath); } catch (e: Exception) { null }
            } else null

            if (loaded != null) {
                setImageBitmap(loaded)
            } else {
                setImageResource(R.drawable.ic_plugin)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
                )
            }
        }
        iconWrapper.addView(iconIv)

        // The name and meta column
        val infoCol = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val nameTv = TextView(ctx).apply {
            text      = plugin.name.ifEmpty { plugin.id }
            textSize  = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Version · Author
        val metaLine = buildString {
            val v = plugin.version.ifEmpty { "1.0" }
            val a = plugin.author.ifEmpty { "Unknown" }
            append("v$v")
            if (a.isNotEmpty() && a != "Unknown") append("  ·  $a")
        }
        val metaTv = TextView(ctx).apply {
            text     = metaLine
            textSize = 11f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }

        // Root badge if it requires Root
        if (plugin.requiresRoot) {
            val rootBadge = TextView(ctx).apply {
                text      = "  ROOT  "
                textSize  = 9f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnErrorContainer))
                background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f).toFloat()
                    setColor(themeColor(com.google.android.material.R.attr.colorErrorContainer))
                }
                layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                    it.topMargin = dp(4f)
                }
            }
            infoCol.addView(nameTv)
            infoCol.addView(metaTv)
            infoCol.addView(rootBadge)
        } else {
            infoCol.addView(nameTv)
            infoCol.addView(metaTv)
        }

        // Switch toggle — SwitchMaterial like the rest of the app
        var active = plugin.isActive
        val toggle = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            isChecked    = active
            layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                it.marginStart = dp(8f)
            }
            setOnCheckedChangeListener(null)
        }

        topRow.addView(iconWrapper)
        topRow.addView(infoCol)
        topRow.addView(toggle)
        cardInner.addView(topRow)

        // ── Description (unchanged, below) ──────────────────────────────────────
        if (plugin.description.isNotEmpty()) {
            val descTv = TextView(ctx).apply {
                text     = plugin.description
                textSize = 12f
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                    it.marginStart  = dp(78f)
                    it.marginEnd    = dp(16f)
                    it.bottomMargin = dp(10f)
                }
            }
            cardInner.addView(descTv)
        }

        // ── Divider ───────────────────────────────────────────────────────────
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 1).also {
                it.marginStart = dp(78f)
            }
            val ta = ctx.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.colorOutlineVariant)
            )
            setBackgroundColor(ta.getColor(0, 0x22FFFFFF))
            ta.recycle()
            alpha = 0.6f
        }
        cardInner.addView(divider)

        // ── Action row: delete + update (like Magisk) ─────────────────────────────
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
        }

        // Delete button — a TonalButton with the same FAB colors (primaryContainer)
        val deleteBtn = MaterialButton(
            android.view.ContextThemeWrapper(
                ctx,
                com.google.android.material.R.style.Widget_Material3_Button_TonalButton
            ),
            null,
            0
        ).apply {
            text = getString(R.string.delete)
            textSize = 13f
            val contentColor = android.content.res.ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
            )
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorPrimaryContainer)
            )
            setTextColor(contentColor)
            icon        = ContextCompat.getDrawable(ctx, R.drawable.ic_uninstall)
            iconTint    = contentColor
            iconGravity  = MaterialButton.ICON_GRAVITY_START
            iconSize     = dp(16f)
            iconPadding  = dp(4f)
            insetTop     = 0
            insetBottom  = 0
            cornerRadius = dp(50f)  // smooth pill edges like the FAB
        }

        actionRow.addView(deleteBtn)
        cardInner.addView(actionRow)

        card.addView(cardInner)

        // ── Handlers ──────────────────────────────────────────────────────────
        toggle.setOnCheckedChangeListener { _, isChecked ->
            active = isChecked
            PluginManager.setActive(requireContext(), plugin.id, active)
            try { runPluginScript(plugin, activate = active) } catch (e: Exception) {}
            val msg = if (active) "${plugin.name} — ${getString(R.string.enabled)}" else "${plugin.name} — ${getString(R.string.disabled)}"
            snack(msg)
        }

        // Tapping the whole card toggles the switch
        card.setOnClickListener {
            toggle.isChecked = !toggle.isChecked
        }

        deleteBtn.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_plugin_confirm_title, plugin.name))
                .setMessage(getString(R.string.delete_plugin_confirm_message))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    try {
                        if (active) runPluginScript(plugin, activate = false)
                    } catch (e: Exception) {}
                    try {
                        PluginManager.uninstall(requireContext(), plugin)
                    } catch (e: Exception) {}
                    loadInstalledPlugins()
                    snack(getString(R.string.plugin_deleted, plugin.name))
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        return card
    }

    // ── Installation ──────────────────────────────────────────────────────────
    private fun startInstallation(uris: List<android.net.Uri>) {
        installingSection.visibility = View.VISIBLE
        installProgressContainer.removeAllViews()

        val rows = uris.associateWith { uri ->
            buildProgressCard(uri.lastPathSegment ?: "plugin.zip")
                .also { installProgressContainer.addView(it.root) }
        }

        lifecycleScope.launch {
            try {
                PluginInstaller.installAll(requireContext(), uris) { uri, state ->
                    rows[uri]?.update(state)
                }
            } catch (e: Exception) {}

            installingSection.postDelayed({
                try {
                    installingSection.visibility = View.GONE
                    installProgressContainer.removeAllViews()
                    loadInstalledPlugins()
                } catch (e: Exception) {}
            }, 1800)
        }
    }

    // ── Progress card — Magisk style ──────────────────────────────────────────
    private inner class ProgressRow(
        val root:        View,
        val nameTv:      TextView,
        val statusTv:    TextView,
        val progressBar: LinearProgressIndicator,
        val statusIcon:  TextView
    ) {
        fun update(state: InstallState) {
            try {
                when (state) {
                    is InstallState.Queued   -> {
                        statusIcon.text = "..."
                        statusTv.text   = getString(R.string.waiting_ellipsis)
                        progressBar.isIndeterminate = true
                    }
                    is InstallState.Progress -> {
                        statusIcon.text = "..."
                        statusTv.text   = state.step
                        progressBar.isIndeterminate = state.pct < 20
                        if (state.pct >= 20) {
                            progressBar.isIndeterminate = false
                            progressBar.progress = state.pct
                        }
                    }
                    is InstallState.Success  -> {
                        statusIcon.text = "OK"
                        nameTv.text     = state.plugin.name
                        statusTv.text   = getString(R.string.installed_successfully)
                        progressBar.progress = 100
                        progressBar.isIndeterminate = false
                    }
                    is InstallState.Failed   -> {
                        statusIcon.text = "X"
                        statusTv.text   = state.reason
                        progressBar.isIndeterminate = false
                        progressBar.progress = 0
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun buildProgressCard(filename: String): ProgressRow {
        val ctx = requireContext()
        val dp  = { n: Float -> (n * resources.displayMetrics.density).toInt() }

        fun themeColor(attr: Int): Int {
            val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
            val c  = ta.getColor(0, 0xFF888888.toInt())
            ta.recycle()
            return c
        }

        // A MaterialCardView with Magisk styling
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.marginStart  = dp(12f)
                it.marginEnd    = dp(12f)
                it.topMargin    = dp(4f)
                it.bottomMargin = dp(4f)
            }
            radius        = dp(16f).toFloat()
            strokeWidth   = dp(1f)
            strokeColor   = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
            cardElevation = dp(2f).toFloat()
            setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        }

        val row1 = LinearLayout(ctx).apply {
            orientation  = LinearLayout.HORIZONTAL
            gravity      = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10f) }
        }

        val icon = TextView(ctx).apply {
            text = "..."; textSize = 20f
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(12f) }
        }

        val col = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val nameTv = TextView(ctx).apply {
            text = filename.substringBeforeLast('.')
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
        }
        val statusTv = TextView(ctx).apply {
            text = getString(R.string.waiting_ellipsis); textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        col.addView(nameTv); col.addView(statusTv)
        row1.addView(icon); row1.addView(col)

        val progressBar = LinearProgressIndicator(ctx).apply {
            layoutParams      = LinearLayout.LayoutParams(-1, -2)
            isIndeterminate   = true
            trackCornerRadius = dp(4f)
        }

        inner.addView(row1); inner.addView(progressBar)
        card.addView(inner)

        return ProgressRow(card, nameTv, statusTv, progressBar, icon)
    }

    private fun runPluginScript(plugin: YoukiPlugin, activate: Boolean) {
        val ctx = requireContext()
        val cmd = try {
            if (activate) PluginManager.buildActivateCmd(plugin)
            else          PluginManager.buildDeactivateCmd(plugin)
        } catch (e: Exception) { null }

        // null = an always-active plugin or one without a script → no command needed, just save the state
        if (cmd == null) return

        val shizuku = ShizukoManager.getInstance(ctx)
        val root    = RootManager.getInstance(ctx)
        try {
            when {
                root.isAvailable      -> root.runShell(cmd, lifecycleScope) {}
                shizuku.hasPermission -> shizuku.runShell(cmd, lifecycleScope) {}
            }
        } catch (e: Exception) {}
    }

    private fun snack(msg: String) = try {
        Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show()
    } catch (e: Exception) {}
}
