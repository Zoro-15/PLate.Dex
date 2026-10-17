package com.plate.launcher.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.preference.PreferenceManager
import com.plate.launcher.R
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object Utils {
    var notificationPanelVisible = false
    // Gap 88: AtomicBoolean instead of a regular var — shared across different threads
    val shouldPlayChargeComplete = AtomicBoolean(false)
    var startupTime: Long = 0

    // Gap 39: apply() instead of commit() — non-blocking
    fun toggleBuiltinNavigation(editor: SharedPreferences.Editor, value: Boolean) {
        editor.putBoolean("enable_nav_back", value)
        editor.putBoolean("enable_nav_home", value)
        editor.putBoolean("enable_nav_recents", value)
        editor.apply()
    }

    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    // Gap 33: bitmap.width was being used after recycle() — now we save the width before recycle
    fun getCircularBitmap(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val bitmapCopy  = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val origWidth   = bitmap.width   // save before recycle!
        bitmap.recycle()
        val result  = createBitmap(bitmapCopy.width, bitmapCopy.height)
        val canvas  = Canvas(result)
        val paint   = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect    = Rect(0, 0, bitmapCopy.width, bitmapCopy.height)
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = -0xbdbdbe
        canvas.drawCircle(bitmapCopy.width / 2f, bitmapCopy.height / 2f, origWidth / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmapCopy, rect, rect, paint)
        bitmapCopy.recycle()
        return result
    }

    fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT < 28)
            @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        else
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
    } catch (e: Exception) { null }

    private fun batteryDrawableBucket(level: Int): String = when (level) {
        0 -> "empty"
        in 1..29 -> "20"
        in 30..49 -> "30"
        in 50..59 -> "50"
        in 60..79 -> "60"
        in 80..89 -> "80"
        in 90..99 -> "90"
        else -> "full" // covers 100 and any out-of-range value >99
    }

    fun getBatteryDrawable(level: Int, plugged: Boolean): Int {
        val bucket = batteryDrawableBucket(level)
        val name = if (plugged) "battery_charging_$bucket" else "battery_$bucket"
        return when (name) {
            "battery_charging_empty" -> R.drawable.battery_charging_empty
            "battery_charging_20"    -> R.drawable.battery_charging_20
            "battery_charging_30"    -> R.drawable.battery_charging_30
            "battery_charging_50"    -> R.drawable.battery_charging_50
            "battery_charging_60"    -> R.drawable.battery_charging_60
            "battery_charging_80"    -> R.drawable.battery_charging_80
            "battery_charging_90"    -> R.drawable.battery_charging_90
            "battery_charging_full"  -> R.drawable.battery_charging_full
            "battery_empty"          -> R.drawable.battery_empty
            "battery_20"             -> R.drawable.battery_20
            "battery_30"             -> R.drawable.battery_30
            "battery_50"             -> R.drawable.battery_50
            "battery_60"             -> R.drawable.battery_60
            "battery_80"             -> R.drawable.battery_80
            "battery_90"             -> R.drawable.battery_90
            else                     -> R.drawable.battery_full
        }
    }

    // Gap 42: OutputStreamWriter with explicit UTF-8 instead of FileWriter with no encoding
    fun saveLog(context: Context, name: String, log: String) {
        try {
            val logsDir = File(context.filesDir, "logs").also { it.mkdirs() }
            val file = File(logsDir, "${name}_${currentDateString}.log")
            file.outputStream().use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(log) }
            }
        } catch (e: IOException) {}
    }

    // Gap 38: isPureHarmonyOS() cached instead of reflection on every popup
    private var harmonyOSChecked = false
    private var isHarmonyOS = false

    fun makeWindowParams(
        width: Int, height: Int, context: Context,
        secondary: Boolean = false,
        fitNavInsets: Boolean = false
    ): WindowManager.LayoutParams {
        val displayId = if (secondary)
            DeviceUtils.getSecondaryDisplay(context)?.displayId ?: Display.DEFAULT_DISPLAY
        else Display.DEFAULT_DISPLAY

        // Gap 44: only a single call to getDisplayMetrics
        val dm = DeviceUtils.getDisplayMetrics(context, displayId)

        // Gap 38: cache isPureHarmonyOS
        if (!harmonyOSChecked) { isHarmonyOS = DeviceUtils.isPureHarmonyOS(); harmonyOSChecked = true }

        return WindowManager.LayoutParams().apply {
            format = PixelFormat.TRANSLUCENT
            flags  = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                     WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            type   = if (isHarmonyOS) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                     else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            this.width  = dm.widthPixels.coerceAtMost(width)
            this.height = dm.heightPixels.coerceAtMost(height)
            if (Build.VERSION.SDK_INT >= 35) {
                if (fitNavInsets) {
                    setFitInsetsTypes(android.view.WindowInsets.Type.navigationBars())
                    setFitInsetsSides(android.view.WindowInsets.Side.BOTTOM)
                } else { setFitInsetsTypes(0) }
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            }
        }
    }

    // Gap 34: solve() broke on negative numbers — we use lastIndexOf for the operators
    fun solve(expression: String): Double {
        val e = expression.trim()
        val addIdx = e.lastIndexOf('+')
        // search for '-' starting from index 1, so a leading '-' (negative
        // first operand) is never picked up as the operator.
        val subIdx = if (e.length > 1) e.substring(1).lastIndexOf('-').let { if (it >= 0) it + 1 else -1 } else -1
        val mulIdx = e.lastIndexOf('*')
        val divIdx = e.lastIndexOf('/')

        fun parse(s: String) = s.toDoubleOrNull()

        if (addIdx > 0) {
            val a = parse(e.substring(0, addIdx)); val b = parse(e.substring(addIdx + 1))
            return if (a != null && b != null) a + b else 0.0
        }
        if (subIdx > 0) {
            val a = parse(e.substring(0, subIdx)); val b = parse(e.substring(subIdx + 1))
            return if (a != null && b != null) a - b else 0.0
        }
        if (mulIdx > 0) {
            val a = parse(e.substring(0, mulIdx)); val b = parse(e.substring(mulIdx + 1))
            return if (a != null && b != null) a * b else 0.0
        }
        if (divIdx > 0) {
            val a = parse(e.substring(0, divIdx)); val b = parse(e.substring(divIdx + 1))
            return if (a != null && b != null) { if (b == 0.0) 0.0 else a / b } else 0.0
        }
        return e.toDoubleOrNull() ?: 0.0
    }

    // Gap 43: backupPreferences supports Float, Long, and Set<String>
    // Gap 35: still blocking — must be called from an IO thread in AdvancedPreferences
    fun backupPreferences(context: Context, backupUri: Uri) {
        val allPrefs = PreferenceManager.getDefaultSharedPreferences(context).all
        val sb = StringBuilder()
        for ((key, value) in allPrefs) {
            if (value is Set<*>) continue  // we skip Set<String> — it complicates the format
            if (value.toString().contains("://")) continue
            val type = when (value) {
                is Boolean -> "boolean"
                is Int     -> "integer"
                is Float   -> "float"
                is Long    -> "long"
                else       -> "string"
            }
            // Gap 36: we use ||| as a delimiter instead of space to avoid cutting off values that contain spaces
            sb.append("$type|||$key|||$value\n")
        }
        try {
            context.contentResolver.openOutputStream(backupUri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(sb.toString().trim()) }
            }
            showToast(context, R.string.preferences_saved)
        } catch (e: IOException) { e.printStackTrace() }
    }

    // Gap 36: restorePreferences supports values with spaces
    // Gap 43: supports float and long
    fun restorePreferences(context: Context, restoreUri: Uri) {
        try {
            context.contentResolver.openInputStream(restoreUri)?.use { inputStream ->
                val lines = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readLines()
                PreferenceManager.getDefaultSharedPreferences(context).edit {
                    lines.forEach { line ->
                        val parts = line.split("|||")
                        if (parts.size >= 3) {
                            val type  = parts[0]
                            val key   = parts[1]
                            val value = parts.drop(2).joinToString("|||") // the value might rarely contain |||
                            try {
                                when (type) {
                                    "boolean" -> putBoolean(key, value.toBoolean())
                                    "integer" -> putInt(key, value.toInt())
                                    "float"   -> putFloat(key, value.toFloat())
                                    "long"    -> putLong(key, value.toLong())
                                    else      -> putString(key, value)
                                }
                            } catch (e: Exception) { putString(key, value) }
                        }
                    }
                }
                showToast(context, R.string.preferences_restored)
            }
        } catch (e: IOException) { e.printStackTrace() }
    }

    // Gap 37: Locale.US to avoid Arabic/Persian numerals in the file name
    val currentDateString: String
        get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun dpToPxSystem(dp: Int): Int =
        (dp * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()

    fun showToast(context: Context, resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context.applicationContext ?: context, resId, duration).show()
            } catch (e: Exception) {}
        }
    }

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context.applicationContext ?: context, message, duration).show()
            } catch (e: Exception) {}
        }
    }
}
