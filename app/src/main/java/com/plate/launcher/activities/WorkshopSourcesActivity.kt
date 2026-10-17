package com.plate.launcher.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.plate.launcher.R
import com.plate.launcher.workshop.data.WorkshopSource
import com.plate.launcher.workshop.data.WorkshopSourceDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * WorkshopSourcesActivity — the Workshop sources management screen
 *
 * - Shows all sources split by category
 * - Add a new category (custom ContentType)
 * - Add a new site within any category
 * - Delete custom sources (isCustom = true)
 * - Enable/disable sources
 */
class WorkshopSourcesActivity : com.plate.launcher.activities.BaseFontScaleActivity() {

    private lateinit var recyclerView   : RecyclerView
    private lateinit var fabAdd         : ExtendedFloatingActionButton
    private lateinit var emptyView      : TextView
    private lateinit var filterChipGroup: ChipGroup

    private lateinit var dao: WorkshopSourceDao

    private var allSources  : List<WorkshopSource> = emptyList()
    private var selectedType: String?               = null  // null = All

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workshop_sources)

        recyclerView    = findViewById(R.id.sources_recycler)
        fabAdd          = findViewById(R.id.fab_add_source)
        emptyView       = findViewById(R.id.sources_empty)
        filterChipGroup = findViewById(R.id.sources_filter_chips)
        dao             = WorkshopSourceDao(applicationContext)

        recyclerView.layoutManager = LinearLayoutManager(this)
        fabAdd.setOnClickListener { showAddDialog() }
        loadSources()
    }

    private fun loadSources() {
        lifecycleScope.launch {
            allSources = try {
                dao.observeAll().first()
            } catch (e: Exception) {
                dao.getAllEnabled()
            }
            runOnUiThread {
                buildFilterChips()
                refreshList()
            }
        }
    }

    private fun buildFilterChips() {
        filterChipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            text        = getString(R.string.all)
            isCheckable = true
            isChecked   = selectedType == null
        }
        allChip.setOnClickListener { selectedType = null; refreshList() }
        filterChipGroup.addView(allChip)

        allSources.map { it.contentType }.distinct().forEach { type ->
            val chip = Chip(this).apply {
                text        = typeDisplayName(type)
                isCheckable = true
                isChecked   = selectedType == type
            }
            chip.setOnClickListener { selectedType = type; refreshList() }
            filterChipGroup.addView(chip)
        }
    }

    private fun refreshList() {
        val filtered = if (selectedType == null) allSources
                       else allSources.filter { it.contentType == selectedType }

        emptyView.visibility    = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE    else View.VISIBLE

        recyclerView.adapter = SourcesAdapter(filtered,
            onToggle = { source, enabled -> toggleSource(source, enabled) },
            onDelete = { source -> deleteSource(source) },
            onEdit   = { source -> editSource(source) }
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Add a new source
    // ════════════════════════════════════════════════════════════════════════

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_workshop_source, null)

        val nameEdit         = view.findViewById<TextInputEditText>(R.id.add_source_name)
        val urlEdit          = view.findViewById<TextInputEditText>(R.id.add_source_url)
        val typeChipGroup    = view.findViewById<ChipGroup>(R.id.add_source_type_chips)
        val customTypeLayout = view.findViewById<TextInputLayout>(R.id.add_source_custom_type_layout)
        val customTypeEdit   = view.findViewById<TextInputEditText>(R.id.add_source_custom_type)

        val builtinTypes  = listOf("WALLPAPER", "FONT", "PLUGIN")
        val existingTypes = allSources.map { it.contentType }.distinct()
        val allTypes      = (builtinTypes + existingTypes).distinct()
        var selectedContentType = allTypes.firstOrNull() ?: "WALLPAPER"

        allTypes.forEach { type ->
            typeChipGroup.addView(Chip(this).apply {
                text        = typeDisplayName(type)
                isCheckable = true
                isChecked   = type == selectedContentType
                tag         = type
                setOnCheckedChangeListener { _, checked ->
                    if (checked) { selectedContentType = type; customTypeLayout.visibility = View.GONE }
                }
            })
        }

        typeChipGroup.addView(Chip(this).apply {
            text = "+ ${getString(R.string.new_category)}"
            isCheckable = true
            setOnCheckedChangeListener { _, checked ->
                customTypeLayout.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) selectedContentType = ""
            }
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_new_source))
            .setView(view)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val name      = nameEdit.text?.toString()?.trim() ?: ""
                val url       = normalizeSourceUrl(urlEdit.text?.toString() ?: "")
                val finalType = if (selectedContentType.isBlank())
                    customTypeEdit.text?.toString()?.trim()?.uppercase() ?: ""
                else selectedContentType

                when (validateSourceFields(name, url, finalType)) {
                    "NAME_BLANK"  -> { snack(getString(R.string.enter_source_name)); return@setPositiveButton }
                    "URL_INVALID" -> { snack(getString(R.string.enter_valid_url)); return@setPositiveButton }
                    "TYPE_BLANK"  -> { snack(getString(R.string.select_or_enter_category)); return@setPositiveButton }
                }

                val searchUrl = buildSearchUrlTemplate(url)

                // No sourceFormat and no forceWebView — they were removed
                addSource(WorkshopSource(
                    searchUrlTemplate = searchUrl,
                    browseUrlTemplate = url.substringBefore("?"),
                    displayName       = name,
                    contentType       = finalType,
                    isCustom          = true,
                    isEnabled         = true
                ))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // DB Operations
    // ════════════════════════════════════════════════════════════════════════

    private fun addSource(source: WorkshopSource) {
        lifecycleScope.launch {
            val result = dao.addCustomSource(source)
            allSources = try { dao.observeAll().first() } catch (e: Exception) { dao.getAllEnabled() }
            runOnUiThread {
                snack(if (result != -1L) getString(R.string.source_added_success, source.displayName) else getString(R.string.source_already_exists))
                buildFilterChips()
                refreshList()
            }
        }
    }

    private fun deleteSource(source: WorkshopSource) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_source_confirm_title, source.displayName))
            .setMessage(getString(R.string.delete_source_confirm_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    dao.delete(source)
                    allSources = try { dao.observeAll().first() } catch (e: Exception) { dao.getAllEnabled() }
                    runOnUiThread {
                        snack(getString(R.string.deleted_successfully))
                        buildFilterChips()
                        refreshList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun editSource(source: WorkshopSource) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_workshop_source, null)

        val nameEdit         = view.findViewById<TextInputEditText>(R.id.add_source_name)
        val urlEdit          = view.findViewById<TextInputEditText>(R.id.add_source_url)
        val typeChipGroup    = view.findViewById<ChipGroup>(R.id.add_source_type_chips)
        val customTypeLayout = view.findViewById<TextInputLayout>(R.id.add_source_custom_type_layout)
        val customTypeEdit   = view.findViewById<TextInputEditText>(R.id.add_source_custom_type)

        nameEdit.setText(source.displayName)
        urlEdit.setText(source.browseUrlTemplate ?: source.searchUrlTemplate)

        val builtinTypes        = listOf("WALLPAPER", "FONT", "PLUGIN")
        val existingTypes       = allSources.map { it.contentType }.distinct()
        val allTypes            = (builtinTypes + existingTypes).distinct()
        var selectedContentType = source.contentType

        allTypes.forEach { type ->
            typeChipGroup.addView(Chip(this).apply {
                text        = typeDisplayName(type)
                isCheckable = true
                isChecked   = type == selectedContentType
                tag         = type
                setOnCheckedChangeListener { _, checked ->
                    if (checked) { selectedContentType = type; customTypeLayout.visibility = View.GONE }
                }
            })
        }

        typeChipGroup.addView(Chip(this).apply {
            text = "+ ${getString(R.string.new_category)}"
            isCheckable = true
            setOnCheckedChangeListener { _, checked ->
                customTypeLayout.visibility = if (checked) View.VISIBLE else View.GONE
                if (checked) selectedContentType = ""
            }
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.edit_source_title, source.displayName))
            .setView(view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name      = nameEdit.text?.toString()?.trim() ?: ""
                val url       = normalizeSourceUrl(urlEdit.text?.toString() ?: "")
                val finalType = if (selectedContentType.isBlank())
                    customTypeEdit.text?.toString()?.trim()?.uppercase() ?: ""
                else selectedContentType

                when (validateSourceFields(name, url, finalType)) {
                    "NAME_BLANK"  -> { snack(getString(R.string.enter_source_name)); return@setPositiveButton }
                    "URL_INVALID" -> { snack(getString(R.string.enter_valid_url)); return@setPositiveButton }
                    "TYPE_BLANK"  -> { snack(getString(R.string.select_category)); return@setPositiveButton }
                }

                val searchUrl = buildSearchUrlTemplate(url)

                lifecycleScope.launch {
                    dao.delete(source)
                    dao.addCustomSource(source.copy(
                        displayName       = name,
                        browseUrlTemplate = url,
                        searchUrlTemplate = searchUrl,
                        contentType       = finalType,
                        isCustom          = true
                    ))
                    allSources = try { dao.observeAll().first() } catch (e: Exception) { dao.getAllEnabled() }
                    runOnUiThread {
                        snack(getString(R.string.edited_successfully))
                        buildFilterChips()
                        refreshList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleSource(source: WorkshopSource, enabled: Boolean) {
        lifecycleScope.launch {
            dao.setEnabled(source.searchUrlTemplate, enabled)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun typeDisplayName(type: String) = when (type) {
        "WALLPAPER" -> getString(R.string.workshop_type_wallpapers)
        "FONT"      -> getString(R.string.workshop_type_fonts)
        "PLUGIN"    -> getString(R.string.workshop_type_plugins)
        else        -> type
    }

    private fun snack(msg: String) =
        Snackbar.make(recyclerView, msg, Snackbar.LENGTH_SHORT).show()

    // ── Source form helpers ──────────────────────────────────────────────

    private fun normalizeSourceUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.isNotEmpty() && !trimmed.startsWith("http")) "https://$trimmed" else trimmed
    }

    private fun buildSearchUrlTemplate(url: String): String = when {
        url.contains("%s") -> url
        url.contains("?")  -> "$url&q=%s"
        else                -> "$url?q=%s"
    }

    /** Returns "NAME_BLANK"/"URL_INVALID"/"TYPE_BLANK", or null if valid. */
    private fun validateSourceFields(name: String, url: String, finalType: String): String? = when {
        name.trim().isEmpty() -> "NAME_BLANK"
        url.trim().isEmpty() || !url.startsWith("http") -> "URL_INVALID"
        finalType.trim().isEmpty() -> "TYPE_BLANK"
        else -> null
    }

    // ════════════════════════════════════════════════════════════════════════
    // Adapter
    // ════════════════════════════════════════════════════════════════════════

    inner class SourcesAdapter(
        private val sources : List<WorkshopSource>,
        private val onToggle: (WorkshopSource, Boolean) -> Unit,
        private val onDelete: (WorkshopSource) -> Unit,
        private val onEdit  : (WorkshopSource) -> Unit
    ) : RecyclerView.Adapter<SourcesAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name   : TextView = view.findViewById(R.id.source_item_name)
            val type   : TextView = view.findViewById(R.id.source_item_type)
            val url    : TextView = view.findViewById(R.id.source_item_url)
            val badge  : TextView = view.findViewById(R.id.source_item_badge)
            val toggle : com.google.android.material.materialswitch.MaterialSwitch = view.findViewById(R.id.source_item_toggle)
            val btnDel : MaterialButton = view.findViewById(R.id.source_item_delete)
            val btnEdit: MaterialButton = view.findViewById(R.id.source_item_edit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_workshop_source, parent, false))

        override fun getItemCount() = sources.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val src = sources[position]
            holder.name.text  = src.displayName
            holder.type.text  = typeDisplayName(src.contentType)
            holder.url.text   = src.browseUrlTemplate ?: src.searchUrlTemplate
            holder.badge.text = if (src.isCustom) getString(R.string.custom) else getString(R.string.default_label)
            holder.badge.alpha = if (src.isCustom) 1f else 0.5f

            holder.toggle.isChecked = src.isEnabled
            holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(src, checked) }

            holder.btnDel.visibility  = View.VISIBLE
            holder.btnEdit.visibility = View.VISIBLE
            holder.btnDel.setOnClickListener  { onDelete(src) }
            holder.btnEdit.setOnClickListener { onEdit(src) }
        }
    }
}
