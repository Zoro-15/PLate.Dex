package com.plate.launcher.fragments

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.plate.launcher.R
import com.plate.launcher.adapters.UserAdapter
import com.plate.launcher.utils.MultiUserManager
import com.plate.launcher.utils.MultiUserManager.YoukiUser
import com.plate.launcher.utils.RootManager
import com.plate.launcher.utils.ShizukoManager
import kotlinx.coroutines.*

/**
 * MultiUserFragment — v2
 *
 * Same structure as v1 (RecyclerView + sealed UiState) but:
 *  - The header is now simple and fixed (matching fragment_plugin_store)
 *  - users_count_tv acts as a dynamic subtitle line under the title
 *  - The card shares the same DNA as the plugin card (UserAdapter v2)
 */
class MultiUserFragment : Fragment() {

    // ─────────────────────────────────────────────────────────────
    //  UI state
    // ─────────────────────────────────────────────────────────────

    private sealed class UiState {
        object Loading     : UiState()
        object NoPrivilege : UiState()
        // FIX: a new state — it has Shizuku/Root but the list actually came back empty
        // (instead of showing a fake fallback, we show an honest message + a diagnostics button)
        object EmptyResult : UiState()
        data class Success(val users: List<YoukiUser>) : UiState()
    }

    // ─────────────────────────────────────────────────────────────
    //  Views
    // ─────────────────────────────────────────────────────────────

    private lateinit var usersRv: RecyclerView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var emptyView: View
    private lateinit var emptyTitleTv: TextView
    private lateinit var emptySubtitleTv: TextView
    private lateinit var emptyDiagnoseBtn: MaterialButton
    private lateinit var addUserFab: ExtendedFloatingActionButton
    private lateinit var privilegeBadge: TextView
    private lateinit var usersCountTv: TextView

    // ─────────────────────────────────────────────────────────────
    //  Adapter & state
    // ─────────────────────────────────────────────────────────────

    private val adapter = UserAdapter(
        onSwitch      = ::confirmSwitch,
        onDelete      = ::confirmDelete,
        onAvatarClick = ::pickAvatarFor
    )

    private lateinit var shizuku: ShizukoManager

    private var pendingAvatarUserId: Int = -1
    private var fetchJob: Job? = null

    // ─────────────────────────────────────────────────────────────
    //  Image picker
    // ─────────────────────────────────────────────────────────────

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val userId = pendingAvatarUserId.also { pendingAvatarUserId = -1 }
        if (userId == -1) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult

