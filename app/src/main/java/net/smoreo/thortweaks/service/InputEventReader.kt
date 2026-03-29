package net.smoreo.thortweaks.service

import android.util.Log
import net.smoreo.thortweaks.util.DeviceConfig
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads raw input events from /dev/input/event* using root getevent.
 * Parses EV_KEY events and EV_ABS hat/stick events into virtual key events.
 */
class InputEventReader(
    private val onKeyEvent: (keyName: String, isDown: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "InputEventReader"
    }

    @Volatile
    private var running = false
    private var process: Process? = null
    private var thread: Thread? = null

    // Track ABS hat/stick state to generate synthetic key down/up
    private var hatX = 0
    private var hatY = 0
    private var stickX = 0
    private var stickY = 0
    private val stickThreshold = 16000 // ~50% of 32767

    fun start() {
        if (running) return
        running = true

        thread = Thread({
            try {
                val pb = ProcessBuilder("su", "-c", "getevent -l")
                pb.redirectErrorStream(true)
                process = pb.start()

                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?

                while (running) {
                    line = reader.readLine()
                    if (line == null) break
                    parseGeteventLine(line)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "getevent read error", e)
                }
            }
        }, "InputEventReader")
        thread!!.isDaemon = true
        thread!!.start()
    }

    fun stop() {
        running = false
        try {
            process?.destroy()
            process?.destroyForcibly()
        } catch (_: Exception) {}
        // Kill orphan getevent processes left behind by su
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f getevent")).waitFor()
        } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
        process = null
    }

    private fun parseGeteventLine(line: String) {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 4) return

        val devicePath = parts[0].trimEnd(':')
        if (devicePath !in DeviceConfig.ALLOWED_DEVICES) return

        val eventType = parts[1]
        val codeName = parts[2]
        val value = parts[3]

        when (eventType) {
            "EV_KEY" -> handleKeyEvent(codeName, value)
            "EV_ABS" -> handleAbsEvent(codeName, value)
        }
    }

    private fun handleKeyEvent(keyName: String, value: String) {
        val isDown = when (value) {
            "DOWN" -> true
            "UP" -> false
            "00000001" -> true
            "00000000" -> false
            else -> return
        }

        Log.d(TAG, "Key: $keyName ${if (isDown) "DOWN" else "UP"}")

        try {
            onKeyEvent(keyName, isDown)
        } catch (e: Exception) {
            Log.e(TAG, "Error in key event callback", e)
        }
    }

    /**
     * Handle ABS events for D-pad hat and left stick.
     * Generates synthetic key down/up events:
     *   ABS_HAT0X: -1 → DPAD_LEFT, +1 → DPAD_RIGHT
     *   ABS_HAT0Y: -1 → DPAD_UP,   +1 → DPAD_DOWN
     *   ABS_X:     left/right based on threshold
     *   ABS_Y:     up/down based on threshold
     *
     * getevent -l format: ABS_HAT0X  00000001  or  ABS_HAT0X  ffffffff (-1)
     */
    private fun handleAbsEvent(codeName: String, value: String) {
        val intVal = try {
            // getevent prints hex values for ABS, e.g. "00000001", "ffffffff", "ffff8001"
            value.toLong(16).toInt() // handles unsigned → signed via overflow
        } catch (_: Exception) {
            return
        }

        when (codeName) {
            "ABS_HAT0X" -> {
                val oldHat = hatX
                hatX = intVal
                if (oldHat <= 0 && intVal > 0) emitKey("DPAD_RIGHT", true)
                if (oldHat >= 0 && intVal < 0) emitKey("DPAD_LEFT", true)
                if (oldHat > 0 && intVal <= 0) emitKey("DPAD_RIGHT", false)
                if (oldHat < 0 && intVal >= 0) emitKey("DPAD_LEFT", false)
            }
            "ABS_HAT0Y" -> {
                val oldHat = hatY
                hatY = intVal
                if (oldHat <= 0 && intVal > 0) emitKey("DPAD_DOWN", true)
                if (oldHat >= 0 && intVal < 0) emitKey("DPAD_UP", true)
                if (oldHat > 0 && intVal <= 0) emitKey("DPAD_DOWN", false)
                if (oldHat < 0 && intVal >= 0) emitKey("DPAD_UP", false)
            }
            "ABS_X" -> {
                val oldDir = stickDir(stickX)
                stickX = intVal
                val newDir = stickDir(intVal)
                if (oldDir != newDir) {
                    if (oldDir < 0) emitKey("STICK_LEFT", false)
                    if (oldDir > 0) emitKey("STICK_RIGHT", false)
                    if (newDir < 0) emitKey("STICK_LEFT", true)
                    if (newDir > 0) emitKey("STICK_RIGHT", true)
                }
            }
            "ABS_Y" -> {
                val oldDir = stickDir(stickY)
                stickY = intVal
                val newDir = stickDir(intVal)
                if (oldDir != newDir) {
                    if (oldDir < 0) emitKey("STICK_UP", false)
                    if (oldDir > 0) emitKey("STICK_DOWN", false)
                    if (newDir < 0) emitKey("STICK_UP", true)
                    if (newDir > 0) emitKey("STICK_DOWN", true)
                }
            }
        }
    }

    private fun stickDir(value: Int): Int = when {
        value < -stickThreshold -> -1
        value > stickThreshold -> 1
        else -> 0
    }

    private fun emitKey(name: String, isDown: Boolean) {
        Log.d(TAG, "Key: $name ${if (isDown) "DOWN" else "UP"} (synthetic)")
        try {
            onKeyEvent(name, isDown)
        } catch (e: Exception) {
            Log.e(TAG, "Error in synthetic key callback", e)
        }
    }
}
