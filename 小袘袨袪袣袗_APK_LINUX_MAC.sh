#!/bin/sh
cd "$(dirname "$0")"
set -e
if command -v gradle >/dev/null 2>&1; then
  gradle assembleDebug
  echo ""
  echo "ГОТОВО: app/build/outputs/apk/debug/app-debug.apk"
else
  echo "Gradle не найден. Откройте эту папку в Android Studio и выберите Build -> Build APK(s)."
fi
