package net.smoreo.thortweaks.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            updateLauncherVisibility(context)

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

    /** Hide launcher icon when running as a system app (shows in Settings instead). */
    private fun updateLauncherVisibility(context: Context) {
        val isSystemApp = context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val alias = ComponentName(context, "net.smoreo.thortweaks.LauncherAlias")
        val desired = if (isSystemApp)
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val current = context.packageManager.getComponentEnabledSetting(alias)
        if (current != desired) {
            context.packageManager.setComponentEnabledSetting(
                alias, desired, PackageManager.DONT_KILL_APP
            )
            Log.d("BootReceiver", "Launcher alias ${if (isSystemApp) "hidden" else "shown"}")
        }
    }
}
