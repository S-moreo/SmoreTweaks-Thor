package net.smoreo.thortweaks.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.SwitchPreference
import android.util.Log
import android.view.KeyEvent
import android.view.MenuItem
import android.widget.Toast
import net.smoreo.thortweaks.model.BindingStore
import net.smoreo.thortweaks.model.HotkeyAction
import net.smoreo.thortweaks.model.HotkeyBinding
import net.smoreo.thortweaks.util.DeviceConfig
import net.smoreo.thortweaks.util.KeyNames
import java.io.BufferedReader
import java.io.InputStreamReader

class AddBindingActivity : Activity() {

    companion object {
        const val EXTRA_BINDING_ID = "binding_id"
        const val EXTRA_BINDING_KEYS = "binding_keys"
        const val EXTRA_BINDING_LONG_PRESS = "binding_long_press"
        const val EXTRA_BINDING_ACTION = "binding_action"
        const val EXTRA_BINDING_EXTRA = "binding_extra"
        const val EXTRA_BINDING_FOREGROUND_APP = "binding_foreground_app"
    }

    // Set by fragment during key capture to swallow all framework key events
    @Volatile
    var capturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        if (intent.hasExtra(EXTRA_BINDING_ID)) {
            title = "Edit Hotkey Binding"
        }
        if (savedInstanceState == null) {
            val fragment = AddBindingFragment()
            fragment.arguments = intent.extras
            fragmentManager
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capturing) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class AddBindingFragment : PreferenceFragment() {

        private var capturedKeys: List<String> = emptyList()
        private var captureProcess: Process? = null
        private var captureThread: Thread? = null

        @Volatile
        private var capturing = false
        private val handler = Handler(Looper.getMainLooper())
        private val allCapturedKeys = mutableSetOf<String>()
        private var settleRunnable: Runnable? = null

        private data class AppEntry(val label: String, val packageName: String)
        private var installedApps: List<AppEntry> = emptyList()

        // Non-null when editing an existing binding
        private var editingId: String? = null

        companion object {
            private const val TAG = "AddBinding"
            private const val SETTLE_MS = 800L
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Check if editing an existing binding
            val args = arguments
            editingId = args?.getString(EXTRA_BINDING_ID)
            val existingKeys = args?.getStringArrayList(EXTRA_BINDING_KEYS)
            val existingLongPress = args?.getBoolean(EXTRA_BINDING_LONG_PRESS, false) ?: false
            val existingAction = args?.getString(EXTRA_BINDING_ACTION)
            val existingExtra = args?.getString(EXTRA_BINDING_EXTRA) ?: ""
            val existingForegroundApp = args?.getString(EXTRA_BINDING_FOREGROUND_APP) ?: ""
            val isEditing = editingId != null

            if (existingKeys != null) {
                capturedKeys = existingKeys.toList()
            }

            val screen = preferenceManager.createPreferenceScreen(activity)

            // --- Key Capture ---
            val captureCategory = PreferenceCategory(activity).apply {
                title = "Trigger"
            }
            screen.addPreference(captureCategory)

            val capturedPref = Preference(activity).apply {
                key = "captured_keys"
                if (isEditing && capturedKeys.isNotEmpty()) {
                    title = "Keys (tap to recapture)"
                    summary = capturedKeys.joinToString(" + ") { KeyNames.friendly(it) }
                } else {
                    title = "Keys"
                    summary = "Tap to capture controller input"
                }
                setOnPreferenceClickListener {
                    startCapture()
                    true
                }
            }
            captureCategory.addPreference(capturedPref)

            val longPressPref = SwitchPreference(activity).apply {
                key = "long_press"
                title = "Long press"
                summary = "Require holding the key(s)"
                setDefaultValue(false)
                isChecked = existingLongPress
            }
            captureCategory.addPreference(longPressPref)

            // Foreground app filter (optional)
            val fgAppPref = ListPreference(activity).apply {
                key = "foreground_app"
                title = "Only when app is active"
                summary = if (existingForegroundApp.isEmpty()) "Any app (global)"
                    else existingForegroundApp
                setOnPreferenceChangeListener { pref, newValue ->
                    val pkg = newValue as String
                    pref.summary = if (pkg.isEmpty()) "Any app (global)" else pkg
                    true
                }
            }
            captureCategory.addPreference(fgAppPref)

            // --- Action ---
            val actionCategory = PreferenceCategory(activity).apply {
                title = "Action"
            }
            screen.addPreference(actionCategory)

            val actions = HotkeyAction.entries.toTypedArray()
            val initialAction = if (existingAction != null) {
                try { HotkeyAction.valueOf(existingAction) } catch (_: Exception) { actions[0] }
            } else {
                actions[0]
            }

            val actionPref = ListPreference(activity).apply {
                key = "action"
                title = "Action type"
                entries = actions.map { it.label }.toTypedArray()
                entryValues = actions.map { it.name }.toTypedArray()
                value = initialAction.name
                summary = initialAction.label
                setOnPreferenceChangeListener { pref, newValue ->
                    val action = HotkeyAction.valueOf(newValue as String)
                    (pref as ListPreference).summary = action.label
                    updateExtraVisibility(action)
                    true
                }
            }
            actionCategory.addPreference(actionPref)

            // App picker (for OPEN_APP)
            val appPref = ListPreference(activity).apply {
                key = "app_package"
                title = "Application"
                summary = "Select an app to launch"
                isEnabled = true
            }
            actionCategory.addPreference(appPref)

            // Text extra (for SHELL_COMMAND / SEND_KEYEVENT)
            val extraPref = EditTextPreference(activity).apply {
                key = "extra_text"
                title = "Parameter"
                if (existingAction != null && existingAction != HotkeyAction.OPEN_APP.name && existingExtra.isNotEmpty()) {
                    text = existingExtra
                    summary = existingExtra
                }
            }
            actionCategory.addPreference(extraPref)

            // --- Save / Delete ---
            val saveCategory = PreferenceCategory(activity).apply {
                title = ""
            }
            screen.addPreference(saveCategory)

            val savePref = Preference(activity).apply {
                key = "save"
                title = if (isEditing) "Save changes" else "Save binding"
                summary = if (isEditing) "Update this hotkey binding" else "Add this hotkey binding"
                setOnPreferenceClickListener {
                    saveBinding()
                    true
                }
            }
            saveCategory.addPreference(savePref)

            if (isEditing) {
                val deletePref = Preference(activity).apply {
                    key = "delete"
                    title = "Delete binding"
                    summary = "Remove this hotkey binding"
                    setOnPreferenceClickListener {
                        confirmDelete()
                        true
                    }
                }
                saveCategory.addPreference(deletePref)
            }

            preferenceScreen = screen

            // Set extra visibility based on initial action
            updateExtraVisibility(initialAction)

            // Load installed apps in background, then set pre-selected value if editing
            loadInstalledApps(
                preselectPackage = if (isEditing && initialAction == HotkeyAction.OPEN_APP) existingExtra else null,
                preselectForegroundApp = existingForegroundApp
            )
        }

        override fun onDestroy() {
            stopCapture()
            super.onDestroy()
        }

        private fun loadInstalledApps(preselectPackage: String?, preselectForegroundApp: String?) {
            Thread {
                val pm = activity.packageManager
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val resolveInfos = pm.queryIntentActivities(intent, 0)

                installedApps = resolveInfos.map { ri ->
                    AppEntry(
                        label = ri.loadLabel(pm).toString(),
                        packageName = ri.activityInfo.packageName
                    )
                }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }

                handler.post {
                    // Action app picker
                    (findPreference("app_package") as? ListPreference)?.apply {
                        entries = installedApps.map { it.label }.toTypedArray()
                        entryValues = installedApps.map { it.packageName }.toTypedArray()
                        if (preselectPackage != null) {
                            value = preselectPackage
                            val idx = installedApps.indexOfFirst { it.packageName == preselectPackage }
                            if (idx >= 0) summary = installedApps[idx].label
                        }
                    }

                    // Foreground app filter picker (with "Any app" at top)
                    val fgLabels = listOf("Any app (global)") + installedApps.map { it.label }
                    val fgValues = listOf("") + installedApps.map { it.packageName }
                    (findPreference("foreground_app") as? ListPreference)?.apply {
                        entries = fgLabels.toTypedArray()
                        entryValues = fgValues.toTypedArray()
                        if (!preselectForegroundApp.isNullOrEmpty()) {
                            value = preselectForegroundApp
                            val idx = installedApps.indexOfFirst { it.packageName == preselectForegroundApp }
                            summary = if (idx >= 0) installedApps[idx].label else preselectForegroundApp
                        } else {
                            value = ""
                            summary = "Any app (global)"
                        }
                    }
                }
            }.start()
        }

