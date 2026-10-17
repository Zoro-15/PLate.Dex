package com.plate.launcher.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.plate.launcher.utils.DeviceUtils
import com.plate.launcher.utils.Utils

class SoundEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> DeviceUtils.playEventSound(context, "usb_sound")
            Intent.ACTION_POWER_CONNECTED -> {
                Utils.shouldPlayChargeComplete.set(true)
                DeviceUtils.playEventSound(context, "charge_sound")
            }
        }
    }
}
