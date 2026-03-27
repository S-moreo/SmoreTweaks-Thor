#!/system/bin/sh
# This runs late in boot, after services are ready.
# The app's BootReceiver handles autostart, but as a fallback:
MODDIR=${0%/*}

# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

# Give the system a moment to settle
sleep 5

# Start the service if the user has it enabled
# (The BootReceiver in the app handles this normally,
#  this is just a safety net)
am start-foreground-service -a com.thor.hotkeys.START com.thor.hotkeys/.service.HotkeyService 2>/dev/null
