package com.youki.dex.utils

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR
import kotlin.math.roundToInt
import androidx.core.graphics.toColorInt
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

object ColorUtils {

    // PERF FIX: DynamicColors.wrapContextIfAvailable() inflates a full themed context —
    // it's surprisingly expensive when called on every applyTheme() / applyMainColor().
    // Cache the result per (context identity + variant) so repeated calls within the same
    // DockService lifecycle cost nothing.
    private val dynamicColorCache = java.util.concurrent.ConcurrentHashMap<Long, android.content.Context>()

    private fun getCachedDynamicContext(context: android.content.Context, styleResId: Int): android.content.Context {
        val key = (System.identityHashCode(context).toLong() shl 32) or styleResId.toLong()
        return dynamicColorCache.getOrPut(key) {
            DynamicColors.wrapContextIfAvailable(context, styleResId)
        }
    }

    /** Call this whenever the wallpaper / Material You palette changes so the cache refreshes. */
    fun invalidateDynamicColorCache() = dynamicColorCache.clear()
    @SuppressLint("MissingPermission")
    private fun getWallpaperColors(context: Context): ArrayList<String> {
        val wallpaperColors = ArrayList<String>()
        /*
		 Generate Wallpaper colors based on light and dark variation
		 Accomplished by inspecting pixels in Wallpaper Bitmap without AndroidX palette library
		 You will want to use a factor less than 1.0f to darken. try 0.8f.
		
		 */if (DeviceUtils.hasStoragePermission(context)) {
            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperDrawable = wallpaperManager.drawable
            if (wallpaperDrawable != null) {
                wallpaperDrawable.mutate()
                wallpaperDrawable.invalidateSelf()
                val wallpaperBitmap = drawableToBitmap(wallpaperDrawable)
                val color = wallpaperBitmap[wallpaperBitmap.width / 4, wallpaperBitmap.height / 4]
                wallpaperColors.add(toHexColor(color))
                wallpaperColors.add(toHexColor(manipulateColor(color, .8f)))
                wallpaperColors.add(toHexColor(manipulateColor(color, .5f)))
            }
        }
        return wallpaperColors
    }

    @SuppressLint("ResourceType")
    fun getThemeColors(context: Context, forceDark: Boolean): IntArray {
        val colors = IntArray(3)
        val variant = if (forceDark) MaterialR.style.ThemeOverlay_Material3_DynamicColors_Dark else MaterialR.style.ThemeOverlay_Material3_DynamicColors_DayNight
        val styledContext = getCachedDynamicContext(context, variant)
        val attrsToResolve = intArrayOf(AppCompatR.attr.colorPrimary, MaterialR.attr.colorSurface, MaterialR.attr.colorPrimaryContainer)
        styledContext.withStyledAttributes(null, attrsToResolve) {
            colors[0] = getColor(0, 0)
            colors[1] = getColor(1, 0)
            colors[2] = getColor(2, 0)
        }
        return colors
    }

