package net.smoreo.thortweaks.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.SwitchPreference
import android.widget.Toast
import net.smoreo.thortweaks.R
import net.smoreo.thortweaks.model.BindingStore
import net.smoreo.thortweaks.model.HotkeyBinding
import net.smoreo.thortweaks.service.AutoRootSetup
import net.smoreo.thortweaks.service.HotkeyService
import net.smoreo.thortweaks.service.ModuleInstaller
import net.smoreo.thortweaks.util.KeyNames
import net.smoreo.thortweaks.util.RootShell

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            fragmentManager
                .beginTransaction()
                .replace(android.R.id.content, HotkeyPreferenceFragment())
                .commit()
        }
    }

    class HotkeyPreferenceFragment : PreferenceFragment() {

        companion object {
            const val REQUEST_ADD_BINDING = 1001
        }

        // Preference keys that should remain enabled even without root
        private val rootExemptKeys = setOf("one_click_setup")

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.preferences_main)

            val autoRoot = AutoRootSetup(activity)
            val installer = ModuleInstaller(activity)

            // Service toggle
            (findPreference("service_enabled") as? SwitchPreference)?.apply {
                isChecked = HotkeyService.isRunning
                setOnPreferenceChangeListener { _, newValue ->
                    val enable = newValue as Boolean
                    val intent = Intent(activity, HotkeyService::class.java)
                    if (enable) {
                        intent.action = HotkeyService.ACTION_START
                        activity.startForegroundService(intent)
                    } else {
                        intent.action = HotkeyService.ACTION_STOP
                        activity.startService(intent)
                    }
                    true
                }
            }

            // Long press duration summary
            (findPreference("long_press_ms") as? ListPreference)?.apply {
                summary = entry ?: "600 ms (default)"
                setOnPreferenceChangeListener { pref, newValue ->
                    val lp = pref as ListPreference
                    val idx = lp.findIndexOfValue(newValue as String)
                    pref.summary = if (idx >= 0) lp.entries[idx] else newValue
                    notifyService()
                    true
                }
            }

            // System info overlay toggle — managed by the HotkeyService
            (findPreference("overlay_enabled") as? SwitchPreference)?.apply {
                setOnPreferenceChangeListener { _, _ ->
                    notifyService()
                    true
                }
            }

            // Overlay update interval — writes to file that the overlay reads each tick
            (findPreference("overlay_interval") as? ListPreference)?.apply {
                summary = entry ?: "1000 ms (stock)"
                setOnPreferenceChangeListener { pref, newValue ->
                    val lp = pref as ListPreference
                    val idx = lp.findIndexOfValue(newValue as String)
                    pref.summary = if (idx >= 0) lp.entries[idx] else newValue
                    Thread {
                        RootShell.cmd("echo $newValue > /data/local/tmp/smore_overlay_interval")
                    }.start()
                    true
                }
                // Sync current value to file
                val currentVal = value ?: "1000"
                Thread {
                    RootShell.cmd("echo $currentVal > /data/local/tmp/smore_overlay_interval")
                }.start()
            }

            // Smallest width (display density)
            (findPreference("smallest_width") as? EditTextPreference)?.apply {
                // Read current override
                Thread {
                    val current = RootShell.cmdOutput("wm density").trim()
                    val override = Regex("Override density: (\\d+)").find(current)?.groupValues?.get(1)
                    activity.runOnUiThread {
                        if (override != null) {
                            text = override
                            summary = "Current: ${override} dpi (persists across reboots)"
                        } else {
                            val physical = Regex("Physical density: (\\d+)").find(current)?.groupValues?.get(1)
                            summary = "No override set (physical: ${physical ?: "?"} dpi)"
                        }
                    }
                }.start()
                setOnPreferenceChangeListener { _, newValue ->
                    val density = (newValue as? String)?.trim()?.toIntOrNull()
                    if (density != null && density in 100..600) {
                        Thread {
                            RootShell.cmd("wm density $density")
                            RootShell.cmd("echo $density > /data/local/tmp/smore_display_density")
                            activity.runOnUiThread {
                                summary = "Current: $density dpi (persists across reboots)"
                            }
                        }.start()
                        true
                    } else {
                        Toast.makeText(activity, "Invalid density (100-600)", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }

            // One Click Setup — click handler set after root check below

            findPreference("restore_boot")?.apply {
                isEnabled = false
                summary = "Checking..."
                Thread {
                    val hasBackup = try {
                        val check = net.smoreo.thortweaks.util.PServerBinder.exec(
                            "test -f /data/adb/smore-tweaks-backup/boot.img.bak && echo y"
                        )
                        check?.trim() == "y"
                    } catch (_: Exception) {
                        RootShell.cmdOutput(
                            "test -f /data/adb/smore-tweaks-backup/boot.img.bak && echo y"
                        ).trim() == "y"
                    }
                    activity.runOnUiThread {
                        isEnabled = hasBackup
                        summary = if (hasBackup) "Unroot by restoring original boot partition"
                            else "No boot backup available"
                    }
                }.start()
                setOnPreferenceClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Restore Everything?")
                        .setMessage("This will:\n\n" +
                            "\u2022 Uninstall S\u2019more Tweaks module\n" +
                            "\u2022 Uninstall APatch Manager\n" +
                            "\u2022 Restore stock vendor_boot (undo GPU OC)\n" +
                            "\u2022 Restore stock boot (remove APatch root)\n\n" +
                            "A reboot is required.")
                        .setPositiveButton("Restore") { _, _ ->
                            summary = "Restoring..."
                            Thread {
                                try {
                                    // 1. Uninstall module
                                    activity.runOnUiThread { summary = "Removing module..." }
                                    installer.uninstallSync()

                                    // 2. Uninstall APatch Manager
                                    activity.runOnUiThread { summary = "Uninstalling APatch..." }
                                    RootShell.cmd("pm uninstall me.bmax.apatch")

                                    // 3. Restore vendor_boot if backup exists
                                    if (installer.hasVendorBootBackup()) {
                                        activity.runOnUiThread { summary = "Restoring vendor_boot..." }
                                        installer.restoreVendorBootSync()
                                    }

                                    // 4. Restore boot partition
                                    activity.runOnUiThread { summary = "Restoring boot..." }
                                    val slot = RootShell.cmdOutput("getprop ro.boot.slot_suffix").trim()
                                    RootShell.cmdStrict(
                                        "dd if=/data/adb/smore-tweaks-backup/boot.img.bak " +
                                        "of=/dev/block/by-name/boot$slot"
                                    )

                                    activity.runOnUiThread {
                                        summary = "Everything restored. Reboot to complete."
                                        AlertDialog.Builder(activity)
                                            .setTitle("Reboot now?")
                                            .setMessage("Module removed, vendor_boot and boot restored.\nReboot to finish.")
                                            .setPositiveButton("Reboot") { _, _ ->
                                                Thread { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) }.start()
                                            }
                                            .setNegativeButton("Later", null)
                                            .show()
                                    }
                                } catch (e: Exception) {
                                    activity.runOnUiThread {
                                        summary = "Restore failed: ${e.message}"
                                    }
                                }
                            }.start()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }

            // Add binding button
            findPreference("add_binding")?.setOnPreferenceClickListener {
                startActivityForResult(
                    Intent(activity, AddBindingActivity::class.java),
                    REQUEST_ADD_BINDING
                )
                true
            }

            // Check root + module status in background, then configure UI
            findPreference("one_click_setup")?.summary = "Checking..."
            Thread {
                val rooted = autoRoot.isRooted()
                val status = if (rooted) installer.getStatus() else null

                activity.runOnUiThread {
                    // Disable/grey out all preferences except one_click_setup when not rooted
                    if (!rooted) {
                        setAllPreferencesEnabled(preferenceScreen, false)
                    }

                    // Update one_click_setup summary based on root + module state
                    val setupPref = findPreference("one_click_setup")
                    setupPref?.isEnabled = true
                    setupPref?.summary = when {
                        !rooted -> "Root device with APatch and install everything"
                        status != null && status.installed -> {
                            val upToDate = status.versionCode == ModuleInstaller.MODULE_VERSION_CODE
                            if (upToDate) "Installed: ${status.version} (up to date)"
                            else "Installed: ${status.version} \u2014 update available (${ModuleInstaller.MODULE_VERSION})"
                        }
                        else -> "Rooted \u2014 tap to install module"
                    }

                    // Set up click handler based on root state
                    setupPref?.setOnPreferenceClickListener {
                        if (rooted) {
                            // Already rooted — install/update module
                            AlertDialog.Builder(activity)
                                .setTitle("Install Module?")
                                .setMessage("This will:\n\n" +
                                    "\u2022 Remove old modules (thor-hotkeys, gpu_oc_692)\n" +
                                    "\u2022 Install S\u2019more Tweaks as system app\n" +
                                    "\u2022 Install GPU overclock (pservice, kernel modules, firmware)\n" +
                                    "\u2022 Patch vendor_boot DTB for GPU clock tables\n" +
                                    "\u2022 Set up priv-app permissions\n\n" +
                                    "A reboot is required to activate.")
                                .setPositiveButton("Install") { _, _ ->
                                    setupPref.summary = "Installing..."
                                    installer.install(
                                        onProgress = { msg ->
                                            activity.runOnUiThread { setupPref.summary = msg }
                                        },
                                        onDone = { ok, msg ->
                                            activity.runOnUiThread {
                                                setupPref.summary = msg
                                                if (ok) {
                                                    AlertDialog.Builder(activity)
                                                        .setTitle("Reboot now?")
                                                        .setMessage("Module installed. Reboot to activate.")
                                                        .setPositiveButton("Reboot") { _, _ ->
                                                            Thread { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) }.start()
                                                        }
                                                        .setNegativeButton("Later", null)
                                                        .show()
                                                }
                                            }
                                        }
                                    )
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            // Not rooted — full setup flow
                            val hasPServer = net.smoreo.thortweaks.util.PServerBinder.isAvailable
                            if (!hasPServer) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Not Supported")
                                    .setMessage("PServerBinder not found. This feature requires stock AYN firmware.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } else {
                                AlertDialog.Builder(activity)
                                    .setTitle("One Click Setup")
                                    .setMessage("This will:\n\n" +
                                        "  1. Root with APatch\n" +
                                        "  2. Install APatch Manager\n" +
                                        "  3. Patch GPU clock (680 -> 692 MHz)\n" +
                                        "  4. Install S\u2019more Tweaks module\n\n" +
                                        "All firmware partitions (boot, vendor_boot, init_boot, vbmeta, dtbo) are backed up automatically.\n" +
                                        "One reboot required.")
                                    .setPositiveButton("Install") { _, _ ->
                                        autoRoot.install(
                                            onProgress = { msg ->
                                                activity.runOnUiThread { setupPref.summary = msg }
                                            },
                                            onDone = { ok, msg ->
                                                activity.runOnUiThread {
                                                    setupPref.summary = if (ok) "Setup complete — reboot required" else msg
                                                    if (ok) {
                                                        AlertDialog.Builder(activity)
                                                            .setTitle("Reboot now?")
                                                            .setMessage(msg)
                                                            .setPositiveButton("Reboot") { _, _ ->
                                                                net.smoreo.thortweaks.util.PServerBinder.exec("reboot")
                                                            }
                                                            .setNegativeButton("Later", null)
                                                            .show()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                        true
                    }
                }
            }.start()

            refreshBindingList()
        }

        private fun setAllPreferencesEnabled(screen: android.preference.PreferenceGroup, enabled: Boolean) {
            for (i in 0 until screen.preferenceCount) {
                val pref = screen.getPreference(i)
                if (pref.key in rootExemptKeys) continue
                if (pref is android.preference.PreferenceGroup) {
                    setAllPreferencesEnabled(pref, enabled)
                } else {
                    pref.isEnabled = enabled
                }
            }
        }

        override fun onResume() {
            super.onResume()
            (findPreference("service_enabled") as? SwitchPreference)?.isChecked =
                HotkeyService.isRunning
            refreshBindingList()
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == REQUEST_ADD_BINDING && resultCode == RESULT_OK) {
                refreshBindingList()
                notifyService()
            }
        }

        private fun refreshBindingList() {
            val category = findPreference("category_bindings") as? PreferenceCategory ?: return

            // Remove old dynamic binding entries
            val toRemove = mutableListOf<Preference>()
            for (i in 0 until category.preferenceCount) {
                val pref = category.getPreference(i)
                if (pref.key?.startsWith("binding_") == true) {
                    toRemove.add(pref)
                }
            }
            toRemove.forEach { category.removePreference(it) }

            // Add current bindings
            val bindings = BindingStore.getBindings(activity)
            for (binding in bindings) {
                val pref = Preference(activity).apply {
                    key = "binding_${binding.id}"
                    title = binding.keys.joinToString(" + ") { KeyNames.friendly(it) }
                    summary = buildString {
                        if (binding.requireLongPress) append("Long press → ")
                        append(binding.action.label)
                        if (binding.extra.isNotEmpty()) append(" (${binding.extra})")
                        if (!binding.foregroundApp.isNullOrEmpty()) append(" [${binding.foregroundApp}]")
                    }
                    setOnPreferenceClickListener {
                        editBinding(binding)
                        true
                    }
                }
                category.addPreference(pref)
            }
        }

        private fun editBinding(binding: HotkeyBinding) {
            val intent = Intent(activity, AddBindingActivity::class.java).apply {
                putExtra(AddBindingActivity.EXTRA_BINDING_ID, binding.id)
                putStringArrayListExtra(
                    AddBindingActivity.EXTRA_BINDING_KEYS,
                    ArrayList(binding.keys)
                )
                putExtra(AddBindingActivity.EXTRA_BINDING_LONG_PRESS, binding.requireLongPress)
                putExtra(AddBindingActivity.EXTRA_BINDING_ACTION, binding.action.name)
                putExtra(AddBindingActivity.EXTRA_BINDING_EXTRA, binding.extra)
                putExtra(AddBindingActivity.EXTRA_BINDING_FOREGROUND_APP, binding.foregroundApp)
            }
            startActivityForResult(intent, REQUEST_ADD_BINDING)
        }

        private fun notifyService() {
            if (HotkeyService.isRunning) {
                val intent = Intent(activity, HotkeyService::class.java).apply {
                    action = HotkeyService.ACTION_RELOAD
                }
                activity.startService(intent)
            }
        }

    }
}
