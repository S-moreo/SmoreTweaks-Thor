package com.thor.hotkeys.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autostart = prefs.getBoolean("autostart", true)
            val enabled = prefs.getBoolean("service_enabled", false)

            if (autostart && enabled) {
                Log.d("BootReceiver", "Boot completed, starting HotkeyService")
                val serviceIntent = Intent(context, HotkeyService::class.java).apply {
                    action = HotkeyService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
