package com.youki.dex.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youki.dex.R
import com.youki.dex.activities.EasterEggActivity
import com.youki.dex.utils.AppUtils

/**
 * About screen — redesigned as a plain Fragment with a real custom layout
 * (fragment_help_about.xml) rather than a PreferenceFragmentCompat screen.
 * A preference list can't express "borderless card, no divider line" on its
 * own (PreferenceCategory always draws a title+divider row) — see that
 * layout's own header comment for the full reasoning. Four cards: app
 * identity, project links, version/license, and — hidden until Developer
 * Mode is unlocked — the Developers card built by [bindContributors].
 */
class HelpAboutPreferences : Fragment(R.layout.fragment_help_about) {

    // ── Developer Mode + Easter Egg — a unified counter (7 taps) ────────
    private var devUnlocked  = false

    // FIX: a fixed window from the first tap (not a sliding window) — so
    // slow tapping doesn't activate it. All 7 taps must fall within
    // EGG_WINDOW_MS of the first tap, otherwise the counter resets to zero.
    private var eggTapCount   = 0
    private var eggFirstTapAt = 0L
    private val EGG_TAPS      = 7      // number of taps (Easter Egg + Developer Mode together)
    private val EGG_WINDOW_MS = 1500L  // all 7 taps must happen within 1.5 seconds

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        devUnlocked = prefs.getBoolean("developer_mode_enabled", false)

        view.findViewById<View>(R.id.row_github).setOnClickListener {
            AppUtils.openUrl(requireContext(), "https://github.com/Zoro-15/PLate.Dex")
        }
        view.findViewById<View>(R.id.row_discord).setOnClickListener {
            AppUtils.openUrl(requireContext(), "https://discord.gg/mKkaMxd5M2")
        }