    fun manipulateColor(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).roundToInt()
        val g = (Color.green(color) * factor).roundToInt()
        val b = (Color.blue(color) * factor).roundToInt()
        return Color.argb(a, r.coerceAtMost(255), g.coerceAtMost(255), b.coerceAtMost(255))
    }

    private fun getBitmapDominantColor(bitmap: Bitmap): Int {
        var color = bitmap[bitmap.width / 2, bitmap.height / 9]
        if (color == Color.TRANSPARENT) color = bitmap[bitmap.width / 4, bitmap.height / 2]
        if (color == Color.TRANSPARENT) color = bitmap[bitmap.width / 2, bitmap.height / 2]
        return color
    }

    fun getDrawableDominantColor(drawable: Drawable): Int {
        return getBitmapDominantColor(drawableToBitmap(drawable))
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            if (drawable.bitmap != null) {
                return drawable.bitmap
            }
        }
        val bitmap: Bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            createBitmap(1, 1) // Single color bitmap will be created of 1x1 pixel
        } else {
            createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun applyColor(view: View, color: Int) {
        view.background?.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
    }

    fun getMainColors(sharedPreferences: SharedPreferences, context: Context): IntArray {
        val theme = sharedPreferences.getString("theme", "material_u")
        var mainColor = 0
        var secondaryColor = 0
        var alpha = 255
        val colors = IntArray(5)
        when (theme) {
            "dark" -> {
                mainColor = "#212121".toColorInt()
                secondaryColor = manipulateColor(mainColor, 1.35f)
            }
            "black" -> {
                mainColor = "#060606".toColorInt()
                secondaryColor = manipulateColor(mainColor, 2.2f)
            }
            "transparent" -> {
                mainColor = "#050505".toColorInt()
                secondaryColor = manipulateColor(mainColor, 2f)
                alpha = 225
            }
            "fully_transparent" -> {
                mainColor = android.graphics.Color.TRANSPARENT
                secondaryColor = android.graphics.Color.TRANSPARENT
                alpha = 0
            }
            "material_u" -> if (DynamicColors.isDynamicColorAvailable()) {
                // forceDark=true gives the dark variant of Material You surface
                val surfaceDark = getThemeColors(context, true)[1]
                mainColor = manipulateColor(surfaceDark, 0.85f)
                secondaryColor = manipulateColor(surfaceDark, 1.1f)
            } else {
                mainColor = getWallpaperColors(context)[2].toColorInt()
                secondaryColor = getWallpaperColors(context)[1].toColorInt()
            }

            "material_u_light" -> if (DynamicColors.isDynamicColorAvailable()) {
                // forceDark=false → the light version of Material You
                val surfaceLight = getThemeColors(context, false)[1]
                mainColor = manipulateColor(surfaceLight, 0.96f)
                secondaryColor = manipulateColor(surfaceLight, 0.88f)
            } else {
                mainColor = getWallpaperColors(context)[0].toColorInt()
                secondaryColor = getWallpaperColors(context)[1].toColorInt()
            }

            "custom" -> {
                mainColor = try {
                    (sharedPreferences.getString("theme_main_color", "#212121") ?: "#212121").toColorInt()
                } catch (e: IllegalArgumentException) {
                    "#212121".toColorInt()
                }
                secondaryColor = manipulateColor(mainColor, 1.2f)
                alpha = sharedPreferences.getInt("theme_main_alpha", 255)
            }
        }
        colors[0] = mainColor
        colors[1] = alpha
        colors[2] = secondaryColor
        if (alpha < 255) alpha = (alpha - alpha * 0.60).toInt()
        colors[3] = alpha
        colors[4] = if (theme == "black") colors[2] else manipulateColor(colors[0], 0.8f)
        return colors
    }

    fun applyMainColor(context: Context, sp: SharedPreferences, view: View) {
        val theme = sp.getString("theme", "dark")
        if (theme == "fully_transparent") {
            view.background = null
            return
        }

        val colors = getMainColors(sp, context)
        applyColor(view, colors[0])
        view.background?.alpha = colors[1]
    }

    fun applySecondaryColor(context: Context, sp: SharedPreferences, view: View) {
        val colors = getMainColors(sp, context)
        applyColor(view, colors[2])
        val alpha = colors[3]
        view.background?.alpha = alpha
    }

    fun applySecondaryColor(context: Context, sp: SharedPreferences, materialButton: MaterialButton) {
        val colors = getMainColors(sp, context)
        materialButton.setBackgroundColor(colors[2])
        val alpha = colors[3]
        materialButton.background?.alpha = alpha
    }

    /**
     * Returns the bubble color with user-controlled opacity.
     * bubble_mode = "material_u" → Material You colorPrimary dark
     * bubble_mode = "custom"     → user-picked hex + bubble_alpha (0-255, default 115 = 45%)
     */
    fun getBubbleColor(sp: SharedPreferences, context: Context): Int {
        val mode = sp.getString("bubble_mode", "custom") ?: "custom"
        val alpha = sp.getInt("bubble_alpha", 115).coerceIn(0, 255)
        val baseColor = when {
            mode == "material_u" &&
            com.google.android.material.color.DynamicColors.isDynamicColorAvailable() -> {
                try {
                    // The dark colorSurface — the darkest color in Material You
                    val surface = getThemeColors(context, true)[1]
                    manipulateColor(surface, 0.80f)
                } catch (e: Exception) { android.graphics.Color.parseColor("#0C0C0C") }
            }
            mode == "material_u_light" &&
            com.google.android.material.color.DynamicColors.isDynamicColorAvailable() -> {
                try {
                    // The light colorPrimaryContainer — Material You's light version for bubbles
                    val colors = getThemeColors(context, false)
                    manipulateColor(colors[2], 1.0f)  // colorPrimaryContainer light
                } catch (e: Exception) { android.graphics.Color.rgb(220, 220, 220) }
            }
            else -> {
                val hex = sp.getString("bubble_color", "#0C0C0C") ?: "#0C0C0C"
                try { hex.toColorInt() } catch (e: Exception) { android.graphics.Color.parseColor("#0C0C0C") }
            }
        }
        // Apply the user's alpha to the color (the RGB isn't affected)
        return android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(baseColor),
            android.graphics.Color.green(baseColor),
            android.graphics.Color.blue(baseColor)
        )
    }

    fun toColor(color: String): Int {
        return try {
            color.toColorInt()
        } catch (e: IllegalArgumentException) {
            -1
        }
    }

    fun toHexColor(color: Int): String {
        return "#" + Integer.toHexString(color).substring(2)
    }

    /**
     * Returns true when the dock's main background color is light enough
     * that white text/icons would be invisible (luminance > 0.35).
     * Used to switch power menu and user-switcher text to dark on light themes.
     */
    fun isDockColorLight(sp: android.content.SharedPreferences, context: android.content.Context): Boolean {
        val color = getMainColors(sp, context)[0]
        val r = android.graphics.Color.red(color)   / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color)  / 255.0
        fun linearize(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val lum = 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
        return lum > 0.35
    }
}
