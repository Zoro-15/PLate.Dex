package com.plate.launcher.activities

import android.content.Intent
import android.os.Bundle
import com.plate.launcher.utils.OnboardingPrefs

/**
 * Entry point of the app (the app-drawer icon) — opens Settings
 * (MainActivity), not the desktop. Opening the desktop is the home-screen
 * shortcut's job (see shortcuts.xml -> ShortcutLauncherActivity), which
 * does its own accessibility-check + launch sequence independently — this
 * activity no longer needs to duplicate that logic, so it's just a thin
 * onboarding-gate + redirect to Settings.
 */
class SplashActivity : BaseFontScaleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch — route to onboarding.
        if (!OnboardingPrefs.isOnboardingComplete(this)) {
            startActivity(Intent(this, com.plate.launcher.onboarding.OnboardingActivity::class.java))
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // App-drawer icon -> Settings. Opening the desktop is the separate
        // home-screen shortcut's responsibility (ShortcutLauncherActivity).
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
