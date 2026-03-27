package com.thor.hotkeys.util

object KeyNames {
    fun friendly(key: String): String = when (key) {
        "BTN_TL" -> "L1"
        "BTN_TR" -> "R1"
        "BTN_TL2" -> "L2"
        "BTN_TR2" -> "R2"
        "BTN_GAMEPAD" -> "A"
        "BTN_EAST" -> "B"
        "BTN_NORTH" -> "X"
        "BTN_WEST" -> "Y"
        "BTN_SELECT" -> "Select"
        "BTN_START" -> "Start"
        "BTN_MODE" -> "Mode"
        "BTN_THUMBL" -> "L3"
        "BTN_THUMBR" -> "R3"
        "BTN_DPAD_UP" -> "D-Up"
        "BTN_DPAD_DOWN" -> "D-Down"
        "BTN_DPAD_LEFT" -> "D-Left"
        "BTN_DPAD_RIGHT" -> "D-Right"
        "KEY_HOME" -> "Home"
        "KEY_BACK" -> "Back"
        "KEY_APPSELECT" -> "AppSelect"
        "KEY_VOLUMEUP" -> "Vol+"
        "KEY_VOLUMEDOWN" -> "Vol-"
        "KEY_POWER" -> "Power"
        "KEY_F24" -> "F24"
        else -> key.removePrefix("KEY_").removePrefix("BTN_")
    }
}
