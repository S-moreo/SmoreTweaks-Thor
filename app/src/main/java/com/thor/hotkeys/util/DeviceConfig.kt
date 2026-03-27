package com.thor.hotkeys.util

object DeviceConfig {
    val ALLOWED_DEVICES = setOf(
        "/dev/input/event9",  // Odin Controller
        "/dev/input/event0"   // gpio-keys (volume up, F24)
    )
}
