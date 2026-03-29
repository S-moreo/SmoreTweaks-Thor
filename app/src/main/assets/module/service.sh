#!/system/bin/sh
# This runs late in boot, after services are ready.
MODDIR=${0%/*}

# Wait for boot to complete
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

# Give the system a moment to settle
sleep 5

# Read superkey if available
SKEY_FILE="/data/local/tmp/smore_apatch_superkey"
SKEY=""
if [ -f "$SKEY_FILE" ]; then
  SKEY=$(cat "$SKEY_FILE")
fi

# Verify KernelPatch is active
if [ -n "$SKEY" ] && [ -x /data/adb/kpatch ]; then
  /data/adb/kpatch "$SKEY" hello > /dev/null 2>&1
fi

# Auto-import superkey into APatch Manager if not already configured
APATCH_DATA="/data/data/me.bmax.apatch"
if [ -n "$SKEY" ] && [ -d "$APATCH_DATA" ]; then
  PREFS_DIR="$APATCH_DATA/shared_prefs"
  if ! grep -q "super_key" "$PREFS_DIR/config.xml" 2>/dev/null; then
    am force-stop me.bmax.apatch 2>/dev/null
    mkdir -p "$PREFS_DIR"
    printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"super_key\">%s</string>\n</map>\n" "$SKEY" > "$PREFS_DIR/config.xml"
    APATCH_UID=$(stat -c %u "$APATCH_DATA" 2>/dev/null)
    if [ -n "$APATCH_UID" ]; then
      chown "$APATCH_UID:$APATCH_UID" "$PREFS_DIR" "$PREFS_DIR/config.xml"
      chmod 660 "$PREFS_DIR/config.xml"
    fi
  fi
fi

# Persist smallest width (display density)
DENSITY_FILE="/data/local/tmp/smore_display_density"
if [ -f "$DENSITY_FILE" ]; then
  wm density "$(cat $DENSITY_FILE)" 2>/dev/null
fi

# Start the service if the user has it enabled
# (The BootReceiver in the app handles this normally,
#  this is just a safety net)
am start-foreground-service -a net.smoreo.thortweaks.START net.smoreo.thortweaks/.service.HotkeyService 2>/dev/null