        // Tapping the app identity card (icon + name + tagline): a small
        // easter egg-ish nudge toward Discord as the faster support channel,
        // separate from the 7-tap Developer Mode counter below. A real
        // dialog (not a Snackbar) since the message is long — a Snackbar's
        // one or two lines truncated it with "..." and there was no way to
        // read the rest. Same MaterialAlertDialogBuilder pattern as
        // DebugActivity's crash dialog for consistency.
        view.findViewById<View>(R.id.row_app_identity).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.discord_is_faster_notice)
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        // ── Tapping the version row: runs both counters together ──────────
        view.findViewById<View>(R.id.app_version_title).setOnClickListener {
            handleVersionTap(prefs)
        }

        bindContributors(view)
        bindExtraContributors(view)
    }

    /**
     * Developers card — hidden entirely unless Developer Mode is already
     * unlocked when this screen opens (matches the brief: the card doesn't
     * exist for a regular user, it isn't just collapsed/greyed out). Yuki's
     * row has a second, independent gate on top of that: watching the
     * Easter Egg for 3 continuous minutes (see EasterEggActivity's
     * PREF_YUKI_UNLOCKED — a separate flag, not related to Developer Mode
     * itself) — she isn't actually involved in building the app, this is a
     * standing joke row, not a real credit, so it stays hidden even from
     * someone who's unlocked Developer Mode until they've also sat through
     * that Easter Egg long enough.
     */
    private fun bindContributors(root: View) {
        val card = root.findViewById<View>(R.id.contributors_card)
        if (!devUnlocked) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE

        val list = root.findViewById<ViewGroup>(R.id.contributors_list)
        list.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        data class Contributor(val name: String, val role: String, val iconRes: Int)

        val developers = mutableListOf(
            Contributor("Zoro-15", "Lead Developer (PLate.Dex)", R.drawable.ic_user),
            Contributor("Mr Youki", "Original Creator (YoukiDex)", R.drawable.contributor_mr_youki),
            Contributor("MystiKitsu", getString(R.string.role_developer_assistant), R.drawable.contributor_mystikitsu),
        )
        val yukiUnlocked = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(EasterEggActivity.PREF_YUKI_UNLOCKED, false)
        if (yukiUnlocked) {
            developers.add(
                Contributor("Yuki", getString(R.string.role_gf_developer), R.drawable.contributor_yuki)
            )
        }

        developers.forEach { c ->
            val row = inflater.inflate(R.layout.contributor_row, list, false)
            row.findViewById<ImageView>(R.id.contributor_avatar).setImageResource(c.iconRes)
            row.findViewById<TextView>(R.id.contributor_name).text = c.name
            row.findViewById<TextView>(R.id.contributor_role).text = c.role
            list.addView(row)
        }
    }

    /**
     * "Contributors" card — the wider circle of people who helped out
     * (testing, feedback, etc.), as opposed to the "Developers" card above
     * which is just the two/three people who actually write code. Same
     * gate as [bindContributors]: hidden entirely (not just collapsed)
     * until Developer Mode is unlocked. Rendered two-per-row via
     * contributor_row_pair.xml — a lighter, avatar+name-only credit.
     */
    private fun bindExtraContributors(root: View) {
        val card = root.findViewById<View>(R.id.contributors_extra_card)
        if (!devUnlocked) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE

        val list = root.findViewById<ViewGroup>(R.id.contributors_extra_list)
        list.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        data class Person(val name: String, val iconRes: Int)

        val people = listOf(
            Person("Vi-", R.drawable.contributor_vi),
            Person("ユザスキくん", R.drawable.contributor_yuzasuki),
            Person("KNIGHT", R.drawable.contributor_knight),
            Person("Bobby the Cat", R.drawable.contributor_bobby),
            Person("Inter", R.drawable.contributor_inter),
            Person("Farelmalas", R.drawable.contributor_farelmalas),
            Person("Silly Pikachu :3", R.drawable.contributor_pikachu),
            Person("冷冷的", R.drawable.contributor_stargdlust),
        )

        people.chunked(2).forEach { pair ->
            val row = inflater.inflate(R.layout.contributor_row_pair, list, false)

            val first = pair[0]
            row.findViewById<ImageView>(R.id.contributor_avatar_start).setImageResource(first.iconRes)
            row.findViewById<TextView>(R.id.contributor_name_start).text = first.name

            val endSlot = row.findViewById<View>(R.id.contributor_slot_end)
            val second = pair.getOrNull(1)
            if (second != null) {
                endSlot.visibility = View.VISIBLE
                row.findViewById<ImageView>(R.id.contributor_avatar_end).setImageResource(second.iconRes)
                row.findViewById<TextView>(R.id.contributor_name_end).text = second.name
            } else {
                // Odd count: leave the second slot invisible but present,
                // so the first person doesn't stretch to fill the row.
                endSlot.visibility = View.INVISIBLE
            }

            list.addView(row)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Tap logic — a single shared counter (7 quick taps)
    // ─────────────────────────────────────────────────────────────

    private fun handleVersionTap(prefs: android.content.SharedPreferences) {

        // ── A single shared counter: 7 taps within a fixed window from the first tap ──
        val now = System.currentTimeMillis()
        val (newCount, newFirstTapAt, reached) = easterEggTapState(
            now, eggTapCount, eggFirstTapAt, EGG_WINDOW_MS, EGG_TAPS
        )
        eggTapCount   = newCount
        eggFirstTapAt = newFirstTapAt

        if (!reached) return

        // ── The seventh tap: play the video + enable Developer Mode (if not already enabled) ──
        launchEasterEgg()

        if (!devUnlocked) {
            devUnlocked = true
            prefs.edit().putBoolean("developer_mode_enabled", true).apply()
            Toast.makeText(requireContext(),
                getString(R.string.developer_mode_unlocked),
                Toast.LENGTH_LONG).show()
            requireActivity().recreate()
        }
    }

    /** Returns (newCount, newFirstTapAtMs, reached). Resets the window if too much time passed since the first tap. */
    private fun easterEggTapState(
        nowMs: Long, count: Int, firstTapAtMs: Long, windowMs: Long, tapsRequired: Int
    ): Triple<Int, Long, Boolean> {
        var (newCount, newFirstTapAt) = if (count == 0 || nowMs - firstTapAtMs > windowMs) {
            1 to nowMs
        } else {
            (count + 1) to firstTapAtMs
        }
        val reached = newCount >= tapsRequired
        if (reached) newCount = 0
        return Triple(newCount, newFirstTapAt, reached)
    }

    // ─────────────────────────────────────────────────────────────
    //  Open the Easter Egg
    // ─────────────────────────────────────────────────────────────

    private fun launchEasterEgg() {
        val intent = Intent(requireContext(), EasterEggActivity::class.java)
        startActivity(intent)
        // fade-in transition
        requireActivity().overridePendingTransition(R.anim.fade_in, 0)
    }
}
