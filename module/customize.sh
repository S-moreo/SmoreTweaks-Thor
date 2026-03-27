#!/system/bin/sh
# S'more Tweaks — Magisk/APatch module installer
# Runs during module flash to set up GPU OC and patch vendor_boot DTB

VENDOR_BOOT_PART="/dev/block/by-name/vendor_boot_a"
BACKUP_DIR="/data/adb/smore-tweaks-backup"

ui_print "- Installing S'more Tweaks"

# Set permissions
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/system/bin/pservice 0 0 0755
set_perm_recursive $MODPATH/system/priv-app 0 0 0755 0644
set_perm $MODPATH/service.sh 0 0 0755

# --- Vendor_boot DTB patching ---

# Find magiskboot
MAGISKBOOT=""
for path in /data/adb/ap/bin/magiskboot /data/adb/magisk/magiskboot; do
  if [ -f "$path" ]; then
    MAGISKBOOT="$path"
    break
  fi
done

if [ -z "$MAGISKBOOT" ]; then
  ui_print "! magiskboot not found — skipping vendor_boot DTB patch"
  ui_print "  You can patch later from within the app"
else
  ui_print "- Backing up vendor_boot..."
  mkdir -p "$BACKUP_DIR"
  if [ ! -f "$BACKUP_DIR/vendor_boot.img.bak" ]; then
    dd if="$VENDOR_BOOT_PART" of="$BACKUP_DIR/vendor_boot.img.bak" 2>/dev/null
    ui_print "  Backup saved to $BACKUP_DIR/vendor_boot.img.bak"
  else
    ui_print "  Backup already exists, skipping"
  fi

  ui_print "- Patching vendor_boot DTB (680 MHz -> 692 MHz)..."
  WORKDIR="$TMPDIR/vendor_boot_work"
  mkdir -p "$WORKDIR"

  dd if="$VENDOR_BOOT_PART" of="$WORKDIR/vendor_boot.img" 2>/dev/null
  cd "$WORKDIR"
  "$MAGISKBOOT" unpack vendor_boot.img 2>/dev/null

  if [ -f "$WORKDIR/dtb" ]; then
    # Convert DTB to hex, search-and-replace GPU frequency, convert back
    # Stock: 680000000 = 0x2887FA00 (big-endian)
    # Patch: 692000000 = 0x29457600 (big-endian)
    STOCK_HEX="2887fa00"
    PATCH_HEX="29457600"

    HEX_DUMP=$(xxd -p "$WORKDIR/dtb" | tr -d '\n')

    if echo "$HEX_DUMP" | grep -q "$PATCH_HEX"; then
      ui_print "  DTB already patched to 692 MHz, skipping"
    elif echo "$HEX_DUMP" | grep -q "$STOCK_HEX"; then
      PATCHED_HEX=$(echo "$HEX_DUMP" | sed "s/$STOCK_HEX/$PATCH_HEX/g")
      echo "$PATCHED_HEX" | xxd -r -p > "$WORKDIR/dtb"

      "$MAGISKBOOT" repack vendor_boot.img 2>/dev/null
      dd if="$WORKDIR/new-boot.img" of="$VENDOR_BOOT_PART" 2>/dev/null
      ui_print "  vendor_boot patched successfully"
    else
      ui_print "! 680 MHz frequency not found in DTB — unexpected firmware"
      ui_print "  Skipping DTB patch. You can try from within the app."
    fi
  else
    ui_print "! Failed to extract DTB from vendor_boot"
    ui_print "  Skipping DTB patch. You can try from within the app."
  fi

  rm -rf "$WORKDIR"
fi

ui_print "- Done! Reboot to activate."