        viewLifecycleOwner.lifecycleScope.launch {
            if (isLikelyGif(uri)) {
                // GIF avatar ("بروفايلك جيفت") — kept at its original
                // aspect/animation rather than square-cropped like a still
                // photo; see MultiUserManager.setUserAvatarFromPickedMedia
                // for how the first frame still gets registered as the
                // system user icon.
                val (_, message) = MultiUserManager.setUserAvatarFromPickedMedia(requireContext(), userId, uri)
                showSnack(message)
                refreshUsers()
                notifyProfileUpdated()
            } else {
                // Bug fix — removed the manual crop screen — CropAvatarActivity.
                // that screen turned out to be a bad experience in practice
                // (a black-circle-with-no-visible-confirm-button bug), so
                // this goes back to an automatic center-square crop, applied
                // immediately with no screen in between. See AvatarAutoCrop's
                // kdoc — this produces the same result the manual screen did
                // at its default (untouched) zoom/pan state, which is what
                // most people ended up with anyway.
                val bmp = withContext(Dispatchers.IO) {
                    com.plate.launcher.utils.AvatarAutoCrop.cropToSquare(requireContext(), uri)
                }
                if (bmp == null) {
                    showSnack(getString(R.string.crop_avatar_load_failed))
                    return@launch
                }
                val (_, message) = MultiUserManager.setUserAvatarFromCroppedBitmap(requireContext(), userId, bmp)
                showSnack(message)
                refreshUsers()
                notifyProfileUpdated()
            }
        }
    }

    /** Cheap magic-bytes sniff (via MultiUserManager.isGif) so a .gif with a
     *  wrong/missing MIME type from some content providers is still
     *  detected correctly, rather than relying on the picker's MIME hint. */
    private fun isLikelyGif(uri: Uri): Boolean = try {
        val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes != null && MultiUserManager.isGif(bytes)
    } catch (e: Exception) { false }

    /**
     * Broadcasts ACTION_REFRESH_USER_PROFILE so the dock (PerfectServer)
     * updates the user button immediately — without the user needing to restart the app.
     */
    private fun notifyProfileUpdated() {
        try {
            requireContext().sendBroadcast(
                Intent(com.plate.launcher.services.DOCK_SERVICE_ACTION)
                    .setPackage(requireContext().packageName)
                    .putExtra("action", com.plate.launcher.services.ACTION_REFRESH_USER_PROFILE)
            )
        } catch (e: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_multi_user, container, false)

        usersRv          = v.findViewById(R.id.users_rv)
        progressBar      = v.findViewById(R.id.multi_user_progress)
        emptyView        = v.findViewById(R.id.empty_view)
        emptyTitleTv     = v.findViewById(R.id.empty_title_tv)
        emptySubtitleTv  = v.findViewById(R.id.empty_subtitle_tv)
        emptyDiagnoseBtn = v.findViewById(R.id.empty_diagnose_btn)
        addUserFab       = v.findViewById(R.id.add_user_fab)
        privilegeBadge   = v.findViewById(R.id.privilege_badge)
        usersCountTv     = v.findViewById(R.id.users_count_tv)

        emptyDiagnoseBtn.setOnClickListener { showDiagnosticsDialog() }

        shizuku = ShizukoManager.getInstance(requireContext())

        // ── RecyclerView
        usersRv.layoutManager = LinearLayoutManager(requireContext())
        usersRv.adapter = adapter

        // ── FAB: extends/shrinks with scrolling
        usersRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) addUserFab.shrink() else if (dy < -8) addUserFab.extend()
            }
        })

        // ── Shizuku listeners — badge only, the refresh happens in onResume
        shizuku.addOnBoundListener("muf_badge") { updatePrivilegeBadge() }
        shizuku.addOnGrantedListener("muf") { updatePrivilegeBadge(); refreshUsers() }
        shizuku.addOnDeniedListener("muf")  { updatePrivilegeBadge() }
        shizuku.addOnUnboundListener("muf") { updatePrivilegeBadge() }

        addUserFab.setOnClickListener { onAddUserClicked() }
        return v
    }

    override fun onResume() {
        super.onResume()
        updatePrivilegeBadge()
        if (shizuku.isAvailable && !shizuku.hasPermission) shizuku.requestPermission()
        refreshUsers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        fetchJob = null
        shizuku.removeOnBoundListener("muf_badge")
        shizuku.removeOnGrantedListener("muf")
        shizuku.removeOnDeniedListener("muf")
        shizuku.removeOnUnboundListener("muf")
    }

    // ─────────────────────────────────────────────────────────────
    //  Refresh — the single entry point for fetching data
    // ─────────────────────────────────────────────────────────────

    private fun refreshUsers() {
        fetchJob?.cancel()
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            applyState(UiState.Loading)

            val users = try {
                MultiUserManager.listUsers(requireContext())
            } catch (e: CancellationException) {
                throw e // always rethrow
            } catch (e: Exception) {
                emptyList()
            }

            if (!isActive) return@launch

            // FIX: the state decision is now three-way and honest:
            //  - I actually have users → Success
            //  - The list is empty + the privilege exists → EmptyResult (a real problem, show diagnostics)
            //  - The list is empty + no privilege → NoPrivilege (the normal case)
            applyState(
                when {
                    users.isNotEmpty() -> UiState.Success(users)
                    MultiUserManager.hasPrivilege(requireContext()) -> UiState.EmptyResult
                    else -> UiState.NoPrivilege
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Applying the UI state
    // ─────────────────────────────────────────────────────────────

    private fun applyState(state: UiState) {
        progressBar.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
        addUserFab.isEnabled   = state !is UiState.Loading

        when (state) {
            is UiState.Loading -> {
                emptyView.visibility = View.GONE
                usersCountTv.text    = getString(R.string.loading_ellipsis)
            }
            is UiState.NoPrivilege -> {
                emptyView.visibility        = View.VISIBLE
                emptyTitleTv.text           = getString(R.string.cannot_fetch_users)
                emptySubtitleTv.text        = getString(R.string.requires_shizuku_or_root_manage_accounts)
                emptyDiagnoseBtn.visibility = View.GONE
                adapter.submitList(emptyList())
                usersCountTv.text    = ""
            }
            is UiState.EmptyResult -> {
                // FIX: it has Shizuku/Root but the list actually came back empty —
                // instead of a fake "current user" fallback, an honest message + a real diagnostics button
                emptyView.visibility        = View.VISIBLE
                emptyTitleTv.text           = getString(R.string.could_not_read_users)
                emptySubtitleTv.text        = getString(R.string.pm_list_users_no_valid_data)
                emptyDiagnoseBtn.visibility = View.VISIBLE
                adapter.submitList(emptyList())
                usersCountTv.text    = ""
            }
            is UiState.Success -> {
                emptyView.visibility        = View.GONE
                emptyDiagnoseBtn.visibility = View.GONE
                adapter.submitList(state.users)
                usersCountTv.text    = when (state.users.size) {
                    1    -> getString(R.string.one_user_on_device)
                    else -> getString(R.string.n_users_on_device, state.users.size)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Actions
    // ─────────────────────────────────────────────────────────────

    private fun confirmSwitch(user: YoukiUser) = confirm(
        title   = getString(R.string.switch_user),
        message = getString(R.string.switch_to_user_confirm, user.name)
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            applyState(UiState.Loading)
            val (_, msg) = MultiUserManager.switchToUser(requireContext(), user.id)
            showSnack(msg)
            refreshUsers()
        }
    }

    private fun confirmDelete(user: YoukiUser) = confirm(
        title   = getString(R.string.delete_user),
        message = getString(R.string.delete_user_confirm, user.name)
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            applyState(UiState.Loading)
            val (ok, msg) = MultiUserManager.removeUser(requireContext(), user.id)
            showSnack(msg)
            if (ok) MultiUserManager.deleteUserAvatar(requireContext(), user.id)
            refreshUsers()
        }
    }

    private fun pickAvatarFor(user: YoukiUser) {
        if (!MultiUserManager.hasPrivilege(requireContext())) {
            showSnack(getString(R.string.shizuku_or_root_required_change_avatar))
            return
        }
        pendingAvatarUserId = user.id
        pickImage.launch(
            android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  Creating a new user
    // ─────────────────────────────────────────────────────────────

    private fun onAddUserClicked() {
        if (!MultiUserManager.hasPrivilege(requireContext())) {
            showSnack(getString(R.string.shizuku_or_root_required_create_user))
            return
        }

        val dv = layoutInflater.inflate(R.layout.dialog_create_user, null)
        val et = dv.findViewById<TextInputEditText>(R.id.create_user_name_et)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.new_user))
            .setView(dv)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = et.text?.toString()?.trim()
                if (name.isNullOrBlank()) { showSnack(getString(R.string.enter_user_name)); return@setPositiveButton }
                launchProvisioning(name)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun launchProvisioning(name: String) {
        val ctx       = requireContext()
        val dv        = layoutInflater.inflate(R.layout.dialog_provisioning, null)
        val stepTv    = dv.findViewById<TextView>(R.id.provision_step_tv)
        val progBar   = dv.findViewById<LinearProgressIndicator>(R.id.provision_progress)
        val logTv     = dv.findViewById<TextView>(R.id.provision_log_tv)
        val counterTv = dv.findViewById<TextView?>(R.id.provision_counter_tv)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.setting_up_user))
            .setView(dv)
            .setCancelable(false)
            .create()
            .also { it.show() }

        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, _, log) = MultiUserManager.createAndProvisionUser(ctx, name) { step, total, msg ->
                withContext(Dispatchers.Main) {
                    if (total > 0) progBar.progress = step * 100 / total
                    stepTv.text        = msg
                    counterTv?.text    = "$step / $total"
                    logTv.append("$msg\n")
                }
            }
            dialog.dismiss()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(if (ok) getString(R.string.completed_successfully) else getString(R.string.completed_with_warnings))
                .setMessage(log)
                .setPositiveButton(getString(R.string.ok)) { _, _ -> refreshUsers() }
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Privilege badge
    // ─────────────────────────────────────────────────────────────

    private fun updatePrivilegeBadge() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        privilegeBadge.text = when {
            RootManager.getInstance(ctx).isAvailable -> "Root"
            shizuku.hasPermission                    -> "Shizuku"
            shizuku.isAvailable                      -> "Shizuku (${getString(R.string.no_permission)})"
            else                                     -> getString(R.string.without_permissions)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Diagnostics — only shown for UiState.EmptyResult
    //  Displays the RAW output of "pm list users" via Shizuku/Root + the
    //  result of UserManager.getUsers(true) — with no fake data at all.
    // ─────────────────────────────────────────────────────────────

    private fun showDiagnosticsDialog() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                MultiUserManager.diagnoseUserListing(ctx)
            }

            val pad = (16 * resources.displayMetrics.density).toInt()
            val tv  = TextView(ctx).apply {
                text = report
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setPadding(pad, pad, pad, pad)
            }

            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.user_diagnostics))
                .setView(ScrollView(ctx).apply { addView(tv) })
                .setPositiveButton(getString(R.string.copy_report)) { _, _ ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("diagnostics", report))
                    showSnack(getString(R.string.report_copied))
                }
                .setNeutralButton(getString(R.string.retry)) { _, _ -> refreshUsers() }
                .setNegativeButton(getString(R.string.close), null)
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────

    private fun confirm(title: String, message: String, action: () -> Unit) =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.confirm)) { _, _ -> action() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

    private fun showSnack(msg: String) =
        view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_LONG).show() }

}