        private fun updateExtraVisibility(action: HotkeyAction) {
            val appPref = findPreference("app_package")
            val extraPref = findPreference("extra_text")

            appPref?.isEnabled = action == HotkeyAction.OPEN_APP
            extraPref?.isEnabled = action.needsExtra && action != HotkeyAction.OPEN_APP

            if (action == HotkeyAction.OPEN_APP) {
                appPref?.title = "Application"
            }

            extraPref?.title = when (action) {
                HotkeyAction.SHELL_COMMAND -> "Shell command"
                HotkeyAction.SEND_KEYEVENT -> "Key code"
                else -> "Parameter"
            }
        }

        // --- Key Capture via root getevent ---

        private fun startCapture() {
            stopCapture()
            capturing = true
            (activity as? AddBindingActivity)?.capturing = true
            allCapturedKeys.clear()

            findPreference("captured_keys")?.summary = "Listening… press keys now"

            captureThread = Thread {
                try {
                    val pb = ProcessBuilder("su", "-c", "getevent -l")
                    pb.redirectErrorStream(true)
                    captureProcess = pb.start()
                    val reader = BufferedReader(InputStreamReader(captureProcess!!.inputStream))
                    var line: String?

                    while (capturing) {
                        line = reader.readLine() ?: break
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size < 4) continue
                        val device = parts[0].trimEnd(':')
                        if (device !in DeviceConfig.ALLOWED_DEVICES) continue
                        if (parts[1] != "EV_KEY") continue

                        val keyName = parts[2]
                        val isDown = parts[3] == "DOWN" || parts[3] == "00000001"

                        if (isDown) {
                            allCapturedKeys.add(keyName)
                            handler.post {
                                findPreference("captured_keys")?.summary =
                                    allCapturedKeys.joinToString(" + ") { KeyNames.friendly(it) }
                            }
                            resetSettleTimer()
                        }
                    }
                } catch (e: Exception) {
                    if (capturing) Log.e(TAG, "Capture error", e)
                }
            }
            captureThread!!.isDaemon = true
            captureThread!!.start()
        }

        private fun resetSettleTimer() {
            settleRunnable?.let { handler.removeCallbacks(it) }
            settleRunnable = Runnable {
                capturedKeys = allCapturedKeys.toList().sorted()
                stopCapture()
                findPreference("captured_keys")?.apply {
                    summary = capturedKeys.joinToString(" + ") { KeyNames.friendly(it) }
                    title = "Keys (tap to recapture)"
                }
            }
            handler.postDelayed(settleRunnable!!, SETTLE_MS)
        }

        private fun stopCapture() {
            capturing = false
            (activity as? AddBindingActivity)?.capturing = false
            settleRunnable?.let { handler.removeCallbacks(it) }
            try {
                captureProcess?.destroy()
                captureProcess?.destroyForcibly()
            } catch (_: Exception) {}
            // Kill orphan getevent processes left behind by su
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f getevent")).waitFor()
            } catch (_: Exception) {}
            captureThread?.interrupt()
            captureProcess = null
            captureThread = null
        }

        // --- Save / Delete ---

        private fun saveBinding() {
            if (capturedKeys.isEmpty()) {
                Toast.makeText(activity, "Capture keys first!", Toast.LENGTH_SHORT).show()
                return
            }

            val actionName = (findPreference("action") as? ListPreference)?.value
                ?: HotkeyAction.entries[0].name
            val action = HotkeyAction.valueOf(actionName)

            val extra = when {
                action == HotkeyAction.OPEN_APP ->
                    (findPreference("app_package") as? ListPreference)?.value ?: ""
                action.needsExtra ->
                    (findPreference("extra_text") as? EditTextPreference)?.text ?: ""
                else -> ""
            }

            if (action.needsExtra && extra.isEmpty()) {
                Toast.makeText(activity, "Select a value for this action", Toast.LENGTH_SHORT).show()
                return
            }

            val longPress = (findPreference("long_press") as? SwitchPreference)?.isChecked ?: false
            val foregroundApp = (findPreference("foreground_app") as? ListPreference)?.value ?: ""

            val binding = HotkeyBinding(
                id = editingId ?: java.util.UUID.randomUUID().toString(),
                keys = capturedKeys,
                requireLongPress = longPress,
                action = action,
                extra = extra,
                foregroundApp = foregroundApp
            )

            if (editingId != null) {
                BindingStore.updateBinding(activity, binding)
            } else {
                BindingStore.addBinding(activity, binding)
            }
            activity.setResult(RESULT_OK)
            activity.finish()
        }

        private fun confirmDelete() {
            val id = editingId ?: return
            AlertDialog.Builder(activity)
                .setTitle("Delete binding?")
                .setMessage(capturedKeys.joinToString(" + ") { KeyNames.friendly(it) })
                .setPositiveButton("Delete") { _, _ ->
                    BindingStore.removeBinding(activity, id)
                    activity.setResult(RESULT_OK)
                    activity.finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
