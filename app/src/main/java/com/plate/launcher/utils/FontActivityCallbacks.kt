package com.plate.launcher.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup

/**
 * FontActivityCallbacks v2
 *
 * ══ The old problem ══
 * onResume only → fragments/dialogs added after Resume didn't get the font
 *
 * ══ The fix ══
 * 1. onActivityCreated   → apply directly to the decorView right after layout
 * 2. onActivityResumed   → reapply (in case the prefs changed while paused)
 * 3. attachDecorListener → add an OnHierarchyChangeListener on the decorView
 *    so any View that's added (fragment, dialog, bottom sheet) gets the font immediately
 */
class FontActivityCallbacks : Application.ActivityLifecycleCallbacks {

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val decor = activity.window?.decorView ?: return
        decor.post {
            val arabicTf = FontManager.getArabicTypeface(activity)
            val latinTf  = FontManager.getLatinTypeface(activity)
            if (arabicTf == null && latinTf == null) return@post

            // Apply to the entire current tree
            FontManager.applyToView(decor, arabicTf, latinTf)

            // FIX: watch for new Views being added at the decorView level
            // This guarantees fragments/dialogs/bottom sheets get the font automatically
            attachDecorListener(activity, arabicTf, latinTf)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        // Reapply if the Typeface changed (e.g. the user changed the font from Settings)
        val decor = activity.window?.decorView ?: return
        decor.post {
            FontManager.applyIfSet(activity, decor)
        }
    }

    // ── A decorView listener to catch any View added later ────────────────────
    private fun attachDecorListener(
        activity: Activity,
        arabicTf: android.graphics.Typeface?,
        latinTf: android.graphics.Typeface?
    ) {
        val decor = activity.window?.decorView as? ViewGroup ?: return

        // We avoid registering a duplicate listener
        if (decor.getTag(DECOR_TAG) != null) return

        val listener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) {
                child?.post {
                    FontManager.applyToView(child, arabicTf, latinTf)
                }
            }
            override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) {}
        }

        decor.setOnHierarchyChangeListener(listener)
        decor.setTag(DECOR_TAG, listener)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private const val DECOR_TAG = 0x594F554D // "YOUM"
    }
}
