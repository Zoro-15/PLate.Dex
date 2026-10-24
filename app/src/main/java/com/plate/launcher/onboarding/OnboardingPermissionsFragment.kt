package com.plate.launcher.onboarding

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.plate.launcher.R
import com.plate.launcher.utils.AppUtils
import com.plate.launcher.utils.DeviceUtils
import com.plate.launcher.utils.ShizukoManager

/**
 * Step 2 - offers to grant Shizuku or acknowledges native system privileges.
 * When integrated as a privileged system app in the ROM, all permissions are granted
 * out-of-the-box so the Shizuku request is automatically bypassed.
 */
class OnboardingPermissionsFragment : Fragment(R.layout.fragment_onboarding_permissions) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val shizuku = ShizukoManager.getInstance(ctx)
        val isSystemPrivileged = AppUtils.isSystemApp(ctx, ctx.packageName) || DeviceUtils.hasWriteSettingsPermission(ctx)

        val titleTv = view.findViewById<TextView>(R.id.onboarding_permissions_title)
        val bodyTv = view.findViewById<TextView>(R.id.onboarding_permissions_body)
        val grantBtn = view.findViewById<MaterialButton>(R.id.onboarding_grant_shizuku_btn)
        val skipBtn = view.findViewById<MaterialButton>(R.id.onboarding_skip_shizuku_btn)
        val warningTv = view.findViewById<View>(R.id.onboarding_skip_warning_tv)

        if (isSystemPrivileged) {
            titleTv?.text = "System Privileges Active"
            bodyTv?.text = "Running natively as a privileged desktop environment. All system permissions have been granted automatically by the ROM."
            grantBtn.text = getString(R.string.ok)
            skipBtn.visibility = View.GONE
            warningTv.visibility = View.GONE
            grantBtn.setOnClickListener {
                (activity as? OnboardingActivity)?.goToNextStep()
            }
            return
        }

        fun refreshGrantButtonState() {
            grantBtn.text = if (shizuku.hasPermission) getString(R.string.ok) else getString(R.string.grant)
        }
        refreshGrantButtonState()

        grantBtn.setOnClickListener {
            if (shizuku.hasPermission) {
                // Already granted - treat tapping this as "acknowledge and continue",
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