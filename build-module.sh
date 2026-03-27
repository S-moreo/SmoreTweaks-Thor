#!/bin/bash
set -e

cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}

echo "==> Building APK..."
./gradlew assembleDebug 2>&1 | tail -3
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Syncing module assets..."
# Copy binary assets from the app's bundled module into the flashable module
cp app/src/main/assets/module/system/bin/pservice          module/system/bin/pservice
cp app/src/main/assets/module/system/vendor/firmware/*      module/system/vendor/firmware/
cp app/src/main/assets/module/system/vendor/lib/modules/*   module/system/vendor/lib/modules/
cp app/src/main/assets/module/system/etc/permissions/*      module/system/etc/permissions/

echo "==> Packaging module..."
mkdir -p module/system/priv-app/ThorHotkeys
cp "$APK" module/system/priv-app/ThorHotkeys/ThorHotkeys.apk

cd module
rm -f ../SmoreTweaks-module.zip
zip -r ../SmoreTweaks-module.zip . \
  -x '*.DS_Store' '*__MACOSX*' '*.gitkeep'

cd ..
echo "==> Built: SmoreTweaks-module.zip ($(du -h SmoreTweaks-module.zip | cut -f1))"
echo ""
echo "    Flash via Magisk/APatch manager:"
echo "    adb push SmoreTweaks-module.zip /sdcard/"
