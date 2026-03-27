#!/bin/bash
set -e

cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}

echo "==> Building APK..."
./gradlew assembleDebug 2>&1 | tail -3
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Packaging module..."
cp "$APK" module/system/priv-app/ThorHotkeys/ThorHotkeys.apk

cd module
rm -f ../ThorHotkeys-module.zip
7z a -tzip ../ThorHotkeys-module.zip . -xr!'*.DS_Store' -xr!'*__MACOSX*'

cd ..
echo "==> Built: ThorHotkeys-module.zip ($(du -h ThorHotkeys-module.zip | cut -f1))"
echo "    Flash via Magisk/APatch manager or:"
echo "    adb push ThorHotkeys-module.zip /sdcard/"
