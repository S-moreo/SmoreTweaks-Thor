package net.smoreo.thortweaks.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import net.smoreo.thortweaks.model.HotkeyBinding

/**
 * Detects hotkey combos and long-presses from raw key events.
 *
 * Long-press bindings fire immediately when the hold timer expires.
 */
class HotkeyDetector(
    private val onTriggered: (HotkeyBinding) -> Unit
) {
    companion object {
        private const val TAG = "HotkeyDetector"
    }

    var longPressMs: Long = 600L
    @Volatile
    var currentForegroundApp: String = ""

    private val heldKeys = mutableSetOf<String>()
    private val firedBindings = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val pendingLongPresses = mutableMapOf<String, Runnable>()

    // Keys suppressed after a binding fires — ignored until released
    private val suppressedKeys = mutableSetOf<String>()

    var bindings: List<HotkeyBinding> = emptyList()

    fun onKeyEvent(keyName: String, isDown: Boolean) {
        if (isDown) {
            if (keyName in suppressedKeys) return
            heldKeys.add(keyName)
            checkBindings()
        } else {
            suppressedKeys.remove(keyName)
            heldKeys.remove(keyName)
            cancelLongPressesInvolving(keyName)
            if (heldKeys.isEmpty()) {
                firedBindings.clear()
            }
        }
    }

    private fun checkBindings() {
        for (binding in bindings) {
            if (binding.id in firedBindings) continue

            // Check foreground app filter
            if (!binding.foregroundApp.isNullOrEmpty() &&
                binding.foregroundApp != currentForegroundApp) continue

            val allHeld = binding.keys.all { it in heldKeys }
            if (!allHeld) continue

            // For combos, require exact key count
            if (binding.keys.size > 1 && heldKeys.size != binding.keys.size) continue

            if (binding.requireLongPress) {
                scheduleLongPress(binding)
            } else {
                fire(binding)
            }
        }
    }

    private fun scheduleLongPress(binding: HotkeyBinding) {
        if (binding.id in pendingLongPresses) return

        val runnable = Runnable {
            pendingLongPresses.remove(binding.id)
            val stillHeld = binding.keys.all { it in heldKeys }
            if (stillHeld && binding.id !in firedBindings) {
                fire(binding)
            }
        }
        pendingLongPresses[binding.id] = runnable
        handler.postDelayed(runnable, longPressMs)
    }

    private fun cancelLongPressesInvolving(keyName: String) {
        val toRemove = mutableListOf<String>()
        for ((bindingId, runnable) in pendingLongPresses) {
            val binding = bindings.find { it.id == bindingId }
            if (binding != null && keyName in binding.keys) {
                handler.removeCallbacks(runnable)
                toRemove.add(bindingId)
            }
        }
        toRemove.forEach { pendingLongPresses.remove(it) }
    }

    private fun fire(binding: HotkeyBinding) {
        firedBindings.add(binding.id)
        // Suppress all currently held keys until they're released
        suppressedKeys.addAll(heldKeys)
        Log.d(TAG, "Fired: ${binding.keys} -> ${binding.action}")
        try {
            onTriggered(binding)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing binding", e)
        }
    }

    fun reset() {
        heldKeys.clear()
        firedBindings.clear()
        suppressedKeys.clear()
        for ((_, runnable) in pendingLongPresses) {
            handler.removeCallbacks(runnable)
        }
        pendingLongPresses.clear()
    }
}
