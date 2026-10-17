package com.plate.launcher.onboarding

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.plate.launcher.R
import com.plate.launcher.utils.ShizukoManager

/**
 * Step 2 — offers to grant Shizuku (the permission behind "direct entry": skipping past
 * individual permission prompts on later launches, see SplashActivity/DockService). Tapping
 * Skip doesn't just move on silently — it reveals a short explanation of the tradeoff
 * (normal permission-prompt flow instead), matching the user's exact spec that this choice
 * should be explained, not hidden.
 */
class OnboardingPermissionsFragment : Fragment(R.layout.fragment_onboarding_permissions) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val shizuku = ShizukoManager.getInstance(ctx)

        val grantBtn = view.findViewById<MaterialButton>(R.id.onboarding_grant_shizuku_btn)
        val skipBtn = view.findViewById<MaterialButton>(R.id.onboarding_skip_shizuku_btn)
        val warningTv = view.findViewById<View>(R.id.onboarding_skip_warning_tv)

        fun refreshGrantButtonState() {
            grantBtn.text = if (shizuku.hasPermission) getString(R.string.ok) else getString(R.string.grant)
        }
        refreshGrantButtonState()

        grantBtn.setOnClickListener {
            if (shizuku.hasPermission) {
                // Already granted — treat tapping this as "acknowledge and continue",
                // same UX shizuku dialog elsewhere in the app already uses for this state.
                (activity as? OnboardingActivity)?.goToNextStep()
            } else {
                shizuku.requestPermission()
            }
        }
        shizuku.addOnGrantedListener("onboarding_permissions_step") {
            activity?.runOnUiThread { refreshGrantButtonState() }
        }

        skipBtn.setOnClickListener {
            if (warningTv.visibility != View.VISIBLE) {
                // First tap: explain the tradeoff rather than silently proceeding.
                warningTv.visibility = View.VISIBLE
            } else {
                // Second tap (already saw the warning): genuinely skip.
                (activity as? OnboardingActivity)?.goToNextStep()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ShizukoManager.getInstance(requireContext()).removeOnGrantedListener("onboarding_permissions_step")
    }
}
