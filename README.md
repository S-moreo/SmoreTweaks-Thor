# S'more Tweaks for AYN Thor

A root-enabled system app for the AYN Thor handheld gaming device. Controller hotkey bindings, GPU overclock, custom task drawer, and more — all managed from a single self-installing module within Android Settings.

---

## Features

### Controller Hotkey Bindings
- Bind any controller button, combo, or long-press to system actions
- 17 built-in actions: launch apps, go home/back, screenshot, volume, brightness, kill foreground app, shell commands, and more
- **App-specific bindings** — restrict bindings to only fire when a specific app is in the foreground
- Configurable long-press duration (200ms–1500ms)
- Raw input capture via `getevent` — works with the Odin Controller and gpio-keys

### Custom Task Drawer
- Replaces the system recents with a lightweight, controller-friendly task drawer
- Horizontal card layout with app icons and labels
- Full D-pad, analog stick, and face button navigation
- A to open, Y to dismiss, B to close, L1 for screenshot, R1 to clear all

### GPU Overclock
- Raises Adreno 740 max frequency from 680 MHz to 692 MHz (hardware PLL fuse limit for SM8550-AB)
- Dynamic vendor_boot DTB patching — firmware-version independent, survives OTA
- Patched `pservice` binary for full 220–692 MHz dynamic scaling (stock caps at 615 MHz)
- Custom kernel modules (`gpucc-kalama.ko`, `msm_lmh_dcvs.ko`, `gpio5_pwm.ko`) and GPU firmware

### Self-Installing Module
- Installs as a Magisk/APatch module from within the app
- Backs up stock `vendor_boot` before any modifications (first install only, never overwritten)
- One-tap restore to revert the GPU overclock DTB patch
- Purges old module versions automatically
- Installs itself as a privileged system app (`/system/priv-app/`)

---

## Installation

### Prerequisites
- AYN Thor (QCS8550 / Snapdragon 8 Gen 2, SM8550-AB)
- Android 13 (API 33)
- Root via APatch or Magisk

### Steps

1. Build the APK or grab a release
2. Install via `adb install` or sideload
3. Open **Settings** — "S'more Tweaks" appears on the main settings page
4. Tap **Install / Update Module** and follow the prompts
5. Reboot when prompted

After reboot, the app runs as a system priv-app with the GPU overclock active.

---

## Building

```bash
# Build the debug APK
./gradlew assembleDebug

# Install directly to a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK with API 34 and JDK 17.

---

## Architecture

```
app/src/main/java/com/thor/hotkeys/
  model/
    HotkeyBinding.kt      # Binding data model (keys, action, extras, foreground filter)
    BindingStore.kt        # SharedPreferences persistence with Gson
  service/
    HotkeyService.kt       # Foreground service, config reload, foreground app polling
    InputEventReader.kt     # Root getevent parser (EV_KEY + ABS hat/stick)
    HotkeyDetector.kt       # Combo matching, long-press timers, key suppression
    ActionExecutor.kt        # Root command execution for all actions
    ModuleInstaller.kt       # Module install/uninstall, DTB patching, backup/restore
    BootReceiver.kt          # Autostart on boot
  ui/
    SettingsActivity.kt      # Main settings (injected into system Settings)
    AddBindingActivity.kt    # Add/edit binding with live key capture
    TaskDrawerActivity.kt    # Custom recents with controller navigation
  util/
    KeyNames.kt              # Friendly names for getevent key codes

app/src/main/assets/module/  # Bundled module files
  system/bin/pservice         # Patched GPU power service
  system/vendor/lib/modules/  # Kernel modules for GPU OC
  system/vendor/firmware/     # GPU microcontroller firmware
  service.sh                  # Boot fallback service starter
  module.prop                 # Module metadata
```

---

## How It Works

**Hotkey detection** reads raw input events from `/dev/input/event9` (controller) and `/dev/input/event0` (gpio-keys) via a root `getevent` process. Events are parsed into key names, matched against user-configured bindings, and executed as root shell commands. Long-press detection uses a fire-on-timer approach — the action fires immediately when the hold threshold is reached, not on release. After firing, all held keys are suppressed until released to prevent duplicate triggers.

**GPU overclock** works by patching the device tree blob (DTB) inside the `vendor_boot` partition. The installer extracts the DTB with `magiskboot`, searches for the stock 680 MHz frequency value (big-endian `0x2887FA00`), and replaces it with 692 MHz (`0x29457600`). This is done dynamically at install time rather than bundling a static DTB, so it works across firmware versions.

**App-specific bindings** use a background poller that checks the current foreground app via `dumpsys activity activities` once per second. The poller only runs when at least one binding uses the foreground filter — zero overhead otherwise.

---

## Device Info

| | |
|---|---|
| **Device** | AYN Thor |
| **SoC** | Qualcomm QCS8550 (Snapdragon 8 Gen 2, SM8550-AB) |
| **GPU** | Adreno 740 |
| **OS** | Android 13 (API 33) |
| **Display** | Dual-screen, 1920x1080, landscape |
| **Controller** | Built-in Odin Controller (`/dev/input/event9`) |

---

## License

This project is provided as-is for personal use on AYN Thor devices. The bundled binary assets (pservice, kernel modules, firmware) are derived from AYN's stock firmware.
