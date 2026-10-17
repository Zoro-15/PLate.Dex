package com.plate.launcher.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.plate.launcher.R
import com.plate.launcher.activities.SplashActivity
import com.plate.launcher.utils.OnboardingPrefs

/**
 * First-run onboarding wizard: Welcome → Permissions (Shizuku) → Profile (name/avatar/
 * cover) → Renderer choice → Done. Reachable only when OnboardingPrefs.isOnboardingComplete
 * is false (see SplashActivity/LauncherActivity's first-launch check — this Activity itself
 * doesn't gate its own entry, whoever starts it is responsible for that check).
 *
 * Uses a fixed, named list of steps rather than a generic "page count" — this wizard's
 * steps aren't interchangeable/reorderable content (unlike the app drawer's pages), each
 * one is a specific screen with its own behavior, so modeling them as an explicit sequence
 * is clearer than treating them as generic pages.
 *
 * The loading-screen media picker (previously its own step here) was removed: every new
 * user now gets the bundled default GIF/video (see UserMediaResolver) with zero setup,
 * and picking something different is a Beta-settings-only override for people actively
 * customizing/testing the app, not a decision onboarding should ask everyone to make.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var backBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton
    private lateinit var dotsContainer: LinearLayout

    private val steps: List<() -> Fragment> = listOf(
        { OnboardingWelcomeFragment() },
        { OnboardingPermissionsFragment() },
        { OnboardingProfileFragment() },
        { OnboardingDoneFragment() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.onboarding_pager)
        pager.isUserInputEnabled = false
        backBtn = findViewById(R.id.onboarding_back_btn)
        nextBtn = findViewById(R.id.onboarding_next_btn)
        dotsContainer = findViewById(R.id.onboarding_page_dots)

        pager.adapter = StepAdapter(this)
        pager.setPageTransformer(SharedAxisPageTransformer())
        buildPageDots()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateNavForStep(position)
                updateDots(position)
            }
        })
        updateNavForStep(0)
        updateDots(0)

        backBtn.setOnClickListener { goToPreviousStep() }
        nextBtn.setOnClickListener { onNextTapped() }
    }

    /** Advances to the next step, or finishes onboarding if already on the last one. Public so step fragments (e.g. OnboardingPermissionsFragment's Skip/Grant handlers) can trigger the same navigation the Next button would. */
    fun goToNextStep() {
        if (pager.currentItem < steps.lastIndex) {
            pager.currentItem += 1
        } else {
            finishOnboarding()
        }
    }

    private fun goToPreviousStep() {
        if (pager.currentItem > 0) pager.currentItem -= 1
    }

    private fun onNextTapped() {
        // The profile step needs its typed name committed before moving on — every other
        // step either has no input to commit (Welcome, Done) or already writes directly to
        // storage the moment the user makes a choice (Permissions, Splash choice), so this
        // is the one place that needs an explicit "commit on advance" call.
        //
        // Found via supportFragmentManager.fragments + isResumed rather than guessing at
        // FragmentStateAdapter's internal child-fragment tag format — that's private
        // implementation detail, not public API, and isn't safe to assume matches across
        // library versions. Filtering the manager's live fragment list by type and
        // "is this one currently resumed/visible" is the reliable way to find the specific
        // instance actually showing on screen right now.
        supportFragmentManager.fragments
            .filterIsInstance<OnboardingProfileFragment>()
            .firstOrNull { it.isResumed }
            ?.commitName()
        goToNextStep()
    }

    private fun finishOnboarding() {
        OnboardingPrefs.setOnboardingComplete(this, true)
        startActivity(Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun updateNavForStep(position: Int) {
        backBtn.visibility = if (position == 0) android.view.View.INVISIBLE else android.view.View.VISIBLE
        nextBtn.text = if (position == steps.lastIndex) getString(R.string.done) else getString(R.string.next)
    }

    private fun buildPageDots() {
        val dotSize = (7 * resources.displayMetrics.density).toInt()
        val dotMargin = (4 * resources.displayMetrics.density).toInt()
        repeat(steps.size) {
            val dot = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = dotMargin; marginEnd = dotMargin
                }
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots(activePosition: Int) {
        for (i in 0 until dotsContainer.childCount) {
            dotsContainer.getChildAt(i).background = ContextCompat.getDrawable(
                this, if (i == activePosition) R.drawable.page_dot_active else R.drawable.page_dot_inactive
            )
        }
    }

    private inner class StepAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = steps.size
        override fun createFragment(position: Int): Fragment = steps[position]()
    }
}
