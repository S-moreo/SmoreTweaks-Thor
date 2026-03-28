#!/system/bin/sh
# This runs late in boot, after services are ready.
MODDIR=${0%/*}

# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

# Give the system a moment to settle
sleep 5

# Auto-configure APatch SuperKey if saved from one-click setup
SKEY_FILE="/data/local/tmp/smore_apatch_superkey"
if [ -f "$SKEY_FILE" ]; then
  SKEY=$(cat "$SKEY_FILE")
  for APD in /data/adb/apd /data/adb/ap/bin/apd; do
    [ -x "$APD" ] && "$APD" superkey "$SKEY" 2>/dev/null && break
  done
fi

# Persist smallest width (display density)
DENSITY_FILE="/data/local/tmp/smore_display_density"
if [ -f "$DENSITY_FILE" ]; then
  wm density "$(cat $DENSITY_FILE)" 2>/dev/null
fi

# Start the service if the user has it enabled
# (The BootReceiver in the app handles this normally,
#  this is just a safety net)
am start-foreground-service -a com.thor.hotkeys.START com.thor.hotkeys/.service.HotkeyService 2>/dev/null
