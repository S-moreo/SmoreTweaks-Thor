package com.thor.hotkeys.ui

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
import com.thor.hotkeys.R
import com.thor.hotkeys.model.BindingStore
import com.thor.hotkeys.model.HotkeyBinding
import com.thor.hotkeys.service.HotkeyService
import com.thor.hotkeys.service.ModuleInstaller
import com.thor.hotkeys.util.KeyNames
import com.thor.hotkeys.util.RootShell

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            fragmentManager
                .beginTransaction()
                .replace(android.R.id.content, HotkeyPreferenceFragment())
                .commit()
        }
        checkRoot()
    }

    private fun checkRoot() {
        Thread {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                p.waitFor()
                val output = p.inputStream.bufferedReader().readText()
                if (!output.contains("uid=0")) {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.no_root), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Root unavailable: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    class HotkeyPreferenceFragment : PreferenceFragment() {

        companion object {
            const val REQUEST_ADD_BINDING = 1001
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.preferences_main)

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

            // Add binding button
            findPreference("add_binding")?.setOnPreferenceClickListener {
                startActivityForResult(
                    Intent(activity, AddBindingActivity::class.java),
                    REQUEST_ADD_BINDING
                )
                true
            }

            // Module install — load status off UI thread
            val installer = ModuleInstaller(activity)

            findPreference("install_module")?.apply {
                summary = "Checking..."
                setOnPreferenceClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Install Module?")
                        .setMessage("This will:\n\n• Remove old modules (thor-hotkeys, gpu_oc_692)\n• Install S'more Tweaks as system app\n• Install GPU overclock (pservice, kernel modules, firmware)\n• Patch vendor_boot DTB for GPU clock tables\n• Set up priv-app permissions\n\nA reboot is required to activate.")
                        .setPositiveButton("Install") { _, _ ->
                            summary = "Installing..."
                            installer.install(
                                onProgress = { msg -> activity.runOnUiThread { summary = msg } },
                                onDone = { ok, msg ->
                                    activity.runOnUiThread {
                                        summary = msg
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
                    true
                }
            }

            findPreference("uninstall_module")?.apply {
                isEnabled = false
                setOnPreferenceClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Uninstall Module?")
                        .setMessage("This will remove the system module. A reboot is required.")
                        .setPositiveButton("Uninstall") { _, _ ->
                            installer.uninstall { ok, msg ->
                                activity.runOnUiThread {
                                    summary = msg
                                    isEnabled = false
                                    if (ok) {
                                        AlertDialog.Builder(activity)
                                            .setTitle("Reboot now?")
                                            .setMessage("Module removed. Reboot to complete.")
                                            .setPositiveButton("Reboot") { _, _ ->
                                                Thread { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) }.start()
                                            }
                                            .setNegativeButton("Later", null)
                                            .show()
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }

            findPreference("restore_vendor_boot")?.apply {
                isEnabled = false
                summary = "Checking..."
                setOnPreferenceClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Restore Stock vendor_boot?")
                        .setMessage("This will flash the backed-up stock vendor_boot partition, reversing the GPU overclock DTB patch.\n\nA reboot is required.")
                        .setPositiveButton("Restore") { _, _ ->
                            summary = "Restoring..."
                            installer.restoreVendorBoot(
                                onProgress = { msg -> activity.runOnUiThread { summary = msg } },
                                onDone = { ok, msg ->
                                    activity.runOnUiThread {
                                        summary = msg
                                        if (ok) {
                                            AlertDialog.Builder(activity)
                                                .setTitle("Reboot now?")
                                                .setMessage("Stock vendor_boot restored. Reboot to apply.")
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
                    true
                }
            }

            // Load module status off UI thread
            Thread {
                val status = installer.getStatus()
                val hasBackup = installer.hasVendorBootBackup()
                activity.runOnUiThread {
                    findPreference("install_module")?.summary = if (status.installed) {
                        val upToDate = status.versionCode == ModuleInstaller.MODULE_VERSION_CODE
                        if (upToDate) "Installed: ${status.version} (up to date)"
                        else "Installed: ${status.version} — update available (${ModuleInstaller.MODULE_VERSION})"
                    } else {
                        "Not installed — tap to install"
                    }
                    findPreference("uninstall_module")?.isEnabled = status.installed
                    findPreference("restore_vendor_boot")?.apply {
                        isEnabled = hasBackup
                        summary = if (hasBackup) "Restore from backup (reverses GPU OC DTB patch)"
                            else "No backup available"
                    }
                }
            }.start()

            refreshBindingList()
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
