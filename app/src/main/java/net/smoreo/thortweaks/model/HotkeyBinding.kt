package net.smoreo.thortweaks.model

data class HotkeyBinding(
    val id: String = java.util.UUID.randomUUID().toString(),
    val keys: List<String>,          // e.g. ["BTN_TL", "BTN_TR"] or ["KEY_HOME"]
    val requireLongPress: Boolean = false,
    val action: HotkeyAction,
    val extra: String = "",          // package name, shell command, keycode, etc.
    val foregroundApp: String = ""   // if set, only fire when this package is in foreground
)

enum class HotkeyAction(val label: String, val needsExtra: Boolean = false) {
    OPEN_RECENTS("Open Task Manager / Recents"),
    GO_HOME("Go Home"),
    GO_BACK("Go Back"),
    SWITCH_SCREEN_FOCUS("Switch Screen Focus"),
    OPEN_APP("Launch App", needsExtra = true),
    OPEN_NOTIFICATIONS("Open Notification Panel"),
    OPEN_QUICK_SETTINGS("Open Quick Settings"),
    TOGGLE_SPLIT_SCREEN("Toggle Split Screen"),
    SCREENSHOT("Take Screenshot"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down"),
    MEDIA_PLAY_PAUSE("Media Play/Pause"),
    BRIGHTNESS_UP("Brightness Up"),
    BRIGHTNESS_DOWN("Brightness Down"),
    KILL_FOREGROUND("Kill Foreground App"),
    SHELL_COMMAND("Run Shell Command", needsExtra = true),
    SEND_KEYEVENT("Send Key Event", needsExtra = true);
}
