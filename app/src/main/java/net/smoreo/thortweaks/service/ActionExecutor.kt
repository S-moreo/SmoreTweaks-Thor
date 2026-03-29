package net.smoreo.thortweaks.service

import android.content.Context
import android.provider.Settings
import android.util.Log
import net.smoreo.thortweaks.model.HotkeyAction
import net.smoreo.thortweaks.model.HotkeyBinding
import net.smoreo.thortweaks.util.RootShell

/**
 * Executes system actions via root shell commands.
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ActionExecutor"

        // getevent key name → (device path, linux key code) for sendevent injection
        private val KEY_DEVICE_MAP = mapOf(
            "KEY_HOME" to Pair("/dev/input/event9", 102),
            "KEY_BACK" to Pair("/dev/input/event9", 158),
            "KEY_APPSELECT" to Pair("/dev/input/event9", 580),
            "KEY_VOLUMEUP" to Pair("/dev/input/event0", 115),
            "KEY_VOLUMEDOWN" to Pair("/dev/input/event9", 114),
            "KEY_POWER" to Pair("/dev/input/event9", 116),
            "KEY_F24" to Pair("/dev/input/event0", 194),
            "BTN_TL" to Pair("/dev/input/event9", 310),
            "BTN_TR" to Pair("/dev/input/event9", 311),
            "BTN_TL2" to Pair("/dev/input/event9", 312),
            "BTN_TR2" to Pair("/dev/input/event9", 313),
            "BTN_GAMEPAD" to Pair("/dev/input/event9", 304),
            "BTN_EAST" to Pair("/dev/input/event9", 305),
            "BTN_NORTH" to Pair("/dev/input/event9", 306),
            "BTN_WEST" to Pair("/dev/input/event9", 307),
            "BTN_SELECT" to Pair("/dev/input/event9", 314),
            "BTN_START" to Pair("/dev/input/event9", 315),
            "BTN_MODE" to Pair("/dev/input/event9", 316),
            "BTN_THUMBL" to Pair("/dev/input/event9", 317),
            "BTN_THUMBR" to Pair("/dev/input/event9", 318),
        )
    }

    /**
     * Inject synthetic key-up events via sendevent so the Android input framework
     * thinks the trigger keys were released. This prevents system actions (e.g. HOME)
     * from firing when the user releases the physical key after a binding fires.
     */
    private fun releaseKeysFromFramework(keys: List<String>) {
        val cmds = StringBuilder()
        for (key in keys) {
            val (device, code) = KEY_DEVICE_MAP[key] ?: continue
            // EV_KEY=1, value=0 (UP), then EV_SYN=0 code=0 value=0
            cmds.append("sendevent $device 1 $code 0; sendevent $device 0 0 0; ")
        }
        if (cmds.isNotEmpty()) {
            RootShell.cmd(cmds.toString())
        }
    }

    fun execute(binding: HotkeyBinding) {
        Log.d(TAG, "Executing: ${binding.action} extra=${binding.extra}")

        when (binding.action) {
            HotkeyAction.OPEN_RECENTS -> {
                releaseKeysFromFramework(binding.keys)
                RootShell.cmd("am start -n net.smoreo.thortweaks/.ui.TaskDrawerActivity")
            }

            HotkeyAction.GO_HOME -> {
                RootShell.cmd("input keyevent KEYCODE_HOME")
            }

            HotkeyAction.GO_BACK -> {
                RootShell.cmd("input keyevent KEYCODE_BACK")
            }

            HotkeyAction.SWITCH_SCREEN_FOCUS -> {
                switchDisplayFocus()
            }

            HotkeyAction.OPEN_APP -> {
                val pkg = binding.extra.trim()
                if (pkg.isNotEmpty()) {
                    launchApp(pkg)
                }
            }

            HotkeyAction.OPEN_NOTIFICATIONS -> {
                RootShell.cmd("cmd statusbar expand-notifications")
            }

            HotkeyAction.OPEN_QUICK_SETTINGS -> {
                RootShell.cmd("cmd statusbar expand-settings")
            }

            HotkeyAction.TOGGLE_SPLIT_SCREEN -> {
                // Long-press recents triggers split screen on Android 13
                RootShell.cmd("input keyevent --longpress KEYCODE_APP_SWITCH")
            }

            HotkeyAction.SCREENSHOT -> {
                RootShell.cmd("input keyevent KEYCODE_SYSRQ")
            }

            HotkeyAction.VOLUME_UP -> {
                RootShell.cmd("input keyevent KEYCODE_VOLUME_UP")
            }

            HotkeyAction.VOLUME_DOWN -> {
                RootShell.cmd("input keyevent KEYCODE_VOLUME_DOWN")
            }

            HotkeyAction.MEDIA_PLAY_PAUSE -> {
                RootShell.cmd("input keyevent KEYCODE_MEDIA_PLAY_PAUSE")
            }

            HotkeyAction.BRIGHTNESS_UP -> {
                adjustBrightness(+25)
            }

            HotkeyAction.BRIGHTNESS_DOWN -> {
                adjustBrightness(-25)
            }

            HotkeyAction.KILL_FOREGROUND -> {
                killForegroundApp()
            }

            HotkeyAction.SHELL_COMMAND -> {
                val cmd = binding.extra.trim()
                if (cmd.isNotEmpty()) {
                    RootShell.cmd(cmd)
                }
            }

            HotkeyAction.SEND_KEYEVENT -> {
                val keycode = binding.extra.trim()
                if (keycode.isNotEmpty()) {
                    RootShell.cmd("input keyevent $keycode")
                }
            }
        }
    }

    private fun launchApp(packageName: String) {
        try {
            // Try launching via monkey (works even without an exported activity)
            RootShell.cmd("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
        }
    }

    private fun switchDisplayFocus() {
        // The Thor has two screens. Try wm commands to move focus.
        // This is device-specific and may need adjustment.
        RootShell.cmd("input keyevent --display 0 KEYCODE_WAKEUP")
    }

    private fun adjustBrightness(delta: Int) {
        try {
            val current = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val newVal = (current + delta).coerceIn(1, 255)
            RootShell.cmd("settings put system screen_brightness $newVal")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust brightness", e)
        }
    }

    private fun killForegroundApp() {
        Thread {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys activity recents"))
                p.waitFor()
                val output = p.inputStream.bufferedReader().readText()
                // Parse first "Recent #0" line to get package name
                val match = Regex("""Recent #0: Task\{[^ ]+ #\d+ type=\w+ (?:A=\d+:)?([^}]+)\}""")
                    .find(output)
                val pkg = match?.groupValues?.get(1)?.trim()
                if (pkg != null && pkg != "com.android.launcher3" && pkg != "net.smoreo.thortweaks") {
                    RootShell.cmd("am force-stop $pkg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to kill foreground app", e)
            }
        }.start()
    }

}
