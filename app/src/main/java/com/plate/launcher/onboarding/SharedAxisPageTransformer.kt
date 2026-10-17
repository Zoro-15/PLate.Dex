package com.plate.launcher.onboarding

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * A ViewPager2.PageTransformer approximating MaterialSharedAxis.X's visual behavior (small
 * horizontal slide + fade as a page enters/exits) for onboarding step transitions.
 *
 * NOTE on why this exists instead of just using MaterialSharedAxis directly: the real
 * MaterialSharedAxis class (verified against the actual material-components-android source
 * — MaterialSharedAxis extends MaterialVisibility<VisibilityAnimatorProvider>) is a
 * Fragment/Activity Visibility transition, driven by a target View's visibility changing or
 * being added/removed — it has no ViewPager2.PageTransformer-compatible API. Applying it
 * here would have been using the wrong mechanism for the wrong widget. This transformer
 * reproduces the same slide+fade visual the library's own Motion docs describe for shared
 * axis X, using ViewPager2's actual supported extension point instead.
 */
class SharedAxisPageTransformer : ViewPager2.PageTransformer {
    private val slideDistanceFraction = 0.25f

    override fun transformPage(page: View, position: Float) {
        val pageWidth = page.width
        when {
            position < -1 || position > 1 -> {
                page.alpha = 0f
            }
            position <= 0 -> { // page is moving toward/away from the left
                page.alpha = 1 - abs(position)
                page.translationX = pageWidth * slideDistanceFraction * -position
                page.translationY = 0f
            }
            else -> { // position in (0, 1] — page is moving toward/away from the right
                page.alpha = 1 - abs(position)
                page.translationX = pageWidth * slideDistanceFraction * -position
                page.translationY = 0f
            }
        }
    }
}
