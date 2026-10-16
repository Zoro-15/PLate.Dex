package com.youki.dex.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.youki.dex.utils.DeviceUtils
import com.youki.dex.utils.Utils

class BatteryStatsReceiver(
    private val context: Context,
    private val batteryBtn: TextView,
    var showLevel: Boolean
) : BroadcastReceiver() {

    var level = 0
    var isCharging = false
    var onBatteryChanged: ((level: Int, charging: Boolean) -> Unit)? = null
    private var lastDrawableRes = -1   // cache to avoid redundant drawable reloads
    private var lastCharging = false

    init {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        isCharging = charging
        lastCharging = charging
        lastDrawableRes = Utils.getBatteryDrawable(level, charging)
        applyDrawable(lastDrawableRes)
        if (showLevel) batteryBtn.text = "$level%"
        onBatteryChanged?.invoke(level, isCharging)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val newLevel = extras.getInt("level", level)
        val plugged = extras.getInt("plugged", 0) != 0
        isCharging = plugged

        val newRes = Utils.getBatteryDrawable(newLevel, plugged)

        // Only redraw if something actually changed — keep it light
        if (newRes != lastDrawableRes || plugged != lastCharging) {
            lastDrawableRes = newRes
            lastCharging = plugged
            applyDrawable(newRes)
        }

        if (newLevel != level) {
            level = newLevel
            if (showLevel) batteryBtn.text = "$level%"
        }
        onBatteryChanged?.invoke(level, isCharging)

        if (!plugged && level == 100) {
            if (Utils.shouldPlayChargeComplete.get())
                DeviceUtils.playEventSound(context, "charge_complete_sound")
            Utils.shouldPlayChargeComplete.set(false)
        }
    }

    private fun applyDrawable(res: Int) {
        val drawable = AppCompatResources.getDrawable(context, res)
        batteryBtn.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
    }
}
